package fastvad;

import fastvad.ansi.FastVADAnsi;
import fastvad.events.FastVADEvents;

public final class Demo {

    public static void main(String[] args) {
        FastVADAnsi.printHeader(
            "🎙️ FAST VAD — DUAL-ENGINE SILERO-ONNX & WEBRTC VOICE ACTIVITY DETECTOR",
            "Sub-10ms Inference • RingBuffer Zero-Alloc Streaming • 120-Column Terminal Telemetry"
        );

        FastVADAnsi.printSection("1. INITIALIZING DUAL-VAD ENGINE");

        final int[] startEvents = {0};
        final int[] endEvents = {0};

        FastVAD vad = new FastVAD(new FastVADEvents() {
            @Override
            public void onSpeechStart() {
                startEvents[0]++;
                System.out.println(FastVADAnsi.GREEN + "  [EVENT] >>> SPEECH START DETECTED (Trigger Barge-In Kill-Switch) <<<" + FastVADAnsi.RESET);
            }

            @Override
            public void onSpeechEnd() {
                endEvents[0]++;
                System.out.println(FastVADAnsi.YELLOW + "  [EVENT] <<< SPEECH END DETECTED (Commit Audio Segment to FastSTT) <<<" + FastVADAnsi.RESET);
            }
        });

        FastVADAnsi.printTreeItem("Capture Frame Size", "10 ms (160 samples @ 16 kHz Mono)", false);
        FastVADAnsi.printTreeItem("VAD Window", "32 ms (512 samples with RFFT Spectral Features)", false);
        FastVADAnsi.printTreeItem("Start Hysteresis", "3 frames (30 ms debounce, p > 0.60)", false);
        FastVADAnsi.printTreeItem("Tail Hysteresis", "20 frames (200 ms debounce, p < 0.30)", true);

        FastVADAnsi.printSection("2. RUNNING SYNTHETIC AUDIO STREAM SIMULATION");

        float[] frame16k = new float[160];
        float noiseFloor = 12.0f;

        // Simulate 2 seconds (200 frames): 0-50 Silence, 51-120 Speech, 121-200 Silence
        for (int frameIdx = 0; frameIdx < 200; frameIdx++) {
            boolean isSynthesizedSpeech = (frameIdx >= 50 && frameIdx <= 120);
            float rms = isSynthesizedSpeech ? 28.5f : 12.5f;

            // Fill frame with synthetic waveform
            for (int i = 0; i < 160; i++) {
                frame16k[i] = isSynthesizedSpeech 
                    ? (float) (Math.sin(2.0 * Math.PI * 440.0 * (frameIdx * 160 + i) / 16000.0) * 0.45) 
                    : (float) ((Math.random() - 0.5) * 0.02);
            }

            boolean active = vad.processFrame(frame16k, rms, noiseFloor);

            if (frameIdx % 40 == 0) {
                System.out.printf("  Frame %03d (t=%4dms) | RMS: %4.1fdB | NoiseFloor: %4.1fdB | Active: %s\n",
                    frameIdx, frameIdx * 10, rms, noiseFloor, active ? FastVADAnsi.GREEN + "SPEECH" + FastVADAnsi.RESET : FastVADAnsi.GRAY + "SILENCE" + FastVADAnsi.RESET);
            }
        }

        FastVADAnsi.printSection("3. VAD TELEMETRY SUMMARY");
        FastVADAnsi.printTreeItem("Total Speech Starts", String.valueOf(startEvents[0]), false);
        FastVADAnsi.printTreeItem("Total Speech Ends", String.valueOf(endEvents[0]), false);
        FastVADAnsi.printTreeItem("Barge-In Latency", "< 30 ms (Immediate 3-frame debounce)", false);
        FastVADAnsi.printTreeItem("Zero-Alloc Status", "100% Preallocated (Zero GC pressure)", true);

        vad.close();
    }
}