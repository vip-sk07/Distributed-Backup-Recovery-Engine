package com.backup.restore;

import java.nio.file.Path;

public class RestoreReport {
    private final String fileId;
    private final String originalRelativePath;
    private final Path restoredFilePath;
    private final long fileSize;
    private final int version;
    private final int chunkCount;
    private final String wholeFileHash;
    private final long durationMs;
    private final boolean integrityVerified;

    public RestoreReport(String fileId, String originalRelativePath, Path restoredFilePath,
                         long fileSize, int version, int chunkCount,
                         String wholeFileHash, long durationMs, boolean integrityVerified) {
        this.fileId = fileId;
        this.originalRelativePath = originalRelativePath;
        this.restoredFilePath = restoredFilePath;
        this.fileSize = fileSize;
        this.version = version;
        this.chunkCount = chunkCount;
        this.wholeFileHash = wholeFileHash;
        this.durationMs = durationMs;
        this.integrityVerified = integrityVerified;
    }

    public String getFileId() { return fileId; }
    public String getOriginalRelativePath() { return originalRelativePath; }
    public Path getRestoredFilePath() { return restoredFilePath; }
    public long getFileSize() { return fileSize; }
    public int getVersion() { return version; }
    public int getChunkCount() { return chunkCount; }
    public String getWholeFileHash() { return wholeFileHash; }
    public long getDurationMs() { return durationMs; }
    public boolean isIntegrityVerified() { return integrityVerified; }

    @Override
    public String toString() {
        return String.format(
            "RestoreReport['%s' v%d -> %s | Size: %d B | Chunks: %d | Hash: %s | Verified: %s | Time: %d ms]",
            originalRelativePath, version, restoredFilePath.getFileName(),
            fileSize, chunkCount, wholeFileHash.substring(0, Math.min(12, wholeFileHash.length())) + "...",
            integrityVerified ? "PASS" : "FAIL", durationMs
        );
    }
}
