# cordova-plugin-voice-tools

Suite de voz **offline** para Cordova Android, sobre [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8. Tres herramientas, cada una se elige aparte:

- `wake`. Detector de palabras (Zipformer inglés, unos 5 MB). Mira trozos cortos y no espera al silencio. La lista que trae es la palabra `KITT`.
- `commands`. El mismo detector, con el Zipformer en español de la comunidad. La orden es una frase corta de la lista (`APAGA`, `SIGUIENTE`, `LOS_CUARENTA`, `METAL`…), no un texto libre.
- `dictation`. FastConformer CTC int8 (español 1424 h, inglés 24500 h) con Silero VAD. Sirve para comparar. El español son unos 99 MB y el inglés unos 100 MB.

La clase TypeScript es `VoiceTools`, en `typescript/voice-tools.ts`. `init()` localiza el plugin. Los resultados llegan por `subscribe()`.

En JavaScript, sin `init()`, el mismo plugin es `cordova.plugins.VoiceTools` después de `deviceready`.

## Instalar

```bash
cordova plugin add RUTA\cordova-plugin-fastconformer
```

El id del plugin es `cordova-plugin-voice-tools`. Requiere Cordova Android 12 o superior. El AAR sigue enlazando `kotlin-stdlib` en tiempo de ejecución.

El primer `download()` de cada herramienta necesita red. Después no. Los ficheros quedan en el almacenamiento privado de la app, en `fastconformer/wake/`, `fastconformer/commands/`, `fastconformer/es/` y `fastconformer/en/`.

## Uso

```typescript
import { VoiceTools, VoiceEvent } from './typescript/voice-tools';

await VoiceTools.init();

VoiceTools.subscribe((event: VoiceEvent) => {
  if (event.type === 'download' && event.state === 'saved' && event.tool === 'wake') {
    VoiceTools.prepare('wake');
  }
  if (event.type === 'ready' && event.tool === 'wake') {
    VoiceTools.start();
  }
  if (event.type === 'keyword') {
    console.log(event.tool, event.keyword);
  }
  if (event.type === 'text') {
    console.log(event.text);
  }
});

VoiceTools.download('wake');
```

`prepare(tool)` carga el modelo y no cierra el micrófono de la herramienta que ya escucha. `use(tool)` la deja activa y cierra el micrófono anterior; después hace falta `start()`. `download('dictation')` baja el FastConformer español. `download('en')` baja el inglés, y `enable('en')` lo cambia en caliente.

`start()` en `wake` y `commands` empuja `{ type: 'keyword', keyword }` en cuanto la lista acierta. En `dictation`, `start('continuous' | 'oneshot' | 'manual')` empuja `{ type: 'text' }` al cerrar la frase.

## JavaScript

Después de `deviceready`, la misma secuencia va sobre `cordova.plugins.VoiceTools`. No hay `init()`.

```javascript
document.addEventListener('deviceready', function () {
  cordova.plugins.VoiceTools.subscribe(function (event) {
    if (event.type === 'keyword') {
      console.log(event.keyword);
    }
  });
  cordova.plugins.VoiceTools.download('wake');
});
```
