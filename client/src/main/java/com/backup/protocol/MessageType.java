package com.backup.protocol;

public enum MessageType {
    HELLO((byte) 0x01),
    HELLO_RESP((byte) 0x02),
    ACK((byte) 0x03),
    ERROR((byte) 0x04),

    /* Phase 3: Upload pipeline */
    BEGIN_UPLOAD((byte) 0x10),
    NEED_CHUNKS((byte) 0x11),
    PUT_CHUNK((byte) 0x12),
    COMMIT_UPLOAD((byte) 0x13),

    /* Phase 4: Download / Restore */
    GET_MANIFEST((byte) 0x20),
    GET_CHUNK((byte) 0x21);

    private final byte code;

    MessageType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static MessageType fromCode(byte code) {
        for (MessageType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException(String.format("Unknown MessageType code: 0x%02X", code));
    }
}
