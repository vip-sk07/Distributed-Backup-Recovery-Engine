#ifndef CRC32_H
#define CRC32_H

#include <stdint.h>
#include <stddef.h>

/* Initialize lookup table */
void crc32_init(void);

/* Calculate IEEE 802.3 CRC32 checksum over buffer */
uint32_t crc32_compute(const uint8_t *data, size_t length);

#endif /* CRC32_H */
