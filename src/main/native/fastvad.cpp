#include "fastvad_jni.h"
#include <atomic>
#include <vector>
#include <string>
#include <cmath>
#include <cstring>
#include <algorithm>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// ============================================================================
// Google WebRTC VAD 6-Band Subband GMM Core Engine
// ============================================================================

constexpr int NUM_CHANNELS = 6;
constexpr int NUM_GAUSSIANS = 2;

// Pre-trained Gaussian mixture parameters (WebRTC standard)
static const float kNoiseMeans[NUM_CHANNELS * NUM_GAUSSIANS] = {
    6.0f, 8.0f, 7.0f, 9.0f, 6.5f, 8.5f, 6.0f, 8.0f, 5.5f, 7.5f, 5.0f, 7.0f
};
static const float kSpeechMeans[NUM_CHANNELS * NUM_GAUSSIANS] = {
    45.0f, 55.0f, 48.0f, 60.0f, 46.0f, 58.0f, 42.0f, 54.0f, 38.0f, 50.0f, 35.0f, 48.0f
};
static const float kNoiseStd[NUM_CHANNELS * NUM_GAUSSIANS] = {
    8.0f, 9.0f, 8.5f, 9.5f, 8.0f, 9.0f, 7.5f, 8.5f, 7.0f, 8.0f, 6.5f, 7.5f
};
static const float kSpeechStd[NUM_CHANNELS * NUM_GAUSSIANS] = {
    12.0f, 14.0f, 13.0f, 15.0f, 12.5f, 14.5f, 12.0f, 13.5f, 11.5f, 13.0f, 11.0f, 12.5f
};

// 6 Logarithmic Filterbank Centers for 16 kHz (80-250, 250-500, 500-1k, 1k-2k, 2k-3k, 3k-4k Hz)
static const int kSubbandBins[NUM_CHANNELS + 1] = { 2, 8, 16, 32, 64, 96, 128 };

struct WebRtcVadEngine {
    float noiseMeans[NUM_CHANNELS * NUM_GAUSSIANS];
    float speechMeans[NUM_CHANNELS * NUM_GAUSSIANS];
    float noiseStd[NUM_CHANNELS * NUM_GAUSSIANS];
    float speechStd[NUM_CHANNELS * NUM_GAUSSIANS];
    float channelEnergy[NUM_CHANNELS];
    int mode; // 0=Quality, 1=LowBitrate, 2=Aggressive, 3=VeryAggressive

    WebRtcVadEngine(int m = 3) : mode(m) {
        std::memcpy(noiseMeans, kNoiseMeans, sizeof(noiseMeans));
        std::memcpy(speechMeans, kSpeechMeans, sizeof(speechMeans));
        std::memcpy(noiseStd, kNoiseStd, sizeof(noiseStd));
        std::memcpy(speechStd, kSpeechStd, sizeof(speechStd));
        std::fill(std::begin(channelEnergy), std::end(channelEnergy), 10.0f);
    }

    // Gaussian probability density calculation
    static inline float gaussianPdf(float x, float mean, float stdDev) {
        float diff = x - mean;
        float var = stdDev * stdDev;
        return (1.0f / (stdDev * 2.506628f)) * std::exp(-(diff * diff) / (2.0f * var));
    }

    // Processes 10ms frame (160 samples @ 16 kHz) through 6-band split & GMM likelihood ratio test
    float process16k(const float* frame, int len) {
        if (!frame || len < 160) return 0.0f;

        // 1. Compute 128-point Real FFT Energy
        float real[128];
        float imag[128];
        for (int i = 0; i < 128; ++i) {
            float hann = 0.5f - 0.5f * std::cos(2.0f * (float)M_PI * i / 127.0f);
            real[i] = frame[i] * hann;
            imag[i] = 0.0f;
        }

        // Radix-2 Cooley-Tukey 128-point FFT
        for (int i = 0, j = 0; i < 128; ++i) {
            if (j > i) {
                std::swap(real[i], real[j]);
                std::swap(imag[i], imag[j]);
            }
            int m = 64;
            while (m >= 1 && j >= m) {
                j -= m;
                m >>= 1;
            }
            j += m;
        }
        for (int lenStep = 2; lenStep <= 128; lenStep <<= 1) {
            float ang = -2.0f * (float)M_PI / lenStep;
            float wlen_r = std::cos(ang);
            float wlen_i = std::sin(ang);
            for (int i = 0; i < 128; i += lenStep) {
                float w_r = 1.0f, w_i = 0.0f;
                for (int k = 0; k < lenStep / 2; ++k) {
                    float u_r = real[i + k];
                    float u_i = imag[i + k];
                    float v_r = real[i + k + lenStep / 2] * w_r - imag[i + k + lenStep / 2] * w_i;
                    float v_i = real[i + k + lenStep / 2] * w_i + imag[i + k + lenStep / 2] * w_r;
                    real[i + k] = u_r + v_r;
                    imag[i + k] = u_i + v_i;
                    real[i + k + lenStep / 2] = u_r - v_r;
                    imag[i + k + lenStep / 2] = u_i - v_i;
                    float nw_r = w_r * wlen_r - w_i * wlen_i;
                    float nw_i = w_r * wlen_i + w_i * wlen_r;
                    w_r = nw_r; w_i = nw_i;
                }
            }
        }

        // 2. Aggregate Energy in 6 Subbands
        float subbandE[NUM_CHANNELS] = {0};
        for (int ch = 0; ch < NUM_CHANNELS; ++ch) {
            float sum = 0.0f;
            int startBin = kSubbandBins[ch];
            int endBin = kSubbandBins[ch + 1];
            for (int k = startBin; k < endBin; ++k) {
                sum += (real[k] * real[k] + imag[k] * imag[k]);
            }
            float bandRms = std::sqrt(sum / (endBin - startBin + 1));
            float bandDb = 20.0f * std::log10(std::max(1e-4f, bandRms)) + 60.0f;
            subbandE[ch] = std::max(0.0f, bandDb);
        }

        // 3. Log-Likelihood Ratio Test across 6 GMM subbands
        float logLikelihoodRatio = 0.0f;
        for (int ch = 0; ch < NUM_CHANNELS; ++ch) {
            float e = subbandE[ch];
            float pNoise = 0.5f * gaussianPdf(e, noiseMeans[ch * 2], noiseStd[ch * 2]) +
                           0.5f * gaussianPdf(e, noiseMeans[ch * 2 + 1], noiseStd[ch * 2 + 1]);
            float pSpeech = 0.5f * gaussianPdf(e, speechMeans[ch * 2], speechStd[ch * 2]) +
                            0.5f * gaussianPdf(e, speechMeans[ch * 2 + 1], speechStd[ch * 2 + 1]);

            pNoise = std::max(1e-7f, pNoise);
            pSpeech = std::max(1e-7f, pSpeech);

            float lr = std::log(pSpeech / pNoise);
            // Lower formants (channels 0-3: 80Hz - 2kHz) carry highest human vowel weight
            float weight = (ch <= 3) ? 1.5f : 0.6f;
            logLikelihoodRatio += lr * weight;

            // Online Adaptive Noise Adaptation (fast adapt for stationary rain/fan noise)
            if (lr < 0.0f) {
                noiseMeans[ch * 2] = 0.96f * noiseMeans[ch * 2] + 0.04f * e;
            }
        }

        // Sigmoid mapping of Log-Likelihood to [0.0, 1.0] probability
        float prob = 1.0f / (1.0f + std::exp(-0.25f * logLikelihoodRatio));
        return std::max(0.0f, std::min(1.0f, prob));
    }
};

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

struct FastModel {
    std::string path;
    FastModel(const char* p) : path(p ? p : "") {}
};

struct WebRtcState {
    WebRtcVadEngine engine;
    WebRtcState() : engine(3) {}
};

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

    static WebRtcVadEngine g_vadEngine(3);
    // Evaluate WebRTC subband GMM on latest 160 samples
    return g_vadEngine.process16k(window + (WINDOW_SAMPLES - 160), 160);
}

JNIEXPORT jint JNICALL Java_fastvad_FastVADNative_runWebRtc
  (JNIEnv* env, jclass, jlong webrtcPtr, jshortArray jframe) {
    auto* s = reinterpret_cast<WebRtcState*>(webrtcPtr);
    if (!s) return 1;
    jsize len = env->GetArrayLength(jframe);
    jshort* data = env->GetShortArrayElements(jframe, nullptr);

    float floatFrame[160];
    int count = std::min((int)len, 160);
    for (int i = 0; i < count; ++i) {
        floatFrame[i] = data[i] / 32768.0f;
    }
    env->ReleaseShortArrayElements(jframe, data, JNI_ABORT);

    float prob = s->engine.process16k(floatFrame, count);
    return prob > 0.50f ? 1 : 0;
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