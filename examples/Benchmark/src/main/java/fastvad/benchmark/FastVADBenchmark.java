package fastvad.benchmark;

import fastvad.FastVAD;
import fastvad.events.FastVADEvents;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class FastVADBenchmark {

    private FastVAD vad;
    private float[] speechFrame;
    private float[] silenceFrame;

    @Setup
    public void setup() {
        vad = new FastVAD(new FastVADEvents() {
            @Override public void onSpeechStart() {}
            @Override public void onSpeechEnd() {}
        });

        speechFrame = new float[160];
        silenceFrame = new float[160];

        for (int i = 0; i < 160; i++) {
            speechFrame[i] = (float) (Math.sin(2.0 * Math.PI * 440.0 * i / 16000.0) * 0.5);
            silenceFrame[i] = (float) ((Math.random() - 0.5) * 0.01);
        }
    }

    @TearDown
    public void tearDown() {
        vad.close();
    }

    @Benchmark
    public boolean benchmarkSpeechFrameProcessing() {
        return vad.processFrame(speechFrame, 28.0f, 10.0f);
    }

    @Benchmark
    public boolean benchmarkSilenceFrameProcessing() {
        return vad.processFrame(silenceFrame, 10.5f, 10.0f);
    }

    @Benchmark
    public boolean benchmarkAlternatingStream() {
        vad.processFrame(speechFrame, 28.0f, 10.0f);
        return vad.processFrame(silenceFrame, 10.5f, 10.0f);
    }
}