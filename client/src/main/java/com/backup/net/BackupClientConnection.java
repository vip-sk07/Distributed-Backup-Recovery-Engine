package com.backup.net;

import com.backup.protocol.Crc32Util;
import com.backup.protocol.FrameHeader;
import com.backup.protocol.MessageType;
import com.backup.protocol.ProtocolFrame;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BackupClientConnection implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(BackupClientConnection.class.getName());

    private final String host;
    private final int port;
    private SocketChannel channel;
    private final AtomicInteger requestIdGenerator = new AtomicInteger(1);

    public BackupClientConnection(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public synchronized void connect() throws IOException {
        if (channel != null && channel.isOpen()) {
            return;
        }
        LOGGER.info(String.format("Connecting to backup server at %s:%d...", host, port));
        channel = SocketChannel.open();
        channel.configureBlocking(true); // Blocking mode for reliable frame sequencing
        channel.connect(new InetSocketAddress(host, port));
        channel.socket().setTcpNoDelay(true); // Disable Nagle's algorithm
        LOGGER.info(String.format("Connected successfully to %s:%d (local: %s)",
                host, port, channel.getLocalAddress()));
    }

    public synchronized void sendFrame(ProtocolFrame frame) throws IOException {
        ensureConnected();
        ByteBuffer buf = frame.toByteBuffer();
        writeFully(buf);
        LOGGER.fine(() -> String.format("Sent frame: %s", frame.getHeader()));
    }

    public synchronized ProtocolFrame receiveFrame() throws IOException {
        ensureConnected();

        // 1. Read the 16-byte fixed frame header
        ByteBuffer headerBuf = ByteBuffer.allocate(FrameHeader.HEADER_LENGTH);
        readFully(headerBuf);
        headerBuf.flip();

        FrameHeader header = FrameHeader.decode(headerBuf);
        LOGGER.fine(() -> String.format("Received header: %s", header));

        // 2. Read the payload if present
        byte[] payloadBytes = new byte[header.getPayloadLength()];
        if (header.getPayloadLength() > 0) {
            ByteBuffer payloadBuf = ByteBuffer.wrap(payloadBytes);
            readFully(payloadBuf);

            // 3. Validate CRC32
            int calculatedCrc = Crc32Util.compute(payloadBytes);
            if (calculatedCrc != header.getCrc32()) {
                throw new IOException(String.format(
                        "CRC32 mismatch! Expected 0x%08X, got 0x%08X for payload of len %d",
                        header.getCrc32(), calculatedCrc, header.getPayloadLength()
                ));
            }
        }

        return new ProtocolFrame(header, payloadBytes);
    }

    public synchronized ProtocolFrame sendRequest(MessageType type, byte[] payload) throws IOException {
        int reqId = requestIdGenerator.getAndIncrement();
        ProtocolFrame request = ProtocolFrame.create(type, reqId, payload);
        sendFrame(request);

        ProtocolFrame response = receiveFrame();
        if (response.getHeader().getRequestId() != reqId) {
            LOGGER.warning(String.format("Mismatched Request ID: sent %d, received %d",
                    reqId, response.getHeader().getRequestId()));
        }
        return response;
    }

    public synchronized String sendHello(String clientIdentity) throws IOException {
        LOGGER.info("Initiating HELLO handshake with payload: " + clientIdentity);
        byte[] payload = clientIdentity != null ? clientIdentity.getBytes() : new byte[0];
        ProtocolFrame response = sendRequest(MessageType.HELLO, payload);

        if (response.getHeader().getType() == MessageType.HELLO_RESP) {
            String serverGreeting = response.getPayloadAsString();
            LOGGER.info("Handshake successful! Server responded: " + serverGreeting);
            return serverGreeting;
        } else if (response.getHeader().getType() == MessageType.ERROR) {
            throw new IOException("Server returned error during HELLO: " + response.getPayloadAsString());
        } else {
            throw new IOException("Unexpected response type to HELLO: " + response.getHeader().getType());
        }
    }

    private void readFully(ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            int read = channel.read(buf);
            if (read == -1) {
                throw new EOFException("End of stream reached while reading frame from socket");
            }
        }
    }

    private void writeFully(ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
    }

    private void ensureConnected() throws IOException {
        if (channel == null || !channel.isOpen() || !channel.isConnected()) {
            throw new IOException("SocketChannel is not connected. Call connect() first.");
        }
    }

    public boolean isConnected() {
        return channel != null && channel.isOpen() && channel.isConnected();
    }

    @Override
    public synchronized void close() {
        if (channel != null) {
            try {
                LOGGER.info("Closing connection to backup server.");
                channel.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing SocketChannel", e);
            }
        }
    }
}
