package com.backup.protocol;

import com.backup.model.Chunk;
import com.backup.model.FileSnapshot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ManifestWireUtil {
    private ManifestWireUtil() {}

    public static byte[] serializeToWireBytes(FileSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("FILE_ID:").append(snapshot.getFileId()).append("\n");
        sb.append("FILE_PATH:").append(snapshot.getRelativePath()).append("\n");
        sb.append("TIMESTAMP:").append(snapshot.getLastModified()).append("\n");
        sb.append("FILE_SIZE:").append(snapshot.getFileSize()).append("\n");
        sb.append("FILE_HASH:").append(snapshot.getWholeFileHash()).append("\n");
        sb.append("CHUNK_COUNT:").append(snapshot.getChunks().size()).append("\n");
        sb.append("CHUNKS_BEGIN\n");

        for (Chunk c : snapshot.getChunks()) {
            sb.append(c.getOffset()).append(" ")
              .append(c.getLength()).append(" ")
              .append(c.getSha256()).append("\n");
        }
        sb.append("CHUNKS_END\n");

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static FileSnapshot deserializeFromWireString(String wireText) throws IOException {
        String fileId = "";
        String filePath = "";
        long timestamp = 0;
        long fileSize = 0;
        String fileHash = "";
        int chunkCount = 0;

        List<Chunk> chunks = new ArrayList<>();
        boolean inChunks = false;
        int chunkIdx = 0;

        try (BufferedReader reader = new BufferedReader(new StringReader(wireText))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (!inChunks) {
                    if (line.startsWith("FILE_ID:")) {
                        fileId = line.substring(8);
                    } else if (line.startsWith("FILE_PATH:")) {
                        filePath = line.substring(10);
                    } else if (line.startsWith("TIMESTAMP:")) {
                        timestamp = Long.parseLong(line.substring(10));
                    } else if (line.startsWith("FILE_SIZE:")) {
                        fileSize = Long.parseLong(line.substring(10));
                    } else if (line.startsWith("FILE_HASH:")) {
                        fileHash = line.substring(10);
                    } else if (line.startsWith("CHUNK_COUNT:")) {
                        chunkCount = Integer.parseInt(line.substring(12));
                    } else if (line.equals("CHUNKS_BEGIN")) {
                        inChunks = true;
                    }
                } else {
                    if (line.equals("CHUNKS_END")) {
                        break;
                    }
                    String[] parts = line.split("\\s+");
                    if (parts.length == 3) {
                        long offset = Long.parseLong(parts[0]);
                        int len = Integer.parseInt(parts[1]);
                        String hash = parts[2];
                        chunks.add(new Chunk(chunkIdx++, offset, len, hash));
                    }
                }
            }
        }

        return new FileSnapshot(fileId, filePath, fileSize, timestamp, fileHash, chunks);
    }
}
