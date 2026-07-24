package com.hyperion.ecs.dsl;

import com.hyperion.ecs.Component;
import com.hyperion.ecs.core.HyperionWorld;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

/**
 * Runtime bytecode generator (JDK 25 {@link java.lang.classfile}) that synthesizes a
 * {@link Marshaler} specialized to a system's component set — the mechanism that makes the
 * ergonomic {@code each(...)} DSL genuinely zero-cost (direct-field speed), resolving ADR-0007.
 *
 * <p>For components {@code [C0..Cm]} with SoA-ordered primitive fields, it emits two straight-line
 * methods:
 *
 * <pre>{@code
 * void load(Object[] views, ChunkView chunk, int row) {
 *     ((C0) views[0]).f0 = chunk.getFloat(cid0, 0, row);   // getfield/putfield, no reflection
 *     ((C0) views[0]).f1 = chunk.getInt  (cid0, 1, row);
 *     ((C1) views[1]).g0 = chunk.getFloat(cid1, 0, row);
 *     ...
 * }
 * void store(Object[] views, ChunkView chunk, int row) {
 *     chunk.setFloat(cid0, 0, row, ((C0) views[0]).f0);
 *     ...
 * }
 * }</pre>
 *
 * <p>The class is defined as a hidden class in this package's lookup; it references only public
 * types (the component classes, {@link com.hyperion.ecs.core.ChunkView}, {@link Marshaler}), so no
 * elevated access is required. All generation is structural (one-time per system).
 *
 * @since JDK-25 migration (DX-Agent)
 */
final class MarshalerFactory {

    private static final ClassDesc CD_MARSHALER = ClassDesc.of("com.hyperion.ecs.dsl.Marshaler");
    private static final ClassDesc CD_CHUNKVIEW = ClassDesc.of("com.hyperion.ecs.core.ChunkView");
    private static final ClassDesc CD_OBJECT_ARRAY = ConstantDescs.CD_Object.arrayType();

    private MarshalerFactory() {
    }

    /**
     * Builds a {@link Marshaler} for the given components.
     *
     * @param world the world (source of stable component ids and SoA field order)
     * @param comps the system's component classes, in query order
     * @return a generated, direct-access marshaler
     */
    static Marshaler create(HyperionWorld world, Class<? extends Component>[] comps) {
        // Resolve per-component layout: component id, field names (SoA order), field types.
        int m = comps.length;
        int[] cids = new int[m];
        ClassDesc[] compDescs = new ClassDesc[m];
        String[][] names = new String[m][];
        Prim[][] kinds = new Prim[m][];
        for (int k = 0; k < m; k++) {
            cids[k] = world.componentId(comps[k]);
            compDescs[k] = ClassDesc.of(comps[k].getName());
            String[] fn = world.componentFieldNames(comps[k]);
            names[k] = fn;
            kinds[k] = new Prim[fn.length];
            for (int f = 0; f < fn.length; f++) {
                try {
                    kinds[k][f] = Prim.of(comps[k].getField(fn[f]).getType(), comps[k], fn[f]);
                } catch (NoSuchFieldException e) {
                    throw new IllegalStateException("Zero-cost view requires public field '" + fn[f]
                            + "' on " + comps[k].getName() + " (ADR-0007).", e);
                }
            }
        }

        ClassDesc genName = ClassDesc.of("com.hyperion.ecs.dsl.Marshaler$Gen");
        MethodTypeDesc mtd = MethodTypeDesc.of(ConstantDescs.CD_void,
                CD_OBJECT_ARRAY, CD_CHUNKVIEW, ConstantDescs.CD_int);

        byte[] bytes = ClassFile.of().build(genName, clb -> {
            clb.withInterfaceSymbols(CD_MARSHALER);
            // public no-arg constructor
            clb.withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC, cob ->
                    cob.aload(0)
                       .invokespecial(ConstantDescs.CD_Object, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void))
                       .return_());
            clb.withMethodBody("load", mtd, ClassFile.ACC_PUBLIC,
                    cob -> emit(cob, m, cids, compDescs, names, kinds, true));
            clb.withMethodBody("store", mtd, ClassFile.ACC_PUBLIC,
                    cob -> emit(cob, m, cids, compDescs, names, kinds, false));
        });

        try {
            MethodHandles.Lookup lk = MethodHandles.lookup().defineHiddenClass(bytes, true);
            return (Marshaler) lk.lookupClass().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to define generated Marshaler", e);
        }
    }

    // Locals: 0=this, 1=views (Object[]), 2=chunk (ChunkView), 3=row (int)
    private static void emit(CodeBuilder cob, int m, int[] cids, ClassDesc[] compDescs,
                             String[][] names, Prim[][] kinds, boolean load) {
        for (int k = 0; k < m; k++) {
            ClassDesc comp = compDescs[k];
            for (int f = 0; f < names[k].length; f++) {
                Prim p = kinds[k][f];
                if (load) {
                    // ((Ck) views[k]).name = chunk.getX(cid, f, row);
                    cob.aload(1).loadConstant(k).aaload().checkcast(comp);   // objectref (view)
                    cob.aload(2).loadConstant(cids[k]).loadConstant(f).iload(3)
                       .invokevirtual(CD_CHUNKVIEW, p.getter, p.getterDesc);  // value
                    cob.putfield(comp, names[k][f], p.desc);
                } else {
                    // chunk.setX(cid, f, row, ((Ck) views[k]).name);
                    cob.aload(2).loadConstant(cids[k]).loadConstant(f).iload(3);
                    cob.aload(1).loadConstant(k).aaload().checkcast(comp)
                       .getfield(comp, names[k][f], p.desc);
                    cob.invokevirtual(CD_CHUNKVIEW, p.setter, p.setterDesc);
                }
            }
        }
        cob.return_();
    }

    /** Supported primitive field kinds and their ChunkView accessor descriptors. */
    private enum Prim {
        FLOAT(ConstantDescs.CD_float, "getFloat", "setFloat"),
        INT(ConstantDescs.CD_int, "getInt", "setInt"),
        LONG(ConstantDescs.CD_long, "getLong", "setLong"),
        DOUBLE(ConstantDescs.CD_double, "getDouble", "setDouble");

        final ClassDesc desc;
        final String getter;
        final String setter;
        final MethodTypeDesc getterDesc; // (int cid, int field, int row) -> T
        final MethodTypeDesc setterDesc; // (int cid, int field, int row, T) -> void

        Prim(ClassDesc desc, String getter, String setter) {
            this.desc = desc;
            this.getter = getter;
            this.setter = setter;
            this.getterDesc = MethodTypeDesc.of(desc,
                    ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int);
            this.setterDesc = MethodTypeDesc.of(ConstantDescs.CD_void,
                    ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int, desc);
        }

        static Prim of(Class<?> t, Class<?> owner, String name) {
            if (t == float.class) return FLOAT;
            if (t == int.class) return INT;
            if (t == long.class) return LONG;
            if (t == double.class) return DOUBLE;
            throw new IllegalArgumentException("Zero-cost view field '" + owner.getSimpleName() + "."
                    + name + "' has unsupported type '" + t.getName() + "'. Supported: float,int,long,double.");
        }
    }
}
