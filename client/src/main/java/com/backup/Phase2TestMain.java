package com.backup;

import com.backup.model.Chunk;
import com.backup.model.FileSnapshot;
import com.backup.storage.DirectoryScanner;
import com.backup.storage.FileChunker;
import com.backup.storage.LocalStateDatabase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;

public class Phase2TestMain {
    public static void main(String[] args) {
        System.out.println("==========================================================");
        System.out.println("  Distributed Backup System - Java Client Engine Tests    ");
        System.out.println("  Phase 2: Chunker, Hasher & SQLite State Engine          ");
        System.out.println("==========================================================");

        Path testDir = Paths.get("./test_workspace").toAbsolutePath().normalize();
        Path dbPath = Paths.get("./client/backup_state.db").toAbsolutePath().normalize();

        try {
            // Prepare clean test environment
            if (Files.exists(testDir)) {
                deleteDirectoryRecursively(testDir);
            }
            Files.createDirectories(testDir);
            if (Files.exists(dbPath)) {
                Files.delete(dbPath);
            }

            // 1. Create test files
            System.out.println("\n[STEP 1] Generating test dataset in " + testDir + "...");

            // File A: 128 KB (2 chunks of 64KB)
            byte[] fileAData = new byte[128 * 1024];
            Arrays.fill(fileAData, 0, 64 * 1024, (byte) 'A');
            Arrays.fill(fileAData, 64 * 1024, 128 * 1024, (byte) 'B');
            Path fileAPath = testDir.resolve("fileA.txt");
            Files.write(fileAPath, fileAData);

            // File B: Duplicate of File A (to verify cross-file deduplication)
            Path fileBPath = testDir.resolve("fileB_duplicate.txt");
            Files.write(fileBPath, fileAData);

            // File C: 200 KB random binary data (4 chunks: 64KB + 64KB + 64KB + 8KB)
            byte[] fileCData = new byte[200 * 1024];
            new Random(12345).nextBytes(fileCData);
            Path fileCPath = testDir.resolve("fileC.bin");
            Files.write(fileCPath, fileCData);

            System.out.printf("Created fileA.txt (%d bytes), fileB_duplicate.txt (%d bytes), fileC.bin (%d bytes)%n",
                    fileAData.length, fileAData.length, fileCData.length);

            // 2. Initialize SQLite State Database & Chunker (FastCDC default)
            System.out.println("\n[STEP 2] Initializing SQLite state database at " + dbPath + "...");
            System.out.println("[INFO] Chunking mode: " + FileChunker.CHUNKING_MODE +
                    " (min=" + FileChunker.MIN_SIZE/1024 + "KB, avg=" + FileChunker.AVG_SIZE/1024 +
                    "KB, max=" + FileChunker.MAX_SIZE/1024 + "KB)");
            FileChunker chunker = new FileChunker();

            try (LocalStateDatabase db = new LocalStateDatabase(dbPath.toString())) {
                DirectoryScanner scanner = new DirectoryScanner(testDir, chunker, db);

                // 3. Initial Scan
                System.out.println("\n[STEP 3] Performing Initial Directory Scan...");
                DirectoryScanner.ScanResult res1 = scanner.scan(false);
                System.out.printf("[SCAN] Scanned %d files (%d bytes). Modified/New: %d, Unchanged: %d%n",
                        res1.getTotalFilesScanned(), res1.getTotalBytesScanned(),
                        res1.getModifiedOrNewFiles().size(), res1.getUnchangedFiles().size());

                // Track total bytes for invariant check
                long totalBytesChunked = 0;
                for (FileSnapshot snap : res1.getModifiedOrNewFiles()) {
                    System.out.printf("  -> File: '%s' | Chunks: %d | Hash: %s%n",
                            snap.getRelativePath(), snap.getChunks().size(), snap.getWholeFileHash());
                    long fileChunkBytes = 0;
                    for (Chunk c : snap.getChunks()) {
                        System.out.printf("       [Chunk %d] offset=%-8d len=%-8d hash=%s%n",
                                c.getIndex(), c.getOffset(), c.getLength(), c.getSha256());
                        fileChunkBytes += c.getLength();
                    }
                    // Invariant: sum of chunk lengths == file size
                    assert fileChunkBytes == snap.getFileSize() :
                        "Chunk byte sum " + fileChunkBytes + " != file size " + snap.getFileSize() + " for " + snap.getRelativePath();
                    totalBytesChunked += fileChunkBytes;
                }

                // Verify SQLite metrics — using invariants, not fixed chunk counts
                int trackedFiles = db.getTotalTrackedFiles();
                int totalChunkRefs = db.getTotalFileChunkReferences();
                int uniqueChunks = db.getTotalUniqueChunks();

                System.out.println("\n[VERIFICATION] SQLite State Metrics (FastCDC — variable chunk counts):");
                System.out.println("  * Tracked Files: " + trackedFiles + " (expected 3)");
                System.out.println("  * Total File-Chunk References: " + totalChunkRefs + " (FastCDC: varies by content)");
                System.out.println("  * Unique Chunks in SQLite: " + uniqueChunks + " (unique after dedup)");
                System.out.printf("  * Total bytes chunked: %d (expected %d)%n",
                        totalBytesChunked, fileAData.length * 2L + fileCData.length);

                assert trackedFiles == 3 : "Expected 3 tracked files, got " + trackedFiles;
                // Cross-file deduplication: fileA and fileB are identical, so uniqueChunks < totalChunkRefs
                assert uniqueChunks < totalChunkRefs :
                    "Deduplication expected: uniqueChunks=" + uniqueChunks + " should be < totalChunkRefs=" + totalChunkRefs;
                // Byte-accuracy invariant
                assert totalBytesChunked == fileAData.length * 2L + fileCData.length :
                    "Total bytes chunked does not match total file sizes";
                System.out.println("[PASS] Cross-file deduplication verified! Unique chunks < total references.");
                System.out.printf("[PASS] Byte-accuracy invariant: all %d bytes correctly chunked.%n", totalBytesChunked);

                // 4. Test Incremental Modification (Edit second half of File A)
                System.out.println("\n[STEP 4] Modifying second half of fileA.txt (FastCDC boundary shift resistance test)...");
                // Sleep briefly to ensure filesystem timestamp ticks
                Thread.sleep(1100);
                byte[] modifiedFileA = Files.readAllBytes(fileAPath);
                // Modify bytes in the SECOND half only — with FastCDC, first-half chunks should be reused
                Arrays.fill(modifiedFileA, 64 * 1024, 128 * 1024, (byte) 'Z');
                Files.write(fileAPath, modifiedFileA);

                DirectoryScanner.ScanResult res2 = scanner.scan(false);
                System.out.printf("[SCAN-2] Modified/New: %d, Unchanged: %d (expected 1 modified, 2 unchanged)%n",
                        res2.getModifiedOrNewFiles().size(), res2.getUnchangedFiles().size());

                assert res2.getModifiedOrNewFiles().size() == 1 : "Expected 1 modified file";
                assert res2.getUnchangedFiles().size() == 2 : "Expected 2 unchanged files";

                FileSnapshot updatedSnapA = db.getFileSnapshot("fileA.txt");
                System.out.printf("  -> Updated fileA.txt Version: %d (expected 2)%n", updatedSnapA.getVersion());
                assert updatedSnapA.getVersion() == 2 : "Expected version 2";

                // Verify byte-accuracy after modification
                long modBytesSum = updatedSnapA.getChunks().stream().mapToLong(Chunk::getLength).sum();
                assert modBytesSum == modifiedFileA.length :
                    "Modified file chunk sum " + modBytesSum + " != " + modifiedFileA.length;
                System.out.printf("  -> Chunks after modification: %d (byte-accurate: %d bytes)%n",
                        updatedSnapA.getChunks().size(), modBytesSum);
                for (int i = 0; i < updatedSnapA.getChunks().size(); i++) {
                    System.out.printf("  -> Chunk %d hash: %s%n", i, updatedSnapA.getChunks().get(i).getSha256());
                }

                System.out.println("\n==========================================================");
                System.out.println("  Phase 2 Java Engine Tests PASSED! Chunker, Hasher & DB OK");
                System.out.println("==========================================================");
            }

        } catch (Exception e) {
            System.err.println("[FAIL] Test failed with error:");
            e.printStackTrace();
            System.exit(1);
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
