var exec = require('cordova/exec');

var SERVICE = 'VoiceTools';

function command(action, args, success, error) {
  exec(success || function () {}, error || function () {}, SERVICE, action, args || []);
}

var VoiceTools = {
  subscribe: function (success, error) {
    exec(success, error, SERVICE, 'subscribe', []);
  },
  unsubscribe: function (success, error) {
    command('unsubscribe', [], success, error);
  },
  download: function (tool, success, error) {
    if (typeof tool === 'function') {
      error = success;
      success = tool;
      tool = 'dictation';
    }
    command('download', [tool || 'dictation'], success, error);
  },
  prepare: function (tool, success, error) {
    command('prepare', [tool], success, error);
  },
  use: function (tool, success, error) {
    command('use', [tool], success, error);
  },
  enable: function (lang, success, error) {
    if (typeof lang === 'function') {
      error = success;
      success = lang;
      lang = 'es';
    }
    command('enable', [lang || 'es'], success, error);
  },
  start: function (mode, success, error) {
    if (typeof mode === 'function') {
      error = success;
      success = mode;
      mode = '';
    }
    command('start', mode ? [mode] : [], success, error);
  },
  stop: function (success, error) {
    command('stop', [], success, error);
  }
};

module.exports = VoiceTools;
