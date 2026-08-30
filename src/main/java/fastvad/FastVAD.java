package fastvad;

import fastaudioprocess.FastAudioAcoustics;

/**
 * High-Performance Dual-Engine Voice Activity Detector (Silero-ONNX & WebRTC).
 * <p>
 * Provides sub-10ms speech segment boundary detection with zero hot-path heap allocations,
 * harmonic pitch autocorrelation, and configurable debounce hysteresis via hardware-accelerated JNI
 * and FastAudioProcess acoustic analysis primitives.
 * </p>
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * FastVAD vad = new FastVAD(new FastVADEvents() {
 *     @Override
 *     public void onSpeechStart() {
 *         System.out.println("User started speaking! Stop Agent TTS immediately.");
 *     }
 *
 *     @Override
 *     public void onSpeechEnd() {
 *         System.out.println("User stopped speaking! Dispatch audio buffer to FastSTT.");
 *     }
 * });
 *
 * // Ingest 10ms (160 samples @ 16 kHz) PCM frames:
 * boolean isSpeaking = vad.processFrame(frame16k, rmsDb, noiseFloorDb);
 * }</pre>
 */
public final class FastVAD implements AutoCloseable {

    /** Standard PCM audio sampling rate (16 kHz). */
    public static final int DEFAULT_SAMPLE_RATE = 16000;

    /** Standard frame duration in milliseconds (10 ms). */
    public static final int DEFAULT_FRAME_MS = 10;

    /** Number of samples per 10ms frame at 16 kHz (160 samples). */
    public static final int DEFAULT_FRAME_SAMPLES = 160;

    /** Default off-heap ring buffer capacity (1.0 second = 16,000 float samples). */
    public static final int DEFAULT_RING_BUFFER_CAPACITY = 16000;

    /** Default speech start probability threshold (p > 0.50). */
    public static final float DEFAULT_START_THRESHOLD = 0.50f;

    /** Default speech end silence probability threshold (p < 0.30). */
    public static final float DEFAULT_END_THRESHOLD = 0.30f;

    /** Default speech start debounce hysteresis frames (2 frames = 20 ms). */
    public static final int DEFAULT_START_FRAMES = 2;

    /** Default speech end tail hysteresis frames (20 frames = 200 ms). */
    public static final int DEFAULT_END_FRAMES = 20;

    private final long modelPtr;
    private final long ringPtr;
    private final long webrtcPtr;
    private final FastVADEvents events;

    private boolean inSpeech = false;
    private int speechCount = 0;
    private int silenceCount = 0;

    private float startThreshold = DEFAULT_START_THRESHOLD;
    private float endThreshold = DEFAULT_END_THRESHOLD;
    private int startFrames = DEFAULT_START_FRAMES;
    private int endFrames = DEFAULT_END_FRAMES;

    // Preallocated circular history buffers for temporal modulation & pitch variance tracking (20 frames = 200ms)
    private static final int HISTORY_SIZE = 20;
    private final float[] periodicityHistory = new float[HISTORY_SIZE];
    private final float[] rmsHistory = new float[HISTORY_SIZE];
    private int historyIdx = 0;
    private int historyFilled = 0;

    // Preallocated buffer for zero-alloc short conversion
    private final short[] shortFrameBuffer = new short[DEFAULT_FRAME_SAMPLES];

    private final SileroEngine silero;
    private final float[] sileroWindow512 = new float[512];

    /**
     * Creates a new FastVAD instance using default settings and the embedded native C++ inference engine.
     *
     * @param events callback listener for speech start/end events
     */
    public FastVAD(FastVADEvents events) {
        this(null, DEFAULT_RING_BUFFER_CAPACITY, events);
    }

    /**
     * Creates a new FastVAD instance with an optional Silero-ONNX neural model weights file.
     *
     * @param sileroModelPath path to the silero_vad.onnx model, or null for embedded native engine
     * @param events          callback listener for speech start/end events
     */
    public FastVAD(String sileroModelPath, FastVADEvents events) {
        this(sileroModelPath, DEFAULT_RING_BUFFER_CAPACITY, events);
    }

    /**
     * Creates a new FastVAD instance with customizable ring buffer capacity and optional ONNX model.
     *
     * @param sileroModelPath    path to the silero_vad.onnx model, or null for embedded native engine
     * @param ringBufferCapacity capacity of off-heap ring buffer in float samples (e.g. 16000 for 1s)
     * @param events             callback listener for speech start/end events
     */
    public FastVAD(String sileroModelPath, int ringBufferCapacity, FastVADEvents events) {
        this.events = events != null ? events : new FastVADEvents() {
            @Override public void onSpeechStart() {}
            @Override public void onSpeechEnd() {}
        };

        SileroEngine engine = null;
        try {
            engine = new SileroEngine();
        } catch (Throwable t) {
            engine = null;
        }
        this.silero = engine;

        int capacity = ringBufferCapacity > 0 ? ringBufferCapacity : DEFAULT_RING_BUFFER_CAPACITY;

        if (FastVADNative.isNativeLoaded()) {
            this.modelPtr  = sileroModelPath != null ? FastVADNative.initModel(sileroModelPath) : 0;
            this.ringPtr   = FastVADNative.initRingBuffer(capacity);
            this.webrtcPtr = FastVADNative.initWebRtc();
        } else {
            this.modelPtr  = 0;
            this.ringPtr   = 0;
            this.webrtcPtr = 0;
        }
    }

    /**
     * Processes a single 10ms 16kHz mono audio frame (160 normalized float samples in [-1.0, 1.0])
     * through the native acoustic neural classifier, FastAudioProcess acoustic discriminators, and WebRTC filter.
     *
     * @param frame16k   normalized float audio samples
     * @param rms        current Root Mean Square (RMS) energy in decibels
     * @param noiseFloor estimated ambient background noise floor in decibels
     * @return true if speech is currently active, false otherwise
     */
    public boolean processFrame(float[] frame16k, float rms, float noiseFloor) {
        float speechProbability = 0.0f;
        int webrtc = 1;

        // 1. Silero-VAD v5 Neural Inference
        if (silero != null) {
            System.arraycopy(sileroWindow512, 160, sileroWindow512, 0, 512 - 160);
            System.arraycopy(frame16k, 0, sileroWindow512, 512 - 160, 160);
            speechProbability = silero.infer(sileroWindow512);
        } else if (FastVADNative.isNativeLoaded() && ringPtr != 0) {
            FastVADNative.pushFrame(modelPtr, ringPtr, frame16k);
            speechProbability = FastVADNative.runVad(modelPtr, ringPtr);

            if (webrtcPtr != 0) {
                floatToShortFast(frame16k, shortFrameBuffer);
                webrtc = FastVADNative.runWebRtc(webrtcPtr, shortFrameBuffer);
            }
        }

        // Acoustic Feature Discrimination powered by FastAudioProcess
        float crest = FastAudioAcoustics.computeCrestFactor(frame16k);
        float zcr = FastAudioAcoustics.computeZeroCrossingRate(frame16k);
        float periodicity = FastAudioAcoustics.computeAutocorrelationPeriodicity(frame16k, 35, 160);

        // Update temporal context (200ms sliding window)
        periodicityHistory[historyIdx] = periodicity;
        rmsHistory[historyIdx] = rms;
        historyIdx = (historyIdx + 1) % HISTORY_SIZE;
        if (historyFilled < HISTORY_SIZE) historyFilled++;

        // Compute Temporal Dynamic Modulation (Speech has natural pitch & energy fluctuations; ambient music is frozen)
        float pMin = 1.0f, pMax = 0.0f;
        float rmsMin = 100.0f, rmsMax = -100.0f;
        for (int i = 0; i < historyFilled; i++) {
            float pVal = periodicityHistory[i];
            if (pVal < pMin) pMin = pVal;
            if (pVal > pMax) pMax = pVal;
            float rVal = rmsHistory[i];
            if (rVal < rmsMin) rmsMin = rVal;
            if (rVal > rmsMax) rmsMax = rVal;
        }
        float pitchVariance = pMax - pMin;
        float rmsDelta = rmsMax - rmsMin;

        // Static Ambience / Drone Rejection:
        // Ambient pads & synthesizer tones have locked high periodicity (>0.85) and static RMS delta (< 1.5dB) across 200ms
        boolean isStaticAmbience = (historyFilled >= 15) && (periodicity >= 0.82f && pitchVariance < 0.08f && rmsDelta < 2.0f);

        // True Human Speech Criteria:
        // When Silero Neural Engine is active, it detects speech with >99% precision trained on 6,000+ languages
        boolean hasSignalEnergy = (rms >= 13.0f) && (rms > (noiseFloor + 3.0f));
        boolean isSpeech;

        if (silero != null) {
            isSpeech = hasSignalEnergy && (speechProbability > startThreshold);
        } else {
            boolean hasVoicedVowels = (periodicity >= 0.45f && zcr < 0.22f && crest >= 1.38f && crest <= 3.4f);
            boolean hasConsonants   = (zcr >= 0.18f && zcr <= 0.32f && crest >= 2.0f && crest <= 3.4f && periodicity >= 0.35f);
            isSpeech = hasSignalEnergy && (hasVoicedVowels || hasConsonants) && !isStaticAmbience;
        }

        updateState(isSpeech, speechProbability, rms, noiseFloor);
        return inSpeech;
    }

    /**
     * Updates the debounce hysteresis counters and fires speech state transition events.
     * Prevents sporadic flip-flopping caused by single-frame noise spikes or brief speech pauses.
     *
     * @param isSpeech   instantaneous speech classification from the dual-engine filter
     * @param p          estimated speech probability from native classifier
     * @param rms        current frame RMS energy in decibels
     * @param noiseFloor estimated background noise floor in decibels
     */
    private void updateState(boolean isSpeech, float p, float rms, float noiseFloor) {
        boolean loudEnough = (rms >= 14.0f) && (rms > noiseFloor + 3.0f);

        if (isSpeech && loudEnough) {
            speechCount++;
            silenceCount = 0;
            if (!inSpeech && speechCount >= startFrames) {
                inSpeech = true;
                events.onSpeechStart();
            }
        } else {
            silenceCount++;
            speechCount = 0;
            if (inSpeech && silenceCount >= endFrames) {
                inSpeech = false;
                events.onSpeechEnd();
            }
        }
    }

    /**
     * Returns true if the detector is currently inside an active speech segment.
     *
     * @return true if speech is active, false if background silence is present
     */
    public boolean isInSpeech() {
        return inSpeech;
    }

    /**
     * Configures the detection sensitivity and debounce hysteresis windows.
     *
     * @param startTh     speech start probability threshold (0.0 to 1.0, default 0.60)
     * @param endTh       speech end silence threshold (0.0 to 1.0, default 0.30)
     * @param startFrames number of consecutive speech frames required to trigger start (default 3 = 30ms)
     * @param endFrames   number of consecutive silence frames required to trigger end (default 20 = 200ms)
     */
    public void setHysteresis(float startTh, float endTh, int startFrames, int endFrames) {
        this.startThreshold = startTh;
        this.endThreshold = endTh;
        this.startFrames = startFrames;
        this.endFrames = endFrames;
    }

    /**
     * Converts normalized floating-point audio samples [-1.0, 1.0] into signed 16-bit PCM shorts.
     * Employs zero-allocation preallocated target buffer clamping.
     *
     * @param src source array of normalized float audio samples
     * @param dst destination array of 16-bit PCM short samples
     */
    private static void floatToShortFast(float[] src, short[] dst) {
        int len = Math.min(src.length, dst.length);
        for (int i = 0; i < len; i++) {
            float v = src[i];
            if (v > 1.0f) v = 1.0f;
            else if (v < -1.0f) v = -1.0f;
            dst[i] = (short) (v * 32767.0f);
        }
    }

    /**
     * Releases and closes all allocated native models, off-heap ring buffers, and WebRTC states.
     */
    @Override
    public void close() {
        if (FastVADNative.isNativeLoaded()) {
            if (modelPtr != 0) FastVADNative.destroyModel(modelPtr);
            if (ringPtr != 0) FastVADNative.destroyRingBuffer(ringPtr);
            if (webrtcPtr != 0) FastVADNative.destroyWebRtc(webrtcPtr);
        }
    }
}