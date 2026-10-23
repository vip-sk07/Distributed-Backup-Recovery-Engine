#include "threadpool.h"
#include <stdlib.h>
#include <stdio.h>

typedef struct {
    threadpool_task_fn function;
    void *argument;
} threadpool_task_t;

struct threadpool {
    pthread_mutex_t lock;
    pthread_cond_t notify_not_empty;
    pthread_cond_t notify_not_full;
    pthread_t *threads;
    threadpool_task_t *queue;
    int thread_count;
    int queue_capacity;
    int head;
    int tail;
    int count;
    int shutdown; /* 0: active, 1: graceful shutdown, 2: immediate shutdown */
};

static void *threadpool_worker(void *arg) {
    threadpool_t *pool = (threadpool_t *)arg;

    while (1) {
        pthread_mutex_lock(&(pool->lock));

        while ((pool->count == 0) && (pool->shutdown == 0)) {
            pthread_cond_wait(&(pool->notify_not_empty), &(pool->lock));
        }

        if ((pool->shutdown == 2) || (pool->shutdown == 1 && pool->count == 0)) {
            pthread_mutex_unlock(&(pool->lock));
            pthread_exit(NULL);
        }

        threadpool_task_t task = pool->queue[pool->head];
        pool->head = (pool->head + 1) % pool->queue_capacity;
        pool->count--;

        pthread_cond_signal(&(pool->notify_not_full));
        pthread_mutex_unlock(&(pool->lock));

        if (task.function != NULL) {
            (*(task.function))(task.argument);
        }
    }
    return NULL;
}

threadpool_t *threadpool_create(int thread_count, int queue_capacity) {
    if (thread_count <= 0 || queue_capacity <= 0) {
        return NULL;
    }

    threadpool_t *pool = (threadpool_t *)malloc(sizeof(threadpool_t));
    if (!pool) return NULL;

    pool->thread_count = thread_count;
    pool->queue_capacity = queue_capacity;
    pool->head = 0;
    pool->tail = 0;
    pool->count = 0;
    pool->shutdown = 0;

    pool->threads = (pthread_t *)malloc(sizeof(pthread_t) * thread_count);
    pool->queue = (threadpool_task_t *)malloc(sizeof(threadpool_task_t) * queue_capacity);

    if (!pool->threads || !pool->queue) {
        if (pool->threads) free(pool->threads);
        if (pool->queue) free(pool->queue);
        free(pool);
        return NULL;
    }

    if (pthread_mutex_init(&(pool->lock), NULL) != 0 ||
        pthread_cond_init(&(pool->notify_not_empty), NULL) != 0 ||
        pthread_cond_init(&(pool->notify_not_full), NULL) != 0) {
        free(pool->threads);
        free(pool->queue);
        free(pool);
        return NULL;
    }

    for (int i = 0; i < thread_count; i++) {
        if (pthread_create(&(pool->threads[i]), NULL, threadpool_worker, (void *)pool) != 0) {
            threadpool_destroy(pool, 0);
            return NULL;
        }
    }

    return pool;
}

int threadpool_add(threadpool_t *pool, threadpool_task_fn fn, void *arg) {
    if (!pool || !fn) return -1;

    pthread_mutex_lock(&(pool->lock));

    while ((pool->count == pool->queue_capacity) && (pool->shutdown == 0)) {
        pthread_cond_wait(&(pool->notify_not_full), &(pool->lock));
    }

    if (pool->shutdown != 0) {
        pthread_mutex_unlock(&(pool->lock));
        return -1;
    }

    pool->queue[pool->tail].function = fn;
    pool->queue[pool->tail].argument = arg;
    pool->tail = (pool->tail + 1) % pool->queue_capacity;
    pool->count++;

    pthread_cond_signal(&(pool->notify_not_empty));
    pthread_mutex_unlock(&(pool->lock));

    return 0;
}

int threadpool_destroy(threadpool_t *pool, int graceful) {
    if (!pool) return -1;

    pthread_mutex_lock(&(pool->lock));
    if (pool->shutdown != 0) {
        pthread_mutex_unlock(&(pool->lock));
        return -1;
    }

    pool->shutdown = (graceful ? 1 : 2);
    pthread_cond_broadcast(&(pool->notify_not_empty));
    pthread_cond_broadcast(&(pool->notify_not_full));
    pthread_mutex_unlock(&(pool->lock));

    for (int i = 0; i < pool->thread_count; i++) {
        pthread_join(pool->threads[i], NULL);
    }

    pthread_mutex_destroy(&(pool->lock));
    pthread_cond_destroy(&(pool->notify_not_empty));
    pthread_cond_destroy(&(pool->notify_not_full));

    free(pool->threads);
    free(pool->queue);
    free(pool);

    return 0;
}
