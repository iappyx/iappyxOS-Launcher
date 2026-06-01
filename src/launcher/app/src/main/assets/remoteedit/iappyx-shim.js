/*
 * REMOTE EDIT FEATURE — browser-side iappyx shim.
 *
 * Inside an iframe served from /api/widgets/{id}/preview.html, this script
 * installs `window.iappyx` to mirror the bridge surface widgets expect on
 * the phone. Every call routes via POST /api/bridge/call to the phone's
 * BridgeProxyApi → real WidgetHost → real device data flows back.
 *
 * Two callback patterns supported, matching the launcher's behavior:
 *
 *  Pattern A (string cbId, pre-registered):
 *    window._iappyxCb.cb1 = function(result) { ... };
 *    iappyx.location.getCurrent('cb1');
 *
 *  Pattern B (function passed directly):
 *    iappyx.location.getCurrent(function(result) { ... });
 *
 * For (B) the shim generates a cbId, registers the function in
 * window._iappyxCb, and substitutes the cbId in the args before posting.
 *
 * Methods also return a Promise that resolves with the result, so modern
 * code can `await iappyx.device.getDeviceInfo()`.
 */
(function() {
  'use strict';

  if (window.iappyx) return; // Idempotent: don't double-install.

  var SESSION = window.__iappyxSession || 'default-session';
  var WIDGET_ID = window.__iappyxWidgetId || '';
  var cbCounter = 0;
  window._iappyxCb = window._iappyxCb || {};
  window._iappyxGesture = window._iappyxGesture || { setLock: function(){} };

  // Map of JS namespace → native bridge name (matches BridgeShims.WIDGET_SHIM).
  var NAMESPACES = [
    'storage', 'device', 'camera', 'location', 'notification', 'vibration',
    'clipboard', 'sensor', 'tts', 'alarm', 'audio', 'screen', 'contacts',
    'sms', 'calendar', 'biometric', 'nfc', 'sqlite', 'download', 'media',
    'httpClient', 'httpServer', 'ssh', 'smb', 'ble', 'tcp', 'udp',
    'wifiDirect', 'nsd', 'bluetooth', 'capabilities', 'intent', 'tasks',
    'trigger',
  ];

  /** Capitalise first letter — 'storage' → 'Storage' for 'iappyxStorage'. */
  function cap(s) { return s.charAt(0).toUpperCase() + s.slice(1); }

  /** Convert a single arg for the wire. Functions are intercepted before
   *  this — anything reaching here should be primitive/serializable. */
  function serializeArg(a) {
    if (a === undefined || a === null) return null;
    var t = typeof a;
    if (t === 'string' || t === 'number' || t === 'boolean') return a;
    // Objects/arrays — JSON-stringify so they cross the wire faithfully.
    // The phone-side bridges that take a String parameter for object
    // payloads expect the JSON string already (matches widget convention).
    return JSON.stringify(a);
  }

  /** Heuristic — methods that begin with subscribe / watch / listen /
   *  observe are streaming (their cbId fires repeatedly until
   *  unsubscribe). Anything else is one-shot. Mirrors server-side
   *  detection in BridgeProxyApi.isStreamingMethod. */
  function isStreamingMethod(name) {
    return /^(subscribe|watch|listen|observe)/i.test(name);
  }

  // SSE channel for streaming-bridge callbacks. OPENED LAZILY on
  // the first call() that registers a cbId — most widgets never
  // subscribe to anything (read-only HTML), and opening this on
  // every iframe-load was burning one of the browser's 6
  // connection-per-origin slots PER widget. With 4 visible
  // widgets + chat-stream + state-stream we hit the limit, and
  // subsequent fetches (drag-drop POST, etc.) silently queued
  // until something timed out. Lazy-open keeps the slot free
  // until it's actually used.
  var bridgeEvents = null;
  function ensureBridgeEvents() {
    if (bridgeEvents) return;
    bridgeEvents = new EventSource('/api/bridge/events?widgetId=' + encodeURIComponent(WIDGET_ID));
    bridgeEvents.addEventListener('bridge-cb', function(ev) {
    try {
      var data = JSON.parse(ev.data);
      var cb = window._iappyxCb[data.cbId];
      if (cb) try { cb(data.value); } catch(e) { console.warn('[iappyx-shim] cb threw:', e); }
    } catch(_){}
  });

  // Some launcher bridges (sensor.startCompass / startMagnetometer,
  // fireEvent, etc.) push results to widget JS by evaluating a script
  // string against the WebView directly — not via the cbId/deliverResult
  // path. The phone-side RemoteEditWebView captures those scripts and
  // streams them here as `eval-js` events; we run them in the iframe
  // window where the widget's actual callback functions live.
  var evalJsCount = 0;
  bridgeEvents.addEventListener('eval-js', function(ev) {
    try {
      var data = JSON.parse(ev.data);
      evalJsCount++;
      if (evalJsCount <= 3) {
        diag('eval-js', { count: evalJsCount, snippet: (data.js || '').slice(0, 120) });
      }
      // indirect eval keeps the script's scope at the global level —
      // matches how WebView.evaluateJavascript runs scripts
      try { (0, eval)(data.js); }
      catch (e) {
        diag('eval-error', { err: String(e), snippet: (data.js || '').slice(0, 120) });
      }
    } catch(_){}
  });
    bridgeEvents.addEventListener('open', function(){ diag('sse-open', {}); });
    bridgeEvents.onerror = function(){ diag('sse-error', { readyState: bridgeEvents ? bridgeEvents.readyState : -1 }); };
  }

  // Tear down all subscriptions when the iframe unloads — frees the
  // sensor / location / etc. on the phone.
  window.addEventListener('beforeunload', function() {
    try {
      navigator.sendBeacon('/api/bridge/unsubscribe_all',
        new Blob([JSON.stringify({ widgetId: WIDGET_ID })], { type: 'application/json' }));
    } catch(_){}
  });

  /** Make a call. Returns a Promise resolving to the result.
   *  Also fires window._iappyxCb[cbId](result) if a callback is in play —
   *  matching the launcher's behavior so widgets that don't await still
   *  receive their result through the cbId channel. */
  function call(bridgeJsName, method, args) {
    var nativeBridge = 'iappyx' + cap(bridgeJsName);
    var sendArgs = Array.prototype.slice.call(args);
    var cbIdToSend = null;

    // Pattern B: a function arg → wrap it in cbId pattern.
    for (var i = 0; i < sendArgs.length; i++) {
      if (typeof sendArgs[i] === 'function') {
        cbIdToSend = 'iax_proxy_' + (++cbCounter);
        window._iappyxCb[cbIdToSend] = sendArgs[i];
        sendArgs[i] = cbIdToSend;
        break;
      }
    }

    // Pattern A: last string arg might be a pre-registered cbId.
    if (cbIdToSend === null && sendArgs.length > 0) {
      var last = sendArgs[sendArgs.length - 1];
      if (typeof last === 'string' && window._iappyxCb[last]) {
        cbIdToSend = last;
      }
    }

    // If a callback is in play, the widget MAY receive bridge events
    // (cbId or eval-js) — open the SSE channel now. Idempotent.
    if (cbIdToSend) ensureBridgeEvents();

    sendArgs = sendArgs.map(serializeArg);

    var body = {
      widgetId: WIDGET_ID,
      session: SESSION,
      bridge: nativeBridge,
      method: method,
      args: sendArgs,
    };
    if (cbIdToSend) body.cbId = cbIdToSend;

    return fetch('/api/bridge/call', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).then(function(r) {
      return r.json().then(function(j) {
        if (!r.ok || j.error || j.ok === false) {
          throw new Error(j.error || ('bridge call failed: ' + r.status));
        }
        // Streaming response — values arrive via SSE. Leave the cbId
        // registered in _iappyxCb so the SSE handler can invoke it on
        // every emitted value.
        if (j.streaming) return undefined;
        // One-shot — fire the cbId callback once and clean up.
        var result = j.result;
        if (cbIdToSend && window._iappyxCb[cbIdToSend]) {
          try { window._iappyxCb[cbIdToSend](result); }
          catch (e) { console.warn('[iappyx-shim] callback threw:', e); }
          delete window._iappyxCb[cbIdToSend];
        }
        return result;
      });
    }).catch(function(err) {
      console.warn('[iappyx-shim] ' + nativeBridge + '.' + method + ' failed:', err);
      // Clean up only one-shot callbacks; streaming might still fire.
      if (cbIdToSend && !isStreamingMethod(method)) delete window._iappyxCb[cbIdToSend];
      throw err;
    });
  }

  /** Build a namespace object whose every property is a method that
   *  forwards to call(). Use a Proxy so unknown bridge methods don't
   *  throw at lookup time — only at HTTP time when the phone returns
   *  "no method." Matches @JavascriptInterface lazy-method-resolution. */
  function namespace(jsName) {
    return new Proxy({}, {
      get: function(_, method) {
        if (typeof method !== 'string') return undefined;
        // Avoid Promise/thenable detection failures and reserved names.
        if (method === 'then' || method === 'catch' || method === 'finally') return undefined;
        return function() { return call(jsName, method, arguments); };
      },
    });
  }

  // Build window.iappyx mirror.
  var api = {};
  for (var i = 0; i < NAMESPACES.length; i++) {
    api[NAMESPACES[i]] = namespace(NAMESPACES[i]);
  }

  // Top-level storage convenience — these MUST be synchronous because
  // widgets do things like `var v = iappyx.load('k'); JSON.parse(v)`. Over
  // async HTTP we'd return a Promise, JSON.parse fails, widget defaults
  // to empty. Instead we read/write a window-scoped cache that the server
  // pre-populated from the real SharedPreferences (see WidgetPreviewApi),
  // and we async-persist any writes to the phone in the background so the
  // real store stays in sync. Same for the namespaced storage methods
  // below.
  var STORE = window.__iappyxStorageCache = window.__iappyxStorageCache || {};

  // Forward diagnostic info to the parent window so it shows in the
  // editor's main console — saves the user from hunting per-iframe
  // consoles in DevTools.
  function diag(kind, payload) {
    try { console.log('[iappyx-shim:' + WIDGET_ID + ']', kind, payload); } catch(_){}
    try {
      window.parent.postMessage({
        __iappyxDiag: true,
        widgetId: WIDGET_ID,
        kind: kind,
        payload: payload,
      }, '*');
    } catch(_){}
  }
  diag('shim-loaded', {
    cacheKeys: Object.keys(STORE).length,
    sample: Object.keys(STORE).slice(0, 12),
  });
  function localSave(k, v) {
    STORE[k] = v;
    // Fire-and-forget persist; ignore the result.
    call('storage', 'save', [k, v]).catch(function(){});
  }
  function localLoad(k) {
    return Object.prototype.hasOwnProperty.call(STORE, k) ? STORE[k] : null;
  }
  function localRemove(k) {
    delete STORE[k];
    call('storage', 'remove', [k]).catch(function(){});
  }
  api.save = localSave;
  api.load = localLoad;
  api.remove = localRemove;
  // Override the namespaced storage so iappyx.storage.save/load/remove
  // also stay synchronous. Other iappyx.storage.* methods (saveFile,
  // loadFile, etc.) keep going through the proxy.
  var origStorage = api.storage;
  api.storage = new Proxy({}, {
    get: function(_, method) {
      if (typeof method !== 'string') return undefined;
      if (method === 'save') return localSave;
      if (method === 'load') return localLoad;
      if (method === 'remove') return localRemove;
      if (method === 'clear') {
        return function() {
          for (var k in STORE) delete STORE[k];
          call('storage', 'clear', []).catch(function(){});
        };
      }
      // Fall through to the proxy namespace for everything else.
      return origStorage[method];
    },
  });
  api.getPackageName = function() { return call('device', 'getPackageName', []); };
  api.getAppName = function() { return call('device', 'getAppName', []); };
  api.sharePhoto = function(b) { return call('camera', 'sharePhoto', [b]); };
  api.shareText = function(t, s) { return call('camera', 'shareText', [t, s || '']); };
  api.capabilities = function() {
    return call('capabilities', 'get', []).then(function(s) {
      try { return JSON.parse(s); } catch(_) { return s; }
    });
  };
  // No-op imperative gesture lock — has no meaning in browser context.
  api.setSwipeLock = function() {};
  // Selection observer — same as launcher shim, browser-native.
  api.onTextSelected = function(fn) {
    document.addEventListener('selectionchange', function() {
      var s = window.getSelection();
      if (s && s.toString().trim().length > 0) fn({ text: s.toString().trim() });
    });
  };

  window.iappyx = api;

  // Some widgets read window.iappyxLocation etc. directly (older convention).
  // Expose those as aliases too.
  for (var j = 0; j < NAMESPACES.length; j++) {
    var ns = NAMESPACES[j];
    var nativeName = 'iappyx' + cap(ns);
    if (!window[nativeName]) window[nativeName] = api[ns];
  }
})();
