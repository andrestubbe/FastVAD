package fastvad.nativebridge;

/**
 * JNI wrapper for native ONNX Runtime Silero VAD v5 and WebRTC VAD.
 */
public final class FastVADNative {

    private static volatile boolean nativeLoaded = false;

    static {
        try {
            System.loadLibrary("fastvad");
            nativeLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            // Fallback to pure JVM hybrid heuristics if native binary is not available
            nativeLoaded = false;
        }
    }

    public static boolean isNativeLoaded() {
        return nativeLoaded;
    }

    public static native long initModel(String modelPath);
    public static native long initRingBuffer(int capacitySamples);
    public static native long initWebRtc();
    public static native void pushFrame(long modelPtr, long ringPtr, float[] frame);
    public static native float runVad(long modelPtr, long ringPtr);
    public static native int runWebRtc(long webrtcPtr, short[] frame16k);
    public static native void destroyModel(long modelPtr);
    public static native void destroyRingBuffer(long ringPtr);
    public static native void destroyWebRtc(long webrtcPtr);
}