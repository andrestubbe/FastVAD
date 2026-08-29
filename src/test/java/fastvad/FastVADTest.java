package fastvad;

import fastvad.events.FastVADEvents;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class FastVADTest {

    @Test
    public void testSpeechLifecycleDetection() {
        int[] starts = {0};
        int[] ends = {0};

        FastVAD vad = new FastVAD(new FastVADEvents() {
            @Override public void onSpeechStart() { starts[0]++; }
            @Override public void onSpeechEnd() { ends[0]++; }
        });

        float[] frame = new float[160];
        
        // 1. Send silence
        for (int i = 0; i < 10; i++) {
            vad.processFrame(frame, 10.0f, 10.0f);
        }
        assertFalse(vad.isInSpeech());
        assertEquals(0, starts[0]);

        // 2. Send active speech frames
        for (int i = 0; i < 10; i++) {
            vad.processFrame(frame, 35.0f, 10.0f);
        }
        assertTrue(vad.isInSpeech());
        assertEquals(1, starts[0]);

        // 3. Send silence to trigger end hysteresis
        for (int i = 0; i < 25; i++) {
            vad.processFrame(frame, 10.0f, 10.0f);
        }
        assertFalse(vad.isInSpeech());
        assertEquals(1, ends[0]);

        vad.close();
    }
}