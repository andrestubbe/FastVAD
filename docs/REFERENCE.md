# FastVAD Reference & API Specification

## 1. Core Vocabulary

*   **VAD (Voice Activity Detection)**: The real-time classification of continuous audio buffers into human speech versus silence/noise.
*   **Barge-In**: The ability of a conversational voice agent to instantly cancel active TTS audio playback when the user begins speaking (<150 ms turnaround).
*   **Start Debounce Hysteresis**: Requiring N consecutive speech frames (e.g. 3 frames = 30 ms) with probability $p > 0.60$ before transitioning into `IN_SPEECH` to prevent false positive triggers on breaths and keyboard clicks.
*   **Tail Debounce Hysteresis**: Requiring N consecutive silence frames (e.g. 20 frames = 200 ms) with probability $p < 0.30$ before transitioning into `SILENCE` to prevent premature sentence cutoffs during micro-pauses.
*   **RingBuffer**: A contiguous, circular native float buffer holding 64 ms of recent PCM audio without JVM object allocations.

## 2. API Quick Reference

### `FastVAD` (Main Facade)
*   `FastVAD(FastVADEvents events)`: Instantiates a dual-engine VAD instance with lifecycle event callbacks.
*   `FastVAD(String sileroModelPath, FastVADEvents events)`: Instantiates FastVAD with a custom Silero ONNX model path.
*   `processFrame(float[] frame16k, float rms, float noiseFloor)`: Processes a 10ms 16kHz audio frame and evaluates state.
*   `isInSpeech()`: Returns current boolean speech activity state.
*   `setHysteresis(float startTh, float endTh, int startFrames, int endFrames)`: Configures custom detection thresholds.
*   `close()`: Releases native ONNX sessions and ring buffer allocations.

### `FastVADEvents` (Lifecycle Interface)
*   `onSpeechStart()`: Emitted when speech activity is confirmed. Use to trigger immediate TTS Barge-In stop.
*   `onSpeechEnd()`: Emitted when silence persists. Use to commit the speech segment to FastSTT.

---
**Part of the FastJava Ecosystem** — *Making the JVM faster. Small package. Maximum speed. Zero bloat. 🚀📋*