package com.backup.sync;

import com.backup.model.Chunk;
import com.backup.model.FileSnapshot;
import com.backup.net.BackupClientConnection;
import com.backup.protocol.ManifestWireUtil;
import com.backup.protocol.MessageType;
import com.backup.protocol.ProtocolFrame;
import com.backup.storage.LocalStateDatabase;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SyncEngine {
    private static final Logger LOGGER = Logger.getLogger(SyncEngine.class.getName());
    private static final Pattern VERSION_PATTERN = Pattern.compile("\"version\"\\s*:\\s*(\\d+)");

    private final LocalStateDatabase database;

    public SyncEngine(LocalStateDatabase database) {
        this.database = database;
    }

    public SyncReport syncFile(Path rootDir, FileSnapshot snapshot, BackupClientConnection conn) throws IOException, SQLException {
        LOGGER.info(String.format("Starting sync for file: %s (%d bytes, %d chunks)",
                snapshot.getRelativePath(), snapshot.getFileSize(), snapshot.getChunks().size()));

        // 1. Send BEGIN_UPLOAD with Manifest
        byte[] manifestWire = ManifestWireUtil.serializeToWireBytes(snapshot);
        ProtocolFrame beginResp = conn.sendRequest(MessageType.BEGIN_UPLOAD, manifestWire);

        if (beginResp.getHeader().getType() == MessageType.ERROR) {
            throw new IOException("Server rejected BEGIN_UPLOAD: " + beginResp.getPayloadAsString());
        }
        if (beginResp.getHeader().getType() != MessageType.NEED_CHUNKS) {
            throw new IOException("Unexpected response to BEGIN_UPLOAD: " + beginResp.getHeader().getType());
        }

        // 2. Parse NEED_CHUNKS response
        Set<String> neededHashes = parseNeedChunks(beginResp.getPayloadAsString());
        LOGGER.info(String.format("Server requested %d of %d chunks for '%s'",
                neededHashes.size(), snapshot.getChunks().size(), snapshot.getRelativePath()));

        // 3. Send Missing Chunks via PUT_CHUNK
        Path fullPath = rootDir.resolve(snapshot.getRelativePath());
        int uploadedCount = 0;
        int dedupCount = 0;
        long uploadedBytes = 0;
        long dedupBytes = 0;

        if (snapshot.getFileSize() > 0) {
            try (FileChannel channel = FileChannel.open(fullPath, StandardOpenOption.READ)) {
                for (Chunk chunk : snapshot.getChunks()) {
                    if (neededHashes.contains(chunk.getSha256())) {
                        // Read chunk raw binary slice from disk
                        ByteBuffer chunkBuf = ByteBuffer.allocate(chunk.getLength());
                        long readBytes = channel.read(chunkBuf, chunk.getOffset());
                        if (readBytes != chunk.getLength()) {
                            throw new IOException(String.format("Short read on file %s at offset %d: expected %d, got %d",
                                    fullPath, chunk.getOffset(), chunk.getLength(), readBytes));
                        }

                        // Payload: 64 bytes ASCII SHA-256 + binary chunk data
                        byte[] hashAscii = chunk.getSha256().getBytes(StandardCharsets.US_ASCII);
                        ByteBuffer framePayload = ByteBuffer.allocate(64 + chunk.getLength());
                        framePayload.put(hashAscii);
                        framePayload.put(chunkBuf.array());

                        ProtocolFrame putResp = conn.sendRequest(MessageType.PUT_CHUNK, framePayload.array());
                        if (putResp.getHeader().getType() == MessageType.ERROR) {
                            throw new IOException(String.format("Server error storing chunk %s: %s",
                                    chunk.getSha256(), putResp.getPayloadAsString()));
                        }

                        database.markChunkSynced(chunk.getSha256());
                        uploadedCount++;
                        uploadedBytes += chunk.getLength();
                    } else {
                        dedupCount++;
                        dedupBytes += chunk.getLength();
                    }
                }
            }
        }

        // 4. Send COMMIT_UPLOAD
        byte[] commitPayload = snapshot.getFileId().getBytes(StandardCharsets.UTF_8);
        ProtocolFrame commitResp = conn.sendRequest(MessageType.COMMIT_UPLOAD, commitPayload);

        if (commitResp.getHeader().getType() == MessageType.ERROR) {
            throw new IOException("Server rejected COMMIT_UPLOAD: " + commitResp.getPayloadAsString());
        }

        String commitAck = commitResp.getPayloadAsString();
        LOGGER.info("Upload committed successfully! Server: " + commitAck);

        int serverVersion = snapshot.getVersion();
        Matcher matcher = VERSION_PATTERN.matcher(commitAck);
        if (matcher.find()) {
            serverVersion = Integer.parseInt(matcher.group(1));
        }

        database.markFileSynced(snapshot.getFileId(), serverVersion);

        return new SyncReport(
                snapshot.getRelativePath(),
                snapshot.getFileSize(),
                snapshot.getChunks().size(),
                uploadedCount,
                dedupCount,
                uploadedBytes,
                dedupBytes,
                serverVersion
        );
    }

    private Set<String> parseNeedChunks(String payload) throws IOException {
        Set<String> needed = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(payload))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("COUNT:")) {
                    continue;
                }
                if (line.length() == 64) {
                    needed.add(line);
                }
            }
        }
        return needed;
    }
}
