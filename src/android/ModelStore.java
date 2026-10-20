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
    static final String TOOL_WAKE = "wake";
    static final String TOOL_COMMANDS = "commands";
    static final String TOOL_DICTATION = "dictation";
    private static final String WAKE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2";
    private static final String COMMANDS_URL =
        "https://huggingface.co/jguerrisi/sherpa-onnx-kws-zipformer-es-3M-2026-08-08/resolve/main/";
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
        Log.i(TAG, "[voz|descarga] download lang=" + lang + " listo=" + isReady(lang));
        logFiles(lang);
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
                Log.i(TAG, "[voz|descarga] estado=saved ya estaba en disco");
                progress.onDownload("saved");
                return;
            }
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("No se pudo crear " + dir.getAbsolutePath());
            }
            Log.i(TAG, "[voz|descarga] estado=start");
            progress.onDownload("start");
            if (getAcousticModel(lang) == null || getTokens(lang) == null) {
                downloadTo(modelUrl(lang), archive);
                Log.i(TAG, "[voz|descarga] estado=extract");
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
            Log.i(TAG, "[voz|descarga] estado=saved");
            logFiles(lang);
            progress.onDownload("saved");
        } catch (Exception e) {
            Log.e(TAG, "[voz|error] download", e);
            progress.onError(e.getMessage() == null ? "Error de descarga" : e.getMessage());
        } finally {
            downloading = false;
        }
    }

    private void logFiles(String lang) {
        File model = canonical(lang) == null ? null : getAcousticModel(lang);
        File tokens = canonical(lang) == null ? null : getTokens(lang);
        File vad = getVadModel();
        Log.i(TAG, "[voz|descarga] modelo=" + describe(model)
            + " tokens=" + describe(tokens)
            + " vad=" + describe(vad));
    }

    private static String describe(File file) {
        if (file == null) {
            return "falta";
        }
        return file.getAbsolutePath() + " (" + file.length() + " bytes)";
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

    static boolean isTool(String value) {
        return TOOL_WAKE.equals(value) || TOOL_COMMANDS.equals(value) || TOOL_DICTATION.equals(value);
    }

    boolean isKwsReady(String tool) {
        return kwsFile(tool, "encoder.int8.onnx") != null
            && kwsFile(tool, "decoder.onnx") != null
            && kwsFile(tool, "joiner.int8.onnx") != null
            && kwsFile(tool, "tokens.txt") != null
            && kwsFile(tool, "keywords.txt") != null;
    }

    File kwsEncoder(String tool) {
        return kwsFile(tool, "encoder.int8.onnx");
    }

    File kwsDecoder(String tool) {
        return kwsFile(tool, "decoder.onnx");
    }

    File kwsJoiner(String tool) {
        return kwsFile(tool, "joiner.int8.onnx");
    }

    File kwsTokens(String tool) {
        return kwsFile(tool, "tokens.txt");
    }

    File kwsKeywords(String tool) {
        return kwsFile(tool, "keywords.txt");
    }

    void downloadTool(String tool, Progress progress) {
        if (TOOL_DICTATION.equals(tool)) {
            download(LANG_ES, progress);
            return;
        }
        if (!TOOL_WAKE.equals(tool) && !TOOL_COMMANDS.equals(tool)) {
            progress.onError("Herramienta no válida: " + tool);
            return;
        }
        if (downloading) {
            progress.onError("Ya hay una descarga en curso");
            return;
        }
        downloading = true;
        File dir = new File(root, tool);
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("No se pudo crear " + dir.getAbsolutePath());
            }
            writeKeywords(tool, dir);
            if (isKwsReady(tool)) {
                progress.onDownload("saved");
                return;
            }
            progress.onDownload("start");
            if (TOOL_WAKE.equals(tool)) {
                File archive = new File(root, "wake.tar.bz2");
                downloadTo(WAKE_URL, archive);
                progress.onDownload("extract");
                extractNamed(archive, dir, wakeNames());
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
            } else {
                downloadTo(COMMANDS_URL + "encoder-epoch-20-avg-2-chunk-16-left-64.int8.onnx", new File(dir, "encoder.int8.onnx"));
                downloadTo(COMMANDS_URL + "decoder-epoch-20-avg-2-chunk-16-left-64.int8.onnx", new File(dir, "decoder.onnx"));
                downloadTo(COMMANDS_URL + "joiner-epoch-20-avg-2-chunk-16-left-64.int8.onnx", new File(dir, "joiner.int8.onnx"));
                downloadTo(COMMANDS_URL + "tokens.txt", new File(dir, "tokens.txt"));
            }
            writeKeywords(tool, dir);
            if (!isKwsReady(tool)) {
                throw new IOException("El modelo de " + tool + " no se ha guardado");
            }
            progress.onDownload("saved");
        } catch (Exception e) {
            Log.e(TAG, "[voz|error] download " + tool, e);
            progress.onError(e.getMessage() == null ? "Error de descarga" : e.getMessage());
        } finally {
            downloading = false;
        }
    }

    private File kwsFile(String tool, String name) {
        File file = new File(new File(root, tool), name);
        return file.isFile() && file.length() > 0 ? file : null;
    }

    private static void writeKeywords(String tool, File dir) throws IOException {
        String body = TOOL_WAKE.equals(tool) ? KeywordLists.WAKE : KeywordLists.COMMANDS;
        File out = new File(dir, "keywords.txt");
        try (FileOutputStream output = new FileOutputStream(out)) {
            output.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static Map<String, String> wakeNames() {
        Map<String, String> names = new HashMap<>();
        names.put("encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx", "encoder.int8.onnx");
        names.put("decoder-epoch-13-avg-2-chunk-8-left-64.onnx", "decoder.onnx");
        names.put("joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx", "joiner.int8.onnx");
        names.put("tokens.txt", "tokens.txt");
        return names;
    }

    private void extractNamed(File archiveFile, File destination, Map<String, String> names) throws IOException {
        try (InputStream fileIn = new BufferedInputStream(new java.io.FileInputStream(archiveFile), BUFFER);
             BZip2CompressorInputStream bzip = new BZip2CompressorInputStream(fileIn);
             TarArchiveInputStream tar = new TarArchiveInputStream(bzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = new File(entry.getName()).getName();
                String stored = names.get(name);
                if (stored == null) {
                    continue;
                }
                if (entry.getName().contains("..")) {
                    throw new IOException("Entrada no válida en el archivo: " + entry.getName());
                }
                File out = new File(destination, stored);
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

    static JSONObject downloadEvent(String state, String tool, String lang) throws Exception {
        JSONObject event = new JSONObject().put("type", "download").put("state", state).put("tool", tool);
        if (lang != null) {
            event.put("lang", lang);
        }
        return event;
    }
}
