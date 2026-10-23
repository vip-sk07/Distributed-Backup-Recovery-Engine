package com.backup.storage;

import com.backup.model.Chunk;
import com.backup.model.FileSnapshot;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class LocalStateDatabase implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(LocalStateDatabase.class.getName());

    private final Connection connection;

    public LocalStateDatabase(String dbFilePath) throws SQLException {
        File dbFile = new File(dbFilePath);
        if (dbFile.getParentFile() != null) {
            dbFile.getParentFile().mkdirs();
        }

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC Driver not found on classpath", e);
        }

        String jdbcUrl = "jdbc:sqlite:" + dbFilePath.replace('\\', '/');
        this.connection = DriverManager.getConnection(jdbcUrl);
        // Enable WAL mode for concurrent performance and foreign keys
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA foreign_keys=ON;");
        }
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS files (
                    file_id TEXT PRIMARY KEY,
                    relative_path TEXT NOT NULL UNIQUE,
                    file_size INTEGER NOT NULL,
                    last_modified INTEGER NOT NULL,
                    whole_file_hash TEXT NOT NULL,
                    version INTEGER NOT NULL DEFAULT 1,
                    sync_status TEXT NOT NULL
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS chunks (
                    chunk_hash TEXT PRIMARY KEY,
                    chunk_size INTEGER NOT NULL,
                    synced_at INTEGER
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS file_chunks (
                    file_id TEXT NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    offset INTEGER NOT NULL,
                    length INTEGER NOT NULL,
                    chunk_hash TEXT NOT NULL,
                    PRIMARY KEY (file_id, chunk_index),
                    FOREIGN KEY (file_id) REFERENCES files(file_id) ON DELETE CASCADE
                );
            """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_files_path ON files(relative_path);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_fc_hash ON file_chunks(chunk_hash);");
        }
    }

    public synchronized FileSnapshot saveFileSnapshot(FileSnapshot snapshot, String syncStatus) throws SQLException {
        connection.setAutoCommit(false);
        int finalVersion;
        try {
            // Check existing version
            int version = snapshot.getVersion();
            try (PreparedStatement checkStmt = connection.prepareStatement("SELECT version, whole_file_hash FROM files WHERE file_id = ?")) {
                checkStmt.setString(1, snapshot.getFileId());
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next()) {
                        int prevVersion = rs.getInt("version");
                        String prevHash = rs.getString("whole_file_hash");
                        if (!prevHash.equals(snapshot.getWholeFileHash())) {
                            version = prevVersion + 1;
                        } else {
                            version = prevVersion;
                        }
                    }
                }
            }
            finalVersion = version;

            // Upsert file
            String upsertFile = """
                INSERT INTO files (file_id, relative_path, file_size, last_modified, whole_file_hash, version, sync_status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(file_id) DO UPDATE SET
                    relative_path = excluded.relative_path,
                    file_size = excluded.file_size,
                    last_modified = excluded.last_modified,
                    whole_file_hash = excluded.whole_file_hash,
                    version = excluded.version,
                    sync_status = excluded.sync_status;
            """;

            try (PreparedStatement stmt = connection.prepareStatement(upsertFile)) {
                stmt.setString(1, snapshot.getFileId());
                stmt.setString(2, snapshot.getRelativePath());
                stmt.setLong(3, snapshot.getFileSize());
                stmt.setLong(4, snapshot.getLastModified());
                stmt.setString(5, snapshot.getWholeFileHash());
                stmt.setInt(6, finalVersion);
                stmt.setString(7, syncStatus);
                stmt.executeUpdate();
            }

            // Insert chunks into global chunk registry
            String insertChunk = """
                INSERT INTO chunks (chunk_hash, chunk_size, synced_at)
                VALUES (?, ?, NULL)
                ON CONFLICT(chunk_hash) DO NOTHING;
            """;
            try (PreparedStatement stmt = connection.prepareStatement(insertChunk)) {
                for (Chunk c : snapshot.getChunks()) {
                    stmt.setString(1, c.getSha256());
                    stmt.setInt(2, c.getLength());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }

            // Replace file_chunks mappings
            try (PreparedStatement delStmt = connection.prepareStatement("DELETE FROM file_chunks WHERE file_id = ?")) {
                delStmt.setString(1, snapshot.getFileId());
                delStmt.executeUpdate();
            }

            String insertMapping = """
                INSERT INTO file_chunks (file_id, chunk_index, offset, length, chunk_hash)
                VALUES (?, ?, ?, ?, ?);
            """;
            try (PreparedStatement stmt = connection.prepareStatement(insertMapping)) {
                for (Chunk c : snapshot.getChunks()) {
                    stmt.setString(1, snapshot.getFileId());
                    stmt.setInt(2, c.getIndex());
                    stmt.setLong(3, c.getOffset());
                    stmt.setInt(4, c.getLength());
                    stmt.setString(5, c.getSha256());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }

            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }

        return new FileSnapshot(snapshot.getFileId(), snapshot.getRelativePath(), snapshot.getFileSize(),
                snapshot.getLastModified(), snapshot.getWholeFileHash(), snapshot.getChunks(), finalVersion, syncStatus);
    }

    public synchronized FileSnapshot getFileSnapshot(String relativePath) throws SQLException {
        String query = "SELECT file_id, relative_path, file_size, last_modified, whole_file_hash, version, sync_status FROM files WHERE relative_path = ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, relativePath);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String fileId = rs.getString("file_id");
                long fileSize = rs.getLong("file_size");
                long lastModified = rs.getLong("last_modified");
                String wholeFileHash = rs.getString("whole_file_hash");
                int version = rs.getInt("version");
                String syncStatus = rs.getString("sync_status");

                List<Chunk> chunks = getFileChunks(fileId);
                return new FileSnapshot(fileId, relativePath, fileSize, lastModified, wholeFileHash, chunks, version, syncStatus);
            }
        }
    }

    public synchronized FileSnapshot getFileSnapshotById(String fileId) throws SQLException {
        String query = "SELECT file_id, relative_path, file_size, last_modified, whole_file_hash, version, sync_status FROM files WHERE file_id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, fileId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String relativePath = rs.getString("relative_path");
                long fileSize = rs.getLong("file_size");
                long lastModified = rs.getLong("last_modified");
                String wholeFileHash = rs.getString("whole_file_hash");
                int version = rs.getInt("version");
                String syncStatus = rs.getString("sync_status");

                List<Chunk> chunks = getFileChunks(fileId);
                return new FileSnapshot(fileId, relativePath, fileSize, lastModified, wholeFileHash, chunks, version, syncStatus);
            }
        }
    }

    public synchronized List<Chunk> getFileChunks(String fileId) throws SQLException {
        String query = "SELECT chunk_index, offset, length, chunk_hash FROM file_chunks WHERE file_id = ? ORDER BY chunk_index ASC";
        List<Chunk> list = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, fileId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new Chunk(
                            rs.getInt("chunk_index"),
                            rs.getLong("offset"),
                            rs.getInt("length"),
                            rs.getString("chunk_hash")
                    ));
                }
            }
        }
        return list;
    }

    public synchronized void markChunkSynced(String chunkHash) throws SQLException {
        String update = "UPDATE chunks SET synced_at = ? WHERE chunk_hash = ?";
        try (PreparedStatement stmt = connection.prepareStatement(update)) {
            stmt.setLong(1, System.currentTimeMillis());
            stmt.setString(2, chunkHash);
            stmt.executeUpdate();
        }
    }

    public synchronized void markFileSynced(String fileId, int version) throws SQLException {
        String update = "UPDATE files SET sync_status = 'SYNCED', version = ? WHERE file_id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(update)) {
            stmt.setInt(1, version);
            stmt.setString(2, fileId);
            stmt.executeUpdate();
        }
    }

    public synchronized void markFileSynced(String fileId) throws SQLException {
        String update = "UPDATE files SET sync_status = 'SYNCED' WHERE file_id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(update)) {
            stmt.setString(1, fileId);
            stmt.executeUpdate();
        }
    }

    public synchronized boolean isChunkSynced(String chunkHash) throws SQLException {
        String query = "SELECT synced_at FROM chunks WHERE chunk_hash = ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, chunkHash);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getObject("synced_at") != null;
            }
        }
    }

    public synchronized int getTotalTrackedFiles() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM files")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public synchronized long getTotalFileSize() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COALESCE(SUM(file_size), 0) FROM files")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public synchronized int getTotalUniqueChunks() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM chunks")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public synchronized int getTotalFileChunkReferences() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM file_chunks")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public static class FileRecord {
        public final String fileId;
        public final String relativePath;
        public final long fileSize;
        public final long lastModified;
        public final String wholeFileHash;
        public final int version;
        public final String syncStatus;
        public final int chunkCount;

        public FileRecord(String fileId, String relativePath, long fileSize, long lastModified,
                          String wholeFileHash, int version, String syncStatus, int chunkCount) {
            this.fileId = fileId;
            this.relativePath = relativePath;
            this.fileSize = fileSize;
            this.lastModified = lastModified;
            this.wholeFileHash = wholeFileHash;
            this.version = version;
            this.syncStatus = syncStatus;
            this.chunkCount = chunkCount;
        }
    }

    public synchronized List<FileRecord> getAllFileRecords() throws SQLException {
        String sql = """
            SELECT f.file_id, f.relative_path, f.file_size, f.last_modified, f.whole_file_hash, f.version, f.sync_status,
                   COUNT(fc.chunk_index) AS chunk_count
            FROM files f
            LEFT JOIN file_chunks fc ON f.file_id = fc.file_id
            GROUP BY f.file_id
            ORDER BY f.relative_path ASC
        """;
        List<FileRecord> list = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new FileRecord(
                    rs.getString("file_id"),
                    rs.getString("relative_path"),
                    rs.getLong("file_size"),
                    rs.getLong("last_modified"),
                    rs.getString("whole_file_hash"),
                    rs.getInt("version"),
                    rs.getString("sync_status"),
                    rs.getInt("chunk_count")
                ));
            }
        }
        return list;
    }

    @Override
    public synchronized void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
