package com.vayapedal.fastconformer;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class KeywordEngine {

    interface Listener {
        void onKeyword(String tool, String keyword);

        void onKeywordStopped(String tool);

        void onKeywordError(String message);
    }

    private static final String TAG = "FastConformer";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK = 1600;
    private static final long REPEAT_GAP_MS = 700;

    private final Object lock = new Object();
    private final Map<String, Slot> slots = new HashMap<>();

    private volatile AudioRecord recorder;
    private Thread audioThread;
    private volatile boolean running;
    private String armed;

    boolean isLoaded(String tool) {
        synchronized (lock) {
            return slots.containsKey(tool);
        }
    }

    boolean isListening() {
        return running;
    }

    String armed() {
        synchronized (lock) {
            return armed;
        }
    }

    void load(String tool, File encoder, File decoder, File joiner, File tokens, File keywords, float threshold) {
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

        KeywordSpotterConfig config = new KeywordSpotterConfig();
        config.setFeatConfig(feat);
        config.setModelConfig(model);
        config.setMaxActivePaths(4);
        config.setKeywordsFile(keywords.getAbsolutePath());
        config.setKeywordsScore(1.5f);
        config.setKeywordsThreshold(threshold);
        config.setNumTrailingBlanks(1);

        KeywordSpotter spotter = new KeywordSpotter(null, config);
        OnlineStream stream = spotter.createStream("");
        synchronized (lock) {
            Slot previous = slots.remove(tool);
            slots.put(tool, new Slot(spotter, stream));
            if (armed == null) {
                armed = tool;
            }
            if (previous != null) {
                previous.release();
            }
        }
        Log.i(TAG, "[voz|modelo] detector cargado tool=" + tool);
    }

    void replaceKeywords(String tool, String body) {
        synchronized (lock) {
            Slot slot = slots.get(tool);
            if (slot != null) {
                slot.replaceKeywords(body);
            }
        }
    }

    void arm(String tool) {
        synchronized (lock) {
            if (!slots.containsKey(tool)) {
                throw new IllegalStateException("El detector " + tool + " no está cargado");
            }
            armed = tool;
        }
    }

    void start(Listener listener) {
        String tool;
        synchronized (lock) {
            tool = armed;
            if (tool == null || !slots.containsKey(tool)) {
                listener.onKeywordError("El detector no está cargado");
                return;
            }
            if (running || (audioThread != null && audioThread.isAlive())) {
                listener.onKeywordError("El reconocedor ya está escuchando");
                return;
            }
            int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            );
            if (minBuffer <= 0) {
                listener.onKeywordError("El micrófono no está disponible");
                return;
            }
            recorder = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuffer, CHUNK * 4)
            );
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release();
                recorder = null;
                listener.onKeywordError("No se pudo abrir el micrófono");
                return;
            }
            running = true;
            recorder.startRecording();
            audioThread = new Thread(() -> capture(tool, listener), "voice-tools-kws");
            audioThread.start();
        }
        Log.i(TAG, "[voz|audio] detector escuchando tool=" + tool);
    }

    void stop() {
        halt(true);
    }

    void release() {
        stop();
        synchronized (lock) {
            for (Slot slot : slots.values()) {
                slot.release();
            }
            slots.clear();
            armed = null;
        }
    }

    private void halt(boolean join) {
        AudioRecord current;
        Thread thread;
        synchronized (lock) {
            running = false;
            current = recorder;
            thread = audioThread;
        }
        if (current != null) {
            try {
                current.stop();
            } catch (IllegalStateException ignored) {
                Log.d(TAG, "AudioRecord ya estaba parado");
            }
        }
        if (join && thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void capture(String tool, Listener listener) {
        short[] pcm = new short[CHUNK];
        float[] samples = new float[CHUNK];
        String last = "";
        long lastAt = 0;
        try {
            while (running) {
                AudioRecord current = recorder;
                if (current == null) {
                    break;
                }
                int read = current.read(pcm, 0, pcm.length);
                if (read <= 0 || !running) {
                    break;
                }
                for (int i = 0; i < read; i++) {
                    samples[i] = pcm[i] / 32768.0f;
                }
                Slot slot;
                synchronized (lock) {
                    slot = slots.get(tool);
                }
                if (slot == null) {
                    break;
                }
                String keyword = slot.accept(samples, read);
                if (keyword.isEmpty()) {
                    continue;
                }
                long now = SystemClock.elapsedRealtime();
                if (keyword.equals(last) && now - lastAt < REPEAT_GAP_MS) {
                    continue;
                }
                last = keyword;
                lastAt = now;
                Log.i(TAG, "[voz|comando] tool=" + tool + " keyword=" + keyword);
                listener.onKeyword(tool, keyword);
            }
        } catch (Exception e) {
            Log.e(TAG, "[voz|error] detector", e);
            listener.onKeywordError(e.getMessage() == null ? "Error del detector" : e.getMessage());
        } finally {
            synchronized (lock) {
                running = false;
                if (recorder != null) {
                    recorder.release();
                    recorder = null;
                }
                audioThread = null;
            }
            listener.onKeywordStopped(tool);
        }
    }

    private static final class Slot {
        private final KeywordSpotter spotter;
        private OnlineStream stream;

        Slot(KeywordSpotter spotter, OnlineStream stream) {
            this.spotter = spotter;
            this.stream = stream;
        }

        synchronized void replaceKeywords(String body) {
            OnlineStream next = spotter.createStream(body);
            OnlineStream previous = stream;
            stream = next;
            previous.release();
        }

        synchronized String accept(float[] samples, int length) {
            float[] chunk = samples;
            if (length != samples.length) {
                chunk = new float[length];
                System.arraycopy(samples, 0, chunk, 0, length);
            }
            stream.acceptWaveform(chunk, SAMPLE_RATE);
            while (spotter.isReady(stream)) {
                spotter.decode(stream);
            }
            String raw = spotter.getResult(stream).getKeyword();
            if (raw == null || raw.isEmpty()) {
                return "";
            }
            spotter.reset(stream);
            int at = raw.lastIndexOf('@');
            return (at >= 0 ? raw.substring(at + 1) : raw).trim();
        }

        void release() {
            stream.release();
            spotter.release();
        }
    }
}
