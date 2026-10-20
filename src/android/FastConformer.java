package com.vayapedal.fastconformer;

import android.Manifest;
import android.content.pm.PackageManager;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class FastConformer extends CordovaPlugin implements RecognizerEngine.Listener, KeywordEngine.Listener, TokenCapture.Listener {

    private static final String TAG = "FastConformer";
    private static final int REQUEST_RECORD_AUDIO = 4721;
    private static final String[] PERMISSIONS = {Manifest.permission.RECORD_AUDIO};

    private final Object subscriptionLock = new Object();
    private final List<JSONObject> pending = new ArrayList<>();

    private ModelStore modelStore;
    private RecognizerEngine engine;
    private KeywordEngine keywords;
    private TokenCapture tokens;
    private CallbackContext subscription;
    private String requestedLang = ModelStore.LANG_ES;
    private String loadedLang;
    private String activeTool = ModelStore.TOOL_DICTATION;
    private String pendingTool;
    private boolean pendingArm;
    private boolean pendingStart;
    private String pendingMode = RecognizerEngine.MODE_CONTINUOUS;
    private String pendingCapture;

    @Override
    protected void pluginInitialize() {
        modelStore = new ModelStore(cordova.getContext());
        engine = new RecognizerEngine();
        keywords = new KeywordEngine();
        tokens = new TokenCapture();
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        Log.i(TAG, "[voz|arranque] accion=" + action + " args=" + args);
        try {
            if ("subscribe".equals(action)) {
                subscribe(callbackContext);
                return true;
            }
            if ("unsubscribe".equals(action)) {
                unsubscribe();
                ack(callbackContext, action);
                return true;
            }
            if ("prepare".equals(action) || "use".equals(action)) {
                String tool = args.length() > 0 ? args.getString(0) : "";
                boolean arm = "use".equals(action);
                ack(callbackContext, action);
                cordova.getThreadPool().execute(() -> openTool(tool, arm));
                return true;
            }
            if ("keywords".equals(action)) {
                String tool = args.getString(0);
                String body = args.getString(1);
                ack(callbackContext, action);
                cordova.getThreadPool().execute(() -> saveKeywords(tool, body));
                return true;
            }
            if ("capture".equals(action)) {
                String tool = args.getString(0);
                ack(callbackContext, action);
                cordova.getThreadPool().execute(() -> capture(tool));
                return true;
            }
            if ("download".equals(action)) {
                String subject = args.length() > 0 ? args.optString(0, ModelStore.LANG_ES) : ModelStore.LANG_ES;
                ack(callbackContext, action);
                cordova.getThreadPool().execute(() -> downloadSubject(subject));
                return true;
            }
            if ("enable".equals(action)) {
                String lang = langFrom(args);
                ack(callbackContext, action);
                cordova.getThreadPool().execute(() -> enable(lang));
                return true;
            }
            if ("start".equals(action)) {
                String mode = args.length() > 0 ? args.optString(0, RecognizerEngine.MODE_CONTINUOUS) : RecognizerEngine.MODE_CONTINUOUS;
                ack(callbackContext, action);
                cordova.getThreadPool().execute(() -> startActive(mode));
                return true;
            }
            if ("stop".equals(action)) {
                ack(callbackContext, action);
                cordova.getThreadPool().execute(this::stopListening);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, action, e);
            callbackContext.error(e.getMessage());
            return true;
        }
        return false;
    }

    @Override
    public void onPause(boolean multitasking) {
        cordova.getThreadPool().execute(this::stopListening);
    }

    @Override
    public void onDestroy() {
        if (tokens != null) {
            tokens.stop();
        }
        if (keywords != null) {
            keywords.release();
        }
        if (engine != null) {
            engine.release();
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        handleRecordPermission(requestCode, grantResults);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        handleRecordPermission(requestCode, grantResults);
    }

    private void handleRecordPermission(int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_RECORD_AUDIO) {
            return;
        }
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Log.i(TAG, "[voz|modelo] permiso de micrófono=" + granted);
        if (!granted) {
            emitError("Permiso de micrófono denegado");
            return;
        }
        cordova.getThreadPool().execute(() -> {
            if (pendingCapture != null) {
                String tool = pendingCapture;
                pendingCapture = null;
                capture(tool);
                return;
            }
            if (pendingStart && pendingTool == null) {
                String mode = pendingMode;
                pendingStart = false;
                startActive(mode);
                return;
            }
            if (pendingTool != null) {
                String tool = pendingTool;
                boolean arm = pendingArm;
                pendingTool = null;
                pendingStart = false;
                openTool(tool, arm);
                return;
            }
            loadModel();
        });
    }

    @Override
    public void onSpeech() {
        try {
            emit(event("speech"));
        } catch (JSONException e) {
            emitError(e.getMessage());
        }
    }

    @Override
    public void onText(String text, String mode) {
        try {
            emit(event("text").put("text", text).put("mode", mode));
        } catch (JSONException e) {
            emitError(e.getMessage());
        }
    }

    @Override
    public void onStopped(String mode) {
        try {
            emit(event("stopped").put("mode", mode));
        } catch (JSONException e) {
            emitError(e.getMessage());
        }
    }

    @Override
    public void onError(String message) {
        emitError(message);
    }

    @Override
    public void onKeyword(String tool, String keyword) {
        try {
            emit(event("keyword").put("tool", tool).put("keyword", keyword));
        } catch (JSONException e) {
            emitError(e.getMessage());
        }
    }

    @Override
    public void onKeywordStopped(String tool) {
        try {
            emit(event("stopped").put("tool", tool));
        } catch (JSONException e) {
            emitError(e.getMessage());
        }
    }

    @Override
    public void onKeywordError(String message) {
        emitError(message);
    }

    private void subscribe(CallbackContext callbackContext) throws JSONException {
        synchronized (subscriptionLock) {
            subscription = callbackContext;
            PluginResult armed = new PluginResult(PluginResult.Status.NO_RESULT);
            armed.setKeepCallback(true);
            callbackContext.sendPluginResult(armed);
            for (JSONObject event : pending) {
                send(callbackContext, event);
            }
            pending.clear();
        }
    }

    private void unsubscribe() {
        stopListening();
        synchronized (subscriptionLock) {
            subscription = null;
            pending.clear();
        }
    }

    private void downloadSubject(String subject) {
        if (subject == null || subject.isEmpty()) {
            emitError("Herramienta no válida");
            return;
        }
        if (ModelStore.TOOL_WAKE.equals(subject) || ModelStore.TOOL_COMMANDS.equals(subject)) {
            modelStore.downloadTool(subject, progress(subject, null));
            return;
        }
        String lang = ModelStore.TOOL_DICTATION.equals(subject) ? ModelStore.LANG_ES : ModelStore.canonical(subject);
        if (lang == null) {
            emitError("Herramienta no válida: " + subject);
            return;
        }
        modelStore.download(lang, progress(ModelStore.TOOL_DICTATION, lang));
    }

    private ModelStore.Progress progress(String tool, String lang) {
        return new ModelStore.Progress() {
            @Override
            public void onDownload(String state) throws Exception {
                emit(ModelStore.downloadEvent(state, tool, lang));
            }

            @Override
            public void onError(String message) {
                emitError(message);
            }
        };
    }

    private void openTool(String tool, boolean arm) {
        if (!ModelStore.isTool(tool)) {
            emitError("Herramienta no válida: " + tool);
            return;
        }
        if (ModelStore.TOOL_DICTATION.equals(tool)) {
            activeTool = tool;
            if (arm) {
                stopKeywords();
            }
            enable(requestedLang == null ? ModelStore.LANG_ES : requestedLang);
            return;
        }
        if (!modelStore.isModelReady(tool)) {
            emitError("Descarga " + tool + " antes de usarlo");
            return;
        }
        if (!modelStore.isKwsReady(tool)) {
            emitError("Pasa la lista de " + tool + " antes de usarlo");
            return;
        }
        if (!hasRecordPermission()) {
            pendingTool = tool;
            pendingArm = arm;
            pendingStart = false;
            cordova.getActivity().runOnUiThread(() ->
                PermissionHelper.requestPermissions(this, REQUEST_RECORD_AUDIO, PERMISSIONS));
            return;
        }
        try {
            if (!keywords.isLoaded(tool)) {
                keywords.load(
                    tool,
                    modelStore.kwsEncoder(tool),
                    modelStore.kwsDecoder(tool),
                    modelStore.kwsJoiner(tool),
                    modelStore.kwsTokens(tool),
                    modelStore.kwsKeywords(tool),
                    ModelStore.TOOL_WAKE.equals(tool) ? 0.15f : 0.25f
                );
            }
            if (arm) {
                stopKeywords();
                if (engine.isListening()) {
                    engine.stop();
                }
                keywords.arm(tool);
                activeTool = tool;
                emitReady(tool, null);
            } else {
                if (keywords.armed() == null) {
                    keywords.arm(tool);
                    activeTool = tool;
                }
                emit(event("prepared").put("tool", tool));
            }
        } catch (Exception e) {
            Log.e(TAG, "openTool", e);
            emitError(e.getMessage() == null ? "No se pudo cargar el detector" : e.getMessage());
        }
    }

    private void startActive(String mode) {
        if (!hasRecordPermission()) {
            pendingStart = true;
            pendingTool = null;
            pendingMode = mode == null || mode.isEmpty() ? RecognizerEngine.MODE_CONTINUOUS : mode;
            cordova.getActivity().runOnUiThread(() ->
                PermissionHelper.requestPermissions(this, REQUEST_RECORD_AUDIO, PERMISSIONS));
            return;
        }
        if (ModelStore.TOOL_WAKE.equals(activeTool) || ModelStore.TOOL_COMMANDS.equals(activeTool)) {
            if (engine.isListening()) {
                engine.stop();
            }
            keywords.start(this);
            return;
        }
        if (keywords.isListening()) {
            keywords.stop();
        }
        engine.start(mode == null || mode.isEmpty() ? RecognizerEngine.MODE_CONTINUOUS : mode, this);
    }

    private void stopListening() {
        if (tokens != null && tokens.isListening()) {
            tokens.stop();
        }
        stopKeywords();
        if (engine != null && engine.isListening()) {
            engine.stop();
        }
    }

    private void saveKeywords(String tool, String body) {
        try {
            modelStore.setKeywords(tool, body);
            keywords.replaceKeywords(tool, body);
            emit(event("keywords").put("tool", tool));
        } catch (Exception e) {
            Log.e(TAG, "keywords", e);
            emitError(e.getMessage() == null ? "No se pudo guardar la lista" : e.getMessage());
        }
    }

    private void capture(String tool) {
        if (!ModelStore.TOOL_WAKE.equals(tool) && !ModelStore.TOOL_COMMANDS.equals(tool)) {
            emitError("Herramienta no válida: " + tool);
            return;
        }
        if (!modelStore.isModelReady(tool)) {
            emitError("Descarga " + tool + " antes de escuchar una palabra");
            return;
        }
        if (!hasRecordPermission()) {
            pendingCapture = tool;
            cordova.getActivity().runOnUiThread(() ->
                PermissionHelper.requestPermissions(this, REQUEST_RECORD_AUDIO, PERMISSIONS));
            return;
        }
        stopListening();
        tokens.start(
            tool,
            modelStore.kwsEncoder(tool),
            modelStore.kwsDecoder(tool),
            modelStore.kwsJoiner(tool),
            modelStore.kwsTokens(tool),
            this
        );
    }

    @Override
    public void onTokens(String tool, String heard) {
        try {
            emit(event("tokens").put("tool", tool).put("tokens", heard == null ? "" : heard));
        } catch (JSONException e) {
            emitError(e.getMessage());
        }
    }

    @Override
    public void onCaptureError(String message) {
        emitError(message);
    }

    private void stopKeywords() {
        if (keywords != null && keywords.isListening()) {
            keywords.stop();
        }
    }

    private void enable(String lang) {
        if (lang == null) {
            emitError("Idioma no válido");
            return;
        }
        if (!modelStore.isReady(lang)) {
            emitError("Descarga el modelo " + lang + " antes de activar el reconocedor");
            return;
        }
        requestedLang = lang;
        if (lang.equals(loadedLang) && engine.isLoaded()) {
            emitReady(ModelStore.TOOL_DICTATION, lang);
            return;
        }
        if (!hasRecordPermission()) {
            Log.i(TAG, "[voz|modelo] pido permiso de micrófono");
            cordova.getActivity().runOnUiThread(() ->
                PermissionHelper.requestPermissions(this, REQUEST_RECORD_AUDIO, PERMISSIONS));
            return;
        }
        swapModel(lang);
    }

    private void loadModel() {
        swapModel(requestedLang);
    }

    private void swapModel(String lang) {
        if (!modelStore.isReady(lang)) {
            emitError("Descarga el modelo " + lang + " antes de activar el reconocedor");
            return;
        }
        boolean resume = engine.isListening();
        String mode = engine.currentMode();
        try {
            String modelPath = modelStore.getAcousticModel(lang).getAbsolutePath();
            String tokensPath = modelStore.getTokens(lang).getAbsolutePath();
            String vadPath = modelStore.getVadModel().getAbsolutePath();
            Log.i(TAG, "[voz|modelo] cargo modelo=" + modelPath
                + " tokens=" + tokensPath
                + " vad=" + vadPath
                + " bytesModelo=" + modelStore.getAcousticModel(lang).length()
                + " bytesTokens=" + modelStore.getTokens(lang).length()
                + " bytesVad=" + modelStore.getVadModel().length());
            engine.load(modelPath, tokensPath, vadPath);
            loadedLang = lang;
            emitReady(ModelStore.TOOL_DICTATION, lang);
            if (resume) {
                engine.start(mode, this);
            }
        } catch (Exception e) {
            loadedLang = null;
            Log.e(TAG, "enable", e);
            emitError(e.getMessage() == null ? "No se pudo cargar el modelo" : e.getMessage());
        }
    }

    private void emitReady(String tool, String lang) {
        try {
            JSONObject ready = event("ready").put("tool", tool);
            if (lang != null) {
                ready.put("lang", lang);
            }
            emit(ready);
        } catch (JSONException e) {
            emitError(e.getMessage());
        }
    }

    private static String langFrom(JSONArray args) throws JSONException {
        if (args == null || args.length() == 0) {
            return ModelStore.LANG_ES;
        }
        String raw = args.optString(0, "");
        if (raw.isEmpty()) {
            return ModelStore.LANG_ES;
        }
        return ModelStore.canonical(raw);
    }

    private boolean hasRecordPermission() {
        return PermissionHelper.hasPermission(this, Manifest.permission.RECORD_AUDIO);
    }

    private void ack(CallbackContext callbackContext, String action) throws JSONException {
        callbackContext.success(new JSONObject().put("type", "ack").put("action", action));
    }

    private void emitError(String message) {
        try {
            emit(event("error").put("message", message == null ? "Error" : message));
        } catch (JSONException e) {
            Log.e(TAG, "emitError", e);
        }
    }

    private void emit(JSONObject event) {
        String type = event.optString("type", "?");
        Log.i(TAG, "[voz|" + origenDe(type) + "] emit " + event + (subscription == null ? " en cola" : ""));
        synchronized (subscriptionLock) {
            if (subscription == null) {
                if (pending.size() >= 32) {
                    pending.remove(0);
                }
                pending.add(event);
                return;
            }
            send(subscription, event);
        }
    }

    private void send(CallbackContext callbackContext, JSONObject event) {
        PluginResult result = new PluginResult(PluginResult.Status.OK, event);
        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);
    }

    private static JSONObject event(String type) throws JSONException {
        return new JSONObject().put("type", type);
    }

    private static String origenDe(String type) {
        if ("error".equals(type)) {
            return "error";
        }
        if ("download".equals(type)) {
            return "descarga";
        }
        if ("ready".equals(type) || "prepared".equals(type)) {
            return "modelo";
        }
        if ("text".equals(type) || "keyword".equals(type) || "tokens".equals(type)) {
            return "texto";
        }
        if ("keywords".equals(type)) {
            return "modelo";
        }
        return "audio";
    }
}
