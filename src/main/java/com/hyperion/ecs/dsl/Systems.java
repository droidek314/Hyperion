package com.hyperion.ecs.dsl;

import com.hyperion.ecs.Component;
import com.hyperion.ecs.core.ChunkFunction;
import com.hyperion.ecs.core.HyperionWorld;
import com.hyperion.ecs.query.Each1;
import com.hyperion.ecs.query.Each2;
import com.hyperion.ecs.query.Each3;
import com.hyperion.ecs.query.Each4;
import com.hyperion.ecs.query.Query;

/**
 * The Phase-3 zero-cost, ergonomic system DSL. Setup stays within the &le; 3-line DX budget:
 *
 * <pre>{@code
 * Systems.of(world)
 *        .with(Position.class, Velocity.class)
 *        .each((long id, Position pos, Velocity vel) -> { pos.x += vel.dx; pos.y += vel.dy; });
 * }</pre>
 *
 * <p><b>Zero-cost abstraction.</b> The terminal {@code each(...)} does not iterate records — it
 * compiles down to a primitive {@link ChunkFunction} (registered via
 * {@link HyperionWorld#registerPrimitive}) closing over reused {@link ComponentBinder} views. The
 * user's lambda touches plain mutable fields ({@code pos.x}); the surrounding per-row field
 * load/store is boxing-free {@code VarHandle}/{@code MemorySegment} access. Net per-tick heap
 * allocation: <b>0 bytes</b> (verified in {@code DxVerification}). This requires <em>mutable class</em>
 * components (ADR-0007); immutable records use the Phase-1 {@code World.createSystem()} read path.
 *
 * @since Phase 3 (DX-Agent)
 */
public final class Systems {

    private final HyperionWorld world;

    private Systems(HyperionWorld world) {
        this.world = world;
    }

    /**
     * @param world the target world (the concrete off-heap engine)
     * @return a system builder
     */
    public static Systems of(HyperionWorld world) {
        return new Systems(world);
    }

    public <A extends Component> Stage1<A> with(Class<A> a) {
        return new Stage1<>(world, a);
    }

    public <A extends Component, B extends Component> Stage2<A, B> with(Class<A> a, Class<B> b) {
        return new Stage2<>(world, a, b);
    }

    public <A extends Component, B extends Component, C extends Component> Stage3<A, B, C> with(
            Class<A> a, Class<B> b, Class<C> c) {
        return new Stage3<>(world, a, b, c);
    }

    public <A extends Component, B extends Component, C extends Component, D extends Component> Stage4<A, B, C, D> with(
            Class<A> a, Class<B> b, Class<C> c, Class<D> d) {
        return new Stage4<>(world, a, b, c, d);
    }

    // ── Compilation: ergonomic each(...) -> primitive ChunkFunction (codegen-backed) ─────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ChunkFunction compile(Marshaler marshaler, Object[] views, Object eachFn, int arity) {
        // Monomorphic call site per system: `marshaler` is one generated class, so load/store
        // inline to straight-line getfield/putfield. Zero allocation on the tick path.
        return chunk -> {
            int n = chunk.count();
            for (int r = 0; r < n; r++) {
                long e = chunk.entity(r);
                marshaler.load(views, chunk, r);
                switch (arity) {
                    case 1 -> ((Each1) eachFn).apply(e, (Component) views[0]);
                    case 2 -> ((Each2) eachFn).apply(e, (Component) views[0], (Component) views[1]);
                    case 3 -> ((Each3) eachFn).apply(e, (Component) views[0], (Component) views[1],
                            (Component) views[2]);
                    case 4 -> ((Each4) eachFn).apply(e, (Component) views[0], (Component) views[1],
                            (Component) views[2], (Component) views[3]);
                    default -> throw new IllegalStateException();
                }
                marshaler.store(views, chunk, r);
            }
        };
    }

    private static Query register(HyperionWorld world, Class<? extends Component>[] included,
                                  Class<? extends Component>[] excluded, Object eachFn, int arity) {
        Object[] views = new Object[included.length];
        for (int i = 0; i < included.length; i++) {
            views[i] = newView(included[i]);
        }
        Marshaler marshaler = MarshalerFactory.create(world, included);
        return world.registerPrimitive(compile(marshaler, views, eachFn, arity), included, excluded);
    }

    /** Allocates one blank reused view instance per component (all-zero fields). Structural. */
    private static Object newView(Class<?> type) {
        for (var ctor : type.getDeclaredConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            Object[] args = new Object[p.length];
            boolean ok = true;
            for (int i = 0; i < p.length; i++) {
                if (p[i] == float.class) args[i] = 0f;
                else if (p[i] == int.class) args[i] = 0;
                else if (p[i] == long.class) args[i] = 0L;
                else if (p[i] == double.class) args[i] = 0d;
                else if (p[i] == boolean.class) args[i] = false;
                else if (p[i] == short.class) args[i] = (short) 0;
                else if (p[i] == byte.class) args[i] = (byte) 0;
                else if (p[i] == char.class) args[i] = (char) 0;
                else { ok = false; break; }
            }
            if (ok) {
                try {
                    ctor.setAccessible(true);
                    return ctor.newInstance(args);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot instantiate view " + type.getName(), e);
                }
            }
        }
        throw new IllegalArgumentException("Component '" + type.getName()
                + "' needs an all-primitive constructor to serve as a zero-cost view (ADR-0007).");
    }

    // ── Typed stages ─────────────────────────────────────────────────────────────────────

    /** A stage holds the included component classes and an optional exclusion set. */
    public abstract sealed static class Stage permits Stage1, Stage2, Stage3, Stage4 {
    }

    public static final class Stage1<A extends Component> extends Stage {
        private final HyperionWorld world;
        private final Class<A> a;
        private Class<? extends Component>[] excluded;

        Stage1(HyperionWorld world, Class<A> a) {
            this.world = world;
            this.a = a;
        }

        @SafeVarargs
        public final Stage1<A> without(Class<? extends Component>... exclude) {
            this.excluded = exclude;
            return this;
        }

        @SuppressWarnings("unchecked")
        public Query each(Each1<A> fn) {
            return register(world, new Class[]{a}, excluded, fn, 1);
        }
    }

    public static final class Stage2<A extends Component, B extends Component> extends Stage {
        private final HyperionWorld world;
        private final Class<A> a;
        private final Class<B> b;
        private Class<? extends Component>[] excluded;

        Stage2(HyperionWorld world, Class<A> a, Class<B> b) {
            this.world = world;
            this.a = a;
            this.b = b;
        }

        @SafeVarargs
        public final Stage2<A, B> without(Class<? extends Component>... exclude) {
            this.excluded = exclude;
            return this;
        }

        @SuppressWarnings("unchecked")
        public Query each(Each2<A, B> fn) {
            return register(world, new Class[]{a, b}, excluded, fn, 2);
        }
    }

    public static final class Stage3<A extends Component, B extends Component, C extends Component>
            extends Stage {
        private final HyperionWorld world;
        private final Class<A> a;
        private final Class<B> b;
        private final Class<C> c;
        private Class<? extends Component>[] excluded;

        Stage3(HyperionWorld world, Class<A> a, Class<B> b, Class<C> c) {
            this.world = world;
            this.a = a;
            this.b = b;
            this.c = c;
        }

        @SafeVarargs
        public final Stage3<A, B, C> without(Class<? extends Component>... exclude) {
            this.excluded = exclude;
            return this;
        }

        @SuppressWarnings("unchecked")
        public Query each(Each3<A, B, C> fn) {
            return register(world, new Class[]{a, b, c}, excluded, fn, 3);
        }
    }

    public static final class Stage4<A extends Component, B extends Component,
            C extends Component, D extends Component> extends Stage {
        private final HyperionWorld world;
        private final Class<A> a;
        private final Class<B> b;
        private final Class<C> c;
        private final Class<D> d;
        private Class<? extends Component>[] excluded;

        Stage4(HyperionWorld world, Class<A> a, Class<B> b, Class<C> c, Class<D> d) {
            this.world = world;
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
        }

        @SafeVarargs
        public final Stage4<A, B, C, D> without(Class<? extends Component>... exclude) {
            this.excluded = exclude;
            return this;
        }

        @SuppressWarnings("unchecked")
        public Query each(Each4<A, B, C, D> fn) {
            return register(world, new Class[]{a, b, c, d}, excluded, fn, 4);
        }
    }
}
