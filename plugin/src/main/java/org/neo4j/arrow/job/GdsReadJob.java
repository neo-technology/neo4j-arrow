package org.neo4j.arrow.job;

import org.apache.arrow.flight.CallStatus;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.neo4j.arrow.*;
import org.neo4j.arrow.action.GdsMessage;
import org.neo4j.arrow.action.KHopMessage;
import org.neo4j.gds.NodeLabel;
import org.neo4j.gds.RelationshipType;
import org.neo4j.gds.api.Graph;
import org.neo4j.gds.api.GraphStore;
import org.neo4j.gds.api.NodeProperties;
import org.neo4j.gds.api.nodeproperties.ValueType;
import org.neo4j.gds.core.loading.GraphStoreCatalog;
import org.neo4j.gds.core.loading.ImmutableCatalogRequest;
import org.neo4j.gds.core.utils.RandomLongIterator;
import org.neo4j.gds.core.utils.collection.primitive.PrimitiveLongIterator;
import org.neo4j.gds.core.utils.mem.AllocationTracker;
import org.neo4j.gds.core.utils.paged.HugeAtomicBitSet;
import org.neo4j.gds.core.utils.paged.HugeObjectArray;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Interact directly with the GDS in-memory Graph, allowing for reads of node properties.
 */
public class GdsReadJob extends ReadJob {
    private final CompletableFuture<Boolean> future;

    /**
     * Create a new GdsReadJob for processing the given {@link GdsMessage} that reads Node or
     * Relationship properties from the GDS in-memory graph(s).
     * <p>
     * The supplied username is assumed to allow GDS to enforce authorization and is assumed to be
     * previously authenticated.
     *
     * @param msg      the {@link GdsMessage} to process in the job
     * @param username an already authenticated username
     */
    public GdsReadJob(GdsMessage msg, String username) throws RuntimeException {
        super();
        final CompletableFuture<Boolean> job;
        logger.info("GdsReadJob called with msg: {}", msg);

        // TODO: error handling for graph store retrieval
        final GraphStore store = GraphStoreCatalog.get(
                        ImmutableCatalogRequest.of(username, msg.getDbName()), msg.getGraphName())
                .graphStore();
        logger.info("got GraphStore for graph named {}", msg.getGraphName());

        switch (msg.getRequestType()) {
            case node:
                job = handleNodeJob(msg, store);
                break;
            case relationship:
                job = handleRelationshipsJob(msg, store);
                break;
            case khop:
                final KHopMessage kmsg = new KHopMessage(msg.getDbName(), msg.getGraphName(), 2, msg.getProperties().get(0));
                job = handleKHopJob(kmsg, store);
                break;

            default:
                throw CallStatus.UNIMPLEMENTED.withDescription("unhandled request type").toRuntimeException();
        }

        future = job.exceptionally(throwable -> {
            logger.error(throwable.getMessage(), throwable);
            return false;
        }).handleAsync((aBoolean, throwable) -> {
            logger.info("GdsReadJob completed! result: {}", (aBoolean == null ? "failed" : "ok!"));
            if (throwable != null)
                logger.error(throwable.getMessage(), throwable);
            return false;
        });
    }

    private Spliterator.OfLong spliterate(PrimitiveLongIterator iterator, long nodeCount) {
        return Spliterators.spliterator(new PrimitiveIterator.OfLong() {
            @Override
            public long nextLong() {
                return iterator.next();
            }

            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }
        }, nodeCount, Spliterator.SIZED | Spliterator.DISTINCT | Spliterator.ORDERED | Spliterator.NONNULL);
    }


    /**
     * Attempt to make a unique index from 2 node ids using bit masks and shifting
     * @param edge a {@Pair} representing out edge
     * @return index to use in a bit set
     */
    private static long index(Pair<Long, Long> edge) {
        // XXX assumption is our node Ids are really never larger than 32 bits
        final long left = edge.getLeft() << 33;
        final long right = (edge.getRight() & 0x00000000FFFFFFFF);
        return left | right;
    }

    private Stream<Triple<Long, Long, Boolean>> hop(long next, Graph graph, Map<Long, List<Triple<Long, Long, Boolean>>> cache, AtomicLong cacheHits) {
        final List<Triple<Long, Long, Boolean>> cachedList = cache.get(next); // XXX unchecked

        if (cachedList == null) {
            return graph.concurrentCopy()
                    .streamRelationships(next, Double.NaN)
                    .map(cursor -> Triple.of(cursor.sourceId(), cursor.targetId(), Double.isNaN(cursor.property())));
        } else {
            cacheHits.incrementAndGet();
            return cachedList.parallelStream();
        }
    }

    private CompletableFuture<Boolean> handleKHopJob(KHopMessage msg, GraphStore store) {
        final Graph graph = store.getGraph(store.nodeLabels(), store.relationshipTypes(),
                Optional.of(msg.getRelProperty()));
        final long nodeCount = graph.nodeCount();
        final long relCount = graph.relationshipCount();
        final AtomicLong cacheHits = new AtomicLong(0);

        final int k = msg.getK();

        // TODO: pull out as static methods once we move into a dedicated class
        final Function<Triple<Long, Pair<Long, Long>, Long>, SubGraphRecord> convert =
                (triple)-> {
                    final long origin = triple.getLeft();
                    final Pair<Long, Long> edge = triple.getMiddle();

                    final long source = edge.getLeft();
                    final long target = edge.getRight();
                    // discard the Right...it's the terminus

                    return SubGraphRecord.of(graph.toOriginalNodeId(origin),
                            graph.toOriginalNodeId(source), graph.nodeLabels(source),
                            "UNKNOWN", // XXX lame
                            graph.toOriginalNodeId(target), graph.nodeLabels(target));
                };

        final AtomicInteger x = new AtomicInteger(0);
        final Function<BiConsumer<RowBasedRecord, Integer>, Consumer<RowBasedRecord>> wrapConsumer =
                (consumer) ->
                        (row) -> consumer.accept(row, Math.abs(x.getAndIncrement()));

        // XXX analyze degrees
        final Map<Integer, Long> histogram = new ConcurrentHashMap<>();
        final Queue<Long> superNodes = new ConcurrentLinkedQueue<>();

        logger.info("checking for super nodes...");
        LongStream.range(0, nodeCount)
                .parallel()
                .mapToObj(id -> Pair.of(id, 0))
                .map(pair -> Pair.of(pair.getLeft(), graph.degree(pair.getLeft())))
                .map(pair -> (pair.getRight() == 0) ? Pair.of(pair.getLeft(), Double.NaN)
                        : Pair.of(pair.getLeft(), Math.floor(Math.log10(pair.getRight()))))
                .map(pair -> (pair.getRight().isNaN()) ? Pair.of(pair.getLeft(), 0)
                        : Pair.of(pair.getLeft(), pair.getRight().intValue() + 1))
                .forEach(pair -> {
                    int magnitude = pair.getRight();
                    histogram.compute(magnitude, (key, val) -> (val == null) ? 1L : val + 1L);
                    if (magnitude > 2) // XXX cutoff?
                        superNodes.add(pair.getLeft());
                });

        histogram.keySet().stream()
                .sorted()
                .forEach(key ->
                        logger.info(String.format("\t[ 10 * %d ]\t- %,d nodes", key, histogram.get(key))));
        histogram.clear();
        logger.info(String.format("%,d potential supernodes!", superNodes.size()));

        // XXX faux record
        onFirstRecord(SubGraphRecord.of(0L, 0L, store.nodeLabels(), "TYPE", 1L, store.nodeLabels()));

        return CompletableFuture.supplyAsync(() -> {
            logger.info(String.format("starting node stream for gds khop job %s (%,d nodes, %,d rels)",
                    jobId, nodeCount, relCount));

            /*
             * "Triples makes it safe. Triples is best."
             *   -- Your dad's old friend (https://www.youtube.com/watch?v=8Inf1Yz_fgk)
             *
             * We'll use a Triple<> that contains the start (L) and end (M) node ids, as well
             * as the detail of if the resulting edge is "natural" (R) or not. (We need to
             * know the type for traversal vs. result purposes.)
             */

            // Pre-cache supernodes adjacency lists
            logger.info("caching supernodes...");
            final Map<Long, List<Triple<Long, Long, Boolean>>> supernodeCache = new ConcurrentHashMap<>();
            superNodes.parallelStream()
                    .forEach(superNodeId ->
                            supernodeCache.put(superNodeId,
                                    graph.concurrentCopy()
                                            .streamRelationships(superNodeId, Double.NaN)
                                            .map(cursor -> {
                                                final boolean isNatural = Double.isNaN(cursor.property());
                                                return Triple.of(cursor.sourceId(), cursor.targetId(), isNatural);
                                            })
                                            .collect(Collectors.toUnmodifiableList())));
            logger.info(String.format("cached %,d supernodes (%,d edges)",
                    superNodes.size(), supernodeCache.values().stream().mapToInt(List::size).sum()));

            var consume = wrapConsumer.apply(futureConsumer.join()); // XXX join()

            // If we know we have supernodes, it might benefit us to randomly process the nodes instead of in a
            // sequential order.
            final LongStream nodeStream;
            if (superNodes.size() > 0) {
                var i = new RandomLongIterator(0, nodeCount);
                var s = Spliterators.spliterator(i, nodeCount,
                        Spliterator.SIZED | Spliterator.DISTINCT | Spliterator.NONNULL | Spliterator.IMMUTABLE);
                nodeStream = StreamSupport.longStream(s, true);
            } else {
                nodeStream = LongStream.range(0, nodeCount);
            }

            final long rows = nodeStream
                    .boxed()
                    .parallel()
                    .flatMap(origin -> {
                        final Set<Long>
                                relHistory = new ConcurrentSkipListSet<>();
                        Stream<Triple<Long, Long, Boolean>> stream = hop(origin, graph, supernodeCache, cacheHits);

                        // k-hop
                        for (int i = 1; i < k; i++) {
                            stream = stream.flatMap(edge -> {
                                final long next = edge.getMiddle();
                                return Stream.concat(Stream.of(edge), hop(next, graph, supernodeCache, cacheHits));
                            });
                        }

                        // Consume the stream >>>
                        return stream
                                .map(triple -> (triple.getRight()) ? // re-orient our edges before processing them
                                        Pair.of(triple.getLeft(), triple.getMiddle()) : Pair.of(triple.getMiddle(), triple.getLeft()))
                                .filter(edge -> relHistory.add(index(edge)))
                                .map(edge -> Pair.of(origin, edge));
                    })
                    .peek(result -> {
                        final long origin = result.getLeft();
                        final Pair<Long, Long> edge = result.getRight();
                        consume.accept(SubGraphRecord.of(origin,
                                edge.getLeft(), graph.nodeLabels(edge.getLeft()),
                                "REL",
                                edge.getRight(), graph.nodeLabels(edge.getRight())));
                    })
                    .count();

            logger.info(String.format("completed gds k-hop job: %,d nodes, %,d total rows", nodeCount, rows));
            logger.info(String.format("cache hits: %,d", cacheHits.get()));
            return true;
        }).handleAsync((isOk, err) -> {
            if (isOk != null && isOk) {
                onCompletion(() -> String.format("job %s finished OK!", jobId));
                return true;
            }
            onCompletion(() -> String.format("job %s FAILED!", jobId));
            logger.error(String.format("job %s FAILED!", jobId), err);
            return false;
        });
    }

    protected CompletableFuture<Boolean> handleRelationshipsJob(GdsMessage msg, GraphStore store) {
        // TODO: support both rel type and node label filtering
        final Collection<RelationshipType> relTypes = (msg.getFilters().size() > 0) ?
                msg.getFilters().stream()
                        .map(RelationshipType::of)
                        .filter(store::hasRelationshipType)
                        .collect(Collectors.toUnmodifiableList())
                : store.relationshipTypes();
        logger.info("proceeding with relTypes: {}", relTypes);

        // Make sure we have the requested rel properties.
        // TODO: nested for-loop is ugly
        if (msg.getProperties() != GdsMessage.ANY_PROPERTIES) {
            for (String key : msg.getProperties()) {
                boolean found = false;
                for (RelationshipType type : relTypes) {
                    logger.info("type {} has key {}", type.name(), key);
                    if (store.hasRelationshipProperty(type, key)) {
                        logger.info("  yes!");
                        found = true;
                        break;
                    } else {
                        logger.info("  nope.");
                    }
                }
                if (!found) {
                    logger.error("no relationship property found for {}", key);
                    throw CallStatus.NOT_FOUND
                            .withDescription(String.format("no relationship property found for %s", key))
                            .toRuntimeException();
                }
            }
        }

        final Graph baseGraph = (relTypes.size() > 0) ?
                store.getGraph(store.nodeLabels(), relTypes, Optional.empty())
                : store.getGraph(store.nodeLabels(), store.relationshipTypes(), Optional.empty());
        logger.info("got graph for labels {}, relationship types {}", baseGraph.schema().nodeSchema().availableLabels(),
                baseGraph.schema().relationshipSchema().availableTypes());

        // Borrow the approach used by gds.graph.streamRelationshipProperties()...i.e. build triples
        // of relationship types, property keys, and references to filtered graph views.
        // XXX for now (as of v1.7.x) all rel properties are doubles, but this could change
        final String[] keys = msg.getProperties().toArray(new String[0]);
        var triples = relTypes.stream()
                .flatMap(relType -> {
                    logger.info("assembling triples for type {}", relType);
                    // Filtering for certain properties
                    if (msg.getProperties() != GdsMessage.ANY_PROPERTIES) {
                        return Arrays.stream(keys)
                                .filter(key -> store.hasRelationshipProperty(relType, key))
                                .map(key -> Triple.of(relType, key, store.getGraph(relType, Optional.of(key))));
                    } else {
                        logger.info("using all property keys for {}", relType);
                        return Stream.concat(
                                store.relationshipPropertyKeys(relType).stream()
                                        .map(key -> Triple.of(relType, key, store.getGraph(relType, Optional.of(key)))),
                                Stream.of(Triple.of(relType, null, store.getGraph(relType, Optional.empty())))
                        );
                    }
                })
                .peek(triple -> logger.debug("constructed triple {}", triple))
                .toArray(Triple[]::new);
        logger.info(String.format("assembled %,d triples", triples.length));

        if (baseGraph.nodeCount() == 0)
            throw CallStatus.NOT_FOUND.withDescription("no matching node ids for GDS job").toRuntimeException();

        AtomicInteger rowCnt = new AtomicInteger(0);

        return CompletableFuture.supplyAsync(() -> {
            // XXX We cheat and make a fake record just to communicate schema :-(
            GdsRelationshipRecord fauxRecord = new GdsRelationshipRecord(0, 1, "type", "key",
                    GdsRecord.wrapScalar(0.0d, ValueType.DOUBLE));
            onFirstRecord(fauxRecord);

            // Make rocket go now
            final BiConsumer<RowBasedRecord, Integer> consumer = futureConsumer.join();

            logger.info(String.format("finding rels for %,d nodes", baseGraph.nodeCount()));
            LongStream.range(0, baseGraph.nodeCount())
                    .parallel()
                    .boxed()
                    .forEach(nodeId -> {
                        final long originalNodeId = baseGraph.toOriginalNodeId(nodeId);
                        // logger.info("processing node {} (originalId: {})", nodeId, originalNodeId);
                        Arrays.stream(triples)
                                .flatMap(triple -> {
                                    final RelationshipType type = (RelationshipType) triple.getLeft();
                                    final String key = (String) triple.getMiddle();
                                    final Graph graph = ((Graph) triple.getRight()).concurrentCopy();

                                    return graph
                                            .streamRelationships(nodeId, Double.NaN)
                                            .map(cursor -> new GdsRelationshipRecord(
                                                    originalNodeId,
                                                    graph.toOriginalNodeId(cursor.targetId()),
                                                    type.name(),
                                                    key,
                                                    GdsRecord.wrapScalar(cursor.property(), ValueType.DOUBLE)));
                                }).forEach(record -> consumer.accept(record, rowCnt.getAndIncrement()));
                    });

            final String summary = String.format("finished generating GDS rel stream, fed %,d rows", rowCnt.get());
            logger.info(summary);
            onCompletion(() -> summary);
            return true;
        }).exceptionally(throwable -> {
            logger.error(throwable.getMessage(), throwable);
            return false;
        }).handleAsync((aBoolean, throwable) -> {
            logger.info("gds job completed. result: {}", (aBoolean == null ? "failed" : "ok!"));
            if (throwable != null)
                logger.error(throwable.getMessage(), throwable);
            return true;
        });
    }

    protected CompletableFuture<Boolean> handleNodeJob(GdsMessage msg, GraphStore store) {
        final Collection<NodeLabel> labels = msg.getFilters()
                .stream().map(NodeLabel::of).collect(Collectors.toUnmodifiableList());

        final Graph graph = (labels.size() > 0) ?
                store.getGraph(labels, store.relationshipTypes(), Optional.empty())
                : store.getGraph(store.nodeLabels(), store.relationshipTypes(), Optional.empty());
        logger.info("got graph for labels {}, relationship types {}", store.nodeLabels(),
                store.relationshipTypes());

        // Make sure we have the requested node properties
        for (String key : msg.getProperties()) {
            if (!store.hasNodeProperty(store.nodeLabels(), key)) {
                logger.error("no node property found for {}", key);
                throw CallStatus.NOT_FOUND
                        .withDescription(String.format("no node property found for %s", key))
                        .toRuntimeException();
            }
        }

        // Setup some arrays
        final String[] keys = msg.getProperties().toArray(new String[0]);
        final NodeProperties[] propertiesArray = new NodeProperties[keys.length];
        for (int i = 0; i < keys.length; i++)
            propertiesArray[i] = store.nodePropertyValues(keys[i]);

        final PrimitiveLongIterator iterator = graph.nodeIterator();

        return CompletableFuture.supplyAsync(() -> {
            // XXX: hacky get first node...assume it exists
            long nodeId = iterator.next();
            GdsNodeRecord record = GdsNodeRecord.wrap(nodeId, graph.nodeLabels(nodeId), keys, propertiesArray, graph::toOriginalNodeId);
            onFirstRecord(record);
            logger.debug("offered first record to producer");
            for (int i = 0; i < keys.length; i++)
                logger.info(" key {}/{}: {} -> {}", i, keys.length - 1, keys[i], propertiesArray[i].valueType());

            final BiConsumer<RowBasedRecord, Integer> consumer = futureConsumer.join();
            logger.info("acquired consumer");

            // Blast off!
            // TODO: GDS lets us batch access to lists of nodes...future opportunity?
            final long start = System.currentTimeMillis();
            consumer.accept(record, 0);

            // TODO: should it be nodeCount - 1? We advanced the iterator...maybe?
            var s = spliterate(iterator, graph.nodeCount() - 1);
            StreamSupport.stream(s, true).forEach(i ->
                    consumer.accept(GdsNodeRecord.wrap(i, graph.nodeLabels(i), keys, propertiesArray, graph::toOriginalNodeId),
                            i.intValue()));

            final long delta = System.currentTimeMillis() - start;

            onCompletion(() -> String.format("finished generating GDS stream, duration %,d ms", delta));
            return true;
        }).exceptionally(throwable -> {
            logger.error(throwable.getMessage(), throwable);
            return false;
        }).handleAsync((aBoolean, throwable) -> {
            logger.info("gds job completed. result: {}", (aBoolean == null ? "failed" : "ok!"));
            if (throwable != null)
                logger.error(throwable.getMessage(), throwable);
            return true;
        });
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return future.cancel(mayInterruptIfRunning);
    }

    @Override
    public void close() {
        future.cancel(true);
    }
}
