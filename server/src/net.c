#include "net.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#ifdef _WIN32
    #include <windows.h>
#else
    #include <signal.h>
#endif

int net_init(void) {
#ifdef _WIN32
    WSADATA wsa;
    int res = WSAStartup(MAKEWORD(2, 2), &wsa);
    if (res != 0) {
        fprintf(stderr, "[NET] WSAStartup failed with error: %d\n", res);
        return -1;
    }
#else
    /* Ignore SIGPIPE on POSIX systems so broken sockets return -1 with EPIPE instead of terminating */
    signal(SIGPIPE, SIG_IGN);
#endif
    return 0;
}

void net_cleanup(void) {
#ifdef _WIN32
    WSACleanup();
#endif
}

void net_close(socket_t fd) {
    if (fd != INVALID_SOCKET_FD) {
        SOCKET_CLOSE(fd);
    }
}

socket_t net_listen(const char *bind_addr, int port, int backlog) {
    socket_t sfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sfd == INVALID_SOCKET_FD) {
        perror("[NET] socket creation failed");
        return INVALID_SOCKET_FD;
    }

    int opt = 1;
#ifdef _WIN32
    if (setsockopt(sfd, SOL_SOCKET, SO_REUSEADDR, (const char *)&opt, sizeof(opt)) < 0) {
#else
    if (setsockopt(sfd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt)) < 0) {
#endif
        perror("[NET] setsockopt(SO_REUSEADDR) failed");
        net_close(sfd);
        return INVALID_SOCKET_FD;
    }

    /* Disable Nagle's algorithm for low-latency frame dispatch */
    int flag = 1;
#ifdef _WIN32
    setsockopt(sfd, IPPROTO_TCP, TCP_NODELAY, (const char *)&flag, sizeof(flag));
#else
    setsockopt(sfd, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));
#endif

    struct sockaddr_in saddr;
    memset(&saddr, 0, sizeof(saddr));
    saddr.sin_family = AF_INET;
    saddr.sin_port = htons((uint16_t)port);

    if (bind_addr == NULL || strlen(bind_addr) == 0 || strcmp(bind_addr, "0.0.0.0") == 0) {
        saddr.sin_addr.s_addr = INADDR_ANY;
    } else {
        if (inet_pton(AF_INET, bind_addr, &saddr.sin_addr) <= 0) {
            fprintf(stderr, "[NET] Invalid address: %s\n", bind_addr);
            net_close(sfd);
            return INVALID_SOCKET_FD;
        }
    }

    if (bind(sfd, (struct sockaddr *)&saddr, sizeof(saddr)) < 0) {
        perror("[NET] bind failed");
        net_close(sfd);
        return INVALID_SOCKET_FD;
    }

    if (listen(sfd, backlog) < 0) {
        perror("[NET] listen failed");
        net_close(sfd);
        return INVALID_SOCKET_FD;
    }

    return sfd;
}

ssize_t recv_exact(socket_t fd, void *buf, size_t len) {
    if (len == 0) return 0;
    size_t total = 0;
    char *p = (char *)buf;

    while (total < len) {
#ifdef _WIN32
        int n = recv(fd, p + total, (int)(len - total), 0);
        if (n == 0) {
            /* Graceful EOF from peer */
            return total == 0 ? 0 : -1;
        }
        if (n < 0) {
            int err = WSAGetLastError();
            if (err == WSAEINTR) {
                continue;
            }
            return -1;
        }
#else
        ssize_t n = recv(fd, p + total, len - total, 0);
        if (n == 0) {
            /* Graceful EOF from peer */
            return total == 0 ? 0 : -1;
        }
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
#endif
        total += (size_t)n;
    }
    return (ssize_t)total;
}

int send_all(socket_t fd, const void *buf, size_t len) {
    size_t total = 0;
    const char *p = (const char *)buf;

    while (total < len) {
#ifdef _WIN32
        int n = send(fd, p + total, (int)(len - total), 0);
        if (n <= 0) {
            int err = WSAGetLastError();
            if (err == WSAEINTR) {
                continue;
            }
            return -1;
        }
#else
        ssize_t n = send(fd, p + total, len - total, 0);
        if (n <= 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
#endif
        total += (size_t)n;
    }
    return 0;
}
