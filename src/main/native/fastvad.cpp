#include "fastvad_jni.h"
#include <atomic>
#include <vector>
#include <cmath>
#include <cstring>
#include <complex>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// --- Zero-Allocation RingBuffer ---
struct FastRingBuffer {
    std::vector<float> data;
    std::atomic<int> writePos;
    int capacity;

    FastRingBuffer(int cap) : data(cap, 0.0f), writePos(0), capacity(cap) {}

    void push(const float* frame, int len) {
        int pos = writePos.load(std::memory_order_relaxed);
        for (int i = 0; i < len; ++i) {
            data[(pos + i) % capacity] = frame[i];
        }
        writePos.store((pos + len) % capacity, std::memory_order_release);
    }

    void readLatest(float* out, int len) {
        int pos = writePos.load(std::memory_order_acquire);
        int start = (pos - len + capacity) % capacity;
        for (int i = 0; i < len; ++i) {
            out[i] = data[(start + i) % capacity];
        }
    }
};

// --- Dummy / Placeholder ONNX & WebRTC Model Structures ---
struct FastModel {
    std::string path;
    FastModel(const char* p) : path(p ? p : "") {}
};

struct WebRtcState {
    int mode;
    WebRtcState() : mode(3) {}
};

static inline void applyHann(float* x, int n) {
    for (int i = 0; i < n; ++i) {
        float w = 0.5f - 0.5f * std::cos(2.0f * (float)M_PI * i / (n - 1));
        x[i] *= w;
    }
}

// Compute Log-Magnitude Spectral Features
static inline void computeSpectralFeatures(const float* pcm, int n, float* outMag, int outLen) {
    std::vector<float> buf(n);
    std::memcpy(buf.data(), pcm, n * sizeof(float));
    applyHann(buf.data(), n);

    for (int k = 0; k < outLen; ++k) {
        float real = 0.0f;
        float imag = 0.0f;
        for (int t = 0; t < n; ++t) {
            float angle = 2.0f * (float)M_PI * k * t / n;
            real += buf[t] * std::cos(angle);
            imag -= buf[t] * std::sin(angle);
        }
        float mag = std::sqrt(real * real + imag * imag);
        outMag[k] = std::log10(mag + 1e-6f);
    }
}

// --- JNI Implementation ---
JNIEXPORT jlong JNICALL Java_fastvad_FastVADNative_initModel
  (JNIEnv* env, jclass, jstring jpath) {
    const char* path = jpath ? env->GetStringUTFChars(jpath, nullptr) : nullptr;
    auto* model = new FastModel(path);
    if (jpath && path) env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT jlong JNICALL Java_fastvad_FastVADNative_initRingBuffer
  (JNIEnv*, jclass, jint capacitySamples) {
    auto* ring = new FastRingBuffer(capacitySamples);
    return reinterpret_cast<jlong>(ring);
}

JNIEXPORT jlong JNICALL Java_fastvad_FastVADNative_initWebRtc
  (JNIEnv*, jclass) {
    auto* s = new WebRtcState();
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL Java_fastvad_FastVADNative_pushFrame
  (JNIEnv* env, jclass, jlong, jlong ringPtr, jfloatArray jframe) {
    auto* ring = reinterpret_cast<FastRingBuffer*>(ringPtr);
    if (!ring) return;
    jsize len = env->GetArrayLength(jframe);
    jfloat* data = env->GetFloatArrayElements(jframe, nullptr);
    ring->push(data, len);
    env->ReleaseFloatArrayElements(jframe, data, JNI_ABORT);
}

JNIEXPORT jfloat JNICALL Java_fastvad_FastVADNative_runVad
  (JNIEnv*, jclass, jlong, jlong ringPtr) {
    auto* ring = reinterpret_cast<FastRingBuffer*>(ringPtr);
    if (!ring) return 0.0f;

    constexpr int WINDOW_SAMPLES = 512;
    float window[WINDOW_SAMPLES];
    ring->readLatest(window, WINDOW_SAMPLES);

    // Multi-feature DSP discriminator: energy, ZCR, and Crest factor
    float energy = 0.0f;
    int zcr = 0;
    float peak = 0.0f;
    for (int i = 0; i < WINDOW_SAMPLES; ++i) {
        float val = window[i];
        float absVal = std::abs(val);
        if (absVal > peak) peak = absVal;
        energy += val * val;
        if (i > 0 && ((val >= 0.0f && window[i-1] < 0.0f) || (val < 0.0f && window[i-1] >= 0.0f))) {
            zcr++;
        }
    }
    float rms = std::sqrt(energy / WINDOW_SAMPLES);
    float zcrRate = (float)zcr / (float)WINDOW_SAMPLES;
    float crest = (rms > 1e-4f) ? (peak / rms) : 0.0f;

    // Speech: RMS > 0.015, moderate ZCR (< 0.28), reasonable Crest (< 3.5)
    // Continuous rain/fan noise: high ZCR (> 0.35) or low crest/low SNR
    if (rms > 0.02f && zcrRate < 0.25f && crest <= 3.4f) {
        return 0.90f;
    }
    return 0.05f;
}

JNIEXPORT jint JNICALL Java_fastvad_FastVADNative_runWebRtc
  (JNIEnv* env, jclass, jlong webrtcPtr, jshortArray jframe) {
    auto* s = reinterpret_cast<WebRtcState*>(webrtcPtr);
    if (!s) return 1;
    jsize len = env->GetArrayLength(jframe);
    jshort* data = env->GetShortArrayElements(jframe, nullptr);

    long long sum = 0;
    for (int i = 0; i < len; ++i) {
        sum += std::abs(data[i]);
    }
    env->ReleaseShortArrayElements(jframe, data, JNI_ABORT);
    return (sum / (len > 0 ? len : 1)) > 350 ? 1 : 0;
}

JNIEXPORT void JNICALL Java_fastvad_FastVADNative_destroyModel
  (JNIEnv*, jclass, jlong ptr) {
    auto* model = reinterpret_cast<FastModel*>(ptr);
    delete model;
}

JNIEXPORT void JNICALL Java_fastvad_FastVADNative_destroyRingBuffer
  (JNIEnv*, jclass, jlong ptr) {
    auto* ring = reinterpret_cast<FastRingBuffer*>(ptr);
    delete ring;
}

JNIEXPORT void JNICALL Java_fastvad_FastVADNative_destroyWebRtc
  (JNIEnv*, jclass, jlong ptr) {
    auto* s = reinterpret_cast<WebRtcState*>(ptr);
    delete s;
}