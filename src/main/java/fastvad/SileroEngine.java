package fastvad;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Embedded Silero-VAD v5 Neural Engine (ONNX Runtime).
 * Processes 32ms frames (512 samples @ 16 kHz) with recurrent LSTM/Attention state tensors.
 */
public final class SileroEngine implements AutoCloseable {

    private final OrtEnvironment env;
    private final OrtSession session;

    // Recurrent Hidden & Cell states: [2, 1, 128] float tensors
    private float[][][] state = new float[2][1][128];
    private final long[] srArray = new long[]{16000L};

    public SileroEngine() {
        try {
            this.env = OrtEnvironment.getEnvironment();
            File tempModel = File.createTempFile("silero_vad_", ".onnx");
            tempModel.deleteOnExit();

            try (InputStream in = SileroEngine.class.getResourceAsStream("/models/silero_vad.onnx")) {
                if (in == null) {
                    throw new IllegalStateException("Embedded silero_vad.onnx not found in classpath (/models/silero_vad.onnx)");
                }
                Files.copy(in, tempModel.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
            opts.setInterOpNumThreads(1);
            opts.setIntraOpNumThreads(1);
            this.session = env.createSession(tempModel.getAbsolutePath(), opts);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Silero-VAD Neural Engine: " + e.getMessage(), e);
        }
    }

    /**
     * Runs forward inference on 512 samples (32ms @ 16kHz) and updates recurrent state.
     *
     * @param window512 512 normalized audio samples in [-1.0, 1.0]
     * @return speech probability in [0.0, 1.0]
     */
    public synchronized float infer(float[] window512) {
        try {
            float[][] inputData = new float[1][window512.length];
            System.arraycopy(window512, 0, inputData[0], 0, window512.length);

            OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputData);
            OnnxTensor stateTensor = OnnxTensor.createTensor(env, state);
            OnnxTensor srTensor    = OnnxTensor.createTensor(env, srArray);

            Map<String, OnnxTensor> inputs = new HashMap<>(3);
            inputs.put("input", inputTensor);
            inputs.put("state", stateTensor);
            inputs.put("sr", srTensor);

            try (OrtSession.Result result = session.run(inputs)) {
                float[][] output = (float[][]) result.get(0).getValue();
                float[][][] nextState = (float[][][]) result.get(1).getValue();

                // Update recurrent internal state
                this.state = nextState;

                return output[0][0];
            } finally {
                inputTensor.close();
                stateTensor.close();
                srTensor.close();
            }
        } catch (Exception e) {
            return 0.0f;
        }
    }

    public synchronized void resetState() {
        this.state = new float[2][1][128];
    }

    @Override
    public synchronized void close() {
        try {
            if (session != null) session.close();
            if (env != null) env.close();
        } catch (Exception ignored) {
        }
    }
}