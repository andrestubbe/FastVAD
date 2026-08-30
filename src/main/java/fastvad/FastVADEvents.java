package fastvad;

/**
 * Event listener interface for Voice Activity Detection (VAD) state transitions.
 * <p>
 * Receives immediate callbacks when speech start is detected (ideal for triggering
 * sub-30ms TTS Barge-In kill-switches) and when speech ends (for committing audio segments to STT).
 * </p>
 */
public interface FastVADEvents {

    /**
     * Triggered immediately when acoustic or neural speech activity begins and satisfies
     * the start hysteresis threshold (e.g. 3 consecutive frames, 30 ms).
     */
    void onSpeechStart();

    /**
     * Triggered when speech ceases and satisfies the tail hysteresis silence threshold
     * (e.g. 20 consecutive frames, 200 ms debounce).
     */
    void onSpeechEnd();
}