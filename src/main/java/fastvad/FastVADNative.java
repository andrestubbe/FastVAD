package fastvad;

/**
 * Low-level JNI/Native interface for hardware-accelerated Silero-ONNX inference
 * and WebRTC energy VAD filters.
 * <p>
 * Bypasses Java Heap allocations by operating directly on off-heap ringbuffer pointers.
 * </p>
 */
public final class FastVADNative {

    private static final boolean NATIVE_LOADED;

    static {
        boolean loaded = false;
        try {
            fastcore.FastCore.loadLibrary("fastvad", FastVADNative.class);
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
        }
        NATIVE_LOADED = loaded;
    }

    private FastVADNative() {
    }

    /**
     * Returns true if the native C++ FastVAD acceleration library is successfully loaded.
     *
     * @return true if the native shared library is bound and active
     */
    public static boolean isNativeLoaded() {
        return NATIVE_LOADED;
    }

    /**
     * Initializes a native ONNX Runtime Silero-VAD model session.
     *
     * @param modelPath filesystem path to the silero_vad.onnx model
     * @return native handle pointer to the ONNX session
     */
    public static native long initModel(String modelPath);

    /**
     * Releases and frees the native Silero-ONNX model session.
     *
     * @param modelPtr native handle pointer returned by {@link #initModel(String)}
     */
    public static native void destroyModel(long modelPtr);

    /**
     * Allocates an off-heap ring buffer for contiguous sliding-window audio feature extraction.
     *
     * @param capacity maximum number of samples to hold (e.g. 16000 for 1 second)
     * @return native handle pointer to the ring buffer structure
     */
    public static native long initRingBuffer(int capacity);

    /**
     * Pushes a 10ms frame of float samples into the native ring buffer.
     *
     * @param modelPtr native handle to the model session
     * @param ringPtr  native handle to the ring buffer
     * @param frame    normalized audio samples in [-1.0, 1.0]
     */
    public static native void pushFrame(long modelPtr, long ringPtr, float[] frame);

    /**
     * Runs the Silero-VAD neural inference kernel over the current sliding window in the ring buffer.
     *
     * @param modelPtr native handle to the model session
     * @param ringPtr  native handle to the ring buffer
     * @return speech probability from 0.0 (certain silence) to 1.0 (certain speech)
     */
    public static native float runVad(long modelPtr, long ringPtr);

    /**
     * Deallocates the native off-heap ring buffer memory.
     *
     * @param ringPtr native handle pointer returned by {@link #initRingBuffer(int)}
     */
    public static native void destroyRingBuffer(long ringPtr);

    /**
     * Initializes a native WebRTC VAD instance.
     *
     * @return native handle pointer to the WebRTC VAD state
     */
    public static native long initWebRtc();

    /**
     * Evaluates a 10ms PCM16 audio frame using the WebRTC Gaussian Mixture Model (GMM) energy classifier.
     *
     * @param webrtcPtr native handle to the WebRTC VAD state
     * @param frame     raw 16-bit PCM samples
     * @return 1 if speech is detected by WebRTC, 0 otherwise
     */
    public static native int runWebRtc(long webrtcPtr, short[] frame);

    /**
     * Frees and destroys the native WebRTC VAD instance.
     *
     * @param webrtcPtr native handle pointer returned by {@link #initWebRtc()}
     */
    public static native void destroyWebRtc(long webrtcPtr);
}