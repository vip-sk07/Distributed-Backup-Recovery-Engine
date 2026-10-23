package com.backup.storage;

import com.backup.model.FileSnapshot;
import com.backup.net.BackupClientConnection;
import com.backup.sync.SyncEngine;
import com.backup.sync.SyncReport;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FileWatcher monitors a directory (and its subdirectories) using Java's
 * WatchService API and automatically triggers a debounced sync to the backup
 * server whenever files are created or modified.
 */
public class FileWatcher implements AutoCloseable {

    // -----------------------------------------------------------------------
    // Status record
    // -----------------------------------------------------------------------

    public record WatcherStatus(
            boolean running,
            boolean enabled,
            int totalEvents,
            int totalSyncs,
            String watchDirectory,
            String lastSyncedFile,
            long lastSyncTimeMs
    ) {}

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final Path watchDir;
    private final String cServerHost;
    private final int cServerPort;
    private final Path dbPath;
    private final int debounceMs;

    private WatchService watcher;
    /** Maps a registered WatchKey to its corresponding directory path. */
    private final Map<WatchKey, Path> keyToDir = new HashMap<>();

    private final ScheduledExecutorService debouncer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fw-debouncer");
                t.setDaemon(true);
                return t;
            });

    private final ExecutorService syncExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    /** Keyed by relative-path string; holds the pending debounced task. */
    private final Map<String, ScheduledFuture<?>> pendingTasks =
            new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean enabled = true;

    private final AtomicInteger totalAutoSyncs       = new AtomicInteger(0);
    private final AtomicInteger totalEventsDetected  = new AtomicInteger(0);

    private volatile String lastSyncedFile  = null;
    private volatile long   lastSyncTimeMs  = 0L;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * @param watchDir     Directory to watch recursively.
     * @param cServerHost  Backup-server hostname.
     * @param cServerPort  Backup-server port.
     * @param dbPath       SQLite state-database path.
     * @param debounceMs   Milliseconds to wait after the last event before syncing.
     */
    public FileWatcher(Path watchDir,
                       String cServerHost,
                       int cServerPort,
                       Path dbPath,
                       int debounceMs) {
        this.watchDir     = watchDir;
        this.cServerHost  = cServerHost;
        this.cServerPort  = cServerPort;
        this.dbPath       = dbPath;
        this.debounceMs   = debounceMs;
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Starts the WatchService event loop in a virtual-thread daemon.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            System.out.println("[WATCHER] Already running.");
            return;
        }
        try {
            watcher = FileSystems.getDefault().newWatchService();
            registerRecursive(watchDir);
        } catch (IOException e) {
            System.err.printf("[WATCHER] Failed to initialise WatchService: %s%n", e.getMessage());
            running.set(false);
            return;
        }

        Thread.ofVirtual().name("fw-event-loop").start(this::watchLoop);
        System.out.printf("[WATCHER] Watching: %s%n", watchDir);
    }

    /**
     * Stops the watcher and shuts down internal executors.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        // Cancel all pending debounced tasks
        pendingTasks.values().forEach(f -> f.cancel(false));
        pendingTasks.clear();

        try {
            if (watcher != null) {
                watcher.close();
            }
        } catch (IOException e) {
            System.err.printf("[WATCHER] Error closing WatchService: %s%n", e.getMessage());
        }

        debouncer.shutdown();
        syncExecutor.shutdown();
        System.out.println("[WATCHER] Stopped.");
    }

    @Override
    public void close() {
        stop();
    }

    // -----------------------------------------------------------------------
    // Enable / disable at runtime
    // -----------------------------------------------------------------------

    public void enable()  { enabled = true;  System.out.println("[WATCHER] Auto-sync enabled.");  }
    public void disable() { enabled = false; System.out.println("[WATCHER] Auto-sync disabled."); }

    // -----------------------------------------------------------------------
    // Status queries
    // -----------------------------------------------------------------------

    public boolean isEnabled() { return enabled; }
    public boolean isRunning() { return running.get(); }

    public WatcherStatus getStatus() {
        return new WatcherStatus(
                running.get(),
                enabled,
                totalEventsDetected.get(),
                totalAutoSyncs.get(),
                watchDir.toString(),
                lastSyncedFile,
                lastSyncTimeMs
        );
    }

    // -----------------------------------------------------------------------
    // Internal – watch loop
    // -----------------------------------------------------------------------

    private void watchLoop() {
        while (running.get()) {
            WatchKey key;
            try {
                key = watcher.take();          // blocks until an event arrives
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.nio.file.ClosedWatchServiceException e) {
                break;                          // stop() was called
            }

            Path dir = keyToDir.get(key);
            if (dir == null) {
                key.reset();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }

                // Only handle create/modify
                if (kind != StandardWatchEventKinds.ENTRY_CREATE &&
                    kind != StandardWatchEventKinds.ENTRY_MODIFY) {
                    key.reset();
                    continue;
                }

                @SuppressWarnings("unchecked")
                WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                Path changed = dir.resolve(pathEvent.context());

                // If a new directory was created, register it for watching
                if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
                    try {
                        registerRecursive(changed);
                    } catch (IOException e) {
                        System.err.printf("[WATCHER] Failed to watch new dir %s: %s%n",
                                changed, e.getMessage());
                    }
                    key.reset();
                    continue;
                }

                if (!Files.isRegularFile(changed)) {
                    key.reset();
                    continue;
                }

                // Skip temp / db / hidden files
                String fileName = changed.getFileName().toString();
                if (shouldSkip(fileName)) {
                    key.reset();
                    continue;
                }

                totalEventsDetected.incrementAndGet();

                if (enabled) {
                    Path relativePath = watchDir.relativize(changed);
                    String relKey = relativePath.toString().replace('\\', '/');
                    scheduleDebouncedSync(relKey, changed);
                }
            }

            key.reset();
        }
    }

    // -----------------------------------------------------------------------
    // Internal – debounced sync scheduling
    // -----------------------------------------------------------------------

    private void scheduleDebouncedSync(String relKey, Path absolutePath) {
        Runnable syncTask = buildSyncTask(relKey, absolutePath);

        pendingTasks.compute(relKey, (k, existing) -> {
            if (existing != null) {
                existing.cancel(false);
            }
            return debouncer.schedule(
                    () -> syncExecutor.submit(syncTask),
                    debounceMs,
                    TimeUnit.MILLISECONDS
            );
        });
    }

    private Runnable buildSyncTask(String relKey, Path absolutePath) {
        return () -> {
            long startMs = System.currentTimeMillis();
            try (LocalStateDatabase db = new LocalStateDatabase(dbPath.toString());
                 BackupClientConnection conn = new BackupClientConnection(cServerHost, cServerPort)) {

                conn.connect();
                conn.sendHello("{\"client\":\"FileWatcher-AutoSync\"}");

                // Check whether the file still exists (could have been deleted)
                if (!Files.exists(absolutePath)) {
                    System.out.printf("[WATCHER] Skipping deleted file: %s%n", relKey);
                    return;
                }

                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(absolutePath, BasicFileAttributes.class);
                } catch (IOException e) {
                    System.err.printf("[WATCHER] Cannot read attributes for %s: %s%n", relKey, e.getMessage());
                    return;
                }

                // Short-circuit: compare mtime+size against DB record
                FileSnapshot existing = db.getFileSnapshot(relKey);
                long currentMtime = attrs.lastModifiedTime().toMillis();
                long currentSize  = attrs.size();

                if (existing != null
                        && existing.getFileSize() == currentSize
                        && existing.getLastModified() == currentMtime) {
                    // File hasn't actually changed; skip
                    return;
                }

                // Chunk the changed file
                FileChunker chunker = new FileChunker(64 * 1024);
                FileSnapshot snapshot = chunker.chunkFile(watchDir, absolutePath, false);
                FileSnapshot saved = db.saveFileSnapshot(snapshot, "SCANNED");
                FileSnapshot toSync = saved != null ? saved : snapshot;

                // Upload to server
                SyncEngine syncEngine = new SyncEngine(db);
                SyncReport report = syncEngine.syncFile(watchDir, toSync, conn);

                long durationMs = System.currentTimeMillis() - startMs;
                totalAutoSyncs.incrementAndGet();
                lastSyncedFile = relKey;
                lastSyncTimeMs = System.currentTimeMillis();

                System.out.printf("[WATCHER] Auto-synced: %s in %dms " +
                        "(uploaded=%d chunks, deduped=%d chunks)%n",
                        relKey, durationMs,
                        report.getUploadedChunks(), report.getDeduplicatedChunks());

            } catch (IOException e) {
                System.err.printf("[WATCHER] IO error syncing %s: %s%n", relKey, e.getMessage());
            } catch (SQLException e) {
                System.err.printf("[WATCHER] DB error syncing %s: %s%n", relKey, e.getMessage());
            } catch (Exception e) {
                System.err.printf("[WATCHER] Unexpected error syncing %s: %s%n", relKey, e.getMessage());
            } finally {
                pendingTasks.remove(relKey);
            }
        };
    }

    // -----------------------------------------------------------------------
    // Internal – helpers
    // -----------------------------------------------------------------------

    /** Register {@code dir} and every sub-directory recursively. */
    private void registerRecursive(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs)
                    throws IOException {
                String name = d.getFileName() != null ? d.getFileName().toString() : "";
                if (name.startsWith(".") || name.equals("node_modules") || name.equals(".git")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                WatchKey k = d.register(watcher,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                keyToDir.put(k, d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Returns {@code true} for files that should never be auto-synced. */
    private static boolean shouldSkip(String fileName) {
        return fileName.startsWith(".")
                || fileName.endsWith(".db")
                || fileName.endsWith(".db-wal")
                || fileName.endsWith(".db-shm")
                || fileName.endsWith(".db-journal");
    }
}
