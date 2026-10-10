package com.nvicache.mixin;

import com.nvicache.cache.PageStore;
import com.nvicache.interfaces.IBufferArenaCacheable;
import com.nvicache.interfaces.IRegionManagerCacheable;
import com.nvicache.interfaces.ISectionManagerCacheable;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.nvidium.managers.RegionManager;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.util.BufferArena;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

// Acedium's SectionManager has no separate translucency index arena, so only terrain is cached.
@Mixin(value = SectionManager.class, remap = false)
public class MixinSectionManager implements ISectionManagerCacheable {

    @Shadow private Long2IntOpenHashMap section2id;
    @Shadow private Long2IntOpenHashMap section2terrain;
    @Shadow private LongSet hiddenSectionKeys;
    @Shadow public BufferArena terrainAreana;
    @Shadow private RegionManager regionManager;

    @Override
    public void nvicache$serialize(DataOutputStream meta, PageStore pages) throws IOException {
        meta.writeInt(section2id.size());
        for (var e : section2id.long2IntEntrySet()) { meta.writeLong(e.getLongKey()); meta.writeInt(e.getIntValue()); }

        meta.writeInt(section2terrain.size());
        for (var e : section2terrain.long2IntEntrySet()) { meta.writeLong(e.getLongKey()); meta.writeInt(e.getIntValue()); }

        meta.writeInt(hiddenSectionKeys.size());
        for (long k : hiddenSectionKeys) meta.writeLong(k);

        ((IRegionManagerCacheable) regionManager).nvicache$serialize(meta);
        ((IBufferArenaCacheable) terrainAreana).nvicache$saveToCache(meta, pages);
    }

    @Override
    public void nvicache$deserialize(DataInputStream meta, PageStore pages) throws IOException {
        section2id.clear();
        int s = meta.readInt();
        for (int i = 0; i < s; i++) section2id.put(meta.readLong(), meta.readInt());

        section2terrain.clear();
        s = meta.readInt();
        for (int i = 0; i < s; i++) section2terrain.put(meta.readLong(), meta.readInt());

        hiddenSectionKeys.clear();
        s = meta.readInt();
        for (int i = 0; i < s; i++) hiddenSectionKeys.add(meta.readLong());

        ((IRegionManagerCacheable) regionManager).nvicache$deserialize(meta);
        ((IBufferArenaCacheable) terrainAreana).nvicache$loadFromCache(meta, pages);
    }

    @Override
    public void nvicache$resetState() {
        section2id.clear();
        section2terrain.clear();
        hiddenSectionKeys.clear();
    }

    @Override
    public int nvicache$trimForSave(double x, double z, long maxBytes) {
        int n = section2terrain.size();
        long[] keys = new long[n];
        double[] distSq = new double[n];
        Integer[] order = new Integer[n];
        int i = 0;
        for (var e : section2terrain.long2IntEntrySet()) {
            long key = e.getLongKey();
            double dx = SectionPos.x(key) * 16 + 8 - x;
            double dz = SectionPos.z(key) * 16 + 8 - z;
            keys[i] = key;
            distSq[i] = dx * dx + dz * dz;
            order[i] = i;
            i++;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(distSq[a], distSq[b]));

        var arena = (IBufferArenaCacheable) terrainAreana;
        LongArrayList drop = new LongArrayList();
        long kept = 0;
        boolean full = false;
        for (int idx : order) {
            long key = keys[idx];
            if (full) {
                drop.add(key);
                continue;
            }
            long bytes = arena.nvicache$allocationBytes(section2terrain.get(key));
            if (kept + bytes > maxBytes) {
                // Everything farther is dropped too, so the saved area stays a solid disc.
                full = true;
                drop.add(key);
                continue;
            }
            kept += bytes;
        }
        var self = (SectionManagerInvoker) this;
        for (int j = 0; j < drop.size(); j++) self.nvicache$deleteSection(drop.getLong(j));
        return drop.size();
    }
}
