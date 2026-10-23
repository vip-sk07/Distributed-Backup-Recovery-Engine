package com.backup.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class FileSnapshot {
    private final String fileId;
    private final String relativePath;
    private final long fileSize;
    private final long lastModified;
    private final String wholeFileHash;
    private final List<Chunk> chunks;
    private final int version;
    private final String syncStatus;

    public FileSnapshot(String fileId, String relativePath, long fileSize,
                        long lastModified, String wholeFileHash, List<Chunk> chunks, int version, String syncStatus) {
        this.fileId = Objects.requireNonNull(fileId);
        this.relativePath = Objects.requireNonNull(relativePath);
        this.fileSize = fileSize;
        this.lastModified = lastModified;
        this.wholeFileHash = Objects.requireNonNull(wholeFileHash);
        this.chunks = chunks != null ? Collections.unmodifiableList(chunks) : Collections.emptyList();
        this.version = version;
        this.syncStatus = syncStatus != null ? syncStatus : "SCANNED";
    }

    public FileSnapshot(String fileId, String relativePath, long fileSize,
                        long lastModified, String wholeFileHash, List<Chunk> chunks, int version) {
        this(fileId, relativePath, fileSize, lastModified, wholeFileHash, chunks, version, "SCANNED");
    }

    public FileSnapshot(String fileId, String relativePath, long fileSize,
                        long lastModified, String wholeFileHash, List<Chunk> chunks) {
        this(fileId, relativePath, fileSize, lastModified, wholeFileHash, chunks, 1, "SCANNED");
    }

    public String getFileId() { return fileId; }
    public String getRelativePath() { return relativePath; }
    public long getFileSize() { return fileSize; }
    public long getLastModified() { return lastModified; }
    public String getWholeFileHash() { return wholeFileHash; }
    public List<Chunk> getChunks() { return chunks; }
    public int getVersion() { return version; }
    public String getSyncStatus() { return syncStatus; }

    @Override
    public String toString() {
        return String.format("FileSnapshot[path='%s', size=%d, chunks=%d, hash=%s, v=%d, status=%s]",
                relativePath, fileSize, chunks.size(),
                wholeFileHash.substring(0, Math.min(12, wholeFileHash.length())) + "...", version, syncStatus);
    }
}
