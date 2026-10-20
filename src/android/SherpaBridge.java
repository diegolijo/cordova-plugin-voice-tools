package com.vayapedal.fastconformer;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

public class SherpaBridge {

    public static final int SAMPLE_RATE = 16000;
    public static final int WINDOW_SIZE = 512;

    private final OfflineRecognizer recognizer;
    private final Vad vad;

    public SherpaBridge(String modelPath, String tokensPath, String vadPath) {
        System.loadLibrary("onnxruntime");
        System.loadLibrary("sherpa-onnx-c-api");
        System.loadLibrary("sherpa-onnx-cxx-api");
        System.loadLibrary("sherpa-onnx-jni");

        OfflineNemoEncDecCtcModelConfig nemo = new OfflineNemoEncDecCtcModelConfig();
        nemo.setModel(modelPath);

        OfflineModelConfig modelConfig = new OfflineModelConfig();
        modelConfig.setNemo(nemo);
        modelConfig.setTokens(tokensPath);
        modelConfig.setNumThreads(2);
        modelConfig.setDebug(false);
        modelConfig.setProvider("cpu");

        OfflineRecognizerConfig recognizerConfig = new OfflineRecognizerConfig();
        recognizerConfig.setModelConfig(modelConfig);
        recognizerConfig.setDecodingMethod("greedy_search");
        recognizer = new OfflineRecognizer(null, recognizerConfig);

        SileroVadModelConfig silero = new SileroVadModelConfig();
        silero.setModel(vadPath);
        silero.setThreshold(0.5f);
        silero.setMinSilenceDuration(0.6f);
        silero.setMinSpeechDuration(0.25f);
        silero.setWindowSize(WINDOW_SIZE);
        silero.setMaxSpeechDuration(8.0f);

        VadModelConfig vadConfig = new VadModelConfig();
        vadConfig.setSileroVadModelConfig(silero);
        vadConfig.setSampleRate(SAMPLE_RATE);
        vadConfig.setNumThreads(1);
        vadConfig.setProvider("cpu");
        vadConfig.setDebug(false);
        vad = new Vad(null, vadConfig);
    }

    public void accept(float[] window) {
        vad.acceptWaveform(window);
    }

    public boolean isSpeechDetected() {
        return vad.isSpeechDetected();
    }

    public float[] pollSamples() {
        if (vad.empty()) {
            return null;
        }
        float[] copy = vad.front().getSamples().clone();
        vad.pop();
        return copy;
    }

    public void resetVad() {
        vad.reset();
    }

    public String transcribe(float[] samples) {
        if (samples.length == 0) {
            return "";
        }
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE);
            recognizer.decode(stream);
            return recognizer.getResult(stream).getText().trim();
        } finally {
            stream.release();
        }
    }

    public void release() {
        vad.release();
        recognizer.release();
    }
}
