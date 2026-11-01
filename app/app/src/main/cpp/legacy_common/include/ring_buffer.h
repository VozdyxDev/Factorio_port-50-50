#ifndef WINLATOR_RING_BUFFER_H
#define WINLATOR_RING_BUFFER_H

#include <stdatomic.h>
#include <stdint.h>

#include "events.h"

typedef struct RingBuffer {
    atomic_uint* head;
    atomic_uint* tail;
    atomic_uint* status;
    void* buffer;
    void* sharedData;
    uint32_t bufferSize;
    // local (not shared): this side has already announced that it wakes the peer (see RING_STATUS_*_WAKES)
    uint8_t announcedReader;
    uint8_t announcedWriter;
} RingBuffer;

#define RING_STATUS_IDLE (1u<<0)
#define RING_STATUS_EXIT (1u<<1)
#define RING_STATUS_WAIT (1u<<2)
// Futex based waiting (instead of usleep polling). The bits live in the shared status word:
//   *_WAITING - this side sleeps in FUTEX_WAIT on tail (reader) / head (writer) and wants a FUTEX_WAKE;
//   *_WAKES   - this side wakes the peer after every head / tail update, so the peer may sleep long.
// A peer that does not know the bits (e.g. the prebuilt Vortek client) never sets *_WAKES: the waiting side
// then keeps the old short 100 us sleeps, the behaviour is the same as before.
#define RING_STATUS_READER_WAITING (1u<<8)
#define RING_STATUS_WRITER_WAITING (1u<<9)
#define RING_STATUS_WRITER_WAKES   (1u<<10)
#define RING_STATUS_READER_WAKES   (1u<<11)

extern void RingBuffer_setHead(RingBuffer* ring, uint32_t head);
extern uint32_t RingBuffer_getHead(RingBuffer* ring);
extern void RingBuffer_setTail(RingBuffer* ring, uint32_t tail);
extern uint32_t RingBuffer_getTail(RingBuffer* ring);
extern void RingBuffer_setStatus(RingBuffer* ring, uint32_t status);
extern void RingBuffer_unsetStatus(RingBuffer* ring, uint32_t status);
extern bool RingBuffer_hasStatus(RingBuffer* ring, uint32_t status);
extern RingBuffer* RingBuffer_create(int shmFd, uint32_t bufferSize);
extern uint32_t RingBuffer_size(RingBuffer* ring);
extern uint32_t RingBuffer_freeSpace(RingBuffer* ring);
extern bool RingBuffer_read(RingBuffer* ring, void* data, uint32_t size);
extern bool RingBuffer_write(RingBuffer* ring, const void *data, uint32_t size);
extern uint32_t RingBuffer_getSHMemSize(uint32_t bufferSize);
extern void RingBuffer_free(RingBuffer* ring);
extern bool RingBuffer_waitForRead(RingBuffer* ring, uint32_t size);
extern bool RingBuffer_waitForWrite(RingBuffer* ring, uint32_t size);

#define RING_READ_BEGIN(ring, data, size) \
    uint32_t ringHead = 0; \
    void* ringData = NULL; \
    do { \
        if (size == 0 || !RingBuffer_waitForRead(ring, size)) break; \
        ringHead = RingBuffer_getHead(ring); \
        uint32_t ringOffset = ringHead & (ring->bufferSize - 1); \
        if ((ringOffset + size) <= ring->bufferSize) { \
            data = ring->buffer + ringOffset; \
        } \
        else { \
            data = malloc(size); \
            uint32_t start = ring->bufferSize - ringOffset; \
            memcpy(data, ring->buffer + ringOffset, start); \
            memcpy(data + start, ring->buffer, size - start);\
            ringData = data; \
        } \
        ringHead += size; \
    } \
    while (0)

#define RING_READ_END(ring) \
    do { \
        if (ringData) { \
            free(ringData); \
            ringData = NULL; \
        } \
        if (ringHead > 0) RingBuffer_setHead(ring, ringHead); \
    } \
    while (0)

#define RING_WRITE_BEGIN(ring, size) \
    uint32_t ringTail = 0; \
    void* ringData = NULL; \
    uint32_t ringDataSize = 0; \
    uint32_t ringOffset = 0; \
    do { \
        if (size == 0 || !RingBuffer_waitForWrite(ring, size)) break; \
        ringTail = RingBuffer_getTail(ring); \
        ringOffset = ringTail & (ring->bufferSize - 1); \
        if ((ringOffset + size) <= ring->bufferSize) { \
            ringData = ring->buffer + ringOffset; \
        } \
        else { \
            ringData = malloc(size); \
            ringDataSize = size; \
        } \
        ringTail += size; \
    } \
    while (0)

#define RING_WRITE_END(ring) \
    do { \
        if (ringDataSize > 0) {  \
            uint32_t ringStart = ring->bufferSize - ringOffset; \
            memcpy(ring->buffer + ringOffset, ringData, ringStart); \
            memcpy(ring->buffer, ringData + ringStart, ringDataSize - ringStart); \
            free(ringData); \
            ringData = NULL; \
        } \
        if (ringTail > 0) RingBuffer_setTail(ring, ringTail); \
    } \
    while (0)

#endif