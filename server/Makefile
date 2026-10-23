CC ?= gcc
CFLAGS ?= -Wall -Wextra -O2 -Iinclude -pthread
LDFLAGS ?= -pthread

ifeq ($(OS),Windows_NT)
    LIBS = -lws2_32 -lpthread
    TARGET = server.exe
    TEST_TARGET = test_storage.exe
    RM = del /Q /F
else
    LIBS = -lpthread
    TARGET = server
    TEST_TARGET = test_storage
    RM = rm -f
endif

SRCS = src/crc32.c src/protocol.c src/net.c src/threadpool.c src/hash.c src/storage.c src/manifest.c src/server.c
OBJS = $(SRCS:.c=.o)

TEST_SRCS = src/crc32.c src/hash.c src/storage.c src/manifest.c src/test_storage.c
TEST_OBJS = $(TEST_SRCS:.c=.o)

all: $(TARGET) $(TEST_TARGET)

$(TARGET): $(OBJS)
	$(CC) $(CFLAGS) -o $@ $(OBJS) $(LDFLAGS) $(LIBS)

$(TEST_TARGET): $(TEST_OBJS)
	$(CC) $(CFLAGS) -o $@ $(TEST_OBJS) $(LDFLAGS) $(LIBS)

%.o: %.c
	$(CC) $(CFLAGS) -c $< -o $@

test: $(TEST_TARGET)
	./$(TEST_TARGET)

clean:
	$(RM) $(OBJS) $(TEST_OBJS) $(TARGET) $(TEST_TARGET)

.PHONY: all clean test
