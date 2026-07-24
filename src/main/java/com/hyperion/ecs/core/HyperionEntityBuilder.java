package com.hyperion.ecs.core;

import com.hyperion.ecs.Component;
import com.hyperion.ecs.EntityBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Concrete {@link EntityBuilder}. Stages components on the (structural) spawn path and, on
 * {@link #id()}, resolves the target archetype, appends one row, and writes the field data in a
 * single pass. Never touches the tick loop.
 *
 * @since Phase 2 (Perf-Core-Agent)
 */
final class HyperionEntityBuilder implements EntityBuilder {

    private final HyperionWorld world;
    private final Map<Class<? extends Component>, Object> staged = new LinkedHashMap<>();

    HyperionEntityBuilder(HyperionWorld world) {
        this.world = world;
    }

    @Override
    public <T extends Component> EntityBuilder add(T component) {
        staged.put(componentClass(component), component);
        return this;
    }

    @Override
    public <T extends Component> EntityBuilder remove(Class<T> type) {
        staged.remove(type);
        return this;
    }

    @Override
    public long id() {
        int n = staged.size();
        int[] ids = new int[n];
        RegisteredComponent[] comps = new RegisteredComponent[n];
        Object[] instances = new Object[n];
        int i = 0;
        for (Map.Entry<Class<? extends Component>, Object> e : staged.entrySet()) {
            RegisteredComponent rc = world.registry().register(e.getKey());
            ids[i] = rc.id;
            comps[i] = rc;
            instances[i] = e.getValue();
            i++;
        }
        return world.materialize(ids, comps, instances);
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Component> componentClass(Component c) {
        return (Class<? extends Component>) c.getClass();
    }
}
