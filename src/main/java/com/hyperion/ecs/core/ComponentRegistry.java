package com.hyperion.ecs.core;

import com.hyperion.ecs.Component;
import com.hyperion.ecs.storage.ComponentBitmask;

import java.util.HashMap;
import java.util.Map;

/**
 * Assigns a stable component id (= bitmask bit index) to each component type on first sight and
 * caches its {@link RegisteredComponent} descriptor.
 *
 * <p>All work here is structural (setup) — never invoked from the tick loop.
 *
 * @since Phase 2 (Perf-Core-Agent)
 */
final class ComponentRegistry {

    private final Map<Class<?>, RegisteredComponent> byClass = new HashMap<>();
    private final RegisteredComponent[] byId = new RegisteredComponent[ComponentBitmask.BIT_WIDTH];
    private int nextId = 0;

    RegisteredComponent register(Class<? extends Component> type) {
        RegisteredComponent existing = byClass.get(type);
        if (existing != null) {
            return existing;
        }
        if (nextId >= ComponentBitmask.BIT_WIDTH) {
            throw new IllegalStateException(
                    "Exceeded the " + ComponentBitmask.BIT_WIDTH + "-component limit imposed by the "
                            + "archetype bitmask width (ADR-0002).");
        }
        RegisteredComponent rc = new RegisteredComponent(nextId, type);
        byClass.put(type, rc);
        byId[nextId] = rc;
        nextId++;
        return rc;
    }

    int idOf(Class<? extends Component> type) {
        return register(type).id;
    }

    RegisteredComponent byId(int id) {
        return byId[id];
    }

    int count() {
        return nextId;
    }
}
