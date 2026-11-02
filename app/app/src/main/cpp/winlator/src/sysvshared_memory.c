#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <string.h>
#include <fcntl.h>
#include <stdbool.h>
#include <pthread.h>
#include <sys/ipc.h>
#include <sys/syscall.h>
#include <jni.h>
#include <android/log.h>
#include <android/sharedmem.h>
#include <sys/ioctl.h>
#include <stdint.h>

#define __u32 uint32_t
#include <linux/ashmem.h>

int ashmemCreateRegion(const char* name, int64_t size) {
#if __ANDROID_API__ >= 26
    int fd = ASharedMemory_create(name, size);
    if (fd < 0) return -1;
    return fd;
#else
    int fd = open("/dev/ashmem", O_RDWR);
    if (fd < 0) return -1;

    char nameBuffer[ASHMEM_NAME_LEN] = {0};
    strncpy(nameBuffer, name, sizeof(nameBuffer));
    nameBuffer[sizeof(nameBuffer) - 1] = 0;

    int ret = ioctl(fd, ASHMEM_SET_NAME, nameBuffer);
    if (ret < 0) goto error;

    ret = ioctl(fd, ASHMEM_SET_SIZE, size);
    if (ret < 0) goto error;

    return fd;
error:
    close(fd);
    return -1;
#endif
}

static int memfd_create(const char *name, unsigned int flags) {
#ifdef __NR_memfd_create
    return syscall(__NR_memfd_create, name, flags);
#else
    return -1;
#endif
}

int createMemoryFd(const char* name, int64_t size) {
    int fd = memfd_create(name, MFD_ALLOW_SEALING);
    if (fd < 0) return -1;

    int res = ftruncate(fd, size);
    if (res < 0) {
        close(fd);
        return -1;
    }

    return fd;
}

JNIEXPORT jint JNICALL
Java_com_factorio_sysvshm_SysVSharedMemory_ashmemCreateRegion(JNIEnv *env, jobject obj, jint index,
                                                              jlong size) {
    char name[32];
    sprintf(name, "sysvshm-%d", index);
    return ashmemCreateRegion(name, size);
}

JNIEXPORT jobject JNICALL
Java_com_factorio_sysvshm_SysVSharedMemory_mapSHMSegment(JNIEnv *env, jobject obj, jint fd, jlong size, jint offset, jboolean readonly) {
    char *data = mmap(NULL, size, readonly ? PROT_READ : PROT_WRITE | PROT_READ, MAP_SHARED, fd, offset);
    if (data == MAP_FAILED) return NULL;
    return (*env)->NewDirectByteBuffer(env, data, size);
}

JNIEXPORT void JNICALL
Java_com_factorio_sysvshm_SysVSharedMemory_unmapSHMSegment(JNIEnv *env, jobject obj, jobject data,
                                                           jlong size) {
    char *dataAddr = (*env)->GetDirectBufferAddress(env, data);
    munmap(dataAddr, size);
}

JNIEXPORT jint JNICALL
Java_com_factorio_sysvshm_SysVSharedMemory_createMemoryFd(JNIEnv *env, jclass obj, jstring name,
                                                          jint size) {
    const char *namePtr = (*env)->GetStringUTFChars(env, name, 0);

    int fd = createMemoryFd(namePtr, size);
    (*env)->ReleaseStringUTFChars(env, name, namePtr);
    if (fd < 0) return -1;

    return fd;
}
/* dma-buf CPU access bracket (DMA_BUF_IOCTL_SYNC): invalidates the CPU cache before the CPU reads memory that the GPU wrote.
   Defined locally so the code builds with any NDK header level. */
struct fio_dma_buf_sync { uint64_t flags; };
#define FIO_DMA_BUF_SYNC_READ  (1 << 0)
#define FIO_DMA_BUF_SYNC_START (0 << 2)
#define FIO_DMA_BUF_SYNC_END   (1 << 2)
#define FIO_DMA_BUF_IOCTL_SYNC _IOW('b', 0, struct fio_dma_buf_sync)

JNIEXPORT jint JNICALL
Java_com_factorio_sysvshm_SysVSharedMemory_dmabufSync(JNIEnv *env, jclass obj, jint fd, jboolean start) {
    struct fio_dma_buf_sync sync;
    sync.flags = FIO_DMA_BUF_SYNC_READ | (start ? FIO_DMA_BUF_SYNC_START : FIO_DMA_BUF_SYNC_END);
    return ioctl(fd, FIO_DMA_BUF_IOCTL_SYNC, &sync);
}

/* xshmfence (libxshmfence, futex variant): one int in shared memory, 0 = not triggered, 1 = triggered,
   -1 = triggered not yet and somebody sleeps in FUTEX_WAIT on it. The client creates it (DRI3 FenceFromFD) and
   waits on it before it reuses a presented buffer; the X server triggers it when it no longer reads the buffer. */
#include <linux/futex.h>
#include <limits.h>

JNIEXPORT void JNICALL
Java_com_factorio_sysvshm_SysVSharedMemory_xshmfenceTrigger(JNIEnv *env, jclass obj, jobject data) {
    int32_t *fence = data ? (*env)->GetDirectBufferAddress(env, data) : NULL;
    if (!fence) return;
    if (__sync_val_compare_and_swap(fence, 0, 1) == -1) {
        if (__sync_val_compare_and_swap(fence, -1, 1) == -1) {
            syscall(SYS_futex, fence, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
        }
    }
}
