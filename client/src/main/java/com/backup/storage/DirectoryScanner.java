package com.backup.storage;

import com.backup.model.FileSnapshot;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class DirectoryScanner {
    private static final Logger LOGGER = Logger.getLogger(DirectoryScanner.class.getName());

    private final Path rootDirectory;
    private final FileChunker chunker;
    private final LocalStateDatabase database;

    public DirectoryScanner(Path rootDirectory, FileChunker chunker, LocalStateDatabase database) {
        this.rootDirectory = rootDirectory;
        this.chunker = chunker;
        this.database = database;
    }

    public static class ScanResult {
        private final List<FileSnapshot> modifiedOrNewFiles = new ArrayList<>();
        private final List<FileSnapshot> unchangedFiles = new ArrayList<>();
        private int totalFilesScanned = 0;
        private long totalBytesScanned = 0;

        public List<FileSnapshot> getModifiedOrNewFiles() { return modifiedOrNewFiles; }
        public List<FileSnapshot> getUnchangedFiles() { return unchangedFiles; }
        public int getTotalFilesScanned() { return totalFilesScanned; }
        public long getTotalBytesScanned() { return totalBytesScanned; }
    }

    public ScanResult scan(boolean keepChunkDataInMemory) throws IOException {
        ScanResult result = new ScanResult();

        if (!Files.exists(rootDirectory) || !Files.isDirectory(rootDirectory)) {
            throw new IOException("Root path does not exist or is not a directory: " + rootDirectory);
        }

        Files.walkFileTree(rootDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                if (dirName.startsWith(".") || dirName.equals("node_modules") || dirName.equals(".git")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }

                String fileName = file.getFileName().toString();
                if (fileName.startsWith(".") || fileName.endsWith(".db") || fileName.endsWith(".db-journal") || fileName.endsWith(".db-wal")) {
                    return FileVisitResult.CONTINUE;
                }

                result.totalFilesScanned++;
                result.totalBytesScanned += attrs.size();

                Path relativePath = rootDirectory.relativize(file);
                String relPathStr = relativePath.toString().replace('\\', '/');

                try {
                    FileSnapshot existing = database.getFileSnapshot(relPathStr);
                    long currentMtime = attrs.lastModifiedTime().toMillis();
                    long currentSize = attrs.size();

                    if (existing != null && existing.getFileSize() == currentSize && existing.getLastModified() == currentMtime) {
                        // Unchanged file - short-circuit!
                        result.unchangedFiles.add(existing);
                    } else {
                        // New or modified file - chunk and hash!
                        FileSnapshot snapshot = chunker.chunkFile(rootDirectory, file, keepChunkDataInMemory);
                        FileSnapshot saved = database.saveFileSnapshot(snapshot, "SCANNED");
                        result.modifiedOrNewFiles.add(saved != null ? saved : snapshot);
                    }
                } catch (SQLException e) {
                    throw new IOException("Database error scanning file: " + file, e);
                }

                return FileVisitResult.CONTINUE;
            }
        });

        return result;
    }
}
