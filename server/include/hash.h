#ifndef HASH_H
#define HASH_H

#include <stdint.h>
#include <stddef.h>

#define SHA256_DIGEST_LENGTH 32
#define SHA256_HEX_LENGTH    64

typedef struct {
    uint32_t state[8];
    uint64_t count;
    uint8_t  buffer[64];
} sha256_ctx_t;

/* Initialize SHA-256 context */
void sha256_init(sha256_ctx_t *ctx);

/* Update SHA-256 context with input data */
void sha256_update(sha256_ctx_t *ctx, const void *data, size_t len);

/* Finalize SHA-256 and produce 32-byte binary digest */
void sha256_final(sha256_ctx_t *ctx, uint8_t digest[SHA256_DIGEST_LENGTH]);

/* One-shot SHA-256 hash of a memory buffer */
void sha256_compute(const void *data, size_t len, uint8_t digest[SHA256_DIGEST_LENGTH]);

/* Convert 32-byte digest into 64-char lowercase hex string + null terminator (65 bytes buffer required) */
void sha256_to_hex(const uint8_t digest[SHA256_DIGEST_LENGTH], char hex_out[SHA256_HEX_LENGTH + 1]);

/* One-shot SHA-256 directly to 64-character lowercase hex string */
void sha256_compute_hex(const void *data, size_t len, char hex_out[SHA256_HEX_LENGTH + 1]);

#endif /* HASH_H */
