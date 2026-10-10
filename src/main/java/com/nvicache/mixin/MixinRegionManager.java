package com.nvicache.mixin;

import com.nvicache.interfaces.IIdProviderCacheable;
import com.nvicache.interfaces.IRegionManagerCacheable;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.nvidium.managers.RegionManager;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.util.IdProvider;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;

@Mixin(value = RegionManager.class, remap = false)
public abstract class MixinRegionManager implements IRegionManagerCacheable {

    @Shadow private Long2IntOpenHashMap regionMap;
    @Shadow private Long2IntOpenHashMap regionTransformationIdMapping;
    @Shadow private IdProvider idProvider;
    // regions and dirtyRegions are typed with private inner class — access via reflection
    @Unique private static final Field F_REGIONS;
    @Unique private static final Field F_DIRTY_REGIONS;

    // Region inner class fields
    @Unique private static Class<?> REGION_CLASS;
    @Unique private static final Field F_RX, F_RY, F_RZ, F_ID, F_COUNT, F_TRANSFORM_ID;
    @Unique private static final Field F_POS2ID, F_ID2POS, F_SECTION_DATA, F_IS_DIRTY;
    @Unique private static final Constructor<?> REGION_CTOR;

    static {
        try {
            Class<?> regionMgrClass = RegionManager.class;

            F_REGIONS = regionMgrClass.getDeclaredField("regions");
            F_REGIONS.setAccessible(true);
            F_DIRTY_REGIONS = regionMgrClass.getDeclaredField("dirtyRegions");
            F_DIRTY_REGIONS.setAccessible(true);

            Class<?> rc = null;
            for (Class<?> c : regionMgrClass.getDeclaredClasses()) {
                if (c.getSimpleName().equals("Region")) { rc = c; break; }
            }
            if (rc == null) throw new RuntimeException("Region class not found");
            REGION_CLASS = rc;

            F_RX           = field(rc, "rx");
            F_RY           = field(rc, "ry");
            F_RZ           = field(rc, "rz");
            F_ID           = field(rc, "id");
            F_COUNT        = field(rc, "count");
            F_TRANSFORM_ID = field(rc, "transformationId");
            F_POS2ID       = field(rc, "pos2id");
            F_ID2POS       = field(rc, "id2pos");
            F_SECTION_DATA = field(rc, "sectionData");
            F_IS_DIRTY     = field(rc, "isDirty");

            Constructor<?> ctor = rc.getDeclaredConstructor(int.class, int.class, int.class, int.class);
            ctor.setAccessible(true);
            REGION_CTOR = ctor;
        } catch (Exception e) {
            throw new RuntimeException("nvicache: failed to init RegionManager reflection", e);
        }
    }

    @Unique
    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @Override
    public void nvicache$serialize(DataOutputStream out) throws IOException {
        try {
            out.writeInt(regionMap.size());
            for (var e : regionMap.long2IntEntrySet()) { out.writeLong(e.getLongKey()); out.writeInt(e.getIntValue()); }

            out.writeInt(regionTransformationIdMapping.size());
            for (var e : regionTransformationIdMapping.long2IntEntrySet()) { out.writeLong(e.getLongKey()); out.writeInt(e.getIntValue()); }

            ((IIdProviderCacheable)(Object) idProvider).nvicache$writeTo(out);

            Object[] regions = (Object[]) F_REGIONS.get(this);
            int maxIdx = idProvider.maxIndex();
            out.writeInt(maxIdx);
            int sectionDataLen = 256 * SectionManager.SECTION_SIZE;
            for (int i = 0; i < maxIdx; i++) {
                Object r = regions[i];
                out.writeBoolean(r != null);
                if (r == null) continue;
                nvicache$writeRegion(out, r, sectionDataLen);
            }
        } catch (IllegalAccessException e) {
            throw new IOException("nvicache reflection error", e);
        }
    }

    @Override
    public void nvicache$deserialize(DataInputStream in) throws IOException {
        try {
            regionMap.clear();
            int s = in.readInt();
            for (int i = 0; i < s; i++) regionMap.put(in.readLong(), in.readInt());

            regionTransformationIdMapping.clear();
            s = in.readInt();
            for (int i = 0; i < s; i++) regionTransformationIdMapping.put(in.readLong(), in.readInt());

            ((IIdProviderCacheable)(Object) idProvider).nvicache$readFrom(in);

            Object[] regions = (Object[]) F_REGIONS.get(this);
            @SuppressWarnings("unchecked")
            ArrayDeque<Object> dirtyRegions = (ArrayDeque<Object>) F_DIRTY_REGIONS.get(this);

            int sectionDataLen = 256 * SectionManager.SECTION_SIZE;
            for (Object r : regions) {
                if (r != null) {
                    MemoryUtil.nmemFree((long) F_SECTION_DATA.get(r));
                }
            }
            java.util.Arrays.fill(regions, null);
            dirtyRegions.clear();

            int maxIdx = in.readInt();
            for (int i = 0; i < maxIdx; i++) {
                boolean present = in.readBoolean();
                if (!present) continue;
                Object r = nvicache$readRegion(in, sectionDataLen);
                int id = (int) F_ID.get(r);
                regions[id] = r;
                // mark dirty
                boolean isDirty = (boolean) F_IS_DIRTY.get(r);
                if (!isDirty) {
                    F_IS_DIRTY.set(r, true);
                    dirtyRegions.add(r);
                }
            }
        } catch (IllegalAccessException | InstantiationException |
                 java.lang.reflect.InvocationTargetException e) {
            throw new IOException("nvicache reflection error", e);
        }
    }

    @Unique
    private void nvicache$writeRegion(DataOutputStream out, Object r, int sectionDataLen) throws IOException {
        try {
            out.writeInt((int) F_RX.get(r));
            out.writeInt((int) F_RY.get(r));
            out.writeInt((int) F_RZ.get(r));
            out.writeInt((int) F_ID.get(r));
            out.writeInt((int) F_COUNT.get(r));
            out.writeInt((int) F_TRANSFORM_ID.get(r));
            for (int v : (int[]) F_POS2ID.get(r)) out.writeInt(v);
            for (int v : (int[]) F_ID2POS.get(r)) out.writeInt(v);
            byte[] bytes = new byte[sectionDataLen];
            MemoryUtil.memByteBuffer((long) F_SECTION_DATA.get(r), sectionDataLen).get(0, bytes);
            out.write(bytes);
        } catch (IllegalAccessException e) {
            throw new IOException(e);
        }
    }

    @Unique
    private Object nvicache$readRegion(DataInputStream in, int sectionDataLen)
            throws IOException, IllegalAccessException, InstantiationException,
                   java.lang.reflect.InvocationTargetException {
        int rx = in.readInt(), ry = in.readInt(), rz = in.readInt();
        int id = in.readInt();
        int count = in.readInt();
        int transformationId = in.readInt();
        Object r = REGION_CTOR.newInstance(id, rx, ry, rz);
        F_COUNT.set(r, count);
        F_TRANSFORM_ID.set(r, transformationId);
        int[] pos2id = (int[]) F_POS2ID.get(r);
        int[] id2pos = (int[]) F_ID2POS.get(r);
        for (int j = 0; j < 256; j++) pos2id[j] = in.readInt();
        for (int j = 0; j < 256; j++) id2pos[j] = in.readInt();
        byte[] bytes = new byte[sectionDataLen];
        in.readFully(bytes);
        MemoryUtil.memByteBuffer((long) F_SECTION_DATA.get(r), sectionDataLen).put(0, bytes);
        return r;
    }
}
