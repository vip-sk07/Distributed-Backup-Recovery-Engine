package com.backup.model;

import java.util.Arrays;
import java.util.Objects;

public class Chunk {
    private final int index;
    private final long offset;
    private final int length;
    private final String sha256;
    private final byte[] data; // May be null when representing metadata only

    public Chunk(int index, long offset, int length, String sha256, byte[] data) {
        this.index = index;
        this.offset = offset;
        this.length = length;
        this.sha256 = Objects.requireNonNull(sha256);
        this.data = data;
    }

    public Chunk(int index, long offset, int length, String sha256) {
        this(index, offset, length, sha256, null);
    }

    public int getIndex() { return index; }
    public long getOffset() { return offset; }
    public int getLength() { return length; }
    public String getSha256() { return sha256; }
    public byte[] getData() { return data; }

    @Override
    public String toString() {
        return String.format("Chunk[idx=%d, off=%d, len=%d, sha256=%s]",
                index, offset, length, sha256.substring(0, Math.min(12, sha256.length())) + "...");
    }
}
