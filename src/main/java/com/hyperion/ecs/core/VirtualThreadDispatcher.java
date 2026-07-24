package com.hyperion.ecs.core;

import com.hyperion.ecs.query.Dispatcher;
import com.hyperion.ecs.query.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Parallel {@link Dispatcher} that maps archetype row-chunks onto
 * {@code Executors.newVirtualThreadPerTaskExecutor()} (Low-Level Requirement #3).
 *
 * <p>For a matching archetype, rows {@code [0, count)} are split into windows of {@link #chunkSize()}
 * and each window runs on its own virtual thread over a private {@link ChunkView}. Because the
 * shared {@code Arena} permits concurrent access and distinct chunks touch disjoint rows, there are
 * no data races.
 *
 * <p><b>Requirement:</b> the dispatched {@link Query} must be a <em>stateless</em> primitive
 * {@link ChunkFunction} system (created via {@link HyperionWorld#createSystem(ChunkFunction, Class[])}).
 * The ergonomic {@code Systems.of(...).each(...)} path uses shared flyweight views and is therefore
 * single-threaded — do not dispatch it here.
 *
 * <p>Unlike {@code tick()}, parallel dispatch is a throughput accelerator, not a zero-GC path: it
 * allocates a small, bounded set of tasks/futures/{@link ChunkView}s per call.
 *
 * @since Phase 3 (DX-Agent)
 */
public final class VirtualThreadDispatcher implements Dispatcher, AutoCloseable {

    private final HyperionWorld world;
    private final int chunkSize;
    private final ExecutorService executor;

    public VirtualThreadDispatcher(HyperionWorld world) {
        this(world, 16_384);
    }

    public VirtualThreadDispatcher(HyperionWorld world, int chunkSize) {
        this.world = world;
        this.chunkSize = Math.max(1, chunkSize);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void dispatch(Query query) {
        if (!(query instanceof RegisteredSystem s) || !s.primitive || s.chunkFn == null) {
            throw new IllegalArgumentException(
                    "Parallel dispatch requires a primitive ChunkFunction system (see class docs).");
        }
        ChunkFunction fn = s.chunkFn;
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < world.tableCount(); t++) {
            ArchetypeTable table = world.tableAt(t);
            int count = table.count();
            if (count == 0 || !world.systemMatches(s, table)) {
                continue;
            }
            for (int start = 0; start < count; start += chunkSize) {
                int len = Math.min(chunkSize, count - start);
                int fStart = start;
                ArchetypeTable fTable = table;
                futures.add(executor.submit(() -> {
                    ChunkView view = new ChunkView();
                    view.bindWindow(fTable, fStart, len);
                    fn.apply(view);
                }));
            }
        }
        join(futures);
    }

    private static void join(List<Future<?>> futures) {
        try {
            for (Future<?> f : futures) {
                f.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Dispatch interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw (cause instanceof RuntimeException re) ? re : new RuntimeException(cause);
        }
    }

    @Override
    public int parallelism() {
        return Runtime.getRuntime().availableProcessors();
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public void close() {
        executor.close();
    }
}
