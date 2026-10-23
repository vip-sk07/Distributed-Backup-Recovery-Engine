package com.backup.sync;

public class SyncReport {
    private final String relativePath;
    private final long fileSize;
    private final int totalChunks;
    private final int uploadedChunks;
    private final int deduplicatedChunks;
    private final long uploadedBytes;
    private final long deduplicatedBytes;
    private final int serverVersion;

    public SyncReport(String relativePath, long fileSize, int totalChunks,
                      int uploadedChunks, int deduplicatedChunks,
                      long uploadedBytes, long deduplicatedBytes, int serverVersion) {
        this.relativePath = relativePath;
        this.fileSize = fileSize;
        this.totalChunks = totalChunks;
        this.uploadedChunks = uploadedChunks;
        this.deduplicatedChunks = deduplicatedChunks;
        this.uploadedBytes = uploadedBytes;
        this.deduplicatedBytes = deduplicatedBytes;
        this.serverVersion = serverVersion;
    }

    public String getRelativePath() { return relativePath; }
    public long getFileSize() { return fileSize; }
    public int getTotalChunks() { return totalChunks; }
    public int getUploadedChunks() { return uploadedChunks; }
    public int getDeduplicatedChunks() { return deduplicatedChunks; }
    public long getUploadedBytes() { return uploadedBytes; }
    public long getDeduplicatedBytes() { return deduplicatedBytes; }
    public int getServerVersion() { return serverVersion; }

    public double getDeduplicationPercentage() {
        if (totalChunks == 0) return 100.0;
        return ((double) deduplicatedChunks / totalChunks) * 100.0;
    }

    @Override
    public String toString() {
        return String.format(
            "SyncReport['%s' v%d | Size: %d B | Chunks: %d (Uploaded: %d, Dedup: %d, Dedup Ratio: %.1f%%) | Transferred: %d B]",
            relativePath, serverVersion, fileSize, totalChunks,
            uploadedChunks, deduplicatedChunks, getDeduplicationPercentage(), uploadedBytes
        );
    }
}
