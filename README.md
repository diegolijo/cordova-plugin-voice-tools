# cordova-plugin-fastconformer

Reconocimiento de voz **offline** en español e inglés para Cordova Android. Usa el FastConformer CTC int8 de NVIDIA a través de [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8, y Silero VAD para cortar las frases. El español está entrenado con 1424 horas y el inglés con 24500. Los dos son el mismo motor CTC. Silero VAD se comparte.

La clase TypeScript es `FastConformer`, en `typescript/fastconformer.ts`. `init()` localiza el plugin nativo. El texto no se pide: `subscribe()` lo entrega en cuanto el reconocedor cierra una frase.

En JavaScript, sin `init()`, el mismo plugin es `cordova.plugins.FastConformer` después de `deviceready`.

## Instalar

```bash
cordova plugin add RUTA\cordova-plugin-fastconformer
```

Requiere Cordova Android 12 o superior. El puente con sherpa-onnx es Java (`SherpaBridge.java`). El AAR sigue enlazando `kotlin-stdlib` en tiempo de ejecución, pero la app no tiene que activar el compilador de Kotlin.

El primer `download()` necesita red. El español pesa unos 99 MB y el inglés unos 100 MB. `silero_vad.onnx` se baja una sola vez. Después el reconocimiento no usa red. Cada idioma queda en su carpeta, `fastconformer/es/` y `fastconformer/en/`, dentro del almacenamiento privado de la app. El VAD queda en `fastconformer/silero_vad.onnx`. Los dos idiomas pueden estar en disco a la vez.

Si no se pasa idioma, `download()` y `enable()` usan español. Así las llamadas sin idioma siguen significando español.

## Uso

```typescript
import { FastConformer, FastConformerEvent } from './typescript/fastconformer';

await FastConformer.init();

FastConformer.subscribe((event: FastConformerEvent) => {
  switch (event.type) {
    case 'download':
      if (event.state === 'saved' && event.lang === 'en') {
        FastConformer.enable('en');
      }
      break;
    case 'ready':
      FastConformer.start('continuous');
      break;
    case 'speech':
      break;
    case 'text':
      console.log(event.text, event.mode);
      break;
    case 'stopped':
      console.log(event.mode);
      break;
    case 'error':
      console.log(event.message);
      break;
  }
});

FastConformer.download('en');
```

`download()` sin argumento baja el español. `enable()` sin argumento carga el español.

Cuando la configuración de la app pase de español a inglés, y el inglés ya esté en disco, basta con:

```typescript
FastConformer.enable('en');
```

Si el micrófono estaba abierto, el plugin lo cierra, carga el modelo nuevo y lo vuelve a abrir en el mismo modo (`continuous`, `oneshot` o `manual`). No hace falta `stop()` ni `start()`. Ese cambio no empuja `stopped`. El texto siguiente ya sale del modelo nuevo. `subscribe()` recibe `{ type: 'ready', lang: 'en' }`. Si el permiso de micrófono llega después de `enable`, se usa el idioma de esa llamada.

Si el inglés no está descargado, llega `{ type: 'error' }` y el español sigue escuchando como estaba. Si el idioma pedido es el que ya está cargado, no se vuelve a leer el ONNX y llega `ready` otra vez.

`init()` hay que esperarlo una vez. Si Cordova ya arrancó, resuelve al momento. Si no, espera a `deviceready`. Llamarlo otra vez no vuelve a buscar.

## JavaScript

Después de `deviceready`, la misma secuencia va sobre `cordova.plugins.FastConformer`. No hay `init()`.

```javascript
document.addEventListener('deviceready', function () {
  cordova.plugins.FastConformer.subscribe(function (event) {
    if (event.type === 'download' && event.state === 'saved' && event.lang === 'en') {
      cordova.plugins.FastConformer.enable('en');
    }
    if (event.type === 'ready') {
      cordova.plugins.FastConformer.start('continuous');
    }
    if (event.type === 'text') {
      console.log(event.text, event.mode);
    }
  });

  cordova.plugins.FastConformer.download('en');
}, false);
```

`download()` y `enable()` sin idioma usan español. Si el primer argumento es la función de éxito, también es español:

```javascript
cordova.plugins.FastConformer.download(function (ack) {}, function (message) {});
cordova.plugins.FastConformer.enable('en');
```

`download`, `enable`, `start` y `stop` solo confirman la orden. Su callback recibe `FastConformerAck` y no el texto. El texto llega en `FastConformerTextEvent`, dentro de `subscribe()`.

Si `subscribe()` se abre cuando ya hubo eventos, esos eventos se entregan en ese momento.

## Modos

`FastConformer.start(mode)` usa `FastConformerMode`:

| Modo | Comportamiento |
|---|---|
| `continuous` | El micrófono sigue abierto. Cada silencio del VAD cierra una frase, empuja `FastConformerTextEvent` y vuelve a escuchar. Es el modo si `start()` se llama sin argumento. |
| `oneshot` | Igual que `continuous`, pero se para solo tras la primera frase y empuja `FastConformerStoppedEvent`. |
| `manual` | Graba desde `start('manual')` hasta `stop()`, sin cortar por silencio. Al parar, reconoce el audio y empuja el texto. Máximo 60 segundos. |

`stop()` cierra cualquiera de los tres. En `manual` el texto sale en ese momento. En `continuous` y `oneshot` no se espera al silencio: lo que aún no se había cerrado no se reconoce, y llega `FastConformerStoppedEvent`.

No hay resultados parciales. Este modelo devuelve el texto al terminar la frase.

Al pausar la actividad el micrófono se cierra y llega `FastConformerStoppedEvent`.

## API

El comentario de cada método está encima de su declaración en `typescript/fastconformer.ts`.

| Método | Qué hace | Qué llega por `subscribe()` |
|---|---|---|
| `init()` | Localiza el plugin nativo. Hay que esperarlo una vez. | Nada |
| `subscribe(success, error)` | Abre el canal y lo deja abierto. `success` se llama una vez por evento. | Todos |
| `unsubscribe(success, error)` | Cierra el canal y para el micrófono si estaba escuchando. | Nada más a partir de aquí |
| `download(lang?, success, error)` | Baja ese idioma y Silero VAD, o avisa si ya están en disco. Sin `lang` es español. El callback es `FastConformerAck`. | `FastConformerDownloadEvent` con `lang` |
| `enable(lang?, success, error)` | Pide `RECORD_AUDIO` si hace falta y carga ese idioma. Sin `lang` es español. Si ya se estaba escuchando, cambia el modelo y sigue en el mismo modo. El callback es `FastConformerAck`. | `FastConformerReadyEvent` con `lang` |
| `start(mode?, success, error)` | Empieza a escuchar. Sin `mode` equivale a `continuous`. El callback es `FastConformerAck`. | `FastConformerSpeechEvent` y luego `FastConformerTextEvent` |
| `stop(success, error)` | Cierra el micrófono. El callback es `FastConformerAck`. | `FastConformerTextEvent` solo en `manual`, y siempre `FastConformerStoppedEvent` |

`FastConformerEvent` es una de estas interfaces:

| Interfaz | Campos |
|---|---|
| `FastConformerDownloadEvent` | `state: 'start' \| 'extract' \| 'saved'`, `lang: 'es' \| 'en'` |
| `FastConformerReadyEvent` | `type: 'ready'`, `lang: 'es' \| 'en'` |
| `FastConformerSpeechEvent` | `type: 'speech'` |
| `FastConformerTextEvent` | `text`, `mode` |
| `FastConformerStoppedEvent` | `mode` |
| `FastConformerErrorEvent` | `message` |

Un fallo de descarga, permiso, modelo o micrófono llega como `FastConformerErrorEvent`. La suscripción sigue abierta. El callback `error` de `subscribe()` solo salta si falla el puente nativo.

## Audio

16 kHz, mono, PCM 16 bit.

## Licencias

- Este plugin: MIT.
- sherpa-onnx y su AAR: Apache-2.0.
- Silero VAD: MIT.
- Modelo `sherpa-onnx-nemo-fast-conformer-ctc-es-1424-int8`: NVIDIA FastConformer Spanish (`stt_es_fastconformer_hybrid_large_pc`), licencia [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/). Modelo: https://huggingface.co/nvidia/stt_es_fastconformer_hybrid_large_pc
- Modelo `sherpa-onnx-nemo-fast-conformer-ctc-en-24500-int8`: NVIDIA FastConformer English CTC, la misma familia NeMo, licencia [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/).
