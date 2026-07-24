package com.hyperion.ecs.core;

import com.hyperion.ecs.Component;
import com.hyperion.ecs.storage.ComponentType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;

/**
 * Cached reflective descriptor for one registered component: its {@link ComponentType}, the
 * {@link FieldKind}/accessor for each field, and the constructor used on the compatibility path.
 *
 * <p>Supports two component shapes (Phase 3 / ADR-0007):
 * <ul>
 *   <li><b>Immutable {@code record}</b> — fields = record components; read-only in systems.</li>
 *   <li><b>Mutable class with public primitive fields</b> — the zero-GC read/write path; fields =
 *       public non-static instance fields in declaration order.</li>
 * </ul>
 *
 * <p>All reflection happens once, at registration. The per-field helpers here run only on the
 * structural (spawn) or compatibility (record) paths — never the gated tick loop.
 *
 * @since Phase 2 (Perf-Core-Agent); extended for mutable components in Phase 3 (DX-Agent)
 */
final class RegisteredComponent {

    final int id;
    final Class<? extends Component> type;
    final ComponentType descriptor;
    final FieldKind[] fieldKinds;
    /** Field names in column order — used by the DX view binder. */
    final String[] fieldNames;
    final boolean mutableClass;

    private final Method[] recordAccessors;  // non-null iff record
    private final Field[] classFields;       // non-null iff mutable class
    private final Constructor<?> constructor;

    @SuppressWarnings("unchecked")
    RegisteredComponent(int id, Class<? extends Component> type) {
        this.id = id;
        this.type = type;

        Class<?>[] paramTypes;
        if (type.isRecord()) {
            this.mutableClass = false;
            RecordComponent[] rcs = type.getRecordComponents();
            this.fieldKinds = new FieldKind[rcs.length];
            this.fieldNames = new String[rcs.length];
            this.recordAccessors = new Method[rcs.length];
            this.classFields = null;
            paramTypes = new Class<?>[rcs.length];
            for (int i = 0; i < rcs.length; i++) {
                fieldKinds[i] = FieldKind.of(rcs[i].getType());
                fieldNames[i] = rcs[i].getName();
                recordAccessors[i] = rcs[i].getAccessor();
                recordAccessors[i].setAccessible(true);
                paramTypes[i] = rcs[i].getType();
            }
        } else {
            this.mutableClass = true;
            Field[] fields = publicInstanceFields(type);
            if (fields.length == 0) {
                throw new IllegalArgumentException(
                        "Component '" + type.getName() + "' must be a record or a class with public "
                                + "primitive fields (see Component contract / ADR-0007).");
            }
            this.fieldKinds = new FieldKind[fields.length];
            this.fieldNames = new String[fields.length];
            this.classFields = fields;
            this.recordAccessors = null;
            paramTypes = new Class<?>[fields.length];
            for (int i = 0; i < fields.length; i++) {
                fieldKinds[i] = FieldKind.of(fields[i].getType());
                fieldNames[i] = fields[i].getName();
                fields[i].setAccessible(true);
                paramTypes[i] = fields[i].getType();
            }
        }

        int byteSize = 0;
        int maxAlign = 1;
        for (FieldKind k : fieldKinds) {
            byteSize += k.byteSize();
            maxAlign = Math.max(maxAlign, k.byteSize());
        }

        try {
            this.constructor = type.getDeclaredConstructor(paramTypes);
            this.constructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "Component " + type.getName() + " needs an all-args constructor ("
                            + paramTypes.length + " primitive params).", e);
        }
        this.descriptor = new ComponentType(id, type, byteSize, maxAlign, fieldKinds.length);
    }

    private static Field[] publicInstanceFields(Class<?> type) {
        Field[] all = type.getFields();
        int n = 0;
        for (Field f : all) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType().isPrimitive()) {
                n++;
            }
        }
        Field[] out = new Field[n];
        int i = 0;
        // Preserve declaration order of the declaring class where possible.
        for (Field f : type.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers())
                    && f.getType().isPrimitive()) {
                out[i++] = f;
            }
        }
        return i == n ? out : java.util.Arrays.copyOf(out, i);
    }

    int fieldCount() {
        return fieldKinds.length;
    }

    /** Writes a component instance's fields into the archetype's SoA columns at {@code row}. Spawn path. */
    void write(ArchetypeTable table, int row, Object instance) {
        for (int f = 0; f < fieldKinds.length; f++) {
            long off = table.fieldOffset(id, f, row);
            try {
                Object value = (recordAccessors != null)
                        ? recordAccessors[f].invoke(instance)
                        : classFields[f].get(instance);
                fieldKinds[f].writeBoxed(table.segment(), off, value);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Failed reading field of " + type.getName(), e);
            }
        }
    }

    /**
     * Reconstructs a component instance from the SoA columns at {@code row}.
     * <b>Compatibility path only</b> — allocates, and is therefore not used by zero-GC systems.
     */
    Component read(ArchetypeTable table, int row) {
        Object[] args = new Object[fieldKinds.length];
        for (int f = 0; f < fieldKinds.length; f++) {
            long off = table.fieldOffset(id, f, row);
            args[f] = fieldKinds[f].readBoxed(table.segment(), off);
        }
        try {
            return (Component) constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed constructing " + type.getName(), e);
        }
    }

    /** Allocates a blank view instance (all-zero fields) for reuse by the zero-cost DX layer. Setup path. */
    Object newBlankInstance() {
        Object[] args = new Object[fieldKinds.length];
        for (int f = 0; f < fieldKinds.length; f++) {
            args[f] = fieldKinds[f].zero();
        }
        try {
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed constructing blank " + type.getName(), e);
        }
    }
}
