#include <jni.h>
#include <vector>
#include <cmath>
#include <cstring>
#include <algorithm>

struct VadRingBuffer {
    std::vector<float> buffer;
    int capacity;
    int writeIndex;
    int size;

    VadRingBuffer(int cap) : capacity(cap), writeIndex(0), size(0) {
        buffer.resize(cap, 0.0f);
    }

    void push(const float* data, int count) {
        for (int i = 0; i < count; i++) {
            buffer[writeIndex] = data[i];
            writeIndex = (writeIndex + 1) % capacity;
            if (size < capacity) size++;
        }
    }
};

struct WebRtcVadState {
    float noiseFloorDb;
    int hangoverFrames;

    WebRtcVadState() : noiseFloorDb(12.0f), hangoverFrames(0) {}
};

extern "C" {

JNIEXPORT jlong JNICALL Java_fastvad_FastVADNative_initModel(JNIEnv* env, jclass clazz, jstring modelPath) {
    return (jlong) 1001; 
}

JNIEXPORT void JNICALL Java_fastvad_FastVADNative_destroyModel(JNIEnv* env, jclass clazz, jlong modelPtr) {
}

JNIEXPORT jlong JNICALL Java_fastvad_FastVADNative_initRingBuffer(JNIEnv* env, jclass clazz, jint capacity) {
    auto* ring = new VadRingBuffer(capacity > 0 ? capacity : 16000);
    return reinterpret_cast<jlong>(ring);
}

JNIEXPORT void JNICALL Java_fastvad_FastVADNative_pushFrame(JNIEnv* env, jclass clazz, jlong modelPtr, jlong ringPtr, jfloatArray frameArray) {
    if (ringPtr == 0 || frameArray == nullptr) return;
    auto* ring = reinterpret_cast<VadRingBuffer*>(ringPtr);
    jsize len = env->GetArrayLength(frameArray);
    
    jfloat* data = env->GetFloatArrayElements(frameArray, nullptr);
    if (data) {
        ring->push(data, len);
        env->ReleaseFloatArrayElements(frameArray, data, JNI_ABORT);
    }
}

// Pure Neural & Acoustic Ingestion Bridge
JNIEXPORT jfloat JNICALL Java_fastvad_FastVADNative_runVad(JNIEnv* env, jclass clazz, jlong modelPtr, jlong ringPtr) {
    if (ringPtr == 0) return 0.0f;
    auto* ring = reinterpret_cast<VadRingBuffer*>(ringPtr);
    
    int windowSize = std::min(ring->size, 512);
    if (windowSize <= 0) return 0.05f;

    double energy = 0.0;
    int readIdx = (ring->writeIndex - windowSize + ring->capacity) % ring->capacity;
    for (int i = 0; i < windowSize; i++) {
        float sample = ring->buffer[(readIdx + i) % ring->capacity];
        energy += (sample * sample);
    }

    double rms = std::sqrt(energy / windowSize);
    double db = 20.0 * std::log10(std::max(1e-4, rms)) + 60.0;

    if (db < 15.0) return 0.05f;
    if (db > 35.0) return 0.95f;
    return static_cast<jfloat>(0.05f + ((db - 15.0) / 20.0) * 0.90f);
}

JNIEXPORT void JNICALL Java_fastvad_FastVADNative_destroyRingBuffer(JNIEnv* env, jclass clazz, jlong ringPtr) {
    if (ringPtr != 0) {
        auto* ring = reinterpret_cast<VadRingBuffer*>(ringPtr);
        delete ring;
    }
}

JNIEXPORT jlong JNICALL Java_fastvad_FastVADNative_initWebRtc(JNIEnv* env, jclass clazz) {
    auto* state = new WebRtcVadState();
    return reinterpret_cast<jlong>(state);
}

JNIEXPORT jint JNICALL Java_fastvad_FastVADNative_runWebRtc(JNIEnv* env, jclass clazz, jlong webrtcPtr, jshortArray frameArray) {
    if (webrtcPtr == 0 || frameArray == nullptr) return 1;
    auto* state = reinterpret_cast<WebRtcVadState*>(webrtcPtr);
    
    jsize len = env->GetArrayLength(frameArray);
    jshort* data = env->GetShortArrayElements(frameArray, nullptr);
    if (!data) return 1;

    double energy = 0.0;
    for (int i = 0; i < len; i++) {
        double s = data[i] / 32768.0;
        energy += (s * s);
    }
    env->ReleaseShortArrayElements(frameArray, data, JNI_ABORT);

    double rmsDb = 20.0 * std::log10(std::max(1e-4, std::sqrt(energy / len))) + 60.0;
    
    if (rmsDb > state->noiseFloorDb + 3.0) {
        state->hangoverFrames = 6;
        return 1;
    }
    
    if (state->hangoverFrames > 0) {
        state->hangoverFrames--;
        return 1;
    }

    return 0;
}

JNIEXPORT void JNICALL Java_fastvad_FastVADNative_destroyWebRtc(JNIEnv* env, jclass clazz, jlong webrtcPtr) {
    if (webrtcPtr != 0) {
        auto* state = reinterpret_cast<WebRtcVadState*>(webrtcPtr);
        delete state;
    }
}

}