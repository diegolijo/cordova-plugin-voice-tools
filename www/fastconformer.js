var exec = require('cordova/exec');

var SERVICE = 'FastConformer';

function command(action, args, success, error) {
  exec(success || function () {}, error || function () {}, SERVICE, action, args || []);
}

var FastConformer = {
  subscribe: function (success, error) {
    exec(success, error, SERVICE, 'subscribe', []);
  },
  unsubscribe: function (success, error) {
    command('unsubscribe', [], success, error);
  },
  download: function (lang, success, error) {
    if (typeof lang === 'function') {
      error = success;
      success = lang;
      lang = 'es';
    }
    command('download', [lang || 'es'], success, error);
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
      mode = 'continuous';
    }
    command('start', [mode || 'continuous'], success, error);
  },
  stop: function (success, error) {
    command('stop', [], success, error);
  }
};

module.exports = FastConformer;
