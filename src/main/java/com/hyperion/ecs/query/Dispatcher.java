package com.hyperion.ecs.query;

/**
 * Parallel execution contract for running a {@link Query} across archetype chunks.
 *
 * <p>The concurrency strategy (Phase 3, {@code DX-Agent}) maps archetype query chunks onto
 * {@code Executors.newVirtualThreadPerTaskExecutor()}. The dispatcher is responsible for
 * partitioning matching rows into chunks, scheduling them, and joining — without allocating on
 * the steady-state tick path.
 *
 * <p><b>Immutable Signature</b> — governed by ADR-0003.
 *
 * @since Phase 1 (Lead-Architect)
 */
public interface Dispatcher {

    /**
     * Executes the query over all matching entities, partitioned across worker threads.
     *
     * @param query the query to dispatch
     */
    void dispatch(Query query);

    /**
     * @return the target number of concurrent chunks (degree of parallelism)
     */
    int parallelism();

    /**
     * @return the number of rows per dispatched chunk
     */
    int chunkSize();
}
