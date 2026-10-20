package com.vayapedal.fastconformer;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

public class RecognizerEngine {

    static final String MODE_CONTINUOUS = "continuous";
    static final String MODE_ONESHOT = "oneshot";
    static final String MODE_MANUAL = "manual";
    private static final String TAG = "FastConformer";
    private static final int MAX_MANUAL_SAMPLES = SherpaBridge.SAMPLE_RATE * 60;

    interface Listener {
        void onSpeech();

        void onText(String text, String mode);

        void onStopped(String mode);

        void onError(String message);
    }

    private final Object lock = new Object();
    private SherpaBridge bridge;
    private volatile AudioRecord recorder;
    private Thread audioThread;
    private volatile boolean running;
    private volatile boolean notifyOnStop = true;
    private String mode = MODE_CONTINUOUS;
    private float[] manualSamples;
    private int manualCount;

    boolean isLoaded() {
        synchronized (lock) {
            return bridge != null;
        }
    }

    boolean isListening() {
        return running;
    }

    String currentMode() {
        synchronized (lock) {
            return mode;
        }
    }

    void load(String modelPath, String tokensPath, String vadPath) {
        stopQuiet();
        synchronized (lock) {
            releaseBridgeLocked();
            bridge = new SherpaBridge(modelPath, tokensPath, vadPath);
        }
    }

    void start(String mode, Listener listener) {
        Log.i(TAG, "[voz|audio] start modo=" + mode);
        if (!MODE_CONTINUOUS.equals(mode) && !MODE_ONESHOT.equals(mode) && !MODE_MANUAL.equals(mode)) {
            listener.onError("Modo no válido: " + mode);
            return;
        }
        synchronized (lock) {
            if (bridge == null) {
                Log.i(TAG, "[voz|error] el modelo no está cargado");
                listener.onError("El modelo no está cargado");
                return;
            }
            if (running || (audioThread != null && audioThread.isAlive())) {
                Log.i(TAG, "[voz|error] el reconocedor ya está escuchando");
                listener.onError("El reconocedor ya está escuchando");
                return;
            }
            this.mode = mode;
            bridge.resetVad();
            int minBuffer = AudioRecord.getMinBufferSize(
                SherpaBridge.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            );
            if (minBuffer <= 0) {
                Log.i(TAG, "[voz|error] minBuffer=" + minBuffer);
                listener.onError("El micrófono no está disponible");
                return;
            }
            int bufferSize = Math.max(minBuffer, SherpaBridge.WINDOW_SIZE * 4);
            recorder = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SherpaBridge.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            );
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "[voz|error] AudioRecord estado=" + recorder.getState());
                recorder.release();
                recorder = null;
                listener.onError("No se pudo abrir el micrófono");
                return;
            }
            manualSamples = MODE_MANUAL.equals(mode) ? new float[MAX_MANUAL_SAMPLES] : null;
            manualCount = 0;
            running = true;
            recorder.startRecording();
            Log.i(TAG, "[voz|audio] micrófono abierto, recordingState=" + recorder.getRecordingState()
                + " buffer=" + bufferSize);
            audioThread = new Thread(() -> capture(listener), "fastconformer-audio");
            audioThread.start();
        }
    }

    void stop() {
        halt(true);
    }

    private void stopQuiet() {
        halt(false);
    }

    private void halt(boolean notify) {
        AudioRecord current;
        Thread thread;
        synchronized (lock) {
            notifyOnStop = notify;
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
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void release() {
        stop();
        synchronized (lock) {
            releaseBridgeLocked();
        }
    }

    private void capture(Listener listener) {
        short[] pcm = new short[SherpaBridge.WINDOW_SIZE];
        float[] window = new float[SherpaBridge.WINDOW_SIZE];
        int filled = 0;
        boolean speechAnnounced = false;
        boolean manualOverflow = false;
        int windows = 0;
        int peak = 0;
        boolean firstWindow = true;
        long nextBeat = SystemClock.elapsedRealtime();
        String activeMode;
        synchronized (lock) {
            activeMode = mode;
        }
        try {
            Log.i(TAG, "[voz|audio] bucle de captura, modo=" + activeMode);
            while (running) {
                AudioRecord current = recorder;
                if (current == null) {
                    Log.i(TAG, "[voz|audio] recorder nulo, salgo");
                    break;
                }
                int read = current.read(pcm, 0, pcm.length);
                if (read <= 0 || !running) {
                    Log.i(TAG, "[voz|audio] read=" + read + " running=" + running);
                    break;
                }
                int windowPeak = 0;
                for (int sample = 0; sample < read; sample++) {
                    int abs = Math.abs(pcm[sample]);
                    if (abs > windowPeak) {
                        windowPeak = abs;
                    }
                }
                if (windowPeak > peak) {
                    peak = windowPeak;
                }
                windows++;
                long now = SystemClock.elapsedRealtime();
                if (firstWindow || now >= nextBeat) {
                    Log.i(TAG, "[voz|audio] ventanas=" + windows + " pico=" + peak);
                    peak = 0;
                    firstWindow = false;
                    nextBeat = now + 2000;
                }
                if (MODE_MANUAL.equals(activeMode)) {
                    manualOverflow = appendManual(pcm, read) || manualOverflow;
                    continue;
                }
                for (int i = 0; i < read && running; i++) {
                    window[filled++] = pcm[i] / 32768.0f;
                    if (filled < SherpaBridge.WINDOW_SIZE) {
                        continue;
                    }
                    filled = 0;
                    SherpaBridge currentBridge;
                    synchronized (lock) {
                        currentBridge = bridge;
                    }
                    if (currentBridge == null) {
                        Log.i(TAG, "[voz|modelo] puente nulo durante la captura");
                        running = false;
                        break;
                    }
                    currentBridge.accept(window);
                    if (!speechAnnounced && currentBridge.isSpeechDetected()) {
                        speechAnnounced = true;
                        Log.i(TAG, "[voz|audio] voz detectada");
                        listener.onSpeech();
                    }
                    FloatArray segment = takeSegment(currentBridge);
                    while (segment != null && running) {
                        Log.i(TAG, "[voz|audio] segmento muestras=" + segment.samples.length);
                        String text = currentBridge.transcribe(segment.samples);
                        Log.i(TAG, "[voz|texto] \"" + text + "\"");
                        speechAnnounced = false;
                        if (!text.isEmpty()) {
                            listener.onText(text, activeMode);
                        }
                        if (MODE_ONESHOT.equals(activeMode)) {
                            running = false;
                            break;
                        }
                        segment = takeSegment(currentBridge);
                    }
                }
            }
            Log.i(TAG, "[voz|audio] fin de captura, modo=" + activeMode);
            if (MODE_MANUAL.equals(activeMode)) {
                finishManual(listener, activeMode, manualOverflow);
            }
            if (notifyOnStop) {
                listener.onStopped(activeMode);
            }
        } catch (Exception e) {
            Log.e(TAG, "[voz|error] capture", e);
            listener.onError(e.getMessage() == null ? "Error de reconocimiento" : e.getMessage());
            if (notifyOnStop) {
                listener.onStopped(activeMode);
            }
        } finally {
            synchronized (lock) {
                running = false;
                if (recorder != null) {
                    recorder.release();
                    recorder = null;
                }
                audioThread = null;
                manualSamples = null;
                manualCount = 0;
            }
        }
    }

    private boolean appendManual(short[] pcm, int count) {
        boolean overflow = false;
        for (int i = 0; i < count; i++) {
            if (manualCount >= manualSamples.length) {
                overflow = true;
                break;
            }
            manualSamples[manualCount++] = pcm[i] / 32768.0f;
        }
        return overflow;
    }

    private void finishManual(Listener listener, String activeMode, boolean overflow) {
        if (overflow) {
            listener.onError("La grabación supera 60 segundos");
        }
        if (manualCount <= 0) {
            return;
        }
        float[] samples = new float[manualCount];
        System.arraycopy(manualSamples, 0, samples, 0, manualCount);
        SherpaBridge currentBridge;
        synchronized (lock) {
            currentBridge = bridge;
        }
        if (currentBridge == null) {
            return;
        }
        String text = currentBridge.transcribe(samples);
        Log.i(TAG, "[voz|texto] manual \"" + text + "\"");
        if (!text.isEmpty()) {
            listener.onText(text, activeMode);
        }
    }

    private FloatArray takeSegment(SherpaBridge currentBridge) {
        float[] samples = currentBridge.pollSamples();
        if (samples == null) {
            return null;
        }
        return new FloatArray(samples);
    }

    private void releaseBridgeLocked() {
        if (bridge != null) {
            bridge.release();
            bridge = null;
        }
    }

    private static final class FloatArray {
        final float[] samples;

        FloatArray(float[] samples) {
            this.samples = samples;
        }
    }
}
