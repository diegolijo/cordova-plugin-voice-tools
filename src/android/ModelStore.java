package com.vayapedal.fastconformer;

import android.content.Context;
import android.util.Log;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class ModelStore {

    static final String LANG_ES = "es";
    static final String LANG_EN = "en";
    private static final String ES_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-fast-conformer-ctc-es-1424-int8.tar.bz2";
    private static final String EN_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-fast-conformer-ctc-en-24500-int8.tar.bz2";
    static final String VAD_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx";

    private static final String TAG = "FastConformer";
    private static final int BUFFER = 64 * 1024;

    private final File root;
    private volatile boolean downloading;

    interface Progress {
        void onDownload(String state) throws Exception;

        void onError(String message);
    }

    ModelStore(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        root = new File(base, "fastconformer");
    }

    static String canonical(String lang) {
        if (lang == null) {
            return null;
        }
        String value = lang.trim().toLowerCase();
        if (LANG_ES.equals(value) || LANG_EN.equals(value)) {
            return value;
        }
        return null;
    }

    boolean isReady(String lang) {
        return getAcousticModel(lang) != null && getTokens(lang) != null && getVadModel() != null;
    }

    File getAcousticModel(String lang) {
        File dir = modelDir(lang);
        File int8 = new File(dir, "model.int8.onnx");
        if (int8.isFile() && int8.length() > 0) {
            return int8;
        }
        File plain = new File(dir, "model.onnx");
        if (plain.isFile() && plain.length() > 0) {
            return plain;
        }
        return null;
    }

    File getTokens(String lang) {
        File tokens = new File(modelDir(lang), "tokens.txt");
        return tokens.isFile() && tokens.length() > 0 ? tokens : null;
    }

    File getVadModel() {
        File vad = new File(root, "silero_vad.onnx");
        return vad.isFile() && vad.length() > 0 ? vad : null;
    }

    void download(String lang, Progress progress) {
        if (canonical(lang) == null) {
            progress.onError("Idioma no válido: " + lang);
            return;
        }
        if (downloading) {
            progress.onError("Ya hay una descarga en curso");
            return;
        }
        downloading = true;
        File dir = modelDir(lang);
        File archive = new File(root, lang + ".tar.bz2");
        try {
            if (isReady(lang)) {
                progress.onDownload("saved");
                return;
            }
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("No se pudo crear " + dir.getAbsolutePath());
            }
            progress.onDownload("start");
            if (getAcousticModel(lang) == null || getTokens(lang) == null) {
                downloadTo(modelUrl(lang), archive);
                progress.onDownload("extract");
                extractModel(archive, dir);
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
            }
            if (getVadModel() == null) {
                downloadTo(VAD_URL, new File(root, "silero_vad.onnx"));
            }
            if (!isReady(lang)) {
                throw new IOException("El modelo descargado no contiene model.int8.onnx y tokens.txt");
            }
            progress.onDownload("saved");
        } catch (Exception e) {
            Log.e(TAG, "download", e);
            progress.onError(e.getMessage() == null ? "Error de descarga" : e.getMessage());
        } finally {
            downloading = false;
        }
    }

    private File modelDir(String lang) {
        return new File(root, lang);
    }

    private static String modelUrl(String lang) {
        Map<String, String> urls = new HashMap<>();
        urls.put(LANG_ES, ES_URL);
        urls.put(LANG_EN, EN_URL);
        return urls.get(lang);
    }

    private void downloadTo(String url, File destination) throws IOException {
        File partial = new File(destination.getAbsolutePath() + ".part");
        HttpURLConnection connection = open(url);
        try (InputStream input = new BufferedInputStream(connection.getInputStream(), BUFFER);
             FileOutputStream output = new FileOutputStream(partial)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }
        if (destination.exists() && !destination.delete()) {
            throw new IOException("No se pudo reemplazar " + destination.getName());
        }
        if (!partial.renameTo(destination)) {
            throw new IOException("No se pudo guardar " + destination.getName());
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        String current = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(120000);
            connection.setRequestProperty("User-Agent", "cordova-plugin-fastconformer");
            connection.setRequestProperty("Accept", "application/octet-stream");
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("Redirección sin destino");
                }
                current = location;
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                throw new IOException("HTTP " + code);
            }
            return connection;
        }
        throw new IOException("Demasiadas redirecciones");
    }

    private void extractModel(File archiveFile, File destination) throws IOException {
        try (InputStream fileIn = new BufferedInputStream(new java.io.FileInputStream(archiveFile), BUFFER);
             BZip2CompressorInputStream bzip = new BZip2CompressorInputStream(fileIn);
             TarArchiveInputStream tar = new TarArchiveInputStream(bzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = new File(entry.getName()).getName();
                if (!"model.int8.onnx".equals(name) && !"model.onnx".equals(name) && !"tokens.txt".equals(name)) {
                    continue;
                }
                if (entry.getName().contains("..")) {
                    throw new IOException("Entrada no válida en el archivo: " + entry.getName());
                }
                File out = new File(destination, name);
                try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(out), BUFFER)) {
                    byte[] buffer = new byte[BUFFER];
                    int read;
                    while ((read = tar.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    static JSONObject downloadEvent(String state, String lang) throws Exception {
        return new JSONObject().put("type", "download").put("state", state).put("lang", lang);
    }
}
