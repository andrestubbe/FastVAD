package fastvad;

import fastvad.events.FastVADEvents;
import fastvad.nativebridge.FastVADNative;

/**
 * Ultra-Fast Voice Activity Detection (Dual-Engine Silero-ONNX + WebRTC Safety-Net).
 * Provides sub-10ms speech segment boundary detection with zero hot-path heap allocations.
 */
public final class FastVAD implements AutoCloseable {

    private final long modelPtr;
    private final long ringPtr;
    private final long webrtcPtr;
    private final FastVADEvents events;

    private boolean inSpeech = false;
    private int speechCount = 0;
    private int silenceCount = 0;

    private float startThreshold = 0.60f;
    private float endThreshold = 0.30f;
    private int startFrames = 3;  // 3 x 10ms = 30ms hysteresis
    private int endFrames = 20;   // 20 x 10ms = 200ms tail hysteresis

    // Preallocated buffer for zero-alloc short conversion
    private final short[] shortFrameBuffer = new short[160];

    public FastVAD(FastVADEvents events) {
        this(null, events);
    }

    public FastVAD(String sileroModelPath, FastVADEvents events) {
        this.events = events != null ? events : new FastVADEvents() {
            @Override public void onSpeechStart() {}
            @Override public void onSpeechEnd() {}
        };

        if (FastVADNative.isNativeLoaded()) {
            this.modelPtr  = sileroModelPath != null ? FastVADNative.initModel(sileroModelPath) : 0;
            this.ringPtr   = FastVADNative.initRingBuffer(16000); // 1s ring buffer
            this.webrtcPtr = FastVADNative.initWebRtc();
        } else {
            this.modelPtr  = 0;
            this.ringPtr   = 0;
            this.webrtcPtr = 0;
        }
    }

    /**
     * Process a 10ms 16kHz Mono audio frame (160 float samples in [-1.0, 1.0]).
     */
    public boolean processFrame(float[] frame16k, float rms, float noiseFloor) {
        float sileroP = 0.0f;
        int webrtc = 1;

        if (FastVADNative.isNativeLoaded() && ringPtr != 0) {
            FastVADNative.pushFrame(modelPtr, ringPtr, frame16k);
            if (modelPtr != 0) {
                sileroP = FastVADNative.runVad(modelPtr, ringPtr);
            } else {
                sileroP = computeEnergyProbability(rms, noiseFloor);
            }

            if (webrtcPtr != 0) {
                floatToShortFast(frame16k, shortFrameBuffer);
                webrtc = FastVADNative.runWebRtc(webrtcPtr, shortFrameBuffer);
            }
        } else {
            // Pure JVM Fallback
            sileroP = computeEnergyProbability(rms, noiseFloor);
            webrtc = rms > (noiseFloor + 3.0f) ? 1 : 0;
        }

        boolean sileroSpeech = sileroP > startThreshold;
        boolean webrtcSpeech = (webrtc == 1) || (webrtcPtr == 0);
        boolean isSpeech = sileroSpeech && webrtcSpeech;

        updateState(isSpeech, sileroP, rms, noiseFloor);
        return inSpeech;
    }

    private float computeEnergyProbability(float rms, float noiseFloor) {
        float snr = rms - noiseFloor;
        if (snr <= 0.0f) return 0.05f;
        if (snr >= 20.0f) return 0.95f;
        return 0.05f + (snr / 20.0f) * 0.90f;
    }

    private void updateState(boolean isSpeech, float p, float rms, float noiseFloor) {
        boolean loudEnough = rms > (noiseFloor + 4.0f);

        if (isSpeech && loudEnough) {
            speechCount++;
            silenceCount = 0;
            if (!inSpeech && speechCount >= startFrames) {
                inSpeech = true;
                events.onSpeechStart();
            }
        } else if (p < endThreshold || !loudEnough) {
            silenceCount++;
            speechCount = 0;
            if (inSpeech && silenceCount >= endFrames) {
                inSpeech = false;
                events.onSpeechEnd();
            }
        }
    }

    public boolean isInSpeech() {
        return inSpeech;
    }

    public void setHysteresis(float startTh, float endTh, int startFrames, int endFrames) {
        this.startThreshold = startTh;
        this.endThreshold = endTh;
        this.startFrames = startFrames;
        this.endFrames = endFrames;
    }

    private static void floatToShortFast(float[] src, short[] dst) {
        int len = Math.min(src.length, dst.length);
        for (int i = 0; i < len; i++) {
            float v = src[i];
            if (v > 1.0f) v = 1.0f;
            else if (v < -1.0f) v = -1.0f;
            dst[i] = (short) (v * 32767.0f);
        }
    }

    @Override
    public void close() {
        if (FastVADNative.isNativeLoaded()) {
            if (modelPtr != 0) FastVADNative.destroyModel(modelPtr);
            if (ringPtr != 0) FastVADNative.destroyRingBuffer(ringPtr);
            if (webrtcPtr != 0) FastVADNative.destroyWebRtc(webrtcPtr);
        }
    }
}