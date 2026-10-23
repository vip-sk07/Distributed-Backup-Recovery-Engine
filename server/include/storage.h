#ifndef STORAGE_H
#define STORAGE_H

#include <stddef.h>
#include <stdint.h>

#define DEFAULT_STORE_DIR "./store"

/* Initialize storage directory tree:
 *   ./store/chunks/
 *   ./store/tmp/
 *   ./store/manifests/
 * Returns 0 on success, -1 on error.
 */
int storage_init(const char *base_path);

/* Check if a chunk exists by its 64-char SHA-256 hash.
 * Returns: 1 if exists, 0 if missing, -1 on error.
 */
int storage_has_chunk(const char *chunk_hash_hex);

/* Atomically write a chunk:
 * 1. Validates content hash against chunk_hash_hex.
 * 2. Writes to ./store/tmp/<unique>.tmp.
 * 3. fsync's data to physical disk.
 * 4. Atomically renames to ./store/chunks/<chunk_hash_hex>.
 * Returns: 0 on success, -1 on I/O error, -2 on hash mismatch.
 */
int storage_write_chunk(const char *chunk_hash_hex, const uint8_t *data, size_t len);

/* Read a chunk into a newly allocated buffer (caller frees).
 * Verifies SHA-256 integrity upon reading.
 * Returns: 0 on success, -1 on read error, -2 on corruption.
 */
int storage_read_chunk(const char *chunk_hash_hex, uint8_t **out_data, size_t *out_len);

#endif /* STORAGE_H */
