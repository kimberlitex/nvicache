package com.nvicache.mixin;

import com.nvicache.interfaces.ISegmentedManagerCacheable;
import it.unimi.dsi.fastutil.longs.LongRBTreeSet;
import me.cortex.nvidium.util.SegmentedManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

@Mixin(value = SegmentedManager.class, remap = false)
public class MixinSegmentedManager implements ISegmentedManagerCacheable {

    @Shadow private LongRBTreeSet FREE;
    @Shadow private LongRBTreeSet TAKEN;
    @Shadow private long totalSize;
    @Shadow private long sizeLimit;

    @Unique
    private static final int ADDR_BITS_NVICACHE = 34;
    @Unique
    private static final long SIZE_MSK_NVICACHE = (1L << (64 - ADDR_BITS_NVICACHE)) - 1;
    @Unique
    private static final long ADDR_MSK_NVICACHE = (1L << ADDR_BITS_NVICACHE) - 1;

    @Unique
    public void nvicache$writeTo(DataOutputStream out) throws IOException {
        out.writeLong(totalSize);
        out.writeLong(sizeLimit);
        out.writeInt(FREE.size());
        for (long v : FREE) out.writeLong(v);
        out.writeInt(TAKEN.size());
        for (long v : TAKEN) out.writeLong(v);
    }

    @Unique
    public void nvicache$readFrom(DataInputStream in) throws IOException {
        totalSize = in.readLong();
        sizeLimit = in.readLong();
        FREE.clear();
        int freeSize = in.readInt();
        for (int i = 0; i < freeSize; i++) FREE.add(in.readLong());
        TAKEN.clear();
        int takenSize = in.readInt();
        for (int i = 0; i < takenSize; i++) TAKEN.add(in.readLong());
    }

    @Unique
    public boolean nvicache$contains(long addr) {
        addr &= ADDR_MSK_NVICACHE;
        var iter = TAKEN.iterator(addr << (64 - ADDR_BITS_NVICACHE));
        if (!iter.hasNext()) return false;
        return iter.nextLong() >> (64 - ADDR_BITS_NVICACHE) == addr;
    }

    @Unique
    public void nvicache$forEachTaken(ISegmentedManagerCacheable.LongLongConsumer consumer) {
        for (long entry : TAKEN) {
            long addr = entry >>> (64 - ADDR_BITS_NVICACHE);
            long size = entry & SIZE_MSK_NVICACHE;
            consumer.accept(addr, size);
        }
    }
}
