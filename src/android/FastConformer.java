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

public class FastConformer extends CordovaPlugin implements RecognizerEngine.Listener {

    private static final String TAG = "FastConformer";
    private static final int REQUEST_RECORD_AUDIO = 4721;
    private static final String[] PERMISSIONS = {Manifest.permission.RECORD_AUDIO};

    private final Object subscriptionLock = new Object();
    private final List<JSONObject> pending = new ArrayList<>();

    private ModelStore modelStore;
    private RecognizerEngine engine;
    private CallbackContext subscription;
    private String requestedLang = ModelStore.LANG_ES;
    private String loadedLang;

    @Override
    protected void pluginInitialize() {
        modelStore = new ModelStore(cordova.getContext());
        engine = new RecognizerEngine();
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
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
            if ("download".equals(action)) {
                String lang = langFrom(args);
                ack(callbackContext, action);
                cordova.getThreadPool().execute(() -> download(lang));
                return true;
            }
            if ("enable".equals(action)) {
                String lang = langFrom(args);
                ack(callbackContext, action);
                cordova.getThreadPool().execute(() -> enable(lang));
                return true;
            }
            if ("start".equals(action)) {
                String mode = args.length() > 0 ? args.getString(0) : RecognizerEngine.MODE_CONTINUOUS;
                ack(callbackContext, action);
                cordova.getThreadPool().execute(() -> engine.start(mode, this));
                return true;
            }
            if ("stop".equals(action)) {
                ack(callbackContext, action);
                cordova.getThreadPool().execute(engine::stop);
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
        if (engine != null && engine.isListening()) {
            cordova.getThreadPool().execute(engine::stop);
        }
    }

    @Override
    public void onDestroy() {
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
        if (!granted) {
            emitError("Permiso de micrófono denegado");
            return;
        }
        cordova.getThreadPool().execute(this::loadModel);
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
        if (engine != null && engine.isListening()) {
            engine.stop();
        }
        synchronized (subscriptionLock) {
            subscription = null;
            pending.clear();
        }
    }

    private void download(String lang) {
        if (lang == null) {
            emitError("Idioma no válido");
            return;
        }
        modelStore.download(lang, new ModelStore.Progress() {
            @Override
            public void onDownload(String state) throws Exception {
                emit(ModelStore.downloadEvent(state, lang));
            }

            @Override
            public void onError(String message) {
                emitError(message);
            }
        });
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
            emitReady(lang);
            return;
        }
        if (!hasRecordPermission()) {
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
            engine.load(
                modelStore.getAcousticModel(lang).getAbsolutePath(),
                modelStore.getTokens(lang).getAbsolutePath(),
                modelStore.getVadModel().getAbsolutePath()
            );
            loadedLang = lang;
            emitReady(lang);
            if (resume) {
                engine.start(mode, this);
            }
        } catch (Exception e) {
            loadedLang = null;
            Log.e(TAG, "enable", e);
            emitError(e.getMessage() == null ? "No se pudo cargar el modelo" : e.getMessage());
        }
    }

    private void emitReady(String lang) {
        try {
            emit(event("ready").put("lang", lang));
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
}
