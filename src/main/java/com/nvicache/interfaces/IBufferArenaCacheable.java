package com.nvicache.interfaces;

import com.nvicache.cache.PageStore;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public interface IBufferArenaCacheable {
    /** Writes the arena layout to meta and every page changed since the last load/save to the store. */
    void nvicache$saveToCache(DataOutputStream meta, PageStore pages) throws IOException;
    void nvicache$loadFromCache(DataInputStream meta, PageStore pages) throws IOException;
    /** Size in bytes of the allocation starting at the given quad address. */
    long nvicache$allocationBytes(int addr);
}
