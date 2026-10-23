#ifndef NET_H
#define NET_H

#include <stddef.h>
#include <stdint.h>

#ifdef _WIN32
    #include <winsock2.h>
    #include <ws2tcpip.h>
    typedef SOCKET socket_t;
    #define INVALID_SOCKET_FD INVALID_SOCKET
    #define SOCKET_CLOSE(s) closesocket(s)
#else
    #include <sys/types.h>
    #include <sys/socket.h>
    #include <netinet/in.h>
    #include <netinet/tcp.h>
    #include <arpa/inet.h>
    #include <unistd.h>
    #include <errno.h>
    typedef int socket_t;
    #define INVALID_SOCKET_FD (-1)
    #define SOCKET_CLOSE(s) close(s)
#endif

/* Initialize networking subsystem (WSA on Windows, no-op on POSIX) */
int net_init(void);

/* Cleanup networking subsystem */
void net_cleanup(void);

/* Close socket */
void net_close(socket_t fd);

/* Create and bind a TCP listening socket */
socket_t net_listen(const char *bind_addr, int port, int backlog);

/* Read exactly len bytes from socket fd.
 * Returns:
 *   len  : success
 *   0    : connection closed gracefully by peer
 *  -1    : read error / disconnected abnormally
 */
ssize_t recv_exact(socket_t fd, void *buf, size_t len);

/* Write all len bytes to socket fd.
 * Returns:
 *   0  : success
 *  -1  : send error
 */
int send_all(socket_t fd, const void *buf, size_t len);

#endif /* NET_H */
