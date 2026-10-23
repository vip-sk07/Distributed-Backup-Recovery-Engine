package com.backup.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ProtocolFrame {
    private final FrameHeader header;
    private final byte[] payload;

    public ProtocolFrame(FrameHeader header, byte[] payload) {
        this.header = header;
        this.payload = payload != null ? payload : new byte[0];
    }

    public static ProtocolFrame create(MessageType type, int requestId, byte[] payload) {
        byte[] safePayload = payload != null ? payload : new byte[0];
        int crc = Crc32Util.compute(safePayload);
        FrameHeader hdr = new FrameHeader(type, requestId, safePayload.length, crc);
        return new ProtocolFrame(hdr, safePayload);
    }

    public static ProtocolFrame create(MessageType type, int requestId, String stringPayload) {
        byte[] bytes = stringPayload != null ? stringPayload.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return create(type, requestId, bytes);
    }

    public FrameHeader getHeader() {
        return header;
    }

    public byte[] getPayload() {
        return payload;
    }

    public String getPayloadAsString() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    public ByteBuffer toByteBuffer() {
        ByteBuffer buf = ByteBuffer.allocate(FrameHeader.HEADER_LENGTH + payload.length);
        header.encode(buf);
        buf.put(payload);
        buf.flip();
        return buf;
    }

    @Override
    public String toString() {
        return String.format("ProtocolFrame[%s, payloadLen=%d]", header, payload.length);
    }
}
