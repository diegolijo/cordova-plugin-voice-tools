/** wake escucha la palabra clave. commands oye órdenes cortas. dictation es FastConformer, para comparar. */
export type VoiceTool = 'wake' | 'commands' | 'dictation';

/** continuous sigue escuchando. oneshot para tras la primera frase. manual graba hasta stop(). Solo dictation. */
export type VoiceDictationMode = 'continuous' | 'oneshot' | 'manual';

/** Español o inglés del dictado. Si no se indica, el plugin usa español. */
export type VoiceLang = 'es' | 'en';

/** Confirmación de una orden. No contiene lo reconocido. */
export interface VoiceAck {
  type: 'ack';
  /** Comando aceptado. */
  action: string;
}

/** Progreso de la descarga de una herramienta. */
export interface VoiceDownloadEvent {
  type: 'download';
  /** start: empieza a bajar. extract: descomprime. saved: ya está en disco. */
  state: 'start' | 'extract' | 'saved';
  /** Herramienta que se está bajando. */
  tool: VoiceTool;
  /** Idioma del dictado, cuando tool es dictation. */
  lang?: VoiceLang;
}

/** La herramienta ya está en memoria y es la activa. Hay que llamar a start(). */
export interface VoiceReadyEvent {
  type: 'ready';
  tool: VoiceTool;
  /** Idioma cargado, solo en dictation. */
  lang?: VoiceLang;
}

/** La herramienta está en memoria. No ha cerrado el micrófono de la que ya escuchaba. */
export interface VoicePreparedEvent {
  type: 'prepared';
  tool: VoiceTool;
}

/** La app ha guardado la lista de esa herramienta. */
export interface VoiceKeywordsEvent {
  type: 'keywords';
  tool: 'wake' | 'commands';
}

/**
 * Tokens de una palabra recién dicha.
 * En wake son fonemas (`K IH1 T`). En commands son las piezas de ese modelo.
 */
export interface VoiceTokensEvent {
  type: 'tokens';
  tool: 'wake' | 'commands';
  tokens: string;
}

/** El detector de palabras ha oído una de su lista. */
export interface VoiceKeywordEvent {
  type: 'keyword';
  tool: 'wake' | 'commands';
  /** Etiqueta de la lista: KITT, APAGA, LOS_CUARENTA, METAL… */
  keyword: string;
}

/** Silero VAD ha detectado voz en dictation. Todavía no hay texto. */
export interface VoiceSpeechEvent {
  type: 'speech';
}

/** Frase cerrada por dictation. */
export interface VoiceTextEvent {
  type: 'text';
  text: string;
  mode: VoiceDictationMode;
}

/** El micrófono de una herramienta se ha cerrado. */
export interface VoiceStoppedEvent {
  type: 'stopped';
  tool?: VoiceTool;
  mode?: VoiceDictationMode;
}

/** Fallo de descarga, permiso, carga o micrófono. La suscripción sigue abierta. */
export interface VoiceErrorEvent {
  type: 'error';
  message: string;
}

/** Cualquier evento que subscribe() empuja. */
export type VoiceEvent =
  | VoiceDownloadEvent
  | VoiceReadyEvent
  | VoicePreparedEvent
  | VoiceKeywordsEvent
  | VoiceTokensEvent
  | VoiceKeywordEvent
  | VoiceSpeechEvent
  | VoiceTextEvent
  | VoiceStoppedEvent
  | VoiceErrorEvent;

export type VoiceSuccess = (ack: VoiceAck) => void;
export type VoiceFailure = (message: string) => void;

interface VoiceBridge {
  subscribe(success: (event: VoiceEvent) => void, error?: VoiceFailure): void;
  unsubscribe(success?: VoiceSuccess, error?: VoiceFailure): void;
  download(tool: string, success?: VoiceSuccess, error?: VoiceFailure): void;
  keywords(tool: 'wake' | 'commands', body: string, success?: VoiceSuccess, error?: VoiceFailure): void;
  capture(tool: 'wake' | 'commands', success?: VoiceSuccess, error?: VoiceFailure): void;
  prepare(tool: VoiceTool, success?: VoiceSuccess, error?: VoiceFailure): void;
  use(tool: VoiceTool, success?: VoiceSuccess, error?: VoiceFailure): void;
  enable(lang: VoiceLang, success?: VoiceSuccess, error?: VoiceFailure): void;
  start(mode?: VoiceDictationMode, success?: VoiceSuccess, error?: VoiceFailure): void;
  stop(success?: VoiceSuccess, error?: VoiceFailure): void;
}

interface CordovaHost {
  cordova?: {
    plugins?: {
      VoiceTools?: VoiceBridge;
    };
  };
}

/**
 * Suite de voz offline: detector de palabra clave, detector de órdenes y dictado.
 * wake y commands avisan con { type: 'keyword' }. dictation avisa con { type: 'text' }.
 */
function voz(origen: 'arranque' | 'error', message: string): void {
  const color = origen === 'error' ? '#f28b82' : '#8ab4f8';
  console.log(`%c[voz|${origen}] ${message}`, `color:${color};font-weight:bold`);
}

export class VoiceTools {

  private static bridge: VoiceBridge | null = null;

  /**
   * Localiza el plugin nativo y lo deja listo.
   * Si Cordova ya arrancó, lo toma al momento. Si no, espera a deviceready.
   * Hay que esperarlo una vez antes del resto de llamadas.
   */
  static init(): Promise<void> {
    if (VoiceTools.bridge) {
      voz('arranque', 'init ya tenía el puente');
      return Promise.resolve();
    }
    const found = VoiceTools.lookup();
    if (found) {
      voz('arranque', 'init encontró cordova.plugins.VoiceTools');
      VoiceTools.bridge = found;
      return Promise.resolve();
    }
    voz('arranque', 'init espera deviceready');
    return new Promise((resolve, reject) => {
      const onReady = (): void => {
        const plugin = VoiceTools.lookup();
        if (!plugin) {
          voz('error', 'init: cordova-plugin-voice-tools no está instalado');
          reject(new Error('cordova-plugin-voice-tools no está instalado'));
          return;
        }
        voz('arranque', 'init en deviceready encontró el puente');
        VoiceTools.bridge = plugin;
        resolve();
      };
      document.addEventListener('deviceready', onReady, { once: true });
    });
  }

  /**
   * Abre el canal de resultados y lo deja abierto.
   * success se llama una vez por evento.
   * error solo salta si falla el puente nativo. Un fallo de modelo llega como { type: 'error' }.
   */
  static subscribe(success: (event: VoiceEvent) => void, error?: VoiceFailure): void {
    VoiceTools.native().subscribe(success, error);
  }

  /**
   * Cierra el canal y para el micrófono si estaba escuchando.
   */
  static unsubscribe(success?: VoiceSuccess, error?: VoiceFailure): void {
    VoiceTools.native().unsubscribe(success, error);
  }

  /**
   * Baja el modelo de esa herramienta.
   * wake: detector inglés de unos 5 MB. commands: detector en español. dictation: FastConformer español.
   * es o en bajan el dictado de ese idioma.
   * El avance llega por subscribe(): start, extract y saved.
   */
  static download(tool: VoiceTool | VoiceLang = 'dictation', success?: VoiceSuccess, error?: VoiceFailure): void {
    VoiceTools.native().download(tool, success, error);
  }

  /**
   * Guarda la lista que manda la app y, si el detector ya estaba cargado, la aplica.
   * Cada línea es `tokens @ETIQUETA`. subscribe() recibe { type: 'keywords', tool }.
   */
  static keywords(tool: 'wake' | 'commands', body: string, success?: VoiceSuccess, error?: VoiceFailure): void {
    VoiceTools.native().keywords(tool, body, success, error);
  }

  /**
   * Escucha una palabra y devuelve los tokens del modelo.
   * En wake son fonemas. En commands son las piezas de ese modelo, que son las que admite su lista.
   * subscribe() recibe { type: 'tokens', tool, tokens }.
   */
  static capture(tool: 'wake' | 'commands', success?: VoiceSuccess, error?: VoiceFailure): void {
    VoiceTools.native().capture(tool, success, error);
  }

  /**
   * Carga la herramienta en memoria sin quitar la que está escuchando.
   * Sirve para tener commands listo mientras wake sigue abierto.
   * Cuando ha cargado, subscribe() recibe { type: 'prepared', tool }.
   */
  static prepare(tool: VoiceTool, success?: VoiceSuccess, error?: VoiceFailure): void {
    VoiceTools.native().prepare(tool, success, error);
  }

  /**
   * Deja esa herramienta como la activa y cierra el micrófono de la anterior.
   * Hay que llamar a start() después del ready.
   */
  static use(tool: VoiceTool, success?: VoiceSuccess, error?: VoiceFailure): void {
    VoiceTools.native().use(tool, success, error);
  }

  /**
   * Carga el dictado de ese idioma. Sin idioma usa español.
   * Si el micrófono del dictado estaba abierto, lo reabre en el mismo modo.
   */
  static enable(lang: VoiceLang = 'es', success?: VoiceSuccess, error?: VoiceFailure): void {
    VoiceTools.native().enable(lang, success, error);
  }

  /**
   * Abre el micrófono de la herramienta activa.
   * En wake y commands cada acierto empuja { type: 'keyword' }.
   * En dictation, mode elige continuous, oneshot o manual. Sin mode, continuous.
   */
  static start(mode?: VoiceDictationMode, success?: VoiceSuccess, error?: VoiceFailure): void {
    VoiceTools.native().start(mode, success, error);
  }

  /**
   * Cierra el micrófono.
   * subscribe() recibe { type: 'stopped' }.
   */
  static stop(success?: VoiceSuccess, error?: VoiceFailure): void {
    VoiceTools.native().stop(success, error);
  }

  private static lookup(): VoiceBridge | null {
    const host = window as Window & CordovaHost;
    return host.cordova?.plugins?.VoiceTools ?? null;
  }

  private static native(): VoiceBridge {
    if (!VoiceTools.bridge) {
      throw new Error('Llama a VoiceTools.init() antes de usar la suite');
    }
    return VoiceTools.bridge;
  }
}
