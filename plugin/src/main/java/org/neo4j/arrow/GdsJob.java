package org.neo4j.arrow;

import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.api.GraphStore;
import org.neo4j.graphalgo.api.NodeProperties;
import org.neo4j.graphalgo.core.loading.GraphStoreCatalog;
import org.neo4j.graphalgo.core.loading.ImmutableCatalogRequest;
import org.neo4j.graphalgo.core.utils.collection.primitive.PrimitiveLongIterator;
import org.neo4j.logging.Log;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class GdsJob extends Neo4jJob {


    private final CompletableFuture<Boolean> future;

    public GdsJob(CypherMessage msg, Mode mode, Log log) {
        super(msg, mode);
        log.info("GdsJob called");

        final GraphStore store = GraphStoreCatalog.get(
                ImmutableCatalogRequest.of("neo4j", "neo4j"), "mygraph")
                .graphStore();
        log.info("got graphstore " + store.toString());

        // just get all stuff for now
        final Graph graph = store
                .getGraph(store.nodeLabels(), store.relationshipTypes(), Optional.empty());
        log.info("got graph " + graph.toString());

        future = CompletableFuture.supplyAsync(() -> {
            log.info("...starting streaming future...");

            final PrimitiveLongIterator iterator = graph.nodeIterator();
            final NodeProperties properties = graph.nodeProperties("n");
            // get first node
            long nodeId = iterator.next();
            onFirstRecord(GdsRecord.wrap(properties.doubleValue(nodeId)));
            log.info("got first record");

            final Consumer<Neo4jRecord> consumer = futureConsumer.join();
            log.info("consuming...");
            consumer.accept(GdsRecord.wrap(properties.doubleValue(nodeId)));

            while (iterator.hasNext()) {
                consumer.accept(GdsRecord.wrap(properties.doubleValue(iterator.next())));
            }
            log.info("finishing stream");
            onCompletion(new JobSummary() {
                @Override
                public String toString() {
                    return "done";
                }
            });
            return true;
        }).handleAsync((aBoolean, throwable) -> {
            log.info("job completed. result: " + (aBoolean == null ? "failed" : "ok!"));
            if (throwable != null)
                log.error(throwable.getMessage(), throwable);
            return false;
        }).toCompletableFuture();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return future.cancel(mayInterruptIfRunning);
    }

    @Override
    public void close() throws Exception {
        future.cancel(true);
    }
}
