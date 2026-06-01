// PLUGINS: BEGIN — shim injected into every plugin-search result WebView.
//
// The result WebView is a tiny, headless WebView (one per search hit)
// hosting the HTML the plugin returned from its `search(query)` method.
// We need to expose a minimal but useful surface so the result HTML
// can actually call back into the plugin and do work — toggle a light,
// hit a play button, etc.
//
// What's exposed:
//   - iappyx.plugin('<id>').*   — Promise-returning Proxy, same as widgets
//   - iappyx.plugins()          — list of enabled plugins
//   - iappyx.log(msg)           — bridge log for debug
//
// What's NOT exposed (deliberately):
//   - Sensors, camera, NFC, location, audio, http (use the plugin proxy)
//   - Storage (the search result is ephemeral; if you need state, the
//     plugin keeps it)
//
// Sizing: the launcher pre-sized the WebView based on the plugin's
// requested `height` in dp. If your HTML needs more room dynamically,
// call `iappyx.resize(newHeightDp)` and the host will animate to it.
(function () {
  'use strict';
  if (window.iappyx) return;
  window._iappyxCb = window._iappyxCb || {};

  function _log(msg) {
    try {
      if (typeof iappyxResultHost !== 'undefined' && iappyxResultHost.log) {
        iappyxResultHost.log(typeof msg === 'string' ? msg : JSON.stringify(msg));
      }
    } catch (_) {}
  }

  // Plugin Proxy — identical shape to the widget shim's. Returns a
  // Promise that resolves to the plugin's reply, rejects on error.
  function _pluginProxy(pluginId) {
    return new Proxy({}, { get: function (_, method) {
      if (typeof method !== 'string') return undefined;
      return function (args) {
        return new Promise(function (resolve, reject) {
          var cbId = '_cb_sr_' + Date.now() + '_' + Math.random().toString(36).slice(2);
          window._iappyxCb[cbId] = function (res) {
            delete window._iappyxCb[cbId];
            if (res && res.ok) resolve(res);
            else reject(new Error((res && res.error) || 'plugin call failed'));
          };
          try {
            iappyxPlugins.invoke(pluginId, method,
              JSON.stringify(args == null ? {} : args), cbId);
          } catch (e) {
            delete window._iappyxCb[cbId];
            reject(e);
          }
        });
      };
    }});
  }

  function _pluginsList() {
    return new Promise(function (resolve, reject) {
      var cbId = '_cb_sl_' + Date.now() + '_' + Math.random().toString(36).slice(2);
      window._iappyxCb[cbId] = function (res) {
        delete window._iappyxCb[cbId];
        if (res && res.ok) resolve(res.plugins || []);
        else reject(new Error((res && res.error) || 'list failed'));
      };
      try { iappyxPlugins.list(cbId); }
      catch (e) { delete window._iappyxCb[cbId]; reject(e); }
    });
  }

  /** Request a new row height from the launcher. Optional — the host
   *  pre-sized us based on the plugin's `height` field. Use this for
   *  expand/collapse interactions inside the result. */
  function _resize(heightDp) {
    try {
      if (typeof iappyxResultHost !== 'undefined' && iappyxResultHost.resize) {
        iappyxResultHost.resize(String(Math.max(40, heightDp | 0)));
      }
    } catch (_) {}
  }

  /** Ask the host to dismiss the search panel. Useful when the result
   *  is a "launch X" action — the user expects the panel to close on
   *  success. Plugins that just want to toggle state (lights, music)
   *  should NOT call this so the panel stays open for the next action. */
  function _dismissSearch() {
    try {
      if (typeof iappyxResultHost !== 'undefined' && iappyxResultHost.dismissSearch) {
        iappyxResultHost.dismissSearch();
      }
    } catch (_) {}
  }

  /** Open a URL in the user's default browser via ACTION_VIEW. The
   *  search panel auto-dismisses on success — the user is leaving
   *  search to view the target anyway. Used by Paperless / Immich /
   *  any plugin that surfaces external documents or web links. */
  function _openUrl(url) {
    try {
      if (typeof iappyxResultHost !== 'undefined' && iappyxResultHost.openUrl) {
        iappyxResultHost.openUrl(String(url || ''));
      }
    } catch (_) {}
  }

  window.iappyx = {
    plugin: _pluginProxy,
    plugins: _pluginsList,
    log: _log,
    resize: _resize,
    dismissSearch: _dismissSearch,
    openUrl: _openUrl,
  };
})();
// PLUGINS: END
