package com.backup.crypto;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class HashUtil {
    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

    private HashUtil() {}

    public static MessageDigest getSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM missing SHA-256 support", e);
        }
    }

    public static String toHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static String sha256Hex(byte[] data) {
        if (data == null || data.length == 0) {
            return sha256Hex(new byte[0], 0, 0);
        }
        return sha256Hex(data, 0, data.length);
    }

    public static String sha256Hex(byte[] data, int offset, int length) {
        MessageDigest md = getSha256Digest();
        md.update(data, offset, length);
        return toHex(md.digest());
    }

    public static String sha256Hex(ByteBuffer buffer) {
        MessageDigest md = getSha256Digest();
        md.update(buffer);
        return toHex(md.digest());
    }
}
