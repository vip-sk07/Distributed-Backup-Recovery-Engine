#include "storage.h"
#include "hash.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <errno.h>

#ifdef _WIN32
    #include <windows.h>
    #include <io.h>
    #include <direct.h>
    #define MKDIR(path) _mkdir(path)
    #define FSYNC(fd)   _commit(fd)
#else
    #include <unistd.h>
    #include <fcntl.h>
    #define MKDIR(path) mkdir(path, 0755)
    #define FSYNC(fd)   fsync(fd)
#endif

#define MAX_PATH_BUF 4096
#define BASE_PATH_BUF 1024

static char g_store_base[512] = DEFAULT_STORE_DIR;
static char g_chunks_dir[BASE_PATH_BUF];
static char g_tmp_dir[BASE_PATH_BUF];
static char g_manifests_dir[BASE_PATH_BUF];

static int is_valid_hex_hash(const char *hash) {
    if (!hash || strlen(hash) != SHA256_HEX_LENGTH) {
        return 0;
    }
    for (int i = 0; i < SHA256_HEX_LENGTH; i++) {
        char c = hash[i];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
            return 0;
        }
    }
    return 1;
}

static int ensure_dir(const char *path) {
    struct stat st;
    if (stat(path, &st) == 0) {
        if (st.st_mode & S_IFDIR) {
            return 0;
        }
        return -1;
    }
    if (MKDIR(path) != 0 && errno != EEXIST) {
        perror("[STORAGE] Failed to create directory");
        return -1;
    }
    return 0;
}

int storage_init(const char *base_path) {
    if (base_path && strlen(base_path) > 0) {
        strncpy(g_store_base, base_path, sizeof(g_store_base) - 1);
        g_store_base[sizeof(g_store_base) - 1] = '\0';
    }

    snprintf(g_chunks_dir, sizeof(g_chunks_dir), "%s/chunks", g_store_base);
    snprintf(g_tmp_dir, sizeof(g_tmp_dir), "%s/tmp", g_store_base);
    snprintf(g_manifests_dir, sizeof(g_manifests_dir), "%s/manifests", g_store_base);

    if (ensure_dir(g_store_base) != 0 ||
        ensure_dir(g_chunks_dir) != 0 ||
        ensure_dir(g_tmp_dir) != 0 ||
        ensure_dir(g_manifests_dir) != 0) {
        fprintf(stderr, "[STORAGE] Failed initializing directory structure under '%s'\n", g_store_base);
        return -1;
    }

    printf("[STORAGE] Content-addressed storage initialized at: %s\n", g_store_base);
    return 0;
}

int storage_has_chunk(const char *chunk_hash_hex) {
    if (!is_valid_hex_hash(chunk_hash_hex)) {
        return -1;
    }
    char chunk_path[MAX_PATH_BUF];
    snprintf(chunk_path, sizeof(chunk_path), "%s/%s", g_chunks_dir, chunk_hash_hex);

    struct stat st;
    if (stat(chunk_path, &st) == 0 && (st.st_mode & S_IFREG)) {
        return 1;
    }
    return 0;
}

int storage_write_chunk(const char *chunk_hash_hex, const uint8_t *data, size_t len) {
    if (!is_valid_hex_hash(chunk_hash_hex)) {
        return -1;
    }

    /* Step 1: Content integrity validation */
    char computed_hash[SHA256_HEX_LENGTH + 1];
    sha256_compute_hex(data, len, computed_hash);
    if (strcmp(computed_hash, chunk_hash_hex) != 0) {
        fprintf(stderr, "[STORAGE] Integrity violation! Expected hash %s, computed %s\n",
                chunk_hash_hex, computed_hash);
        return -2;
    }

    /* Step 2: Deduplication short-circuit */
    if (storage_has_chunk(chunk_hash_hex) == 1) {
        return 0; /* Chunk already exists immutably */
    }

    /* Step 3: Write to temporary file */
    char tmp_path[MAX_PATH_BUF];
    char target_path[MAX_PATH_BUF];
    snprintf(target_path, sizeof(target_path), "%s/%s", g_chunks_dir, chunk_hash_hex);
    snprintf(tmp_path, sizeof(tmp_path), "%s/%s.%u.%lu.tmp",
             g_tmp_dir, chunk_hash_hex, (unsigned int)rand(), (unsigned long)len);

    FILE *f = fopen(tmp_path, "wb");
    if (!f) {
        perror("[STORAGE] Failed to open temp file for writing chunk");
        return -1;
    }

    if (len > 0) {
        size_t written = fwrite(data, 1, len, f);
        if (written != len) {
            perror("[STORAGE] Failed writing chunk data");
            fclose(f);
            remove(tmp_path);
            return -1;
        }
    }

    /* Step 4: Ensure physical persistence via fsync */
    fflush(f);
    int fd = fileno(f);
    if (fd >= 0) {
        FSYNC(fd);
    }
    fclose(f);

    /* Step 5: Atomic commit to chunk store */
#ifdef _WIN32
    if (!MoveFileExA(tmp_path, target_path, MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        fprintf(stderr, "[STORAGE] MoveFileEx failed with error %lu\n", GetLastError());
        remove(tmp_path);
        return -1;
    }
#else
    if (rename(tmp_path, target_path) != 0) {
        perror("[STORAGE] Atomic rename failed");
        remove(tmp_path);
        return -1;
    }
#endif

    return 0;
}

int storage_read_chunk(const char *chunk_hash_hex, uint8_t **out_data, size_t *out_len) {
    if (!is_valid_hex_hash(chunk_hash_hex) || !out_data || !out_len) {
        return -1;
    }

    char chunk_path[MAX_PATH_BUF];
    snprintf(chunk_path, sizeof(chunk_path), "%s/%s", g_chunks_dir, chunk_hash_hex);

    struct stat st;
    if (stat(chunk_path, &st) != 0) {
        return -1;
    }

    size_t file_size = (size_t)st.st_size;
    FILE *f = fopen(chunk_path, "rb");
    if (!f) {
        return -1;
    }

    uint8_t *buf = (uint8_t *)malloc(file_size > 0 ? file_size : 1);
    if (!buf) {
        fclose(f);
        return -1;
    }

    if (file_size > 0) {
        size_t read_bytes = fread(buf, 1, file_size, f);
        if (read_bytes != file_size) {
            fclose(f);
            free(buf);
            return -1;
        }
    }
    fclose(f);

    /* Validate stored chunk integrity */
    char verify_hash[SHA256_HEX_LENGTH + 1];
    sha256_compute_hex(buf, file_size, verify_hash);
    if (strcmp(verify_hash, chunk_hash_hex) != 0) {
        fprintf(stderr, "[STORAGE] Stored chunk corrupted: %s\n", chunk_hash_hex);
        free(buf);
        return -2;
    }

    *out_data = buf;
    *out_len = file_size;
    return 0;
}
