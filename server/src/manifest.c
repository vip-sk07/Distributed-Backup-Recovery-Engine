#include "manifest.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <inttypes.h>
#include <sys/stat.h>

#ifdef _WIN32
    #include <direct.h>
    #include <io.h>
    #define MKDIR(path) _mkdir(path)
    #define FSYNC(fd)   _commit(fd)
#else
    #include <unistd.h>
    #include <fcntl.h>
    #define MKDIR(path) mkdir(path, 0755)
    #define FSYNC(fd)   fsync(fd)
#endif

static int is_valid_file_id(const char *id) {
    if (!id || strlen(id) == 0 || strlen(id) > 127) return 0;
    for (size_t i = 0; id[i] != '\0'; i++) {
        char c = id[i];
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
            return 0;
        }
    }
    return 1;
}

manifest_t *manifest_create(const char *file_id, const char *file_path,
                            uint64_t file_size, uint64_t timestamp,
                            const char *whole_file_hash, uint32_t chunk_count) {
    manifest_t *m = (manifest_t *)malloc(sizeof(manifest_t));
    if (!m) return NULL;

    memset(m, 0, sizeof(*m));
    if (file_id) strncpy(m->file_id, file_id, sizeof(m->file_id) - 1);
    if (file_path) strncpy(m->file_path, file_path, sizeof(m->file_path) - 1);
    if (whole_file_hash) strncpy(m->whole_file_hash, whole_file_hash, sizeof(m->whole_file_hash) - 1);

    m->file_size = file_size;
    m->timestamp = timestamp;
    m->chunk_count = chunk_count;

    if (chunk_count > 0) {
        m->chunks = (manifest_chunk_t *)calloc(chunk_count, sizeof(manifest_chunk_t));
        if (!m->chunks) {
            free(m);
            return NULL;
        }
    } else {
        m->chunks = NULL;
    }

    return m;
}

void manifest_free(manifest_t *m) {
    if (!m) return;
    if (m->chunks) {
        free(m->chunks);
    }
    free(m);
}

char *manifest_serialize(const manifest_t *m) {
    if (!m) return NULL;

    /* Estimate buffer size: header ~512 bytes + ~80 bytes per chunk */
    size_t est_size = 512 + (size_t)m->chunk_count * 90;
    char *buf = (char *)malloc(est_size);
    if (!buf) return NULL;

    int written = snprintf(buf, est_size,
        "FILE_ID:%s\n"
        "FILE_PATH:%s\n"
        "TIMESTAMP:%" PRIu64 "\n"
        "FILE_SIZE:%" PRIu64 "\n"
        "FILE_HASH:%s\n"
        "CHUNK_COUNT:%" PRIu32 "\n"
        "CHUNKS_BEGIN\n",
        m->file_id, m->file_path, m->timestamp, m->file_size, m->whole_file_hash, m->chunk_count);

    if (written < 0 || (size_t)written >= est_size) {
        free(buf);
        return NULL;
    }

    size_t offset = (size_t)written;
    for (uint32_t i = 0; i < m->chunk_count; i++) {
        int chunk_len = snprintf(buf + offset, est_size - offset,
            "%" PRIu64 " %" PRIu32 " %s\n",
            m->chunks[i].offset, m->chunks[i].length, m->chunks[i].chunk_hash);

        if (chunk_len < 0 || offset + (size_t)chunk_len >= est_size) {
            free(buf);
            return NULL;
        }
        offset += (size_t)chunk_len;
    }

    int end_len = snprintf(buf + offset, est_size - offset, "CHUNKS_END\n");
    if (end_len < 0 || offset + (size_t)end_len >= est_size) {
        free(buf);
        return NULL;
    }

    return buf;
}

manifest_t *manifest_deserialize(const char *data) {
    if (!data) return NULL;

    char file_id[128] = {0};
    char file_path[512] = {0};
    uint64_t timestamp = 0;
    uint64_t file_size = 0;
    char file_hash[65] = {0};
    uint32_t chunk_count = 0;

    const char *line = data;
    while (line && *line) {
        if (strncmp(line, "FILE_ID:", 8) == 0) {
            sscanf(line + 8, "%127[^\r\n]", file_id);
        } else if (strncmp(line, "FILE_PATH:", 10) == 0) {
            sscanf(line + 10, "%511[^\r\n]", file_path);
        } else if (strncmp(line, "TIMESTAMP:", 10) == 0) {
            sscanf(line + 10, "%" SCNu64, &timestamp);
        } else if (strncmp(line, "FILE_SIZE:", 10) == 0) {
            sscanf(line + 10, "%" SCNu64, &file_size);
        } else if (strncmp(line, "FILE_HASH:", 10) == 0) {
            sscanf(line + 10, "%64s", file_hash);
        } else if (strncmp(line, "CHUNK_COUNT:", 12) == 0) {
            sscanf(line + 12, "%" SCNu32, &chunk_count);
        } else if (strncmp(line, "CHUNKS_BEGIN", 12) == 0) {
            const char *next_nl = strchr(line, '\n');
            line = next_nl ? next_nl + 1 : NULL;
            break;
        }

        const char *next = strchr(line, '\n');
        line = next ? next + 1 : NULL;
    }

    manifest_t *m = manifest_create(file_id, file_path, file_size, timestamp, file_hash, chunk_count);
    if (!m) return NULL;

    uint32_t idx = 0;
    while (line && *line && idx < chunk_count) {
        if (strncmp(line, "CHUNKS_END", 10) == 0) {
            break;
        }

        uint64_t off = 0;
        uint32_t len = 0;
        char hash_str[65] = {0};

        if (sscanf(line, "%" SCNu64 " %" SCNu32 " %64s", &off, &len, hash_str) == 3) {
            m->chunks[idx].offset = off;
            m->chunks[idx].length = len;
            memcpy(m->chunks[idx].chunk_hash, hash_str, 65);
            idx++;
        }

        const char *next = strchr(line, '\n');
        line = next ? next + 1 : NULL;
    }

    return m;
}

int manifest_save_version(const char *base_path, const manifest_t *m, uint32_t version) {
    if (!base_path || !m || !is_valid_file_id(m->file_id)) return -1;

    char dir_path[1024];
    snprintf(dir_path, sizeof(dir_path), "%s/manifests/%s", base_path, m->file_id);
    MKDIR(dir_path);

    char file_path[2048];
    char tmp_path[2048];
    snprintf(file_path, sizeof(file_path), "%s/v%" PRIu32 ".manifest", dir_path, version);
    snprintf(tmp_path, sizeof(tmp_path), "%s/v%" PRIu32 ".%u.tmp", dir_path, version, (unsigned int)rand());

    char *data = manifest_serialize(m);
    if (!data) return -1;

    FILE *f = fopen(tmp_path, "wb");
    if (!f) {
        free(data);
        return -1;
    }

    size_t len = strlen(data);
    size_t written = fwrite(data, 1, len, f);
    if (written != len) {
        fclose(f);
        remove(tmp_path);
        free(data);
        return -1;
    }

    fflush(f);
    int fd = fileno(f);
    if (fd >= 0) {
        FSYNC(fd);
    }
    fclose(f);
    free(data);

#ifdef _WIN32
    if (!MoveFileExA(tmp_path, file_path, MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        remove(tmp_path);
        return -1;
    }
#else
    if (rename(tmp_path, file_path) != 0) {
        remove(tmp_path);
        return -1;
    }
#endif

    return 0;
}

manifest_t *manifest_load_version(const char *base_path, const char *file_id, uint32_t version) {
    if (!base_path || !file_id || !is_valid_file_id(file_id)) return NULL;

    char file_path[2048];
    snprintf(file_path, sizeof(file_path), "%s/manifests/%s/v%" PRIu32 ".manifest", base_path, file_id, version);

    FILE *f = fopen(file_path, "rb");
    if (!f) return NULL;

    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);

    if (sz <= 0) {
        fclose(f);
        return NULL;
    }

    char *buf = (char *)malloc(sz + 1);
    if (!buf) {
        fclose(f);
        return NULL;
    }

    size_t read_bytes = fread(buf, 1, sz, f);
    fclose(f);
    buf[read_bytes] = '\0';

    manifest_t *m = manifest_deserialize(buf);
    free(buf);
    return m;
}

uint32_t manifest_get_latest_version(const char *base_path, const char *file_id) {
    if (!base_path || !file_id || !is_valid_file_id(file_id)) return 0;
    uint32_t v = 1;
    while (1) {
        char file_path[2048];
        snprintf(file_path, sizeof(file_path), "%s/manifests/%s/v%" PRIu32 ".manifest", base_path, file_id, v);
        struct stat st;
        if (stat(file_path, &st) != 0) {
            return v - 1;
        }
        v++;
    }
}
