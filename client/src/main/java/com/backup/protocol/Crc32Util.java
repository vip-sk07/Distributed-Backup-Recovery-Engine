package com.backup.protocol;

import java.util.zip.CRC32;

public final class Crc32Util {
    private Crc32Util() {}

    /**
     * Computes the IEEE 802.3 CRC32 checksum for the given byte array.
     * Returns an unsigned 32-bit integer representation (cast to int).
     */
    public static int compute(byte[] data) {
        if (data == null || data.length == 0) {
            return 0;
        }
        CRC32 crc = new CRC32();
        crc.update(data);
        return (int) (crc.getValue() & 0xFFFFFFFFL);
    }

    public static int compute(byte[] data, int offset, int length) {
        if (data == null || length <= 0) {
            return 0;
        }
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return (int) (crc.getValue() & 0xFFFFFFFFL);
    }
}
