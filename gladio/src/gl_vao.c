#include <sys/mman.h>
#include "gl_context.h"
#include "gl_vao.h"
#include "gl_client_state.h"

void* GLVertexAttrib_resolve(GLClientState* clientState, GLVertexAttrib* attrib, int64_t* limit) {
    if (attrib->bufferId) {
        // glBufferData / glBufferStorage re-map the buffer when it grows: the address stored at glVertexAttribPointer time is stale then
        GLBuffer* buffer = SparseArray_get(clientState->buffers, attrib->bufferId);
        if (!buffer || !buffer->mappedData) {
            *limit = 0;
            return NULL;
        }
        *limit = MAX(0, (int64_t)buffer->size - attrib->bufferOffset);
        return (uint8_t*)buffer->mappedData + attrib->bufferOffset;
    }
    *limit = -1;
    return attrib->pointer;
}

// A client array pointer comes straight from the game: if it is not mapped (stale pointer, wrong first/count, attribute the game
// has already disabled) the memcpy would kill the whole process. mincore() tells whether the pages are mapped without touching them.
// GLADIO_SAFE_ARRAYS=0 turns the check off.
static int safeArrays = -1;

bool gl_isReadable(const void* address, int64_t size) {
    uintptr_t start = (uintptr_t)address & ~(uintptr_t)4095;
    uintptr_t end = ((uintptr_t)address + size + 4095) & ~(uintptr_t)4095;
    if ((uintptr_t)address < 65536 || end < start) return false;
    unsigned char vec[256];
    while (start < end) {
        size_t length = end - start;
        if (length > sizeof(vec) * 4096) length = sizeof(vec) * 4096;
        if (mincore((void*)start, length, vec) != 0) return false;
        start += length;
    }
    return true;
}

/** Copies size bytes at base+offset, never reading past limit; the missing tail is zero so the stream layout stays the same. */
void GLVertexAttrib_putBytes(ArrayBuffer* out, const void* base, int64_t offset, int64_t size, int64_t limit) {
    if (size <= 0) return;
    int64_t available = size;
    if (!base) available = 0;
    else if (limit >= 0) available = MAX(0, MIN(size, limit - offset));
    if (offset < 0) available = 0;

    if (available > 0 && limit < 0) {
        if (safeArrays == -1) safeArrays = !(getenv("GLADIO_SAFE_ARRAYS") && atoi(getenv("GLADIO_SAFE_ARRAYS")) == 0);
        if (safeArrays && !gl_isReadable((const uint8_t*)base + offset, available)) available = 0;
    }

    if (available > 0) ArrayBuffer_putBytes(out, (const uint8_t*)base + offset, (int)available);
    if (available < size) ArrayBuffer_putBytes(out, NULL, (int)(size - available));
}

void GLVertexArrayObject_setAttribState(GLClientState* clientState, GLuint index, uint8_t state, bool clear) {
    switch (index) {
        case GL_VERTEX_ARRAY:
            index = POSITION_ARRAY_INDEX;
            if (state) state = VERTEX_ATTRIB_LEGACY_ENABLED;
            break;
        case GL_COLOR_ARRAY:
            index = COLOR_ARRAY_INDEX;
            if (state) state = VERTEX_ATTRIB_LEGACY_ENABLED;
            break;
        case GL_NORMAL_ARRAY:
            index = NORMAL_ARRAY_INDEX;
            if (state) state = VERTEX_ATTRIB_LEGACY_ENABLED;
            break;
        case GL_TEXTURE_COORD_ARRAY:
            index = TEXCOORD_ARRAY_INDEX;
            if (state) state = VERTEX_ATTRIB_LEGACY_ENABLED;
            break;
        case GL_GENERIC_VERTEX_ARRAY:
            index = GENERIC_VERTEX_ARRAY_INDEX;
            if (state) state = VERTEX_ATTRIB_LEGACY_ENABLED;
            break;
    }

    if (index < VERTEX_ATTRIB_COUNT) {
        if (clear) {
            GLVertexAttrib* vertexAttrib = &clientState->vao->attribs[index];
#ifdef GL_SERVER
            vertexAttrib->normalized = false;
            vertexAttrib->type = 0;
            vertexAttrib->size = 0;
            vertexAttrib->boundArrayBuffer = 0;
#endif
            vertexAttrib->pointer = NULL;
            vertexAttrib->bufferId = 0;
            vertexAttrib->bufferOffset = 0;
            vertexAttrib->stride = 0;
        }
        clientState->vao->attribs[index].state = state;
        if (state) {
            clientState->vao->maxEnabledAttribs = MAX(index+1, clientState->vao->maxEnabledAttribs);
        }
        else if (clientState->vao->maxEnabledAttribs > 0 && index >= clientState->vao->maxEnabledAttribs-1) {
            clientState->vao->maxEnabledAttribs = 0;
            for (int i = 0; i < VERTEX_ATTRIB_COUNT; i++) if (clientState->vao->attribs[i].state) clientState->vao->maxEnabledAttribs = i+1;
        }
    }
}

void GLVertexArrayObject_setBound(GLClientState* clientState, GLuint id) {
    if (id > 0) {
        GLVertexArrayObject* vao = SparseArray_get(&clientState->vertexArrays, id);
        if (!vao) {
            vao = calloc(1, sizeof(GLVertexArrayObject));
            SparseArray_put(&clientState->vertexArrays, id, vao);
        }
        clientState->vao = vao;
    }
    else clientState->vao = &clientState->defaultVAO;
}

void GLVertexArrayObject_delete(GLClientState* clientState, GLuint id) {
    GLVertexArrayObject* vao;
    if (id > 0) {
        vao = SparseArray_get(&clientState->vertexArrays, id);
        if (vao == clientState->vao) clientState->vao = &clientState->defaultVAO;
    }
    else vao = &clientState->defaultVAO;
    if (!vao) return;

    if (vao->bgraBuffer > 0) {
        glDeleteBuffers(1, &vao->bgraBuffer);
        vao->bgraBuffer = 0;
    }

    if (id > 0) {
        MEMFREE(vao);
        SparseArray_remove(&clientState->vertexArrays, id);
    }
}
// Raw bytes after a texture upload request. The game's pointer can be bogus (e.g. a pixel unpack buffer offset the client lost track of):
// the server still expects exactly `size` bytes, so send zeros instead of crashing.
void gl_write_raw_checked(RingBuffer* ring, const void* data, int size, int requestCode) {
    if (size <= 0) return;
    if (data && gl_isReadable(data, size)) {
        gl_write_raw(ring, data, size);
        return;
    }
    static const char zeros[65536];
    for (int left = size; left > 0; ) {
        int chunk = left < (int)sizeof(zeros) ? left : (int)sizeof(zeros);
        if (!gl_write_raw(ring, zeros, chunk)) return;
        left -= chunk;
    }
}
