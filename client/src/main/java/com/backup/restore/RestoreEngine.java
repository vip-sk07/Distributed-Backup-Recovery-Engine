package com.backup.restore;

import com.backup.crypto.HashUtil;
import com.backup.model.Chunk;
import com.backup.model.FileSnapshot;
import com.backup.net.BackupClientConnection;
import com.backup.protocol.ManifestWireUtil;
import com.backup.protocol.MessageType;
import com.backup.protocol.ProtocolFrame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.logging.Logger;

public class RestoreEngine {
    private static final Logger LOGGER = Logger.getLogger(RestoreEngine.class.getName());

    public FileSnapshot fetchManifest(BackupClientConnection conn, String fileId, int version) throws IOException {
        String reqPayload = fileId + (version > 0 ? (" " + version) : "");
        ProtocolFrame resp = conn.sendRequest(MessageType.GET_MANIFEST, reqPayload.getBytes(StandardCharsets.UTF_8));

        if (resp.getHeader().getType() == MessageType.ERROR) {
            throw new IOException("Failed to fetch manifest from server: " + resp.getPayloadAsString());
        }

        return ManifestWireUtil.deserializeFromWireString(resp.getPayloadAsString());
    }

    public RestoreReport restoreFile(BackupClientConnection conn, String fileId, int version, Path destPath) throws IOException {
        long startTime = System.currentTimeMillis();
        LOGGER.info(String.format("Initiating restore for fileId='%s' version=%d into '%s'...",
                fileId, version, destPath));

        // 1. Fetch Versioned Manifest from Server
        FileSnapshot manifest = fetchManifest(conn, fileId, version);
        LOGGER.info(String.format("Retrieved manifest for '%s' (Size: %d bytes, Chunks: %d, Hash: %s)",
                manifest.getRelativePath(), manifest.getFileSize(), manifest.getChunks().size(), manifest.getWholeFileHash()));

        // 2. Prepare Destination File
        if (destPath.getParent() != null) {
            Files.createDirectories(destPath.getParent());
        }

        if (manifest.getFileSize() == 0) {
            Files.write(destPath, new byte[0]);
            long duration = System.currentTimeMillis() - startTime;
            return new RestoreReport(fileId, manifest.getRelativePath(), destPath, 0,
                    version, 0, manifest.getWholeFileHash(), duration, true);
        }

        // 3. Download & Reconstruct Chunks
        try (FileChannel channel = FileChannel.open(destPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            for (Chunk chunk : manifest.getChunks()) {
                byte[] chunkHashBytes = chunk.getSha256().getBytes(StandardCharsets.US_ASCII);
                ProtocolFrame chunkResp = conn.sendRequest(MessageType.GET_CHUNK, chunkHashBytes);

                if (chunkResp.getHeader().getType() == MessageType.ERROR) {
                    throw new IOException(String.format("Server error retrieving chunk %s: %s",
                            chunk.getSha256(), chunkResp.getPayloadAsString()));
                }

                byte[] chunkData = chunkResp.getPayload();
                if (chunkData.length != chunk.getLength()) {
                    throw new IOException(String.format("Chunk %s size mismatch: expected %d bytes, received %d bytes",
                            chunk.getSha256(), chunk.getLength(), chunkData.length));
                }

                // Verify chunk SHA-256
                String computedChunkHash = HashUtil.sha256Hex(chunkData);
                if (!computedChunkHash.equals(chunk.getSha256())) {
                    throw new IOException(String.format("Integrity violation for chunk %s! Computed: %s",
                            chunk.getSha256(), computedChunkHash));
                }

                // Write chunk slice to exact byte offset
                ByteBuffer buf = ByteBuffer.wrap(chunkData);
                channel.write(buf, chunk.getOffset());
            }
        }

        // 4. Validate Whole-File Checksum
        MessageDigest wholeFileDigest = HashUtil.getSha256Digest();
        try (FileChannel verifyChannel = FileChannel.open(destPath, StandardOpenOption.READ)) {
            ByteBuffer verifyBuf = ByteBuffer.allocateDirect(128 * 1024);
            while (verifyChannel.read(verifyBuf) > 0) {
                verifyBuf.flip();
                wholeFileDigest.update(verifyBuf);
                verifyBuf.clear();
            }
        }

        String restoredFileHash = HashUtil.toHex(wholeFileDigest.digest());
        if (!restoredFileHash.equalsIgnoreCase(manifest.getWholeFileHash())) {
            Files.deleteIfExists(destPath);
            throw new IOException(String.format("Restored file corruption! Manifest: %s, Restored: %s",
                    manifest.getWholeFileHash(), restoredFileHash));
        }

        long duration = System.currentTimeMillis() - startTime;
        LOGGER.info(String.format("Restore successful! File '%s' verified byte-for-byte (%d ms)",
                destPath.getFileName(), duration));

        return new RestoreReport(
                fileId,
                manifest.getRelativePath(),
                destPath,
                manifest.getFileSize(),
                version,
                manifest.getChunks().size(),
                restoredFileHash,
                duration,
                true
        );
    }
}
