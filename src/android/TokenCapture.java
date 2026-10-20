package com.vayapedal.fastconformer;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;

/**
 * Decodifica una frase corta con el mismo transducer del detector.
 * En wake los tokens son fonemas. En commands son las piezas de ese modelo.
 */
public class TokenCapture {

    interface Listener {
        void onTokens(String tool, String tokens);

        void onCaptureError(String message);
    }

    private static final String TAG = "FastConformer";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK = 1600;
    private static final long MAX_MS = 4000;

    private final Object lock = new Object();
    private volatile boolean running;
    private AudioRecord recorder;
    private Thread thread;

    boolean isListening() {
        return running;
    }

    void start(String tool, File encoder, File decoder, File joiner, File tokens, Listener listener) {
        synchronized (lock) {
            if (running) {
                listener.onCaptureError("El reconocedor ya está escuchando");
                return;
            }
            running = true;
        }
        thread = new Thread(() -> run(tool, encoder, decoder, joiner, tokens, listener), "voice-tools-tokens");
        thread.start();
    }

    void stop() {
        AudioRecord current;
        Thread worker;
        synchronized (lock) {
            running = false;
            current = recorder;
            worker = thread;
        }
        if (current != null) {
            try {
                current.stop();
            } catch (IllegalStateException ignored) {
                Log.d(TAG, "AudioRecord ya estaba parado");
            }
        }
        if (worker != null && worker != Thread.currentThread()) {
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run(String tool, File encoder, File decoder, File joiner, File tokens, Listener listener) {
        OnlineRecognizer recognizer = null;
        OnlineStream stream = null;
        try {
            recognizer = open(encoder, decoder, joiner, tokens);
            stream = recognizer.createStream("");
            AudioRecord audio = openMic();
            synchronized (lock) {
                recorder = audio;
            }
            audio.startRecording();
            Log.i(TAG, "[voz|audio] captura de tokens tool=" + tool);
            short[] pcm = new short[CHUNK];
            long deadline = SystemClock.elapsedRealtime() + MAX_MS;
            while (running && SystemClock.elapsedRealtime() < deadline) {
                int read = audio.read(pcm, 0, pcm.length);
                if (read <= 0 || !running) {
                    break;
                }
                float[] samples = new float[read];
                for (int i = 0; i < read; i++) {
                    samples[i] = pcm[i] / 32768.0f;
                }
                stream.acceptWaveform(samples, SAMPLE_RATE);
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream);
                }
                if (recognizer.isEndpoint(stream)) {
                    break;
                }
            }
            stream.inputFinished();
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream);
            }
            String heard = join(recognizer.getResult(stream));
            Log.i(TAG, "[voz|texto] tokens tool=" + tool + " \"" + heard + "\"");
            listener.onTokens(tool, heard);
        } catch (Exception e) {
            Log.e(TAG, "[voz|error] captura de tokens", e);
            listener.onCaptureError(e.getMessage() == null ? "No se pudieron leer los tokens" : e.getMessage());
        } finally {
            if (stream != null) {
                stream.release();
            }
            if (recognizer != null) {
                recognizer.release();
            }
            synchronized (lock) {
                running = false;
                if (recorder != null) {
                    recorder.release();
                    recorder = null;
                }
                thread = null;
            }
        }
    }

    private static OnlineRecognizer open(File encoder, File decoder, File joiner, File tokens) {
        System.loadLibrary("onnxruntime");
        System.loadLibrary("sherpa-onnx-c-api");
        System.loadLibrary("sherpa-onnx-cxx-api");
        System.loadLibrary("sherpa-onnx-jni");

        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(encoder.getAbsolutePath());
        transducer.setDecoder(decoder.getAbsolutePath());
        transducer.setJoiner(joiner.getAbsolutePath());

        OnlineModelConfig model = new OnlineModelConfig();
        model.setTransducer(transducer);
        model.setTokens(tokens.getAbsolutePath());
        model.setNumThreads(2);
        model.setProvider("cpu");
        model.setModelType("zipformer2");

        FeatureConfig feat = new FeatureConfig();
        feat.setSampleRate(SAMPLE_RATE);
        feat.setFeatureDim(80);

        EndpointConfig endpoint = new EndpointConfig();
        endpoint.setRule1(new EndpointRule(false, 0.8f, 0.0f));
        endpoint.setRule2(new EndpointRule(true, 0.4f, 0.0f));
        endpoint.setRule3(new EndpointRule(false, 0.0f, 5.0f));

        OnlineRecognizerConfig config = new OnlineRecognizerConfig();
        config.setFeatConfig(feat);
        config.setModelConfig(model);
        config.setEndpointConfig(endpoint);
        config.setEnableEndpoint(true);
        config.setDecodingMethod("greedy_search");
        config.setMaxActivePaths(4);
        return new OnlineRecognizer(null, config);
    }

    private static AudioRecord openMic() {
        int minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBuffer <= 0) {
            throw new IllegalStateException("El micrófono no está disponible");
        }
        AudioRecord audio = new AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            Math.max(minBuffer, CHUNK * 4)
        );
        if (audio.getState() != AudioRecord.STATE_INITIALIZED) {
            audio.release();
            throw new IllegalStateException("No se pudo abrir el micrófono");
        }
        return audio;
    }

    private static String join(OnlineRecognizerResult result) {
        String[] tokens = result.getTokens();
        if (tokens == null || tokens.length == 0) {
            String text = result.getText();
            return text == null ? "" : text.trim();
        }
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            if (token == null || token.isEmpty() || "<blk>".equals(token) || "<sos/eos>".equals(token)) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(token);
        }
        return out.toString();
    }
}
