package com.backup.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class FrameHeader {
    public static final short PROTOCOL_MAGIC = (short) 0x4253;
    public static final byte PROTOCOL_VERSION = (byte) 0x01;
    public static final int HEADER_LENGTH = 16;
    public static final int MAX_PAYLOAD_LENGTH = 16 * 1024 * 1024; // 16 MB

    private final short magic;
    private final byte version;
    private final MessageType type;
    private final int requestId;
    private final int payloadLength;
    private final int crc32;

    public FrameHeader(short magic, byte version, MessageType type, int requestId, int payloadLength, int crc32) {
        this.magic = magic;
        this.version = version;
        this.type = type;
        this.requestId = requestId;
        this.payloadLength = payloadLength;
        this.crc32 = crc32;
    }

    public FrameHeader(MessageType type, int requestId, int payloadLength, int crc32) {
        this(PROTOCOL_MAGIC, PROTOCOL_VERSION, type, requestId, payloadLength, crc32);
    }

    public short getMagic() { return magic; }
    public byte getVersion() { return version; }
    public MessageType getType() { return type; }
    public int getRequestId() { return requestId; }
    public int getPayloadLength() { return payloadLength; }
    public int getCrc32() { return crc32; }

    public void encode(ByteBuffer buffer) {
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putShort(magic);
        buffer.put(version);
        buffer.put(type.getCode());
        buffer.putInt(requestId);
        buffer.putInt(payloadLength);
        buffer.putInt(crc32);
    }

    public static FrameHeader decode(ByteBuffer buffer) throws IOException {
        buffer.order(ByteOrder.BIG_ENDIAN);
        if (buffer.remaining() < HEADER_LENGTH) {
            throw new IOException("Buffer underflow: cannot decode 16-byte frame header");
        }

        short magic = buffer.getShort();
        if (magic != PROTOCOL_MAGIC) {
            throw new IOException(String.format("Invalid protocol magic: 0x%04X (expected 0x%04X)", magic, PROTOCOL_MAGIC));
        }

        byte version = buffer.get();
        if (version != PROTOCOL_VERSION) {
            throw new IOException(String.format("Unsupported protocol version: %d (expected %d)", version, PROTOCOL_VERSION));
        }

        byte typeCode = buffer.get();
        MessageType type = MessageType.fromCode(typeCode);
        int requestId = buffer.getInt();
        int payloadLength = buffer.getInt();
        int crc32 = buffer.getInt();

        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_LENGTH) {
            throw new IOException("Payload length exceeds limit: " + payloadLength);
        }

        return new FrameHeader(magic, version, type, requestId, payloadLength, crc32);
    }

    @Override
    public String toString() {
        return String.format("FrameHeader[type=%s, reqId=%d, len=%d, crc32=0x%08X]",
                type, requestId, payloadLength, crc32);
    }
}
