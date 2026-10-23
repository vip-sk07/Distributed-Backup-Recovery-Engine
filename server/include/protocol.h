#ifndef PROTOCOL_H
#define PROTOCOL_H

#include <stdint.h>
#include <stddef.h>

#define PROTOCOL_MAGIC_BYTE_0 0x42 /* 'B' */
#define PROTOCOL_MAGIC_BYTE_1 0x53 /* 'S' */
#define PROTOCOL_MAGIC        0x4253
#define PROTOCOL_VERSION      0x01

/* Header size: 2 (magic) + 1 (version) + 1 (type) + 4 (request_id) + 4 (payload_len) + 4 (crc32) = 16 bytes */
#define FRAME_HEADER_LEN      16
#define MAX_PAYLOAD_LEN       (16 * 1024 * 1024) /* 16 MB max chunk / frame size */

/* Core Message Types */
typedef enum {
    MSG_HELLO         = 0x01,
    MSG_HELLO_RESP    = 0x02,
    MSG_ACK           = 0x03,
    MSG_ERROR         = 0x04,
    
    /* Phase 3: Upload pipeline */
    MSG_BEGIN_UPLOAD  = 0x10,
    MSG_NEED_CHUNKS   = 0x11,
    MSG_PUT_CHUNK     = 0x12,
    MSG_COMMIT_UPLOAD = 0x13,
    
    /* Phase 4: Download / Restore */
    MSG_GET_MANIFEST  = 0x20,
    MSG_GET_CHUNK     = 0x21
} message_type_t;

/* Header representation in host memory */
typedef struct {
    uint16_t magic;
    uint8_t  version;
    uint8_t  type;
    uint32_t request_id;
    uint32_t payload_len;
    uint32_t crc32;
} frame_header_t;

/* Serialize header into 16-byte network byte order buffer */
void serialize_header(const frame_header_t *hdr, uint8_t *out_buf);

/* Deserialize 16-byte network byte order buffer into header struct */
int deserialize_header(const uint8_t *in_buf, frame_header_t *hdr);

#endif /* PROTOCOL_H */
