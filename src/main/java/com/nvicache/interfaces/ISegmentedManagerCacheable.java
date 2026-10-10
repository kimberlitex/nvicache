package com.nvicache.interfaces;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public interface ISegmentedManagerCacheable {
    @FunctionalInterface
    interface LongLongConsumer {
        void accept(long addr, long size);
    }

    void nvicache$writeTo(DataOutputStream out) throws IOException;
    void nvicache$readFrom(DataInputStream in) throws IOException;
    void nvicache$forEachTaken(LongLongConsumer consumer);
}
