package com.backup.web;

import com.backup.model.Chunk;
import com.backup.model.FileSnapshot;
import com.backup.net.BackupClientConnection;
import com.backup.restore.RestoreEngine;
import com.backup.restore.RestoreReport;
import com.backup.storage.DirectoryScanner;
import com.backup.storage.FileChunker;
import com.backup.storage.FileWatcher;
import com.backup.storage.LocalStateDatabase;
import com.backup.sync.SyncEngine;
import com.backup.sync.SyncReport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DashboardServer {
    private static final Logger LOGGER = Logger.getLogger(DashboardServer.class.getName());

    private final int webPort;
    private final String cServerHost;
    private final int cServerPort;
    private final Path workspacePath;
    private final Path dbPath;
    private HttpServer server;

    /** Optional FileWatcher – may be null if watcher was not started. */
    private volatile FileWatcher fileWatcher;

    public DashboardServer(int webPort, String cServerHost, int cServerPort, Path workspacePath, Path dbPath) {
        this.webPort = webPort;
        this.cServerHost = cServerHost;
        this.cServerPort = cServerPort;
        this.workspacePath = workspacePath;
        this.dbPath = dbPath;
    }

    public void setFileWatcher(FileWatcher watcher) {
        this.fileWatcher = watcher;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", webPort), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/", new StaticFileHandler());
        server.createContext("/api/stats", new StatsHandler());
        server.createContext("/api/files", new FilesHandler());
        server.createContext("/api/manifest", new ManifestHandler());
        server.createContext("/api/sync", new SyncHandler());
        server.createContext("/api/restore", new RestoreHandler());
        server.createContext("/api/watcher", new WatcherHandler());
        server.createContext("/api/versions", new VersionsHandler());

        server.start();
        LOGGER.info(String.format("Web Dashboard listening at http://127.0.0.1:%d", webPort));
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] responseBytes = null;
            try (InputStream is = DashboardServer.class.getResourceAsStream("/web/index.html")) {
                if (is != null) {
                    responseBytes = is.readAllBytes();
                }
            }
            if (responseBytes == null) {
                Path[] candidatePaths = {
                    Paths.get("client/src/main/resources/web/index.html"),
                    Paths.get("src/main/resources/web/index.html"),
                    Paths.get("resources/web/index.html"),
                    Paths.get("web/index.html")
                };
                for (Path p : candidatePaths) {
                    if (Files.exists(p)) {
                        responseBytes = Files.readAllBytes(p);
                        break;
                    }
                }
            }
            if (responseBytes == null) {
                responseBytes = "<h1>Dashboard index.html not found</h1>".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }

    private class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (LocalStateDatabase db = new LocalStateDatabase(dbPath.toString())) {
                int totalFiles = db.getTotalTrackedFiles();
                int uniqueChunks = db.getTotalUniqueChunks();
                int chunkRefs = db.getTotalFileChunkReferences();
                long totalBytes = db.getTotalFileSize();

                double dedupRatio = chunkRefs > 0 ? ((double) (chunkRefs - uniqueChunks) / chunkRefs) * 100.0 : 0.0;
                if (dedupRatio < 0) dedupRatio = 0.0;

                long savedBytes = chunkRefs > uniqueChunks ? (long) (chunkRefs - uniqueChunks) * (64 * 1024) : 0;

                String json = String.format(
                    "{\"totalFiles\":%d,\"uniqueChunks\":%d,\"chunkRefs\":%d,\"totalBytes\":%d,\"dedupRatio\":%.2f,\"savedBytes\":%d}",
                    totalFiles, uniqueChunks, chunkRefs, totalBytes, dedupRatio, savedBytes
                );
                sendJsonResponse(exchange, 200, json);
            } catch (SQLException e) {
                sendErrorResponse(exchange, 500, e.getMessage());
            }
        }
    }

    private class FilesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            StringBuilder sb = new StringBuilder("[");
            try (LocalStateDatabase db = new LocalStateDatabase(dbPath.toString())) {
                List<LocalStateDatabase.FileRecord> records = db.getAllFileRecords();
                boolean first = true;
                for (LocalStateDatabase.FileRecord r : records) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append(String.format(
                        "{\"fileId\":\"%s\",\"relativePath\":\"%s\",\"fileSize\":%d,\"lastModified\":%d,\"wholeFileHash\":\"%s\",\"version\":%d,\"syncStatus\":\"%s\",\"chunkCount\":%d}",
                        escapeJson(r.fileId), escapeJson(r.relativePath), r.fileSize,
                        r.lastModified, escapeJson(r.wholeFileHash),
                        r.version, escapeJson(r.syncStatus), r.chunkCount
                    ));
                }
                sb.append("]");
                sendJsonResponse(exchange, 200, sb.toString());
            } catch (SQLException e) {
                sendErrorResponse(exchange, 500, e.getMessage());
            }
        }
    }

    private class ManifestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            URI uri = exchange.getRequestURI();
            Map<String, String> params = parseQueryParams(uri.getQuery());
            String fileId = params.get("fileId");
            int version = params.containsKey("version") ? Integer.parseInt(params.get("version")) : 0;

            if (fileId == null || fileId.isEmpty()) {
                sendErrorResponse(exchange, 400, "Missing fileId parameter");
                return;
            }

            try (BackupClientConnection conn = new BackupClientConnection(cServerHost, cServerPort)) {
                conn.connect();
                conn.sendHello("{\"client\":\"Dashboard-Manifest\"}");
                RestoreEngine restoreEngine = new RestoreEngine();
                FileSnapshot manifest = restoreEngine.fetchManifest(conn, fileId, version);

                StringBuilder sb = new StringBuilder();
                sb.append(String.format("{\"fileId\":\"%s\",\"filePath\":\"%s\",\"fileSize\":%d,\"wholeFileHash\":\"%s\",\"chunks\":[",
                        escapeJson(manifest.getFileId()), escapeJson(manifest.getRelativePath()), manifest.getFileSize(), escapeJson(manifest.getWholeFileHash())));

                List<Chunk> chunks = manifest.getChunks();
                for (int i = 0; i < chunks.size(); i++) {
                    Chunk c = chunks.get(i);
                    sb.append(String.format("{\"index\":%d,\"offset\":%d,\"length\":%d,\"hash\":\"%s\"}",
                            c.getIndex(), c.getOffset(), c.getLength(), escapeJson(c.getSha256())));
                    if (i < chunks.size() - 1) sb.append(",");
                }
                sb.append("]}");

                sendJsonResponse(exchange, 200, sb.toString());
            } catch (Exception e) {
                sendErrorResponse(exchange, 500, "Failed to load manifest: " + e.getMessage());
            }
        }
    }

    private class SyncHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendErrorResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            try (LocalStateDatabase db = new LocalStateDatabase(dbPath.toString());
                 BackupClientConnection conn = new BackupClientConnection(cServerHost, cServerPort)) {

                conn.connect();
                conn.sendHello("{\"client\":\"Dashboard-Sync\"}");
                FileChunker chunker = new FileChunker(64 * 1024);
                DirectoryScanner scanner = new DirectoryScanner(workspacePath, chunker, db);
                SyncEngine syncEngine = new SyncEngine(db);

                DirectoryScanner.ScanResult scan = scanner.scan(false);
                int totalUploaded = 0;
                int totalDedup = 0;

                for (FileSnapshot snap : scan.getModifiedOrNewFiles()) {
                    SyncReport report = syncEngine.syncFile(workspacePath, snap, conn);
                    totalUploaded += report.getUploadedChunks();
                    totalDedup += report.getDeduplicatedChunks();
                }

                String resp = String.format("{\"status\":\"SUCCESS\",\"filesSynced\":%d,\"chunksUploaded\":%d,\"chunksDeduped\":%d}",
                        scan.getModifiedOrNewFiles().size(), totalUploaded, totalDedup);
                sendJsonResponse(exchange, 200, resp);
            } catch (Exception e) {
                sendErrorResponse(exchange, 500, "Sync failed: " + e.getMessage());
            }
        }
    }

    private class RestoreHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendErrorResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String fileId = extractJsonString(body, "fileId");
            int version = extractJsonInt(body, "version", 1);
            String targetDirStr = extractJsonString(body, "targetDir");
            if (targetDirStr == null || targetDirStr.isEmpty()) targetDirStr = "./restored_files";

            try (BackupClientConnection conn = new BackupClientConnection(cServerHost, cServerPort)) {
                conn.connect();
                conn.sendHello("{\"client\":\"Dashboard-Restore\"}");
                RestoreEngine restoreEngine = new RestoreEngine();

                // Fetch manifest to know target filename
                FileSnapshot manifest = restoreEngine.fetchManifest(conn, fileId, version);
                Path baseTargetDir = Paths.get(targetDirStr).toAbsolutePath().normalize();
                Path targetPath = baseTargetDir.resolve(manifest.getRelativePath()).normalize();

                // Path traversal check
                if (!targetPath.startsWith(baseTargetDir)) {
                    sendErrorResponse(exchange, 400, "Path traversal detected in restore relative path");
                    return;
                }

                RestoreReport report = restoreEngine.restoreFile(conn, fileId, version, targetPath);

                String resp = String.format(
                    "{\"status\":\"RESTORED\",\"fileId\":\"%s\",\"version\":%d,\"restoredPath\":\"%s\",\"hash\":\"%s\",\"durationMs\":%d,\"verified\":true}",
                    escapeJson(fileId), version, escapeJson(targetPath.toString().replace('\\', '/')),
                    escapeJson(report.getWholeFileHash()), report.getDurationMs()
                );
                sendJsonResponse(exchange, 200, resp);
            } catch (Exception e) {
                sendErrorResponse(exchange, 500, "Restore failed: " + e.getMessage());
            }
        }
    }

    private class VersionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            URI uri = exchange.getRequestURI();
            Map<String, String> params = parseQueryParams(uri.getQuery());
            String fileId = params.get("fileId");

            if (fileId == null || fileId.isEmpty()) {
                sendErrorResponse(exchange, 400, "Missing fileId parameter");
                return;
            }

            try (BackupClientConnection conn = new BackupClientConnection(cServerHost, cServerPort)) {
                conn.connect();
                conn.sendHello("{\"client\":\"Dashboard-Versions\"}");
                RestoreEngine restoreEngine = new RestoreEngine();

                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (int v = 1; v <= 1000; v++) {
                    try {
                        FileSnapshot manifest = restoreEngine.fetchManifest(conn, fileId, v);
                        if (!first) sb.append(",");
                        first = false;
                        sb.append(String.format(
                            "{\"fileId\":\"%s\",\"version\":%d,\"fileSize\":%d,\"chunkCount\":%d,\"wholeFileHash\":\"%s\",\"relativePath\":\"%s\",\"timestamp\":0}",
                            escapeJson(manifest.getFileId()),
                            v,
                            manifest.getFileSize(),
                            manifest.getChunks().size(),
                            escapeJson(manifest.getWholeFileHash()),
                            escapeJson(manifest.getRelativePath())
                        ));
                    } catch (IOException e) {
                        // version not found — stop probing
                        break;
                    }
                }
                sb.append("]");
                sendJsonResponse(exchange, 200, sb.toString());
            } catch (Exception e) {
                sendErrorResponse(exchange, 500, "Failed to load versions: " + e.getMessage());
            }
        }
    }

    private class WatcherHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();

            if ("GET".equalsIgnoreCase(method)) {
                // Return current watcher status as JSON
                String json = buildWatcherJson();
                sendJsonResponse(exchange, 200, json);

            } else if ("POST".equalsIgnoreCase(method)) {
                String body = new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
                String action = extractJsonString(body, "action");

                if (fileWatcher == null) {
                    sendErrorResponse(exchange, 503, "FileWatcher not running");
                    return;
                }

                if ("enable".equalsIgnoreCase(action)) {
                    fileWatcher.enable();
                } else if ("disable".equalsIgnoreCase(action)) {
                    fileWatcher.disable();
                } else {
                    sendErrorResponse(exchange, 400, "Unknown action: " + action);
                    return;
                }

                sendJsonResponse(exchange, 200, buildWatcherJson());

            } else {
                sendErrorResponse(exchange, 405, "Method Not Allowed");
            }
        }

        private String buildWatcherJson() {
            if (fileWatcher == null) {
                return "{\"running\":false,\"enabled\":false,\"totalEvents\":0,\"totalSyncs\":0," +
                       "\"watchDirectory\":\"\",\"lastSyncedFile\":\"\",\"lastSyncTimeMs\":0}";
            }
            FileWatcher.WatcherStatus s = fileWatcher.getStatus();
            return String.format(
                "{\"running\":%b,\"enabled\":%b,\"totalEvents\":%d,\"totalSyncs\":%d," +
                "\"watchDirectory\":\"%s\",\"lastSyncedFile\":\"%s\",\"lastSyncTimeMs\":%d}",
                s.running(), s.enabled(), s.totalEvents(), s.totalSyncs(),
                escapeJson(s.watchDirectory()),
                escapeJson(s.lastSyncedFile() != null ? s.lastSyncedFile() : ""),
                s.lastSyncTimeMs()
            );
        }
    }

    private void sendJsonResponse(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendErrorResponse(HttpExchange exchange, int code, String error) throws IOException {
        String json = String.format("{\"error\":\"%s\"}", error.replace("\"", "\\\""));
        sendJsonResponse(exchange, code, json);
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length == 2) {
                map.put(pair[0], pair[1]);
            }
        }
        return map;
    }

    private String extractJsonString(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private int extractJsonInt(String json, String key, int defaultVal) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : defaultVal;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
