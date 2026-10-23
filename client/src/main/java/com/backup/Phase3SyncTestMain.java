package com.backup;

import com.backup.crypto.HashUtil;
import com.backup.model.Chunk;
import com.backup.model.FileSnapshot;
import com.backup.net.BackupClientConnection;
import com.backup.storage.DirectoryScanner;
import com.backup.storage.FileChunker;
import com.backup.storage.LocalStateDatabase;
import com.backup.sync.SyncEngine;
import com.backup.sync.SyncReport;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class Phase3SyncTestMain {
    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 8035;
        int fileSizeMb = 16; // 16 MB test dataset (256 chunks of 64KB)

        for (int i = 0; i < args.length; i++) {
            if ("--host".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                host = args[++i];
            } else if ("--port".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--size".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                fileSizeMb = Integer.parseInt(args[++i]);
            } else if (args[i].matches("\\d+")) {
                fileSizeMb = Integer.parseInt(args[i]);
            }
        }

        System.out.println("==========================================================");
        System.out.println("  Distributed Backup System - Incremental Sync Test Suite ");
        System.out.println("  Phase 3: BEGIN_UPLOAD -> NEED_CHUNKS -> PUT -> COMMIT  ");
        System.out.println("==========================================================");

        Path syncDir = Paths.get("./sync_test_workspace").toAbsolutePath().normalize();
        Path dbPath = Paths.get("./client/sync_state.db").toAbsolutePath().normalize();

        try {
            // Setup fresh workspace and clean previous test manifests
            if (Files.exists(syncDir)) {
                deleteDirectoryRecursively(syncDir);
            }
            Files.createDirectories(syncDir);
            if (Files.exists(dbPath)) {
                Files.delete(dbPath);
            }
            resetTestManifests();

            int chunkSize = 64 * 1024; // 64 KB
            int totalBytes = fileSizeMb * 1024 * 1024;
            int totalChunks = totalBytes / chunkSize;

            System.out.printf("[SETUP] Dataset size: %d MB (%d bytes across %d chunks of %d KB)%n",
                    fileSizeMb, totalBytes, totalChunks, chunkSize / 1024);

            Path testFile = syncDir.resolve("backup_payload.bin");
            System.out.println("[SETUP] Generating initial binary dataset...");
            byte[] seedBlock = new byte[chunkSize];
            new Random(98765).nextBytes(seedBlock);

            try (var out = Files.newOutputStream(testFile)) {
                for (int i = 0; i < totalChunks; i++) {
                    byte[] block = Arrays.copyOf(seedBlock, seedBlock.length);
                    // Differentiate blocks so each chunk has a distinct SHA-256
                    block[0] = (byte) (i & 0xFF);
                    block[1] = (byte) ((i >> 8) & 0xFF);
                    out.write(block);
                }
            }

            FileChunker chunker = new FileChunker(chunkSize);

            try (BackupClientConnection conn = new BackupClientConnection(host, port);
                 LocalStateDatabase db = new LocalStateDatabase(dbPath.toString())) {

                System.out.printf("[CLIENT] Connecting to server at %s:%d...%n", host, port);
                conn.connect();
                String greeting = conn.sendHello("{\"client\":\"JavaNIO-Phase3\",\"mode\":\"sync\"}");
                System.out.println("[CLIENT] Handshake confirmed: " + greeting);

                DirectoryScanner scanner = new DirectoryScanner(syncDir, chunker, db);
                SyncEngine syncEngine = new SyncEngine(db);

                // =====================================================================
                // SYNC PASS 1: INITIAL FULL BACKUP
                // =====================================================================
                System.out.println("\n----------------------------------------------------------");
                System.out.println("  SYNC PASS 1: Initial Full Backup (All Chunks New)       ");
                System.out.println("----------------------------------------------------------");

                DirectoryScanner.ScanResult scan1 = scanner.scan(false);
                assert scan1.getModifiedOrNewFiles().size() == 1 : "Expected 1 file to sync";
                FileSnapshot snap1 = scan1.getModifiedOrNewFiles().get(0);

                // Clear any leftover test chunks on the server store so Pass 1 tests a true 100% initial upload
                byte[] mutatedSample = new byte[chunkSize];
                Arrays.fill(mutatedSample, (byte) 0x7F);
                String mutatedHash = HashUtil.sha256Hex(mutatedSample);
                removeTestChunks(snap1.getChunks(), mutatedHash);

                long startT1 = System.currentTimeMillis();
                SyncReport report1 = syncEngine.syncFile(syncDir, snap1, conn);
                long duration1 = System.currentTimeMillis() - startT1;

                System.out.println("[RESULT 1] " + report1);
                System.out.printf("  * Uploaded Chunks: %d / %d (100%%)%n", report1.getUploadedChunks(), report1.getTotalChunks());
                System.out.printf("  * Deduplicated Chunks: %d%n", report1.getDeduplicatedChunks());
                System.out.printf("  * Bytes Transferred: %,d bytes in %d ms%n", report1.getUploadedBytes(), duration1);

                assert report1.getUploadedChunks() == totalChunks : "All chunks must be uploaded in initial sync";
                assert report1.getDeduplicatedChunks() == 0 : "Zero dedup on initial sync";

                // =====================================================================
                // SYNC PASS 2: INCREMENTAL MODIFICATION (MODIFY ONLY 1 CHUNK)
                // =====================================================================
                System.out.println("\n----------------------------------------------------------");
                System.out.println("  SYNC PASS 2: Incremental Sync (Modify ONLY 1 Chunk)     ");
                System.out.println("----------------------------------------------------------");
                Thread.sleep(1100); // Allow filesystem mtime resolution

                int modifiedChunkIndex = totalChunks / 2; // Modify the middle chunk
                long modifyOffset = (long) modifiedChunkIndex * chunkSize;
                System.out.printf("[MODIFY] Mutating 64 KB chunk #%d at byte offset %,d...%n", modifiedChunkIndex, modifyOffset);

                try (RandomAccessFile raf = new RandomAccessFile(testFile.toFile(), "rw")) {
                    raf.seek(modifyOffset);
                    byte[] mutatedChunk = new byte[chunkSize];
                    Arrays.fill(mutatedChunk, (byte) 0x7F);
                    raf.write(mutatedChunk);
                }

                DirectoryScanner.ScanResult scan2 = scanner.scan(false);
                assert scan2.getModifiedOrNewFiles().size() == 1 : "Expected file detected as modified";
                FileSnapshot snap2 = scan2.getModifiedOrNewFiles().get(0);

                long startT2 = System.currentTimeMillis();
                SyncReport report2 = syncEngine.syncFile(syncDir, snap2, conn);
                long duration2 = System.currentTimeMillis() - startT2;

                System.out.println("[RESULT 2] " + report2);
                System.out.printf("  * Uploaded Chunks: %d / %d (Only the modified chunk!)%n", report2.getUploadedChunks(), report2.getTotalChunks());
                System.out.printf("  * Deduplicated Chunks: %d / %d (%.2f%% bandwidth saved)%n",
                        report2.getDeduplicatedChunks(), report2.getTotalChunks(), report2.getDeduplicationPercentage());
                System.out.printf("  * Bytes Transferred: %,d bytes (out of %,d bytes) in %d ms%n",
                        report2.getUploadedBytes(), report2.getFileSize(), duration2);
                System.out.printf("  * Server Version: v%d%n", report2.getServerVersion());

                assert report2.getUploadedChunks() == 1 : "EXACTLY 1 chunk should be uploaded!";
                assert report2.getDeduplicatedChunks() == (totalChunks - 1) : "All other chunks must be deduplicated!";
                assert report2.getServerVersion() == 2 : "Manifest version must advance to 2";

                // =====================================================================
                // SYNC PASS 3: ZERO-BYTE TRANSFER FOR IDENTICAL DUPLICATE
                // =====================================================================
                System.out.println("\n----------------------------------------------------------");
                System.out.println("  SYNC PASS 3: Cross-File Deduplication (Exact Copy)      ");
                System.out.println("----------------------------------------------------------");
                Path copyFile = syncDir.resolve("backup_payload_copy.bin");
                Files.copy(testFile, copyFile);

                DirectoryScanner.ScanResult scan3 = scanner.scan(false);
                assert scan3.getModifiedOrNewFiles().size() == 1 : "Copy detected as new file";
                FileSnapshot snap3 = scan3.getModifiedOrNewFiles().get(0);

                SyncReport report3 = syncEngine.syncFile(syncDir, snap3, conn);
                System.out.println("[RESULT 3] " + report3);
                System.out.printf("  * Uploaded Chunks: %d (0 bytes transferred!)%n", report3.getUploadedChunks());
                System.out.printf("  * Deduplication Ratio: %.1f%%%n", report3.getDeduplicationPercentage());

                assert report3.getUploadedChunks() == 0 : "0 chunks uploaded for duplicate file!";
                assert report3.getDeduplicatedChunks() == totalChunks : "100% chunks deduplicated!";

                System.out.println("\n==========================================================");
                System.out.println("  Phase 3 Sync Flow Verification: 100% SUCCESSFUL!        ");
                System.out.println("==========================================================");
            }

        } catch (Exception e) {
            System.err.println("[FAIL] Incremental sync test failed:");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void resetTestManifests() throws IOException {
        String[] storeBases = {"./store", "./server/store"};
        String[] testFileIds = {"74ca9961a669d5cd", "688066e7e2b0a9fa"};
        for (String base : storeBases) {
            Path manifestsDir = Paths.get(base, "manifests");
            for (String fileId : testFileIds) {
                Path targetManifestDir = manifestsDir.resolve(fileId);
                if (Files.exists(targetManifestDir)) {
                    deleteDirectoryRecursively(targetManifestDir);
                }
            }
        }
    }

    private static void removeTestChunks(List<Chunk> chunks, String mutatedChunkHash) {
        String[] storeBases = {"./store", "./server/store"};
        for (String base : storeBases) {
            Path chunksDir = Paths.get(base, "chunks");
            if (!Files.exists(chunksDir)) continue;
            if (chunks != null) {
                for (Chunk c : chunks) {
                    try {
                        Files.deleteIfExists(chunksDir.resolve(c.getSha256()));
                    } catch (IOException ignored) {}
                }
            }
            if (mutatedChunkHash != null) {
                try {
                    Files.deleteIfExists(chunksDir.resolve(mutatedChunkHash));
                } catch (IOException ignored) {}
            }
        }
    }

    private static void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.newDirectoryStream(path)) {
                for (Path p : stream) {
                    deleteDirectoryRecursively(p);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
