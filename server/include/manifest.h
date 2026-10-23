#ifndef MANIFEST_H
#define MANIFEST_H

#include <stdint.h>
#include <stddef.h>

typedef struct {
    uint64_t offset;
    uint32_t length;
    char chunk_hash[65];
} manifest_chunk_t;

typedef struct {
    char file_id[128];
    char file_path[512];
    uint64_t timestamp;
    uint64_t file_size;
    char whole_file_hash[65];
    uint32_t chunk_count;
    manifest_chunk_t *chunks;
} manifest_t;

/* Allocate a new manifest */
manifest_t *manifest_create(const char *file_id, const char *file_path,
                            uint64_t file_size, uint64_t timestamp,
                            const char *whole_file_hash, uint32_t chunk_count);

/* Free manifest and allocated chunk entries */
void manifest_free(manifest_t *m);

/* Save a manifest to ./store/manifests/<file_id>/v<version>.manifest */
int manifest_save_version(const char *base_path, const manifest_t *m, uint32_t version);

/* Load a manifest from ./store/manifests/<file_id>/v<version>.manifest */
manifest_t *manifest_load_version(const char *base_path, const char *file_id, uint32_t version);

/* Find highest version number for a file_id, returns 0 if no versions exist */
uint32_t manifest_get_latest_version(const char *base_path, const char *file_id);

/* Serialize manifest to wire-compatible string payload */
char *manifest_serialize(const manifest_t *m);

/* Deserialize manifest from wire payload */
manifest_t *manifest_deserialize(const char *data);

#endif /* MANIFEST_H */
