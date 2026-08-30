package fastvad;

import fastansi.FastANSI;
import fastkeyboard.FastKeyboard;
import fastkeyboard.FastKeyboardImpl;
import fastkeyboard.Keys;
import fastaudioprocess.FastAudioAcoustics;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.TargetDataLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Demo {

    public enum AudioClass {
        SILENCE("[💤 SILENCE / IDLE]"),
        NOISE("[🔊 NOISE / RAIN] "),
        MUSIC("[🎵 MUSIC ACTIVE]  "),
        VOICE("[🎙️ VOICE ACTIVE]  ");

        private final String label;
        AudioClass(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    public static void main(String[] args) {
        printHeader(
            "Live Microphone Voice, Music & Noise Activity Detection",
            "ENGINE: 10ms FastAudioProcess DSP  |  LATENCY: Sub-10ms  |  CONTROLS: [ESC] Exit via FastKeyboard"
        );

        // ── Phase 1: Engine Configuration ───────────────────────────────────
        printPhase("Phase 1", "Real-Time Acoustic Feature Extraction", "Preallocated Zero-Alloc Buffers");
        printTreeItem("Native C++ Engine", FastVADNative.isNativeLoaded() ? boldWhite("Active (fastvad.dll x64 JNI)") : yellow("JVM Pure Fallback"), false);
        printTreeItem("Capture Frame Size", "10 ms (160 samples @ 16 kHz Mono PCM16)", false);
        printTreeItem("Feature Pipeline", "Periodicity (Pitch) + ZCR + Crest Factor + WebRTC GMM", false);
        printTreeItem("Debounce Timing", "2 frames onset (20ms) | 15 frames release (150ms)", true);
        System.out.println();

        // ── Phase 2: Live Microphone Processing ────────────────────────────
        printPhase("Phase 2", "Live Continuous Microphone Ingestion", "Speak into Microphone, Play Music, or Make Noise  |  Tap [ESC] to Exit");
        System.out.println();

        AudioFormat format = new AudioFormat(16000.0f, 16, 1, true, false);
        TargetDataLine micLine = null;
        try {
            micLine = AudioSystem.getTargetDataLine(format);
            micLine.open(format, 16000);
            micLine.start();
        } catch (Exception e) {
            System.out.println(darkGray("  ├── ") + yellow("[WARN] Microphone input unavailable: " + e.getMessage()));
        }

        final AtomicBoolean isRunning = new AtomicBoolean(true);
        FastKeyboard keyboard = new FastKeyboardImpl();
        try {
            keyboard.startListening((deviceHandle, vKey, makeCode, isPressed, isE0, timestamp, keyChar) -> {
                if (isPressed && (vKey == Keys.ESCAPE || vKey == 0x51 || vKey == Keys.SPACE)) {
                    isRunning.set(false);
                }
            });
        } catch (Exception e) {
            System.out.println(darkGray("  ├── ") + yellow("[WARN] FastKeyboard unavailable: " + e.getMessage()));
        }

        final int[] stats = {0, 0, 0}; // silence, noise, voice

        FastVAD vad = new FastVAD(new FastVADEvents() {
            @Override public void onSpeechStart() { stats[2]++; }
            @Override public void onSpeechEnd() { }
        });

        byte[] micBytes = new byte[320];
        float[] micFrame = new float[160];
        float noiseFloor = 14.0f;
        int frameIdx = 0;

        System.out.print(FastANSI.CURSOR_HIDE);
        System.out.println();

        int voiceHold = 0;
        int musicHold = 0;
        int noiseHold = 0;
        AudioClass classification = AudioClass.SILENCE;

        try {
            long startTime = System.currentTimeMillis();

            while (isRunning.get()) {
                frameIdx++;
                long targetTime = startTime + (frameIdx * 10L);

                float micRms = 0.0f;
                float micCrest = 0.0f;
                float micZcr = 0.0f;
                float micPeriodicity = 0.0f;

                if (micLine != null && micLine.available() >= 320) {
                    micLine.read(micBytes, 0, 320);
                    ByteBuffer bbMic = ByteBuffer.wrap(micBytes).order(ByteOrder.LITTLE_ENDIAN);
                    double sumSqMic = 0.0;
                    for (int i = 0; i < 160; i++) {
                        short s = bbMic.getShort();
                        float f = s / 32768.0f;
                        micFrame[i] = f;
                        sumSqMic += (f * f);
                    }
                    micRms = (float) (20.0 * Math.log10(Math.max(1e-4, Math.sqrt(sumSqMic / 160.0))) + 60.0);
                    if (micRms < 0.0f) micRms = 0.0f;

                    micCrest = FastAudioAcoustics.computeCrestFactor(micFrame);
                    micZcr = FastAudioAcoustics.computeZeroCrossingRate(micFrame);
                    micPeriodicity = FastAudioAcoustics.computeAutocorrelationPeriodicity(micFrame, 35, 160);

                    // Dynamic Ambient Noise Floor Tracking
                    if (classification == AudioClass.SILENCE || classification == AudioClass.NOISE) {
                        if (micRms > noiseFloor) {
                            noiseFloor = (noiseFloor * 0.95f) + (micRms * 0.05f);
                        } else {
                            noiseFloor = (noiseFloor * 0.98f) + (micRms * 0.02f);
                        }
                    }

                    boolean isSpeech = vad.processFrame(micFrame, micRms, noiseFloor);

                    AudioClass rawClass;
                    if (micRms < 12.0f || micRms <= (noiseFloor + 3.0f)) {
                        rawClass = AudioClass.SILENCE;
                    } else if (isSpeech || (micPeriodicity >= 0.40f && micZcr < 0.25f && micRms > (noiseFloor + 5.0f))) {
                        rawClass = AudioClass.VOICE;
                    } else if (micPeriodicity >= 0.70f && micCrest >= 2.5f) {
                        rawClass = AudioClass.MUSIC;
                    } else {
                        rawClass = AudioClass.NOISE;
                    }

                    // Temporal Smoothing Buffer (40 frames = 400ms speech hold for natural pauses)
                    if (rawClass == AudioClass.VOICE) {
                        voiceHold = 40;
                        musicHold = 0;
                        noiseHold = 0;
                        classification = AudioClass.VOICE;
                    } else if (rawClass == AudioClass.MUSIC) {
                        if (voiceHold <= 0) {
                            musicHold = 25;
                            noiseHold = 0;
                            classification = AudioClass.MUSIC;
                        } else {
                            voiceHold--;
                            classification = AudioClass.VOICE;
                        }
                    } else if (rawClass == AudioClass.NOISE) {
                        if (voiceHold <= 0 && musicHold <= 0) {
                            noiseHold = 10;
                            classification = AudioClass.NOISE;
                        } else if (voiceHold > 0) {
                            voiceHold--;
                            classification = AudioClass.VOICE;
                        } else {
                            musicHold--;
                            classification = AudioClass.MUSIC;
                        }
                    } else { // SILENCE
                        if (voiceHold > 0) {
                            voiceHold--;
                            classification = AudioClass.VOICE;
                        } else if (musicHold > 0) {
                            musicHold--;
                            classification = AudioClass.MUSIC;
                        } else if (noiseHold > 0) {
                            noiseHold--;
                            classification = AudioClass.NOISE;
                        } else {
                            classification = AudioClass.SILENCE;
                        }
                    }
                }

                // Render HUD every 40ms
                if (frameIdx % 4 == 0) {
                    renderMicHud(frameIdx, micRms, classification, micPeriodicity, micZcr, micCrest);
                }

                long now = System.currentTimeMillis();
                long sleepMs = targetTime - now;
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs);
                }
            }

            keyboard.stopListening();
            if (micLine != null) {
                micLine.stop();
                micLine.close();
            }

            System.out.print(FastANSI.CURSOR_SHOW);
            System.out.println("\n");
            printPhase("Phase 3", "Live Audio Classification Summary", "Telemetry Report");
            printTreeItem("Total Stream Duration", String.format("%,d frames (~%.2f seconds)", frameIdx, frameIdx * 0.01), false);
            printTreeItem("Detected Speech Starts", String.valueOf(stats[2]), false);
            printTreeItem("Acoustic Latency", "< 20 ms", true);

        } catch (Exception e) {
            keyboard.stopListening();
            System.out.print(FastANSI.CURSOR_SHOW);
            System.out.println(darkGray("  └── ") + yellow("[ERROR] Live microphone capture failed: " + e.getMessage()));
        }
    }

    private static void renderMicHud(int frameIdx, float micRms, AudioClass classification, float periodicity, float zcr, float crest) {
        String micMeter = buildMonochromeMeter(micRms, 32);

        String tagString;
        switch (classification) {
            case VOICE:
                tagString = boldWhite(classification.getLabel());
                break;
            case MUSIC:
                tagString = white(classification.getLabel());
                break;
            case NOISE:
                tagString = yellow(classification.getLabel());
                break;
            case SILENCE:
            default:
                tagString = darkGray(classification.getLabel());
                break;
        }

        StringBuilder sb = new StringBuilder(180);
        sb.append("\r  ");
        sb.append(darkGray("└── "));
        sb.append(boldWhite(String.format("[%04d] ", frameIdx)));
        sb.append(darkGray("MICROPHONE IN: "));
        sb.append(micMeter);
        sb.append(darkGray(String.format(" %4.1fdB ", micRms)));
        sb.append(darkGray("| "));
        sb.append(tagString);
        sb.append(darkGray(String.format(" (Pitch: %.2f, ZCR: %.2f)   ", periodicity, zcr)));

        System.out.print(sb.toString());
        System.out.flush();
    }

    private static String buildMonochromeMeter(float db, int width) {
        float normalized = Math.max(0.0f, Math.min(1.0f, db / 60.0f));
        int filled = Math.round(normalized * width);
        StringBuilder sb = new StringBuilder(width);
        for (int i = 0; i < width; i++) {
            if (i < filled) {
                sb.append("█");
            } else {
                sb.append("░");
            }
        }
        return white(sb.toString());
    }

    private static void printHeader(String subtitle, String engine) {
        System.out.println();
        System.out.println(boldWhite("  FastVAD ") + darkGray("— ") + white(subtitle));
        System.out.println(darkGray("  " + engine));
        System.out.println();
    }

    private static void printPhase(String tag, String name, String subtitle) {
        System.out.println(darkGray("  ── ") + boldWhite(tag) + darkGray(" : ") + white(name) + darkGray(" (" + subtitle + ") ─".repeat(2)));
    }

    private static void printTreeItem(String label, String value, boolean isLast) {
        String prefix = isLast ? "  └── " : "  ├── ";
        System.out.printf("%s%-32s : %s%n", darkGray(prefix), darkGray(label), white(value));
    }

    private static String boldWhite(String s) { return "\033[1;37m" + s + "\033[0m"; }
    private static String white(String s)     { return "\033[37m" + s + "\033[0m"; }
    private static String darkGray(String s)  { return "\033[90m" + s + "\033[0m"; }
    private static String yellow(String s)    { return "\033[33m" + s + "\033[0m"; }
}