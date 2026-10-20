/** continuous sigue escuchando. oneshot para tras la primera frase. manual graba hasta stop(). */
export type FastConformerMode = 'continuous' | 'oneshot' | 'manual';

/** Español o inglés. Si no se indica, el plugin usa español. */
export type FastConformerLang = 'es' | 'en';

/** Confirmación de una orden. No contiene el texto reconocido. */
export interface FastConformerAck {
  type: 'ack';
  /** Comando aceptado: unsubscribe, download, enable, start o stop. */
  action: string;
}

/** Progreso de la descarga del modelo y de Silero VAD. */
export interface FastConformerDownloadEvent {
  type: 'download';
  /** start: empieza a bajar. extract: descomprime el tar.bz2. saved: los ficheros ya están en disco. */
  state: 'start' | 'extract' | 'saved';
  /** Idioma que se está bajando. */
  lang: FastConformerLang;
}

/** El modelo ya está en memoria y el micrófono se puede abrir. */
export interface FastConformerReadyEvent {
  type: 'ready';
  /** Idioma cargado. */
  lang: FastConformerLang;
}

/** Silero VAD ha detectado voz. Todavía no hay texto. */
export interface FastConformerSpeechEvent {
  type: 'speech';
}

/** Frase cerrada. Es el único evento que trae la transcripción. */
export interface FastConformerTextEvent {
  type: 'text';
  /** Texto reconocido, ya recortado. */
  text: string;
  /** Modo con el que se abrió el micrófono. */
  mode: FastConformerMode;
}

/** El micrófono se ha cerrado: por stop(), por oneshot o porque la app pasó a segundo plano. */
export interface FastConformerStoppedEvent {
  type: 'stopped';
  mode: FastConformerMode;
}

/** Fallo de descarga, permiso, carga del modelo o del micrófono. La suscripción sigue abierta. */
export interface FastConformerErrorEvent {
  type: 'error';
  message: string;
}

/** Cualquier evento que subscribe() empuja cuando el plugin lo tiene. */
export type FastConformerEvent =
  | FastConformerDownloadEvent
  | FastConformerReadyEvent
  | FastConformerSpeechEvent
  | FastConformerTextEvent
  | FastConformerStoppedEvent
  | FastConformerErrorEvent;

export type FastConformerSuccess = (ack: FastConformerAck) => void;
export type FastConformerFailure = (message: string) => void;

interface FastConformerBridge {
  subscribe(success: (event: FastConformerEvent) => void, error?: FastConformerFailure): void;
  unsubscribe(success?: FastConformerSuccess, error?: FastConformerFailure): void;
  download(success?: FastConformerSuccess, error?: FastConformerFailure): void;
  download(lang: FastConformerLang, success?: FastConformerSuccess, error?: FastConformerFailure): void;
  enable(success?: FastConformerSuccess, error?: FastConformerFailure): void;
  enable(lang: FastConformerLang, success?: FastConformerSuccess, error?: FastConformerFailure): void;
  start(success?: FastConformerSuccess, error?: FastConformerFailure): void;
  start(mode: FastConformerMode, success?: FastConformerSuccess, error?: FastConformerFailure): void;
  stop(success?: FastConformerSuccess, error?: FastConformerFailure): void;
}

interface CordovaHost {
  cordova?: {
    plugins?: {
      FastConformer?: FastConformerBridge;
    };
  };
}

/**
 * Reconocimiento offline en español o inglés.
 * El texto llega a subscribe() en cuanto el reconocedor cierra una frase.
 */
function voz(origen: 'arranque' | 'error', message: string): void {
  const color = origen === 'error' ? '#f28b82' : '#8ab4f8';
  console.log(`%c[voz|${origen}] ${message}`, `color:${color};font-weight:bold`);
}

export class FastConformer {

  private static bridge: FastConformerBridge | null = null;

  /**
   * Localiza el plugin nativo y lo deja listo.
   * Si Cordova ya arrancó, lo toma al momento. Si no, espera a deviceready.
   * Hay que esperarlo una vez antes de subscribe, download, enable, start o stop.
   * Llamarlo otra vez no vuelve a buscar.
   */
  static init(): Promise<void> {
    if (FastConformer.bridge) {
      voz('arranque', 'init ya tenía el puente');
      return Promise.resolve();
    }
    const found = FastConformer.lookup();
    if (found) {
      voz('arranque', 'init encontró cordova.plugins.FastConformer');
      FastConformer.bridge = found;
      return Promise.resolve();
    }
    voz('arranque', 'init espera deviceready');
    return new Promise((resolve, reject) => {
      const onReady = (): void => {
        const plugin = FastConformer.lookup();
        if (!plugin) {
          voz('error', 'init: cordova-plugin-fastconformer no está instalado');
          reject(new Error('cordova-plugin-fastconformer no está instalado'));
          return;
        }
        voz('arranque', 'init en deviceready encontró el puente');
        FastConformer.bridge = plugin;
        resolve();
      };
      document.addEventListener('deviceready', onReady, { once: true });
    });
  }

  /**
   * Abre el canal de resultados y lo deja abierto.
   * success se llama una vez por evento, no una sola vez.
   * Si la descarga o el reconocedor emitieron algo antes de esta llamada, esos eventos se entregan aquí.
   * error solo salta si falla el puente nativo. Un fallo de modelo o de micrófono llega como { type: 'error' }.
   */
  static subscribe(success: (event: FastConformerEvent) => void, error?: FastConformerFailure): void {
    FastConformer.native().subscribe(success, error);
  }

  /**
   * Cierra el canal y para el micrófono si estaba escuchando.
   * success recibe el ack. Después de esto no llega ningún evento más.
   */
  static unsubscribe(success?: FastConformerSuccess, error?: FastConformerFailure): void {
    FastConformer.native().unsubscribe(success, error);
  }

  /**
   * Pide la descarga del FastConformer int8 de ese idioma y de silero_vad.onnx, que se comparte.
   * Sin idioma baja el español. Cada idioma va a su carpeta y los dos pueden estar en disco a la vez.
   * success recibe solo el ack, enseguida.
   * El avance llega por subscribe(): state 'start', luego 'extract', y 'saved' cuando ya se puede llamar a enable() de ese idioma.
   * Si los ficheros de ese idioma ya estaban guardados, el único evento es { type: 'download', state: 'saved', lang }.
   */
  static download(success?: FastConformerSuccess, error?: FastConformerFailure): void;

  static download(lang: FastConformerLang, success?: FastConformerSuccess, error?: FastConformerFailure): void;

  static download(
    langOrSuccess?: FastConformerLang | FastConformerSuccess,
    successOrError?: FastConformerSuccess | FastConformerFailure,
    error?: FastConformerFailure
  ): void {
    if (typeof langOrSuccess === 'string') {
      FastConformer.native().download(langOrSuccess, successOrError as FastConformerSuccess, error);
      return;
    }
    FastConformer.native().download(langOrSuccess, successOrError as FastConformerFailure);
  }

  /**
   * Pide RECORD_AUDIO si hace falta y carga el modelo de ese idioma en memoria.
   * Sin idioma carga el español. Hay que esperar a download state 'saved' de ese idioma.
   * Si se llama antes, subscribe() recibe { type: 'error' } y el idioma que ya estaba escuchando sigue igual.
   * Si el idioma pedido ya está cargado, no vuelve a leer el ONNX y subscribe() recibe { type: 'ready', lang } otra vez.
   * Si el micrófono estaba abierto, lo cierra, sustituye el modelo y lo vuelve a abrir en el mismo modo. La app no tiene que llamar a stop() ni a start(). Ese cambio no empuja { type: 'stopped' }.
   * Si el permiso de micrófono llega después de esta llamada, se usa el idioma pedido aquí.
   * success recibe solo el ack. Cuando el modelo ha cargado, subscribe() recibe { type: 'ready', lang }.
   */
  static enable(success?: FastConformerSuccess, error?: FastConformerFailure): void;

  static enable(lang: FastConformerLang, success?: FastConformerSuccess, error?: FastConformerFailure): void;

  static enable(
    langOrSuccess?: FastConformerLang | FastConformerSuccess,
    successOrError?: FastConformerSuccess | FastConformerFailure,
    error?: FastConformerFailure
  ): void {
    if (typeof langOrSuccess === 'string') {
      FastConformer.native().enable(langOrSuccess, successOrError as FastConformerSuccess, error);
      return;
    }
    FastConformer.native().enable(langOrSuccess, successOrError as FastConformerFailure);
  }

  /**
   * Empieza a escuchar en continuous: el micrófono sigue abierto y cada silencio empuja { type: 'text' }.
   * success recibe solo el ack. El texto no llega por este callback.
   */
  static start(success?: FastConformerSuccess, error?: FastConformerFailure): void;

  /**
   * Empieza a escuchar en el modo indicado.
   * continuous: cada silencio empuja { type: 'text' } y sigue.
   * oneshot: igual que continuous, pero se cierra solo tras la primera frase y empuja { type: 'stopped' }.
   * manual: graba en silencio hasta stop(), sin cortar por VAD, con un tope de 60 segundos.
   * success recibe solo el ack. Al detectar voz, subscribe() recibe { type: 'speech' }.
   */
  static start(mode: FastConformerMode, success?: FastConformerSuccess, error?: FastConformerFailure): void;

  static start(
    modeOrSuccess?: FastConformerMode | FastConformerSuccess,
    successOrError?: FastConformerSuccess | FastConformerFailure,
    error?: FastConformerFailure
  ): void {
    if (typeof modeOrSuccess === 'string') {
      FastConformer.native().start(modeOrSuccess, successOrError as FastConformerSuccess, error);
      return;
    }
    FastConformer.native().start(modeOrSuccess, successOrError as FastConformerFailure);
  }

  /**
   * Cierra el micrófono.
   * En manual reconoce entonces el audio grabado, empuja { type: 'text' } si hay frase y después { type: 'stopped' }.
   * En continuous y oneshot no espera al silencio: lo que aún no se había cerrado no se transcribe, y empuja { type: 'stopped' }.
   * success recibe solo el ack.
   */
  static stop(success?: FastConformerSuccess, error?: FastConformerFailure): void {
    FastConformer.native().stop(success, error);
  }

  private static lookup(): FastConformerBridge | null {
    const host = window as Window & CordovaHost;
    return host.cordova?.plugins?.FastConformer ?? null;
  }

  private static native(): FastConformerBridge {
    if (!FastConformer.bridge) {
      throw new Error('Llama a FastConformer.init() antes de usar el reconocedor');
    }
    return FastConformer.bridge;
  }
}
