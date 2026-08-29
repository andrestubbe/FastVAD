package fastvad.events;

/**
 * Real-time voice activity lifecycle callback listener.
 */
public interface FastVADEvents {
    /**
     * Emitted when continuous speech activity is confirmed past start hysteresis.
     * Use to instantly trigger Barge-In TTS cancellation and begin STT segment stream.
     */
    void onSpeechStart();

    /**
     * Emitted when silence persists past the tail hysteresis duration.
     * Use to finalize and commit the STT speech segment for LLM inference.
     */
    void onSpeechEnd();
}