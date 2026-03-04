package com.nvicache.mixin;

import com.nvicache.interfaces.IBufferArenaCacheable;
import com.nvicache.interfaces.IRegionManagerCacheable;
import com.nvicache.interfaces.ISectionManagerCacheable;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.nvidium.managers.RegionManager;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.util.BufferArena;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

@Mixin(value = SectionManager.class, remap = false)
public class MixinSectionManager implements ISectionManagerCacheable {

    @Shadow private Long2IntOpenHashMap section2id;
    @Shadow private Long2IntOpenHashMap section2terrain;
    @Shadow private Long2IntOpenHashMap section2index;
    @Shadow private Long2ObjectOpenHashMap<int[]> translucencyQuadCounts;
    @Shadow private LongSet hiddenSectionKeys;
    @Shadow public BufferArena terrainAreana;
    @Shadow public BufferArena translucencyIndexArena;
    @Shadow private RegionManager regionManager;

    @Override
    public void nvicache$serialize(DataOutputStream meta, FileChannel terrain, FileChannel index) throws IOException {
        meta.writeInt(section2id.size());
        for (var e : section2id.long2IntEntrySet()) { meta.writeLong(e.getLongKey()); meta.writeInt(e.getIntValue()); }

        meta.writeInt(section2terrain.size());
        for (var e : section2terrain.long2IntEntrySet()) { meta.writeLong(e.getLongKey()); meta.writeInt(e.getIntValue()); }

        meta.writeInt(section2index.size());
        for (var e : section2index.long2IntEntrySet()) { meta.writeLong(e.getLongKey()); meta.writeInt(e.getIntValue()); }

        meta.writeInt(translucencyQuadCounts.size());
        for (var e : translucencyQuadCounts.long2ObjectEntrySet()) {
            meta.writeLong(e.getLongKey());
            for (int v : e.getValue()) meta.writeInt(v);
        }

        meta.writeInt(hiddenSectionKeys.size());
        for (long k : hiddenSectionKeys) meta.writeLong(k);

        ((IRegionManagerCacheable) regionManager).nvicache$serialize(meta);
        ((IBufferArenaCacheable) terrainAreana).nvicache$saveToCache(meta, terrain);
        ((IBufferArenaCacheable) translucencyIndexArena).nvicache$saveToCache(meta, index);
    }

    @Override
    public void nvicache$deserialize(DataInputStream meta, FileChannel terrain, FileChannel index) throws IOException {
        section2id.clear();
        int s = meta.readInt();
        for (int i = 0; i < s; i++) section2id.put(meta.readLong(), meta.readInt());

        section2terrain.clear();
        s = meta.readInt();
        for (int i = 0; i < s; i++) section2terrain.put(meta.readLong(), meta.readInt());

        section2index.clear();
        s = meta.readInt();
        for (int i = 0; i < s; i++) section2index.put(meta.readLong(), meta.readInt());

        translucencyQuadCounts.clear();
        s = meta.readInt();
        for (int i = 0; i < s; i++) {
            long key = meta.readLong();
            int[] arr = new int[7];
            for (int j = 0; j < 7; j++) arr[j] = meta.readInt();
            translucencyQuadCounts.put(key, arr);
        }

        hiddenSectionKeys.clear();
        s = meta.readInt();
        for (int i = 0; i < s; i++) hiddenSectionKeys.add(meta.readLong());

        ((IRegionManagerCacheable) regionManager).nvicache$deserialize(meta);
        ((IBufferArenaCacheable) terrainAreana).nvicache$loadFromCache(meta, terrain);
        ((IBufferArenaCacheable) translucencyIndexArena).nvicache$loadFromCache(meta, index);
    }

    @Override
    public void nvicache$resetState() {
        section2id.clear();
        section2terrain.clear();
        section2index.clear();
        translucencyQuadCounts.clear();
        hiddenSectionKeys.clear();
    }
}
