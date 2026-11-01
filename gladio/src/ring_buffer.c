#include <unistd.h>
#include <stdio.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stddef.h>
#include <pthread.h>
#include <sys/mman.h>
#include <limits.h>
#include <time.h>
#include <threads.h>
#include <sys/syscall.h>
#include <linux/futex.h>

#include "ring_buffer.h"
#include "time_utils.h"
#include "events.h"

#define STRUCT_OFFSETS() \
    struct Offsets { \
        uint32_t head; \
        uint32_t tail; \
        uint32_t status; \
        void* buffer; \
    }

#ifdef __ANDROID__
#include <android/log.h>
#define debug_printf(...) __android_log_print(ANDROID_LOG_DEBUG, "System.out", __VA_ARGS__)
#else
#define debug_printf(...) fprintf(stderr, __VA_ARGS__)
#endif

// Spin a little (cheap when the peer answers fast), then sleep in FUTEX_WAIT.
#define RING_SPIN_ITER_WITH_WAKE 64
// Upper bound for one sleep when the peer wakes us (EXIT is also checked this often).
#define RING_WAIT_MAX_US 20000

static inline void futexWait(atomic_uint* addr, uint32_t expected, uint32_t timeoutUs) {
    struct timespec timeout = {timeoutUs / 1000000, (long)(timeoutUs % 1000000) * 1000};
    // no FUTEX_PRIVATE_FLAG: the word is shared between two processes (ashmem / memfd mapping)
    syscall(SYS_futex, (uint32_t*)addr, FUTEX_WAIT, expected, &timeout, NULL, 0);
}

static inline void futexWake(atomic_uint* addr) {
    syscall(SYS_futex, (uint32_t*)addr, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
}

// Called after head / tail was published: wake the peer if it sleeps on that word.
static inline void notifyPeer(RingBuffer* ring, atomic_uint* word, uint32_t waitingBit) {
    atomic_thread_fence(memory_order_seq_cst); // the store of head / tail must be visible before the status is read
    if (atomic_load_explicit(ring->status, memory_order_relaxed) & waitingBit) {
        atomic_fetch_and_explicit(ring->status, ~waitingBit, memory_order_seq_cst);
        futexWake(word);
    }
}

void RingBuffer_setHead(RingBuffer* ring, uint32_t head) {
    atomic_store_explicit(ring->head, head, memory_order_release);
    if (!ring->announcedReader) {
        ring->announcedReader = 1;
        atomic_fetch_or_explicit(ring->status, RING_STATUS_READER_WAKES, memory_order_seq_cst);
    }
    notifyPeer(ring, ring->head, RING_STATUS_WRITER_WAITING);
}

uint32_t RingBuffer_getHead(RingBuffer* ring) {
    return atomic_load_explicit(ring->head, memory_order_acquire);
}

void RingBuffer_setTail(RingBuffer* ring, uint32_t tail) {
    atomic_store_explicit(ring->tail, tail, memory_order_release);
    if (!ring->announcedWriter) {
        ring->announcedWriter = 1;
        atomic_fetch_or_explicit(ring->status, RING_STATUS_WRITER_WAKES, memory_order_seq_cst);
    }
    notifyPeer(ring, ring->tail, RING_STATUS_READER_WAITING);
}

uint32_t RingBuffer_getTail(RingBuffer* ring) {
    return atomic_load_explicit(ring->tail, memory_order_acquire);
}

void RingBuffer_setStatus(RingBuffer* ring, uint32_t status) {
    atomic_fetch_or_explicit(ring->status, status, memory_order_seq_cst);
}

void RingBuffer_unsetStatus(RingBuffer* ring, uint32_t status) {
    atomic_fetch_and_explicit(ring->status, ~status, memory_order_seq_cst);
}

bool RingBuffer_hasStatus(RingBuffer* ring, uint32_t status) {
    return (atomic_load_explicit(ring->status, memory_order_seq_cst) & status);
}

RingBuffer* RingBuffer_create(int shmFd, uint32_t bufferSize) {
    STRUCT_OFFSETS();

    int shmSize = RingBuffer_getSHMemSize(bufferSize);
    void* sharedData = mmap(NULL, shmSize, PROT_READ | PROT_WRITE, MAP_SHARED, shmFd, 0);
    if (sharedData == MAP_FAILED) return NULL;
    memset(sharedData, 0, shmSize);

    RingBuffer* ring = calloc(1, sizeof(RingBuffer));
    ring->sharedData = sharedData;
    ring->head = sharedData + offsetof(struct Offsets, head);
    ring->tail = sharedData + offsetof(struct Offsets, tail);
    ring->status = sharedData + offsetof(struct Offsets, status);
    ring->buffer = sharedData + offsetof(struct Offsets, buffer);
    ring->bufferSize = bufferSize;

    RingBuffer_setStatus(ring, RING_STATUS_IDLE);
    return ring;
}

uint32_t RingBuffer_size(RingBuffer* ring) {
    return RingBuffer_getTail(ring) - RingBuffer_getHead(ring);
}

uint32_t RingBuffer_freeSpace(RingBuffer* ring) {
    return ring->bufferSize - RingBuffer_size(ring);
}

bool RingBuffer_read(RingBuffer* ring, void* data, uint32_t size) {
    if (size > ring->bufferSize) {
        debug_printf("ring: buffer overflow on read (%d/%d)\n", size, ring->bufferSize);
        return false;
    }

    if (!RingBuffer_waitForRead(ring, size)) return false;
    uint32_t head = RingBuffer_getHead(ring);
    uint32_t offset = head & (ring->bufferSize - 1);

    if ((offset + size) <= ring->bufferSize) {
        memcpy(data, ring->buffer + offset, size);
    }
    else {
        uint32_t start = ring->bufferSize - offset;
        memcpy(data, ring->buffer + offset, start);
        memcpy(data + start, ring->buffer, size - start);
    }

    RingBuffer_setHead(ring, head + size);
    return true;
}

bool RingBuffer_write(RingBuffer* ring, const void* data, uint32_t size) {
    if (size > ring->bufferSize) {
        debug_printf("ring: buffer overflow on write (%d/%d)\n", size, ring->bufferSize);
        return false;
    }

    if (!RingBuffer_waitForWrite(ring, size)) return false;
    uint32_t tail = RingBuffer_getTail(ring);
    uint32_t offset = tail & (ring->bufferSize - 1);

    if ((offset + size) <= ring->bufferSize) {
        memcpy(ring->buffer + offset, data, size);
    }
    else {
        uint32_t start = ring->bufferSize - offset;
        memcpy(ring->buffer + offset, data, start);
        memcpy(ring->buffer, data + start, size - start);
    }

    RingBuffer_setTail(ring, tail + size);
    return true;
}

uint32_t RingBuffer_getSHMemSize(uint32_t bufferSize) {
    STRUCT_OFFSETS();

    return bufferSize + offsetof(struct Offsets, buffer);
}

void RingBuffer_free(RingBuffer* ring) {
    if (!ring) return;

    if (ring->sharedData) {
        RingBuffer_setStatus(ring, RING_STATUS_EXIT);
        futexWake(ring->head);
        futexWake(ring->tail);
        munmap(ring->sharedData, RingBuffer_getSHMemSize(ring->bufferSize));
        ring->sharedData = NULL;
    }

    free(ring->batch);
    free(ring);
}

// Waits until `enough` holds. `word` is the counter the peer moves (tail for the reader, head for the writer).
static bool waitFor(RingBuffer* ring, uint32_t size, bool forRead) {
    atomic_uint* word = forRead ? ring->tail : ring->head;
    uint32_t waitingBit = forRead ? RING_STATUS_READER_WAITING : RING_STATUS_WRITER_WAITING;
    uint32_t peerWakesBit = forRead ? RING_STATUS_WRITER_WAKES : RING_STATUS_READER_WAKES;
    uint32_t iter = 0;
    uint32_t sleepUs = BUSY_WAIT_SLEEP_US;

    while (forRead ? RingBuffer_size(ring) < size : RingBuffer_freeSpace(ring) < size) {
        if (RingBuffer_hasStatus(ring, RING_STATUS_EXIT)) return false;

        bool peerWakes = RingBuffer_hasStatus(ring, peerWakesBit);
        if (++iter < (peerWakes ? RING_SPIN_ITER_WITH_WAKE : BUSY_WAIT_MAX_ITER)) {
            thrd_yield();
            continue;
        }

        uint32_t observed = atomic_load_explicit(word, memory_order_acquire);
        atomic_fetch_or_explicit(ring->status, waitingBit, memory_order_seq_cst);
        atomic_thread_fence(memory_order_seq_cst);
        // FUTEX_WAIT returns at once if the peer moved `word` after `observed` was read (no lost wake-up)
        futexWait(word, observed, sleepUs);

        // A peer without wake-ups keeps the old 100 us polling; with wake-ups the sleep may grow (idle = no CPU use).
        if (peerWakes && sleepUs < RING_WAIT_MAX_US) {
            sleepUs *= 2;
            if (sleepUs > RING_WAIT_MAX_US) sleepUs = RING_WAIT_MAX_US;
        }
    }
    return true;
}

bool RingBuffer_waitForRead(RingBuffer* ring, uint32_t size) {
    return waitFor(ring, size, true);
}

bool RingBuffer_waitForWrite(RingBuffer* ring, uint32_t size) {
    return waitFor(ring, size, false);
}
