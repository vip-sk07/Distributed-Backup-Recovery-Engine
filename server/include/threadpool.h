#ifndef THREADPOOL_H
#define THREADPOOL_H

#include <pthread.h>
#include <stddef.h>

typedef void (*threadpool_task_fn)(void *arg);

typedef struct threadpool threadpool_t;

/* Create a threadpool with a fixed number of threads and bounded task queue capacity */
threadpool_t *threadpool_create(int thread_count, int queue_capacity);

/* Add a task to the threadpool queue. Blocks if queue is full.
 * Returns 0 on success, -1 on error/shutdown.
 */
int threadpool_add(threadpool_t *pool, threadpool_task_fn fn, void *arg);

/* Destroy the threadpool.
 * graceful = 1 waits for queued tasks to finish; graceful = 0 shuts down immediately.
 * Returns 0 on success.
 */
int threadpool_destroy(threadpool_t *pool, int graceful);

#endif /* THREADPOOL_H */
