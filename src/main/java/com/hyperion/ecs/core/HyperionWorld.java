package com.hyperion.ecs.core;

import com.hyperion.ecs.Component;
import com.hyperion.ecs.Entity;
import com.hyperion.ecs.EntityBuilder;
import com.hyperion.ecs.Relation;
import com.hyperion.ecs.World;
import com.hyperion.ecs.WorldConfig;
import com.hyperion.ecs.query.Query;
import com.hyperion.ecs.query.QueryBuilder;
import com.hyperion.ecs.query.Each1;
import com.hyperion.ecs.query.Each2;
import com.hyperion.ecs.query.Each3;
import com.hyperion.ecs.query.Each4;

import java.lang.foreign.Arena;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * Concrete off-heap ECS {@link World} — the Phase-2 engine.
 *
 * <p>Owns a single shared {@link Arena} from which every off-heap structure (entity slots,
 * archetype SoA columns) is allocated. Entity lifecycle and spawning are structural paths where
 * allocation is permitted; {@link #tick()} and query execution honour the Strict Zero-GC Rule —
 * they iterate pre-allocated arrays by index, match archetypes with primitive bitmask {@code AND},
 * reuse a single {@link ChunkView}, and invoke {@link ChunkFunction} callbacks that touch only
 * native memory.
 *
 * @since Phase 2 (Perf-Core-Agent)
 */
public final class HyperionWorld implements World, AutoCloseable {

    private static final int INITIAL_ARCHETYPE_CAPACITY = 1024;

    private final Arena arena;
    private final ComponentRegistry registry = new ComponentRegistry();
    private final EntityStore entityStore;

    private ArchetypeTable[] tables = new ArchetypeTable[16];
    private int tableCount;
    private final Map<NativeBitmask, Integer> tableIndex = new HashMap<>();

    private RegisteredSystem[] systems = new RegisteredSystem[16];
    private int systemCount;

    /** Reused across every archetype chunk of every tick — the crux of the zero-GC guarantee. */
    private final ChunkView chunkView = new ChunkView();

    // Relationship graph — on-heap adjacency: source -> relation type -> target handles. A fully
    // off-heap composite-EdgeKey index is a follow-up. Structural / query path only (never in tick).
    private final Map<Long, Map<Class<? extends Relation>, Set<Long>>> graph = new HashMap<>();

    private OffHeapRewindBuffer rewindBuffer;
    private TickListener tickListener;

    public HyperionWorld(WorldConfig config) {
        this.arena = Arena.ofShared();
        int cap = (int) Math.min(Math.max(1, config.initialEntityCapacity()), Integer.MAX_VALUE);
        this.entityStore = new EntityStore(arena, cap);
        if (config.rewindFrames() > 0) {
            enableRewindBuffer(config.rewindFrames());
        }
    }

    ComponentRegistry registry() {
        return registry;
    }

    EntityStore entityStore() {
        return entityStore;
    }

    /**
     * Registers (if needed) and returns the stable component id / bitmask bit index for a type.
     * Primitive {@link ChunkFunction} systems use this id with {@link ChunkView}'s accessors.
     *
     * @param type a component type
     * @return the component id
     */
    public int componentId(Class<? extends Component> type) {
        return registry.idOf(type);
    }

    // ── Entity lifecycle (structural) ────────────────────────────────────────────────────

    @Override
    public EntityBuilder spawn() {
        return new HyperionEntityBuilder(this);
    }

    /** Materialises a staged entity: resolve archetype, append a row, write field data. */
    long materialize(int[] ids, RegisteredComponent[] comps, Object[] instances) {
        NativeBitmask mask = new NativeBitmask();
        for (int id : ids) {
            mask.set(id);
        }
        int archIdx = getOrCreateArchetype(mask, ids);
        ArchetypeTable table = tables[archIdx];
        long entity = entityStore.create();
        int row = table.addRow(entity);
        entityStore.bind(Entity.index(entity), archIdx, row);
        for (int k = 0; k < comps.length; k++) {
            comps[k].write(table, row, instances[k]);
        }
        return entity;
    }

    @Override
    public boolean isAlive(long entity) {
        return entityStore.isAlive(entity);
    }

    @Override
    public void despawn(long entity) {
        if (!entityStore.isAlive(entity)) {
            return;
        }
        int index = Entity.index(entity);
        int archIdx = entityStore.archetypeIndex(index);
        int row = entityStore.row(index);
        long moved = tables[archIdx].removeRowSwap(row);
        if (!Entity.isNull(moved)) {
            entityStore.setRow(Entity.index(moved), row);
        }
        entityStore.free(index);
    }

    @Override
    public long entityCount() {
        return entityStore.liveCount();
    }

    // ── Archetype management (structural) ────────────────────────────────────────────────

    private int getOrCreateArchetype(NativeBitmask mask, int[] ids) {
        Integer existing = tableIndex.get(mask);
        if (existing != null) {
            return existing;
        }
        int[] sorted = ids.clone();
        Arrays.sort(sorted);
        NativeBitmask key = mask.copy();
        ArchetypeTable table = new ArchetypeTable(arena, key, sorted, registry, INITIAL_ARCHETYPE_CAPACITY);
        if (tableCount == tables.length) {
            tables = Arrays.copyOf(tables, tables.length << 1);
        }
        int idx = tableCount++;
        tables[idx] = table;
        tableIndex.put(key, idx);
        return idx;
    }

    // ── System registration (structural) ─────────────────────────────────────────────────

    @Override
    public QueryBuilder createSystem() {
        return new HyperionQueryBuilder(this);
    }

    /**
     * Registers a primitive, zero-GC system — the path the Phase-3 DSL compiles down to.
     *
     * @param fn         the per-chunk callback
     * @param components the required component types
     * @return the registered {@link Query} handle
     */
    @SafeVarargs
    public final Query createSystem(ChunkFunction fn, Class<? extends Component>... components) {
        NativeBitmask required = new NativeBitmask();
        for (Class<? extends Component> c : components) {
            required.set(registry.idOf(c));
        }
        return addSystem(RegisteredSystem.primitive(this, required, fn));
    }

    /**
     * Registers a primitive {@link ChunkFunction} system with required and excluded component sets.
     * The DX layer ({@code com.hyperion.ecs.dsl.Systems}) compiles its ergonomic {@code each(...)}
     * down to this seam.
     *
     * @param fn       the per-chunk callback
     * @param included required component types
     * @param excluded component types that must be absent (may be {@code null}/empty)
     * @return the registered {@link Query} handle
     */
    public Query registerPrimitive(ChunkFunction fn, Class<? extends Component>[] included,
                                   Class<? extends Component>[] excluded) {
        NativeBitmask required = new NativeBitmask();
        for (Class<? extends Component> c : included) {
            required.set(registry.idOf(c));
        }
        NativeBitmask exc = null;
        if (excluded != null && excluded.length > 0) {
            exc = new NativeBitmask();
            for (Class<? extends Component> c : excluded) {
                exc.set(registry.idOf(c));
            }
        }
        return addSystem(RegisteredSystem.primitive(this, required, exc, fn));
    }

    /**
     * @param type a component type
     * @return the ordered SoA field names (column order) — used by the DX view binder to build
     * {@code VarHandle}s consistent with the storage layout
     */
    public String[] componentFieldNames(Class<? extends Component> type) {
        return registry.register(type).fieldNames.clone();
    }

    /** @return the number of distinct archetypes (SoA tables) currently allocated. For tooling/telemetry. */
    public int archetypeCount() {
        return tableCount;
    }

    /**
     * Writes a single {@code int} component field for one entity, addressed by handle. Random-access
     * companion to the chunk-oriented hot path — intended for tooling and gameplay events (e.g. the
     * Phase-6 demo recolouring graph-linked entities), not for per-frame bulk updates.
     *
     * @param entity     the entity handle; ignored if dead
     * @param type       the component type
     * @param fieldIndex the field index within that component
     * @param value      the value to store
     */
    public void setComponentInt(long entity, Class<? extends Component> type, int fieldIndex, int value) {
        if (!entityStore.isAlive(entity)) {
            return;
        }
        int slot = Entity.index(entity);
        ArchetypeTable table = tables[entityStore.archetypeIndex(slot)];
        int cid = registry.idOf(type);
        if (!table.contains(cid)) {
            return;
        }
        table.segment().set(java.lang.foreign.ValueLayout.JAVA_INT,
                table.fieldOffset(cid, fieldIndex, entityStore.row(slot)), value);
    }

    /**
     * Reads a single {@code float} component field for one entity, addressed by handle.
     *
     * @return the field value, or {@code 0f} if the entity is dead or lacks the component
     */
    public float getComponentFloat(long entity, Class<? extends Component> type, int fieldIndex) {
        if (!entityStore.isAlive(entity)) {
            return 0f;
        }
        int slot = Entity.index(entity);
        ArchetypeTable table = tables[entityStore.archetypeIndex(slot)];
        int cid = registry.idOf(type);
        if (!table.contains(cid)) {
            return 0f;
        }
        return table.segment().get(java.lang.foreign.ValueLayout.JAVA_FLOAT,
                table.fieldOffset(cid, fieldIndex, entityStore.row(slot)));
    }

    // ── Dispatcher seams (package-private) ───────────────────────────────────────────────

    int tableCount() {
        return tableCount;
    }

    ArchetypeTable tableAt(int i) {
        return tables[i];
    }

    boolean systemMatches(RegisteredSystem s, ArchetypeTable table) {
        NativeBitmask sig = table.nativeSignature();
        return sig.matches(s.required) && (s.excluded == null || !intersects(sig, s.excluded));
    }

    Query registerEach(Class<? extends Component>[] included, Class<? extends Component>[] excluded,
                       Object eachFn, int arity) {
        RegisteredComponent[] comps = new RegisteredComponent[included.length];
        NativeBitmask required = new NativeBitmask();
        for (int i = 0; i < included.length; i++) {
            comps[i] = registry.register(included[i]);
            required.set(comps[i].id);
        }
        NativeBitmask exc = null;
        if (excluded != null && excluded.length > 0) {
            exc = new NativeBitmask();
            for (Class<? extends Component> c : excluded) {
                exc.set(registry.idOf(c));
            }
        }
        return addSystem(RegisteredSystem.each(this, required, exc, eachFn, arity, comps));
    }

    private RegisteredSystem addSystem(RegisteredSystem s) {
        if (systemCount == systems.length) {
            systems = Arrays.copyOf(systems, systems.length << 1);
        }
        systems[systemCount++] = s;
        return s;
    }

    // ── Simulation loop (ZERO-GC hot path) ───────────────────────────────────────────────

    @Override
    public void tick() {
        TickListener l = tickListener;
        long start = (l != null) ? System.nanoTime() : 0L;

        RegisteredSystem[] sys = systems;
        int sc = systemCount;
        for (int i = 0; i < sc; i++) {
            RegisteredSystem s = sys[i];
            if (s.enabledFlag()) {
                execute(s);
            }
        }
        if (rewindBuffer != null) {
            rewindBuffer.capture();
        }

        if (l != null) {
            l.onTick(this, System.nanoTime() - start);
        }
    }

    /**
     * Attaches (or clears, with {@code null}) a per-tick telemetry hook. The hook runs on the
     * simulation thread after the tick body; keep it allocation-free (&lt; 2% overhead guardrail).
     *
     * @param listener the tick listener, or {@code null} to detach
     */
    public void setTickListener(TickListener listener) {
        this.tickListener = listener;
    }

    /** Executes one system over every matching archetype. Zero-GC for primitive systems. */
    void execute(RegisteredSystem s) {
        ArchetypeTable[] tbls = tables;
        int tc = tableCount;
        NativeBitmask required = s.required;
        NativeBitmask excluded = s.excluded;
        boolean primitive = s.primitive;
        for (int t = 0; t < tc; t++) {
            ArchetypeTable table = tbls[t];
            if (table.count() == 0) {
                continue;
            }
            NativeBitmask sig = table.nativeSignature();
            if (!sig.matches(required)) {
                continue;
            }
            if (excluded != null && intersects(sig, excluded)) {
                continue;
            }
            if (primitive) {
                chunkView.bind(table);
                s.chunkFn.apply(chunkView);
            } else {
                runEach(s, table);
            }
        }
    }

    long matchCount(RegisteredSystem s) {
        long total = 0;
        for (int t = 0; t < tableCount; t++) {
            ArchetypeTable table = tables[t];
            NativeBitmask sig = table.nativeSignature();
            if (sig.matches(s.required) && (s.excluded == null || !intersects(sig, s.excluded))) {
                total += table.count();
            }
        }
        return total;
    }

    private static boolean intersects(NativeBitmask a, NativeBitmask b) {
        return (a.word(0) & b.word(0)) != 0L
                || (a.word(1) & b.word(1)) != 0L
                || (a.word(2) & b.word(2)) != 0L
                || (a.word(3) & b.word(3)) != 0L;
    }

    /**
     * Compatibility (record) execution path. Reconstructs immutable component records per row and
     * dispatches to the {@code EachN} callback. <b>Allocating and read-only</b> — retained for the
     * Phase-1 ergonomic API pending Phase-3 zero-cost views (ADR-0005). Not used by primitive systems.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void runEach(RegisteredSystem s, ArchetypeTable table) {
        int n = table.count();
        RegisteredComponent[] c = s.eachComps;
        Object fn = s.eachFn;
        for (int r = 0; r < n; r++) {
            long e = table.entityAt(r);
            switch (s.arity) {
                case 1 -> ((Each1) fn).apply(e, c[0].read(table, r));
                case 2 -> ((Each2) fn).apply(e, c[0].read(table, r), c[1].read(table, r));
                case 3 -> ((Each3) fn).apply(e, c[0].read(table, r), c[1].read(table, r), c[2].read(table, r));
                case 4 -> ((Each4) fn).apply(e, c[0].read(table, r), c[1].read(table, r),
                        c[2].read(table, r), c[3].read(table, r));
                default -> throw new IllegalStateException("Unsupported query arity: " + s.arity);
            }
        }
    }

    // ── Relationship graph (structural, minimal) ─────────────────────────────────────────

    @Override
    public <R extends Relation> void link(long source, Class<R> relation, long target) {
        graph.computeIfAbsent(source, k -> new HashMap<>())
                .computeIfAbsent(relation, k -> new HashSet<>())
                .add(target);
    }

    @Override
    public <R extends Relation> void unlink(long source, Class<R> relation, long target) {
        Map<Class<? extends Relation>, Set<Long>> rels = graph.get(source);
        if (rels != null) {
            Set<Long> targets = rels.get(relation);
            if (targets != null) {
                targets.remove(target);
            }
        }
    }

    /**
     * Visits every live entity linked from {@code source} via {@code relation}. Backs the DX graph
     * query syntax ({@code com.hyperion.ecs.dsl.Relations}). Query path — not the zero-GC tick loop.
     *
     * @param source   the source entity handle
     * @param relation the relation type
     * @param action   invoked with each live target handle
     */
    public void related(long source, Class<? extends Relation> relation, LongConsumer action) {
        Map<Class<? extends Relation>, Set<Long>> rels = graph.get(source);
        if (rels == null) {
            return;
        }
        Set<Long> targets = rels.get(relation);
        if (targets == null) {
            return;
        }
        for (long t : targets) {
            if (entityStore.isAlive(t)) {
                action.accept(t);
            }
        }
    }

    // ── Rewind (contract present; ring buffer is Phase 4) ────────────────────────────────

    @Override
    public void enableRewindBuffer(int frames) {
        if (rewindBuffer != null) {
            rewindBuffer.close();
        }
        this.rewindBuffer = new OffHeapRewindBuffer(this, frames);
    }

    @Override
    public void rewind(int frame) {
        if (rewindBuffer == null) {
            throw new IllegalStateException("Rewind buffer not enabled; call enableRewindBuffer(frames) first.");
        }
        rewindBuffer.restore(frame);
    }

    /** @return the rewind ring buffer, or {@code null} if not enabled (for telemetry/tooling). */
    public OffHeapRewindBuffer rewindBuffer() {
        return rewindBuffer;
    }

    // ── Native memory lifecycle ──────────────────────────────────────────────────────────

    /** Releases all off-heap memory owned by this world. */
    @Override
    public void close() {
        if (rewindBuffer != null) {
            rewindBuffer.close();
            rewindBuffer = null;
        }
        arena.close();
    }
}
