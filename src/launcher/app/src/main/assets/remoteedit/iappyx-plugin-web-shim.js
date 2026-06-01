// PLUGINS: BEGIN — Remote-edit web shim for plugin settings.html.
//
// Plugin settings.html files were authored against the native (in-WebView)
// iappyx shim where secureStore/storage are SYNCHRONOUS. We can't talk to
// the launcher's SharedPreferences synchronously from a browser, so this
// shim preloads the data into __iappyxPluginRemoteEdit and serves reads
// from that local cache. Writes are POSTed back to the server and tracked
// so iappyx.plugin.close() can wait for them before tearing down the
// iframe.
//
// httpClient.fetch is fully proxied to /api/plugins/<id>/fetch — the
// browser can't fetch cross-origin token endpoints directly.
//
// API parity with iappyx-plugin-shim.js:
//   iappyx.secureStore.{get, set, remove, isAvailable}     (sync — cached)
//   iappyx.storage.{save, load, remove, clear, snapshot}    (sync — cached)
//   iappyx.httpClient.fetch(opts)                           (async, proxied)
//   iappyx.plugin.{id, log, close, export}                  (close postMessages parent)
//
// Capabilities that don't make sense in a settings flow (scheduler,
// notifications, push) are intentionally NOT shimmed — they'd never run
// at config time. If a plugin tries to use them, it gets a clean
// `not available in remote settings` error.
(function () {
  'use strict';

  var PRELOAD = window.__iappyxPluginRemoteEdit || {};
  var PLUGIN_ID = PRELOAD.pluginId || '';
  var CAPS = PRELOAD.capabilities || [];
  // Session token issued by the server when this iframe was served.
  // Sent in `Authorization: Bearer <token>` because the iframe is
  // sandboxed without `allow-same-origin` → opaque origin → no cookies
  // → can't rely on the paired-session cookie that authenticates the
  // parent. The token is plugin-scoped + IP-pinned + 30-min TTL on the
  // server side, see PluginSessionTokens.kt.
  var SESSION_TOKEN = PRELOAD.sessionToken || '';
  // Mutable local cache — writes update this AND POST to the server.
  var secCache = Object.assign({}, PRELOAD.secureStore || {});
  var stoCache = Object.assign({}, PRELOAD.storage || {});

  // Track in-flight writes so close() can drain before tearing down.
  var pending = 0;
  function track(p) {
    pending++;
    return p.finally(function () { pending--; });
  }

  function api(method, path, body) {
    var opts = { method: method, headers: { 'Accept': 'application/json' } };
    if (SESSION_TOKEN) opts.headers['Authorization'] = 'Bearer ' + SESSION_TOKEN;
    if (body !== undefined) {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
    return fetch(path, opts).then(function (r) {
      if (!r.ok) return r.json().catch(function () { return {}; }).then(function (j) {
        throw new Error(j.error || ('HTTP ' + r.status));
      });
      return r.json();
    });
  }

  function bridgeWrite(ns, method, key, value) {
    return api('POST', '/api/plugins/' + encodeURIComponent(PLUGIN_ID) + '/bridge', {
      ns: ns, method: method, key: String(key), value: value == null ? null : String(value),
    }).catch(function (e) {
      // Surface but don't throw — settings.html doesn't expect set() to fail.
      try { console.warn('plugin bridge write failed:', e); } catch (_) {}
    });
  }

  var iappyx = {
    plugin: {
      id: PLUGIN_ID,
      log: function (msg) { try { console.log('[plugin ' + PLUGIN_ID + ']', msg); } catch (_) {} },
      // settings.html calls export(map) only when the plugin doubles as a
      // runtime exporter. In settings flows it's typically unused — we
      // accept the call so existing plugins don't crash.
      export: function (map) { window._iappyxPluginExports = map; },
      close: function () {
        // Wait for any pending bridge writes before signalling parent —
        // settings.html does `set(...) ; close()` synchronously, and the
        // set's POST is still in flight at the time close() runs.
        var poll = function () {
          if (pending > 0) { setTimeout(poll, 25); return; }
          try { window.parent.postMessage({ type: 'iappyx-plugin-close', pluginId: PLUGIN_ID }, '*'); }
          catch (_) {}
        };
        // setTimeout(0) lets the calling code's `set()` start its fetch
        // before we begin polling — otherwise pending could still be 0.
        setTimeout(poll, 0);
      },
    },
    // Promise wrapper around cbId-style calls. Kept for parity with the
    // native shim — settings code rarely uses this directly.
    cb: function (fn) {
      return new Promise(function (resolve, reject) {
        try { fn(resolve); } catch (e) { reject(e); }
      });
    },
  };

  function attachHttpClient() {
    iappyx.httpClient = {
      fetch: function (options) {
        return api('POST',
          '/api/plugins/' + encodeURIComponent(PLUGIN_ID) + '/fetch',
          options || {})
        .then(function (r) {
          // Shape match: native httpClient.fetch returns {ok, status, body, headers}.
          return {
            ok: !!r.ok,
            status: r.status || 0,
            body: r.body || '',
            headers: r.headers || {},
          };
        })
        .catch(function (e) {
          return { ok: false, status: 0, body: '', headers: {}, error: e.message || String(e) };
        });
      },
    };
  }

  function attachStorage() {
    iappyx.storage = {
      save: function (k, v) {
        stoCache[k] = v == null ? '' : String(v);
        track(bridgeWrite('storage', 'save', k, v == null ? '' : String(v)));
      },
      load: function (k) { return stoCache[String(k)] != null ? stoCache[String(k)] : null; },
      remove: function (k) {
        delete stoCache[String(k)];
        track(bridgeWrite('storage', 'remove', k, null));
      },
      clear: function () {
        var keys = Object.keys(stoCache);
        stoCache = {};
        keys.forEach(function (k) { track(bridgeWrite('storage', 'remove', k, null)); });
      },
      snapshot: function () { return Object.assign({}, stoCache); },
    };
  }

  function attachSecureStore() {
    iappyx.secureStore = {
      get: function (k) { return secCache[String(k)] != null ? secCache[String(k)] : null; },
      set: function (k, v) {
        secCache[String(k)] = v == null ? null : String(v);
        track(bridgeWrite('secureStore', 'set', k, v));
      },
      remove: function (k) {
        delete secCache[String(k)];
        track(bridgeWrite('secureStore', 'remove', k, null));
      },
      // The browser side can't actually probe the Android Keystore —
      // assume it's available if the server gave us a (possibly empty)
      // secureStore preload object.
      isAvailable: function () { return PRELOAD.secureStore !== undefined; },
    };
  }

  function attachUnsupported(name) {
    iappyx[name] = new Proxy({}, {
      get: function () {
        return function () {
          return Promise.resolve({
            ok: false,
            error: 'iappyx.' + name + '.* not available in remote settings',
          });
        };
      },
    });
  }

  // Wire capabilities the plugin manifest declared. Unknown / config-only
  // ones get the unsupported stub so plugin code doesn't trip on `undefined`.
  var attachers = {
    http: attachHttpClient,
    storage: attachStorage,
    secureStore: attachSecureStore,
  };
  CAPS.forEach(function (c) {
    var fn = attachers[c];
    if (fn) fn();
    else attachUnsupported(c.replace(':', '_'));
  });

  // Always expose secureStore + storage even if not declared — settings UIs
  // commonly call them defensively (the plugin probably needs them anyway).
  if (!iappyx.secureStore) attachSecureStore();
  if (!iappyx.storage)     attachStorage();
  if (!iappyx.httpClient)  attachHttpClient();

  window.iappyx = iappyx;
})();
// PLUGINS: END
