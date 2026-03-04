package com.nvicache.interfaces;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

public interface IBufferArenaCacheable {
    void nvicache$saveToCache(DataOutputStream meta, FileChannel data) throws IOException;
    void nvicache$loadFromCache(DataInputStream meta, FileChannel data) throws IOException;
}
