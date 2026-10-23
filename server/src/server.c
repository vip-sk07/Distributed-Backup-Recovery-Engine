#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include "protocol.h"
#include "net.h"
#include "crc32.h"
#include "hash.h"
#include "storage.h"
#include "manifest.h"
#include "threadpool.h"

#define DEFAULT_PORT 9876
#define WORKER_THREADS 8
#define TASK_QUEUE_CAPACITY 64

static char g_server_store_dir[1024] = DEFAULT_STORE_DIR;
static volatile int server_running = 1;
static socket_t listen_fd = INVALID_SOCKET_FD;

typedef struct {
    socket_t client_fd;
    char client_ip[INET_ADDRSTRLEN];
    int client_port;
    manifest_t *pending_manifest;
} client_ctx_t;

#ifndef _WIN32
static void handle_signal(int sig) {
    (void)sig;
    server_running = 0;
    if (listen_fd != INVALID_SOCKET_FD) {
        net_close(listen_fd);
        listen_fd = INVALID_SOCKET_FD;
    }
}
#endif

static int send_response(socket_t client_fd, uint8_t type, uint32_t request_id, const uint8_t *payload, uint32_t payload_len) {
    uint8_t hdr_buf[FRAME_HEADER_LEN];
    frame_header_t hdr;
    hdr.magic = PROTOCOL_MAGIC;
    hdr.version = PROTOCOL_VERSION;
    hdr.type = type;
    hdr.request_id = request_id;
    hdr.payload_len = payload_len;
    hdr.crc32 = (payload_len > 0 && payload != NULL) ? crc32_compute(payload, payload_len) : 0;

    serialize_header(&hdr, hdr_buf);

    if (send_all(client_fd, hdr_buf, FRAME_HEADER_LEN) != 0) {
        return -1;
    }

    if (payload_len > 0 && payload != NULL) {
        if (send_all(client_fd, payload, payload_len) != 0) {
            return -1;
        }
    }

    return 0;
}

static void handle_begin_upload(client_ctx_t *ctx, uint32_t request_id, const uint8_t *payload, uint32_t payload_len) {
    if (!payload || payload_len == 0) {
        const char *err = "Empty manifest payload";
        send_response(ctx->client_fd, MSG_ERROR, request_id, (const uint8_t *)err, (uint32_t)strlen(err));
        return;
    }

    manifest_t *m = manifest_deserialize((const char *)payload);
    if (!m) {
        const char *err = "Malformed manifest specification";
        send_response(ctx->client_fd, MSG_ERROR, request_id, (const uint8_t *)err, (uint32_t)strlen(err));
        return;
    }

    if (ctx->pending_manifest) {
        manifest_free(ctx->pending_manifest);
    }
    ctx->pending_manifest = m;

    /* Query storage engine for missing chunk hashes */
    uint32_t missing_count = 0;
    uint32_t *missing_indices = NULL;
    if (m->chunk_count > 0) {
        missing_indices = (uint32_t *)malloc(sizeof(uint32_t) * m->chunk_count);
        if (!missing_indices) {
            const char *err = "OOM allocating chunk index table";
            send_response(ctx->client_fd, MSG_ERROR, request_id, (const uint8_t *)err, (uint32_t)strlen(err));
            return;
        }
        for (uint32_t i = 0; i < m->chunk_count; i++) {
            if (storage_has_chunk(m->chunks[i].chunk_hash) != 1) {
                missing_indices[missing_count++] = i;
            }
        }
    }

    /* Build NEED_CHUNKS response */
    size_t resp_buf_size = 64 + (size_t)missing_count * 66;
    char *resp_buf = (char *)malloc(resp_buf_size);
    if (!resp_buf) {
        if (missing_indices) free(missing_indices);
        const char *err = "OOM formatting NEED_CHUNKS";
        send_response(ctx->client_fd, MSG_ERROR, request_id, (const uint8_t *)err, (uint32_t)strlen(err));
        return;
    }

    int written = snprintf(resp_buf, resp_buf_size, "COUNT:%u\n", missing_count);
    size_t cur_offset = (written > 0 && (size_t)written < resp_buf_size) ? (size_t)written : 0;

    for (uint32_t i = 0; i < missing_count && cur_offset + 66 < resp_buf_size; i++) {
        int line_len = snprintf(resp_buf + cur_offset, resp_buf_size - cur_offset, "%s\n",
                               m->chunks[missing_indices[i]].chunk_hash);
        if (line_len > 0) {
            cur_offset += (size_t)line_len;
        }
    }

    if (missing_indices) free(missing_indices);

    send_response(ctx->client_fd, MSG_NEED_CHUNKS, request_id, (const uint8_t *)resp_buf, (uint32_t)cur_offset);
    free(resp_buf);

    printf("[SERVER] [%s:%d] BEGIN_UPLOAD for '%s' (Total chunks: %u, Missing to upload: %u)\n",
           ctx->client_ip, ctx->client_port, m->file_path, m->chunk_count, missing_count);
}

static void handle_put_chunk(client_ctx_t *ctx, uint32_t request_id, const uint8_t *payload, uint32_t payload_len) {
    if (payload_len < SHA256_HEX_LENGTH) {
        const char *err = "PUT_CHUNK frame missing 64-char hash prefix";
        send_response(ctx->client_fd, MSG_ERROR, request_id, (const uint8_t *)err, (uint32_t)strlen(err));
        return;
    }

    char chunk_hash[SHA256_HEX_LENGTH + 1];
    memcpy(chunk_hash, payload, SHA256_HEX_LENGTH);
    chunk_hash[SHA256_HEX_LENGTH] = '\0';

    const uint8_t *chunk_data = payload + SHA256_HEX_LENGTH;
    size_t chunk_data_len = payload_len - SHA256_HEX_LENGTH;

    int res = storage_write_chunk(chunk_hash, chunk_data, chunk_data_len);
    if (res == 0) {
        char ack[128];
        snprintf(ack, sizeof(ack), "{\"status\":\"CHUNK_STORED\",\"hash\":\"%s\"}", chunk_hash);
        send_response(ctx->client_fd, MSG_ACK, request_id, (const uint8_t *)ack, (uint32_t)strlen(ack));
    } else if (res == -2) {
        fprintf(stderr, "[SERVER] [%s:%d] Integrity error storing chunk %s\n", ctx->client_ip, ctx->client_port, chunk_hash);
        const char *err = "Chunk SHA-256 integrity mismatch";
        send_response(ctx->client_fd, MSG_ERROR, request_id, (const uint8_t *)err, (uint32_t)strlen(err));
    } else {
        const char *err = "I/O error persisting chunk";
        send_response(ctx->client_fd, MSG_ERROR, request_id, (const uint8_t *)err, (uint32_t)strlen(err));
    }
}

static void handle_commit_upload(client_ctx_t *ctx, uint32_t request_id, const uint8_t *payload, uint32_t payload_len) {
    (void)payload;
    (void)payload_len;

    if (!ctx->pending_manifest) {
        const char *err = "No upload session in progress to commit";
        send_response(ctx->client_fd, MSG_ERROR, request_id, (const uint8_t *)err, (uint32_t)strlen(err));
        return;
    }

    manifest_t *m = ctx->pending_manifest;

    /* Verify 100% of chunks exist in store */
    for (uint32_t i = 0; i < m->chunk_count; i++) {
        if (storage_has_chunk(m->chunks[i].chunk_hash) != 1) {
            fprintf(stderr, "[SERVER] [%s:%d] Commit rejected: chunk %s missing!\n",
                    ctx->client_ip, ctx->client_port, m->chunks[i].chunk_hash);
            const char *err = "Commit rejected: incomplete chunks on server";
            send_response(ctx->client_fd, MSG_ERROR, request_id, (const uint8_t *)err, (uint32_t)strlen(err));
            return;
        }
    }

    /* Save versioned manifest */
    uint32_t latest_v = manifest_get_latest_version(g_server_store_dir, m->file_id);
    uint32_t next_v = latest_v + 1;

    if (manifest_save_version(g_server_store_dir, m, next_v) != 0) {
        const char *err = "Failed persisting manifest version";
        send_response(ctx->client_fd, MSG_ERROR, request_id, (const uint8_t *)err, (uint32_t)strlen(err));
        return;
    }

    char ack_msg[256];
    snprintf(ack_msg, sizeof(ack_msg),
             "{\"status\":\"COMMITTED\",\"file_id\":\"%s\",\"version\":%u,\"chunks\":%u}",
             m->file_id, next_v, m->chunk_count);
    send_response(ctx->client_fd, MSG_ACK, request_id, (const uint8_t *)ack_msg, (uint32_t)strlen(ack_msg));

    printf("[SERVER] [%s:%d] Successfully COMMITTED file '%s' as Version %u (Chunks: %u)\n",
           ctx->client_ip, ctx->client_port, m->file_path, next_v, m->chunk_count);

    manifest_free(ctx->pending_manifest);
    ctx->pending_manifest = NULL;
}

static void handle_get_manifest(client_ctx_t *ctx, uint32_t request_id, const uint8_t *payload, uint32_t payload_len) {
    if (!payload || payload_len == 0) {
        const char *err = "Missing file_id in GET_MANIFEST";
        send_response(ctx->client_fd, MSG_ERROR, request_id, (const uint8_t *)err, (uint32_t)strlen(err));
        return;
    }

    char file_id[128] = {0};
    uint32_t version = 0;
    char req_str[256] = {0};
    size_t copy_len = payload_len < sizeof(req_str) - 1 ? payload_len : sizeof(req_str) - 1;
    memcpy(req_str, payload, copy_len);
    req_str[copy_len] = '\0';

    if (sscanf(req_str, "%127s %u", file_id, &version) < 1) {
        const char *err = "Invalid GET_MANIFEST syntax";
        send_response(ctx->client_fd, MSG_ERROR, request_id, (const uint8_t *)err, (uint32_t)strlen(err));
        return;
    }

    if (version == 0) {
        version = manifest_get_latest_version(g_server_store_dir, file_id);
        if (version == 0) {
            const char *err = "No manifest versions found for file";
            send_response(ctx->client_fd, MSG_ERROR, request_id, (const uint8_t *)err, (uint32_t)strlen(err));
            return;
        }
    }

    manifest_t *m = manifest_load_version(g_server_store_dir, file_id, version);
    if (!m) {
        const char *err = "Manifest version not found";
        send_response(ctx->client_fd, MSG_ERROR, request_id, (const uint8_t *)err, (uint32_t)strlen(err));
        return;
    }

    char *serialized = manifest_serialize(m);
    manifest_free(m);

    if (!serialized) {
        const char *err = "Failed serializing manifest";
        send_response(ctx->client_fd, MSG_ERROR, request_id, (const uint8_t *)err, (uint32_t)strlen(err));
        return;
    }

    uint32_t len = (uint32_t)strlen(serialized);
    send_response(ctx->client_fd, MSG_GET_MANIFEST, request_id, (const uint8_t *)serialized, len);
    printf("[SERVER] [%s:%d] Served GET_MANIFEST for '%s' v%u (%u bytes)\n",
           ctx->client_ip, ctx->client_port, file_id, version, len);
    free(serialized);
}

static void handle_get_chunk(client_ctx_t *ctx, uint32_t request_id, const uint8_t *payload, uint32_t payload_len) {
    if (payload_len < SHA256_HEX_LENGTH) {
        const char *err = "Invalid chunk hash length";
        send_response(ctx->client_fd, MSG_ERROR, request_id, (const uint8_t *)err, (uint32_t)strlen(err));
        return;
    }

    char chunk_hash[SHA256_HEX_LENGTH + 1];
    memcpy(chunk_hash, payload, SHA256_HEX_LENGTH);
    chunk_hash[SHA256_HEX_LENGTH] = '\0';

    uint8_t *chunk_data = NULL;
    size_t chunk_len = 0;

    int res = storage_read_chunk(chunk_hash, &chunk_data, &chunk_len);
    if (res != 0 || !chunk_data) {
        fprintf(stderr, "[SERVER] [%s:%d] Chunk %s requested but not found in store\n", ctx->client_ip, ctx->client_port, chunk_hash);
        const char *err = "Chunk not found";
        send_response(ctx->client_fd, MSG_ERROR, request_id, (const uint8_t *)err, (uint32_t)strlen(err));
        return;
    }

    send_response(ctx->client_fd, MSG_GET_CHUNK, request_id, chunk_data, (uint32_t)chunk_len);
    free(chunk_data);
}

static void handle_client(void *arg) {
    client_ctx_t *ctx = (client_ctx_t *)arg;
    socket_t cfd = ctx->client_fd;

    printf("[SERVER] [%s:%d] Client connected on worker thread\n", ctx->client_ip, ctx->client_port);

    uint8_t hdr_buf[FRAME_HEADER_LEN];

    while (1) {
        ssize_t n = recv_exact(cfd, hdr_buf, FRAME_HEADER_LEN);
        if (n == 0) {
            printf("[SERVER] [%s:%d] Client closed connection normally\n", ctx->client_ip, ctx->client_port);
            break;
        }
        if (n < 0) {
            fprintf(stderr, "[SERVER] [%s:%d] Socket disconnected or read error\n", ctx->client_ip, ctx->client_port);
            break;
        }

        frame_header_t hdr;
        int parse_res = deserialize_header(hdr_buf, &hdr);
        if (parse_res != 0) {
            fprintf(stderr, "[SERVER] [%s:%d] Malformed header received (%d). Aborting session.\n", ctx->client_ip, ctx->client_port, parse_res);
            const char *err_msg = "Malformed frame header";
            send_response(cfd, MSG_ERROR, 0, (const uint8_t *)err_msg, (uint32_t)strlen(err_msg));
            break;
        }

        uint8_t *payload = NULL;
        if (hdr.payload_len > 0) {
            payload = (uint8_t *)malloc(hdr.payload_len + 1);
            if (!payload) {
                const char *err_msg = "Server Out of Memory";
                send_response(cfd, MSG_ERROR, hdr.request_id, (const uint8_t *)err_msg, (uint32_t)strlen(err_msg));
                break;
            }

            ssize_t payload_bytes = recv_exact(cfd, payload, hdr.payload_len);
            if (payload_bytes <= 0) {
                free(payload);
                break;
            }
            payload[hdr.payload_len] = '\0';

            uint32_t calculated_crc = crc32_compute(payload, hdr.payload_len);
            if (calculated_crc != hdr.crc32) {
                fprintf(stderr, "[SERVER] [%s:%d] CRC32 Mismatch! Header: 0x%08X, Computed: 0x%08X\n",
                        ctx->client_ip, ctx->client_port, hdr.crc32, calculated_crc);
                const char *err_msg = "CRC32 checksum validation failed";
                send_response(cfd, MSG_ERROR, hdr.request_id, (const uint8_t *)err_msg, (uint32_t)strlen(err_msg));
                free(payload);
                break;
            }
        }

        /* Message Dispatch Table */
        switch (hdr.type) {
            case MSG_HELLO: {
                printf("[SERVER] [%s:%d] Processing HELLO handshake\n", ctx->client_ip, ctx->client_port);
                const char *resp = "{\"status\":\"OK\",\"server\":\"DistributedBackup-POSIX-1.0\",\"protocol_version\":1}";
                send_response(cfd, MSG_HELLO_RESP, hdr.request_id, (const uint8_t *)resp, (uint32_t)strlen(resp));
                break;
            }

            case MSG_BEGIN_UPLOAD:
                handle_begin_upload(ctx, hdr.request_id, payload, hdr.payload_len);
                break;

            case MSG_PUT_CHUNK:
                handle_put_chunk(ctx, hdr.request_id, payload, hdr.payload_len);
                break;

            case MSG_COMMIT_UPLOAD:
                handle_commit_upload(ctx, hdr.request_id, payload, hdr.payload_len);
                break;

            case MSG_GET_MANIFEST:
                handle_get_manifest(ctx, hdr.request_id, payload, hdr.payload_len);
                break;

            case MSG_GET_CHUNK:
                handle_get_chunk(ctx, hdr.request_id, payload, hdr.payload_len);
                break;

            default: {
                printf("[SERVER] [%s:%d] Generic ACK for type 0x%02X\n", ctx->client_ip, ctx->client_port, hdr.type);
                const char *ack_msg = "{\"status\":\"ACK\"}";
                send_response(cfd, MSG_ACK, hdr.request_id, (const uint8_t *)ack_msg, (uint32_t)strlen(ack_msg));
                break;
            }
        }

        if (payload) {
            free(payload);
        }
    }

    if (ctx->pending_manifest) {
        manifest_free(ctx->pending_manifest);
        ctx->pending_manifest = NULL;
    }

    net_close(cfd);
    free(ctx);
}

int main(int argc, char *argv[]) {
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    int port = DEFAULT_PORT;
    if (argc > 1) {
        port = atoi(argv[1]);
        if (port <= 0 || port > 65535) {
            fprintf(stderr, "Usage: %s [port] [store_dir]\n", argv[0]);
            return 1;
        }
    }
    if (argc > 2) {
        strncpy(g_server_store_dir, argv[2], sizeof(g_server_store_dir) - 1);
        g_server_store_dir[sizeof(g_server_store_dir) - 1] = '\0';
    }

    printf("========================================================\n");
    printf("  Distributed Backup System - C POSIX Backend Server   \n");
    printf("  Phase 3: The Sync Flow & Incremental Upload Engine   \n");
    printf("========================================================\n");

    if (net_init() != 0) {
        fprintf(stderr, "[SERVER] Network initialization failed\n");
        return 1;
    }

    crc32_init();

    if (storage_init(g_server_store_dir) != 0) {
        fprintf(stderr, "[SERVER] Storage engine initialization failed\n");
        net_cleanup();
        return 1;
    }

#ifndef _WIN32
    struct sigaction sa;
    sa.sa_handler = handle_signal;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
#endif

    threadpool_t *pool = threadpool_create(WORKER_THREADS, TASK_QUEUE_CAPACITY);
    if (!pool) {
        fprintf(stderr, "[SERVER] Failed to initialize thread pool\n");
        net_cleanup();
        return 1;
    }
    printf("[SERVER] Thread pool initialized (%d threads, %d queue capacity)\n",
           WORKER_THREADS, TASK_QUEUE_CAPACITY);

    listen_fd = net_listen("0.0.0.0", port, 128);
    if (listen_fd == INVALID_SOCKET_FD) {
        fprintf(stderr, "[SERVER] Could not listen on port %d\n", port);
        threadpool_destroy(pool, 0);
        net_cleanup();
        return 1;
    }

    printf("[SERVER] Server listening on 0.0.0.0:%d\n", port);
    printf("[SERVER] Using storage directory: %s\n", g_server_store_dir);
    printf("[SERVER] Ready for incremental chunk uploads...\n");

    while (server_running) {
        struct sockaddr_in client_addr;
#ifdef _WIN32
        int addr_len = sizeof(client_addr);
#else
        socklen_t addr_len = sizeof(client_addr);
#endif
        socket_t cfd = accept(listen_fd, (struct sockaddr *)&client_addr, &addr_len);
        if (cfd == INVALID_SOCKET_FD) {
            if (!server_running) break;
            perror("[SERVER] accept failed");
            continue;
        }

        client_ctx_t *ctx = (client_ctx_t *)malloc(sizeof(client_ctx_t));
        if (!ctx) {
            net_close(cfd);
            continue;
        }

        ctx->client_fd = cfd;
        ctx->pending_manifest = NULL;
        inet_ntop(AF_INET, &(client_addr.sin_addr), ctx->client_ip, sizeof(ctx->client_ip));
        ctx->client_port = ntohs(client_addr.sin_port);

        if (threadpool_add(pool, handle_client, ctx) != 0) {
            fprintf(stderr, "[SERVER] Thread pool saturated; dropping connection\n");
            net_close(cfd);
            free(ctx);
        }
    }

    printf("[SERVER] Gracefully stopping...\n");
    if (listen_fd != INVALID_SOCKET_FD) {
        net_close(listen_fd);
        listen_fd = INVALID_SOCKET_FD;
    }
    threadpool_destroy(pool, 1);
    net_cleanup();
    printf("[SERVER] Shutdown complete.\n");

    return 0;
}
