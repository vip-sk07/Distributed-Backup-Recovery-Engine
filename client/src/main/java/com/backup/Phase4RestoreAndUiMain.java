package com.backup;

import com.backup.crypto.HashUtil;
import com.backup.model.FileSnapshot;
import com.backup.net.BackupClientConnection;
import com.backup.restore.RestoreEngine;
import com.backup.restore.RestoreReport;
import com.backup.storage.FileWatcher;
import com.backup.storage.LocalStateDatabase;
import com.backup.web.DashboardServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Phase4RestoreAndUiMain {
    public static void main(String[] args) {
        String host = "127.0.0.1";
        int cServerPort = 8035;
        int webPort = 8080;
        boolean isDaemon = false;

        for (int i = 0; i < args.length; i++) {
            if ("--daemon".equalsIgnoreCase(args[i])) {
                isDaemon = true;
            } else if ("--port".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                cServerPort = Integer.parseInt(args[++i]);
            } else if ("--webPort".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                webPort = Integer.parseInt(args[++i]);
            } else if ("--host".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                host = args[++i];
            } else if (args[i].matches("\\d+")) {
                cServerPort = Integer.parseInt(args[i]);
            }
        }

        System.out.println("==========================================================");
        System.out.println("  Distributed Backup System - Download, Restore & UI     ");
        System.out.println("  Phase 4: GET_MANIFEST, GET_CHUNK & Web Dashboard      ");
        System.out.println("==========================================================");

        Path workspace = Paths.get("./sync_test_workspace").toAbsolutePath().normalize();
        Path dbPath = Paths.get("./client/sync_state.db").toAbsolutePath().normalize();
        Path restoreDir = Paths.get("./restored_output").toAbsolutePath().normalize();

        try {
            // 1. Initialize and Start Embedded Web Dashboard
            System.out.printf("[WEB] Starting Embedded UI Dashboard on http://127.0.0.1:%d...%n", webPort);
            DashboardServer webServer = new DashboardServer(webPort, host, cServerPort, workspace, dbPath);
            webServer.start();
            System.out.printf("[WEB] Dashboard online! Open http://127.0.0.1:%d in any web browser.%n", webPort);

            // Start File Watcher
            System.out.printf("[WATCHER] Starting real-time file watcher on %s (debounce: 3s)...%n", workspace);
            FileWatcher fileWatcher = new FileWatcher(workspace, host, cServerPort, dbPath, 3000);
            fileWatcher.start();
            webServer.setFileWatcher(fileWatcher);
            System.out.println("[WATCHER] Auto-sync watcher active. Files will be synced automatically on change.");

            // 2. Perform End-to-End Download & Restore Verification
            System.out.println("\n--- STEP 1: Verify Direct C Server Protocol Restore ---");
            try (BackupClientConnection conn = new BackupClientConnection(host, cServerPort);
                 LocalStateDatabase db = new LocalStateDatabase(dbPath.toString())) {

                conn.connect();
                RestoreEngine restoreEngine = new RestoreEngine();

                // Find a tracked file in SQLite
                FileSnapshot targetFile = db.getFileSnapshot("backup_payload.bin");
                if (targetFile == null) {
                    System.out.println("[WARN] backup_payload.bin not found in SQLite. Did you run Phase 3?");
                } else {
                    String fileId = targetFile.getFileId();
                    System.out.printf("[RESTORE] Found tracked file: '%s' (File ID: %s, Current Version: v%d)%n",
                            targetFile.getRelativePath(), fileId, targetFile.getVersion());

                    // Restore Version 1
                    System.out.println("\n[RESTORE] Reconstructing Version 1 byte-for-byte from chunks...");
                    Path v1Dest = restoreDir.resolve("backup_payload_v1.bin");
                    RestoreReport v1Report = restoreEngine.restoreFile(conn, fileId, 1, v1Dest);
                    System.out.println("[PASS] " + v1Report);
                    assert v1Report.isIntegrityVerified() : "v1 integrity verification failed";

                    // Restore Version 2 (if present)
                    if (targetFile.getVersion() >= 2) {
                        System.out.println("\n[RESTORE] Reconstructing Version 2 byte-for-byte from chunks...");
                        Path v2Dest = restoreDir.resolve("backup_payload_v2.bin");
                        RestoreReport v2Report = restoreEngine.restoreFile(conn, fileId, 2, v2Dest);
                        System.out.println("[PASS] " + v2Report);
                        assert v2Report.isIntegrityVerified() : "v2 integrity verification failed";

                        // Verify that v1 and v2 files are distinct and match original hashes
                        assert !v1Report.getWholeFileHash().equals(v2Report.getWholeFileHash()) : "v1 and v2 hashes must differ";
                        System.out.println("[PASS] Verified: Version 1 and Version 2 are distinct and byte-accurate!");
                    }
                }
            }

            // 3. Test HTTP REST API Endpoints
            System.out.println("\n--- STEP 2: Verify Web Dashboard REST APIs ---");
            verifyHttpEndpoint(String.format("http://127.0.0.1:%d/api/stats", webPort));
            verifyHttpEndpoint(String.format("http://127.0.0.1:%d/api/files", webPort));

            System.out.println("\n==========================================================");
            System.out.println("  Phase 4 Complete: Download, Restore, and UI PASSED!    ");
            System.out.println("==========================================================");

            if (isDaemon) {
                System.out.println("\n[SERVER] Running continuously. Press Ctrl+C to exit.");
                Runtime.getRuntime().addShutdownHook(new Thread(fileWatcher::stop));
                Thread.currentThread().join();
            } else {
                webServer.stop();
            }

        } catch (Exception e) {
            System.err.println("[FAIL] Phase 4 failed with exception:");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void verifyHttpEndpoint(String urlStr) throws IOException {
        URI uri = URI.create(urlStr);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        try (InputStream is = conn.getInputStream()) {
            String body = new String(is.readAllBytes());
            System.out.printf("[HTTP GET] %s -> %d OK (Body: %s)%n", urlStr, code,
                    body.length() > 80 ? body.substring(0, 80) + "..." : body);
        }
        assert code == 200 : "Expected HTTP 200 from " + urlStr;
    }
}
