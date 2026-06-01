/*
 * MIT License - Copyright (c) 2026 iappyx
 * PLUGINS — runtime shim injected into every plugin's hidden WebView
 * BEFORE plugin.html's own scripts execute. Provides:
 *
 *   - `iappyx.plugin.export({...})`        declare exposed methods
 *   - `iappyx.plugin.id`                    plugin's own id
 *   - `iappyx.plugin.log(msg)`              debug log to launcher logcat
 *   - `iappyx.cb(fn)`                       Promise wrapper around any
 *                                           cbId-style bridge call
 *   - `iappyx.httpClient.fetch(opts)`       (only when http capability)
 *   - `iappyx.storage.{save,load,...}`      (only when storage capability)
 *   - `iappyx.secureStore.{get,set,...}`    (only when secureStore capability)
 *
 * The host injects `__iappyxPluginCaps.ids` listing the bridges that
 * were actually attached. We expose only those — calling a non-granted
 * bridge throws immediately rather than failing silently.
 *
 * The launcher invokes plugin methods by calling
 * `evaluateJavascript()` on the plugin's WebView with a snippet that
 * looks up `window._iappyxPluginExports[methodName]` and replies via
 * `iappyxPluginInternal.reply(cbId, jsonResult)`.
 */
(function () {
  'use strict';

  if (window.iappyx && window.iappyx.plugin) return;

  var PLUGIN_ID = window.__iappyxPluginId || '';
  var CAPS = (window.__iappyxPluginCaps && window.__iappyxPluginCaps.ids) || [];

  window._iappyxCb = window._iappyxCb || {};

  /** Forward a value through the internal bridge for host-side logging. */
  function _log(msg) {
    var s;
    try {
      s = typeof msg === 'string' ? msg : JSON.stringify(msg);
    } catch (_) { s = String(msg); }
    try {
      if (typeof iappyxPluginInternal !== 'undefined' &&
          typeof iappyxPluginInternal.log === 'function') {
        iappyxPluginInternal.log(s);
      }
    } catch (_) { /* logging is best-effort */ }
  }

  /** Promise wrapper around any cbId-style bridge call. Mirrors
   *  `iappyx.cb` in the wallpaper shim so plugin authors familiar with
   *  the wallpaper SDK feel at home. Usage:
   *    const r = await iappyx.cb(id => iappyxHttpClient.fetch(opts, id));
   *  Resolves with the value the bridge passed to `_iappyxCb[cbId]`. */
  function _cb(fn) {
    return new Promise(function (resolve, reject) {
      var id = '_cb' + Date.now() + '_' + Math.random().toString(36).slice(2);
      window._iappyxCb[id] = function (res) {
        delete window._iappyxCb[id];
        // Convention: bridges return {ok:true, ...} | {ok:false, error:"..."}.
        // Resolve the whole object — plugin code can decide what to
        // do with non-ok responses (e.g. an HTTP 404 is `ok:true,
        // status:404`, not an error). Bridges that throw before
        // delivering a result will leave the cb dangling; the
        // `setTimeout` fallback below ensures we don't hang forever.
        resolve(res);
      };
      try { fn(id); }
      catch (e) {
        delete window._iappyxCb[id];
        reject(e);
      }
    });
  }

  var api = {
    plugin: {
      id: PLUGIN_ID,
      export: function (map) {
        if (!map || typeof map !== 'object') {
          _log('iappyx.plugin.export ignored: expected an object');
          return;
        }
        window._iappyxPluginExports = map;
        try {
          if (typeof iappyxPluginInternal !== 'undefined' &&
              typeof iappyxPluginInternal.declareExports === 'function') {
            iappyxPluginInternal.declareExports(JSON.stringify(Object.keys(map)));
          }
        } catch (_) { /* declareExports is the ready signal — best effort */ }
      },
      log: _log,
    },
    cb: _cb,
  };

  // Capability-gated namespaces. Each maps `iappyx.<ns>.<method>(args)`
  // to a Promise-returning wrapper over the underlying bridge.
  function _attachHttpClient() {
    api.httpClient = {
      /** Fetch a URL. Returns Promise resolving to
       *  {ok, status, body, headers}. Body is the raw response text;
       *  plugin parses JSON itself. */
      fetch: function (options) {
        return _cb(function (id) {
          iappyxHttpClient.fetch(JSON.stringify(options || {}), id);
        });
      },
    };
  }

  function _attachStorage() {
    api.storage = {
      save: function (k, v) { iappyxStorage.save(String(k), v == null ? '' : String(v)); },
      load: function (k) { return iappyxStorage.load(String(k)); },
      remove: function (k) { iappyxStorage.remove(String(k)); },
      clear: function () { iappyxStorage.clear(); },
      snapshot: function () {
        try { return JSON.parse(iappyxStorage.snapshot()); }
        catch (_) { return {}; }
      },
    };
  }

  function _attachSecureStore() {
    api.secureStore = {
      get: function (k) { return iappyxSecureStore.get(String(k)); },
      set: function (k, v) { iappyxSecureStore.set(String(k), v == null ? null : String(v)); },
      remove: function (k) { iappyxSecureStore.remove(String(k)); },
      isAvailable: function () { return !!iappyxSecureStore.isAvailable(); },
    };
  }

  function _attachScheduler() {
    api.scheduler = {
      /** Schedule the plugin's exported `method` to run every
       *  `intervalMin` minutes. Minimum 15 min (AlarmManager-imposed).
       *  Returns Promise<{ok, subId, nextFireAtMs}>. */
      every: function (options) {
        return _cb(function (id) {
          iappyxScheduler.every(JSON.stringify(options || {}), id);
        });
      },
      /** One-shot — fires once at the given wall-clock time
       *  (`timestampMs`). Returns Promise<{ok, subId, nextFireAtMs}>. */
      at: function (options) {
        return _cb(function (id) {
          iappyxScheduler.at(JSON.stringify(options || {}), id);
        });
      },
      /** Cancel by subId. Returns Promise<{ok, cancelled:bool}>. */
      cancel: function (subId) {
        return _cb(function (id) {
          iappyxScheduler.cancel(String(subId || ''), id);
        });
      },
      /** List active schedules. Returns
       *  Promise<{ok, schedules:[{subId, kind, method, nextFireAtMs, intervalMin?}]}>. */
      list: function () {
        return _cb(function (id) {
          iappyxScheduler.list(id);
        });
      },
    };
  }

  function _attachNotifications() {
    api.notifications = {
      /** Subscribe to incoming notifications. Filter is all-optional
       *  AND-combined: packages[], categories[], ongoing. Plugin's
       *  `method` fires with the notification payload on each match.
       *  Returns Promise<{ok, subId}>. Subscriptions persist across
       *  plugin restarts. */
      subscribe: function (options) {
        return _cb(function (id) {
          iappyxNotifications.subscribe(JSON.stringify(options || {}), id);
        });
      },
      /** Cancel a subscription by subId.
       *  Returns Promise<{ok, cancelled:bool}>. */
      unsubscribe: function (subId) {
        return _cb(function (id) {
          iappyxNotifications.unsubscribe(String(subId || ''), id);
        });
      },
      /** Snapshot currently-active notifications matching the filter.
       *  Returns Promise<{ok, notifications:[...], accessGranted}>. */
      recent: function (options) {
        return _cb(function (id) {
          iappyxNotifications.recent(JSON.stringify(options || {}), id);
        });
      },
    };
  }

  function _attachMqtt() {
    api.mqtt = {
      /** Connect to a broker.
       *  opts = { url:"tcp://host:1883" | "ssl://" | "ws://" | "wss://",
       *           username?, password?, clientId?, cleanSession?,
       *           keepAlive?, insecure?, lwt? }
       *  Returns Promise<{ok, connected}>. The `insecure` flag is only
       *  honoured for ssl/wss URLs whose host resolves to a LAN address —
       *  same LAN-gate as iappyx.httpClient.fetch. */
      connect: function (options) {
        return _cb(function (id) {
          iappyxMqtt.connect(JSON.stringify(options || {}), id);
        });
      },
      /** Subscribe to a topic. The plugin's exported `method` fires
       *  per message with { subId, topic, payload, qos, retained,
       *  timestamp }. Standard MQTT wildcards supported (`+`, `#`).
       *  Returns Promise<{ok, subId}>. */
      subscribe: function (options) {
        return _cb(function (id) {
          iappyxMqtt.subscribe(JSON.stringify(options || {}), id);
        });
      },
      /** Cancel a subscription by subId.
       *  Returns Promise<{ok, cancelled}>. */
      unsubscribe: function (subId) {
        return _cb(function (id) {
          iappyxMqtt.unsubscribe(String(subId || ''), id);
        });
      },
      /** Publish a message.
       *  opts = { topic, payload, qos?:0|1|2, retained?:bool }
       *  Returns Promise<{ok}>. */
      publish: function (options) {
        return _cb(function (id) {
          iappyxMqtt.publish(JSON.stringify(options || {}), id);
        });
      },
      /** Query state; optionally register a state-change observer.
       *  opts = { method?:string }
       *  Returns Promise<{ok, connected, broker}>. If method is given,
       *  that exported method fires on every subsequent connect /
       *  reconnect / disconnect with { connected, broker, timestamp }. */
      state: function (options) {
        return _cb(function (id) {
          iappyxMqtt.state(JSON.stringify(options || {}), id);
        });
      },
      /** Disconnect cleanly. Returns Promise<{ok}>. */
      disconnect: function () {
        return _cb(function (id) {
          iappyxMqtt.disconnect(id);
        });
      },
    };
  }

  function _attachPush() {
    api.push = {
      /** FCM device token. Server side uses this as the addressee.
       *  Promise<{ok, token}> or {ok:false, error:"FCM not configured"}. */
      token: function () {
        return _cb(function (id) { iappyxPush.token(id); });
      },
      /** Subscribe to incoming pushes filtered by data.topic. Plugin's
       *  `method` fires with {title, body, topic, data}. Use
       *  topic:"*" to receive every push. */
      subscribe: function (options) {
        return _cb(function (id) {
          iappyxPush.subscribe(JSON.stringify(options || {}), id);
        });
      },
      unsubscribe: function (subId) {
        return _cb(function (id) {
          iappyxPush.unsubscribe(String(subId || ''), id);
        });
      },
      list: function () {
        return _cb(function (id) { iappyxPush.list(id); });
      },
    };
  }

  var attachers = {
    httpClient: _attachHttpClient,
    storage: _attachStorage,
    secureStore: _attachSecureStore,
    scheduler: _attachScheduler,
    notifications: _attachNotifications,
    push: _attachPush,
    mqtt: _attachMqtt,
  };

  for (var i = 0; i < CAPS.length; i++) {
    var fn = attachers[CAPS[i]];
    if (fn) {
      try { fn(); } catch (e) { _log('cap ' + CAPS[i] + ' attach failed: ' + e); }
    }
  }

  window.iappyx = api;
})();
