#include "protocol.h"

void serialize_header(const frame_header_t *hdr, uint8_t *out_buf) {
    if (!hdr || !out_buf) return;

    out_buf[0]  = (uint8_t)((hdr->magic >> 8) & 0xFF);
    out_buf[1]  = (uint8_t)(hdr->magic & 0xFF);
    out_buf[2]  = hdr->version;
    out_buf[3]  = hdr->type;
    out_buf[4]  = (uint8_t)((hdr->request_id >> 24) & 0xFF);
    out_buf[5]  = (uint8_t)((hdr->request_id >> 16) & 0xFF);
    out_buf[6]  = (uint8_t)((hdr->request_id >> 8) & 0xFF);
    out_buf[7]  = (uint8_t)(hdr->request_id & 0xFF);
    out_buf[8]  = (uint8_t)((hdr->payload_len >> 24) & 0xFF);
    out_buf[9]  = (uint8_t)((hdr->payload_len >> 16) & 0xFF);
    out_buf[10] = (uint8_t)((hdr->payload_len >> 8) & 0xFF);
    out_buf[11] = (uint8_t)(hdr->payload_len & 0xFF);
    out_buf[12] = (uint8_t)((hdr->crc32 >> 24) & 0xFF);
    out_buf[13] = (uint8_t)((hdr->crc32 >> 16) & 0xFF);
    out_buf[14] = (uint8_t)((hdr->crc32 >> 8) & 0xFF);
    out_buf[15] = (uint8_t)(hdr->crc32 & 0xFF);
}

int deserialize_header(const uint8_t *in_buf, frame_header_t *hdr) {
    if (!in_buf || !hdr) return -1;

    hdr->magic       = ((uint16_t)in_buf[0] << 8) | (uint16_t)in_buf[1];
    hdr->version     = in_buf[2];
    hdr->type        = in_buf[3];
    hdr->request_id  = ((uint32_t)in_buf[4] << 24) |
                       ((uint32_t)in_buf[5] << 16) |
                       ((uint32_t)in_buf[6] << 8)  |
                       (uint32_t)in_buf[7];
    hdr->payload_len = ((uint32_t)in_buf[8] << 24) |
                       ((uint32_t)in_buf[9] << 16) |
                       ((uint32_t)in_buf[10] << 8) |
                       (uint32_t)in_buf[11];
    hdr->crc32       = ((uint32_t)in_buf[12] << 24) |
                       ((uint32_t)in_buf[13] << 16) |
                       ((uint32_t)in_buf[14] << 8)  |
                       (uint32_t)in_buf[15];

    if (hdr->magic != PROTOCOL_MAGIC) {
        return -2; /* Invalid Magic */
    }
    if (hdr->version != PROTOCOL_VERSION) {
        return -3; /* Unsupported Version */
    }
    if (hdr->payload_len > MAX_PAYLOAD_LEN) {
        return -4; /* Payload Exceeds Maximum Permitted Length */
    }

    return 0;
}
