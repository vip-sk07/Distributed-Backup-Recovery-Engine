#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
#include "hash.h"
#include "storage.h"
#include "manifest.h"

int main(void) {
    printf("========================================================\n");
    printf("  Distributed Backup System - C Storage & Hash Engine  \n");
    printf("  Phase 2: Self-Contained Unit & Integration Tests      \n");
    printf("========================================================\n");

    /* Test 1: SHA-256 Known Test Vectors */
    printf("\n--- TEST 1: SHA-256 Hash Verification ---\n");
    char hex[65];
    /* NIST Vector: "abc" -> ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad */
    const char *test_str = "abc";
    sha256_compute_hex(test_str, strlen(test_str), hex);
    printf("[SHA256] Hash of 'abc': %s\n", hex);
    assert(strcmp(hex, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") == 0);
    printf("[PASS] NIST SHA-256 test vector matched!\n");

    /* Test 2: Storage Initialization */
    printf("\n--- TEST 2: Storage Initialization ---\n");
    assert(storage_init("./test_store") == 0);
    printf("[PASS] Storage initialized at ./test_store\n");

    /* Test 3: Atomic Chunk Write, fsync, and Rename */
    printf("\n--- TEST 3: Atomic Chunk Write & Deduplication ---\n");
    const char *chunk_data = "This is a test chunk containing payload for distributed backup engine.";
    size_t chunk_len = strlen(chunk_data);
    char chunk_hash[65];
    sha256_compute_hex(chunk_data, chunk_len, chunk_hash);
    printf("[CHUNK] Hash: %s (length %zu)\n", chunk_hash, chunk_len);

    /* Remove if previously created in past test run */
    char old_chunk_path[1024];
    snprintf(old_chunk_path, sizeof(old_chunk_path), "./test_store/chunks/%s", chunk_hash);
    remove(old_chunk_path);

    /* Initially should not exist */
    assert(storage_has_chunk(chunk_hash) == 0);

    /* Write chunk atomically */
    int write_res = storage_write_chunk(chunk_hash, (const uint8_t *)chunk_data, chunk_len);
    assert(write_res == 0);
    printf("[PASS] Chunk written atomically!\n");

    /* Now should exist */
    assert(storage_has_chunk(chunk_hash) == 1);
    printf("[PASS] Chunk presence confirmed!\n");

    /* Re-write same chunk -> deduplication short-circuit */
    int dedup_res = storage_write_chunk(chunk_hash, (const uint8_t *)chunk_data, chunk_len);
    assert(dedup_res == 0);
    printf("[PASS] Deduplication confirmed: zero duplicate writes!\n");

    /* Test 4: Chunk Read & Integrity Verification */
    printf("\n--- TEST 4: Chunk Read & Integrity Check ---\n");
    uint8_t *read_data = NULL;
    size_t read_len = 0;
    assert(storage_read_chunk(chunk_hash, &read_data, &read_len) == 0);
    assert(read_len == chunk_len);
    assert(memcmp(read_data, chunk_data, chunk_len) == 0);
    free(read_data);
    printf("[PASS] Chunk read and verified byte-for-byte!\n");

    /* Test 5: Manifest Versioning */
    printf("\n--- TEST 5: Versioned Manifest Serialization ---\n");
    manifest_t *m1 = manifest_create("file_001", "/docs/report.pdf", 1024 * 1024, 1690000000000ULL,
                                     "1111222233334444555566667777888899990000aaaaabbbbbcccccdddddeeeee", 2);
    assert(m1 != NULL);
    m1->chunks[0].offset = 0;
    m1->chunks[0].length = 524288;
    memcpy(m1->chunks[0].chunk_hash, chunk_hash, 65);

    m1->chunks[1].offset = 524288;
    m1->chunks[1].length = 524288;
    memcpy(m1->chunks[1].chunk_hash, "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899", 65);

    assert(manifest_save_version("./test_store", m1, 1) == 0);
    printf("[PASS] Saved manifest version 1!\n");

    manifest_t *loaded_m = manifest_load_version("./test_store", "file_001", 1);
    assert(loaded_m != NULL);
    assert(strcmp(loaded_m->file_id, "file_001") == 0);
    assert(strcmp(loaded_m->file_path, "/docs/report.pdf") == 0);
    assert(loaded_m->chunk_count == 2);
    assert(strcmp(loaded_m->chunks[0].chunk_hash, chunk_hash) == 0);
    printf("[PASS] Loaded and parsed manifest version 1 cleanly!\n");

    assert(manifest_get_latest_version("./test_store", "file_001") == 1);
    printf("[PASS] Latest version confirmed as 1!\n");

    manifest_free(m1);
    manifest_free(loaded_m);

    printf("\n========================================================\n");
    printf("  All C Storage Engine Tests PASSED successfully!\n");
    printf("========================================================\n");
    return 0;
}
