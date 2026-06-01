/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

/**
 * Shared bridge-init JavaScript snippets, kept in one place so the real
 * cell / service AND the preview dialog all inject the exact same code.
 * Drift between "real" and "preview" bridge initialisation is a serious
 * footgun — a wallpaper that works in preview but not on the home screen
 * (or vice versa) would be confusing — so we centralise here.
 */
object BridgeShims {

    /**
     * Widget shim — namespaces every `iappyxFoo` bridge under `iappyx.foo` so
     * widget code can write `iappyx.storage.saveFile(...)` etc. (the
     * iappyxOS convention) instead of `iappyxStorage.saveFile(...)`. Mirrors
     * the iappyxOS app shell exactly so widget HTML is portable. Idempotent.
     */
    const val WIDGET_SHIM = """
        (function(){
          if (typeof window.iappyx !== 'undefined') return;
          window._iappyxCb = window._iappyxCb || {};
          window.iappyx = {
            storage: iappyxStorage, device: iappyxDevice, camera: iappyxCamera,
            location: iappyxLocation, notification: iappyxNotification,
            vibration: iappyxVibration, clipboard: iappyxClipboard,
            sensor: iappyxSensor, tts: iappyxTts,
            alarm: iappyxAlarm, audio: iappyxAudio, screen: iappyxScreen,
            contacts: iappyxContacts, sms: iappyxSms, calendar: iappyxCalendar,
            biometric: iappyxBiometric,
            nfc: iappyxNfc, sqlite: iappyxSqlite, download: iappyxDownload, media: iappyxMedia,
            httpServer: iappyxHttpServer, httpClient: iappyxHttpClient, ssh: iappyxSsh, smb: iappyxSmb,
            ble: iappyxBle, tcp: iappyxTcp, nsd: iappyxNsd, udp: iappyxUdp, wifiDirect: iappyxWifiDirect,
            save: function(k,v) { iappyxStorage.save(k, v); },
            load: function(k) { return iappyxStorage.load(k); },
            remove: function(k) { iappyxStorage.remove(k); },
            getPackageName: function() { return iappyxDevice.getPackageName(); },
            getAppName: function() { return iappyxDevice.getAppName(); },
            sharePhoto: function(b) { iappyxCamera.sharePhoto(b); },
            shareText: function(t,s) { iappyxCamera.shareText(t, s || ''); },
            /* Imperative gesture lock. Widgets that handle their own drag /
             * draw / 2D-pan logic call setSwipeLock(true) on the touchstart
             * or pointerdown of their interactive element, and
             * setSwipeLock(false) on touchend/cancel. While true, the
             * launcher cell holds the touch stream end-to-end — no page
             * swipe, no drawer/search fires from this gesture. The
             * declarative `touch-action: pan-x | none` route remains the
             * preferred mechanism for STATIC regions (sliders, maps);
             * setSwipeLock is the imperative fallback for dynamic UIs
             * where draggable elements appear, move, or disappear at
             * runtime (sticky notes, drawing canvases, image cropper). */
            setSwipeLock: function(locked) {
              try { window._iappyxGesture.setLock(!!locked); } catch(e) {}
            },
            bluetooth: iappyxBluetooth,
            intent: iappyxIntent,
            capabilities: function() { return JSON.parse(iappyxCapabilities.get()); },
            onTextSelected: function(fn) {
              document.addEventListener('selectionchange', function() {
                var s = window.getSelection();
                if (s && s.toString().trim().length > 0) { fn({text: s.toString().trim()}); }
              });
            }
          };
          // PLUGINS: BEGIN — Proxy-based plugin caller sugar. Widgets
          // call `iappyx.plugin('immich').recent({count:20})` and get a
          // Promise back. No-op when the iappyxPlugins bridge isn't
          // attached (plugin system disabled / removed).
          if (typeof iappyxPlugins !== 'undefined' && !window.iappyx.plugin) {
            window.iappyx.plugin = function(pluginId) {
              return new Proxy({}, { get: function(_, method) {
                if (typeof method !== 'string') return undefined;
                return function(args) {
                  return new Promise(function(resolve, reject) {
                    var cbId = '_cb_p_' + Date.now() + '_' + Math.random().toString(36).slice(2);
                    window._iappyxCb[cbId] = function(res) {
                      delete window._iappyxCb[cbId];
                      if (res && res.ok) resolve(res.result);
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
            };
            // List enabled plugins. Returns a Promise resolving to
            // [{id, name, version}, ...]. Use for feature-detection
            // ("if the Immich plugin is installed, render the photo
            // tile, otherwise show a setup prompt").
            window.iappyx.plugins = function() {
              return new Promise(function(resolve, reject) {
                var cbId = '_cb_pl_' + Date.now() + '_' + Math.random().toString(36).slice(2);
                window._iappyxCb[cbId] = function(res) {
                  delete window._iappyxCb[cbId];
                  if (res && res.ok) resolve(res.plugins || []);
                  else reject(new Error((res && res.error) || 'list failed'));
                };
                try { iappyxPlugins.list(cbId); }
                catch (e) { delete window._iappyxCb[cbId]; reject(e); }
              });
            };
          }
          // PLUGINS: END
        })();
    """

    /**
     * Touch-claim region probe. Walks the DOM at page load and on layout
     * changes, finds elements whose computed `touch-action` declares the
     * widget wants to handle gestures itself, and reports their device-
     * pixel bounding rects to the cell via `_iappyxHClaim.set`. Each rect
     * carries an axis tag:
     *
     *  - `'h'`  — `touch-action: pan-x` (horizontal only, e.g. slider /
     *             chip row). Claims horizontal drags; vertical falls
     *             through to drawer/search.
     *  - `'all'`— `touch-action: none` OR `pan-x pan-y` (widget owns
     *             every gesture, e.g. map pan/zoom, drawing canvas).
     *             Claims BOTH axes regardless of whether the doc can
     *             scroll — vertical drags inside the rect don't fire
     *             drawer/search.
     *
     *  Rects are padded 12 CSS px on each side so thin slider tracks
     *  still capture thumb taps.
     */
    const val H_CLAIM_PROBE = """
        (function(){
          if (window._iappyxHClaimInstalled) return;
          if (typeof window._iappyxHClaim === 'undefined') return;
          window._iappyxHClaimInstalled = true;
          function probe(){
            var rects = [];
            var dpr = window.devicePixelRatio || 1;
            var pad = 12;
            var els = document.querySelectorAll('*');
            for (var i = 0; i < els.length; i++) {
              var el = els[i];
              var ta = '';
              try { ta = getComputedStyle(el).touchAction || ''; } catch(e) { continue; }
              var hasX = ta.indexOf('pan-x') !== -1;
              var hasY = ta.indexOf('pan-y') !== -1;
              var none = ta.indexOf('none') !== -1;
              var axis;
              if (none || (hasX && hasY)) axis = 'all';
              else if (hasX) axis = 'h';
              else continue;
              var r = el.getBoundingClientRect();
              if (r.width <= 0 || r.height <= 0) continue;
              rects.push({
                x: Math.round((r.left - pad) * dpr),
                y: Math.round((r.top - pad) * dpr),
                w: Math.round((r.width + pad * 2) * dpr),
                h: Math.round((r.height + pad * 2) * dpr),
                axis: axis
              });
            }
            try { window._iappyxHClaim.set(JSON.stringify(rects)); } catch(e) {}
          }
          probe();
          // Schedule probe in the next animation frame so a burst of
          // mutations (e.g. dragging a note moves several attributes at
          // once) collapses into a single re-scan. Without debouncing we'd
          // re-probe per attribute change → expensive on dragging UIs.
          var pending = false;
          function schedule(){
            if (pending) return;
            pending = true;
            requestAnimationFrame(function(){ pending = false; probe(); });
          }
          // MutationObserver catches every DOM change: nodes added/removed,
          // attribute changes (style/transform/class), and text changes.
          // This is what makes the rect cache stay correct as widgets
          // animate, drag, or insert new draggable elements after load.
          try {
            new MutationObserver(schedule).observe(document.body, {
              childList: true, subtree: true, attributes: true, characterData: false,
            });
          } catch(e) {}
          // ResizeObserver covers size changes that don't reflect as
          // attribute mutations (e.g. flex re-layout from a sibling change).
          if (typeof ResizeObserver !== 'undefined') {
            try { new ResizeObserver(schedule).observe(document.documentElement); } catch(e) {}
          }
          // Scroll changes element rects without firing a mutation. Capture
          // phase so nested scrollers also bubble up to the document.
          window.addEventListener('scroll', schedule, true);
        })();
    """

    /**
     * Wallpaper shim — the iappyx-prefixed bridges (iappyxStorage etc.) are
     * still registered as themselves, but `window.iappyx` is set up here as
     * a regular JS object so wallpaper authors can attach push-event
     * handlers (`iappyx.onPageOffset = …`) AND use `iappyx.log`,
     * `iappyx.cb(...)` etc. The native `_iappyxBridge` is the wallpaper-only
     * extension surface (log, enableAccelerometer + push events for
     * onPageOffset/onAccelerometer/onVisibility).
     */
    const val WALLPAPER_SHIM = """
        (function() {
          var b = window._iappyxBridge;
          if (!b) return;
          var existing = window.iappyx || {};
          window._iappyxCb = window._iappyxCb || {};
          window.iappyx = Object.assign(existing, {
            log: function(m) { try { b.log(String(m)); } catch(e) {} },
            enableAccelerometer: function(e) { b.enableAccelerometer(!!e); },
            cb: function(fn) {
              return new Promise(function(resolve){
                var id = '_cb' + Date.now() + '_' + Math.random().toString(36).slice(2);
                window._iappyxCb[id] = resolve;
                try { fn(id); }
                catch(e) { delete window._iappyxCb[id]; resolve({ok:false, error: String(e)}); }
              });
            },
          });
          // PLUGINS: BEGIN — same Promise sugar wallpapers get. Mirrors
          // WIDGET_SHIM's plugin block so wallpaper authors can call
          // `await iappyx.plugin('immich').recent({count:30})` without
          // touching iappyxPlugins.invoke directly. No-op when the
          // plugin system isn't installed.
          if (typeof iappyxPlugins !== 'undefined' && !window.iappyx.plugin) {
            window.iappyx.plugin = function(pluginId) {
              return new Proxy({}, { get: function(_, method) {
                if (typeof method !== 'string') return undefined;
                return function(args) {
                  return new Promise(function(resolve, reject) {
                    var cbId = '_cb_p_' + Date.now() + '_' + Math.random().toString(36).slice(2);
                    window._iappyxCb[cbId] = function(res) {
                      delete window._iappyxCb[cbId];
                      if (res && res.ok) resolve(res.result);
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
            };
            window.iappyx.plugins = function() {
              return new Promise(function(resolve, reject) {
                var cbId = '_cb_pl_' + Date.now() + '_' + Math.random().toString(36).slice(2);
                window._iappyxCb[cbId] = function(res) {
                  delete window._iappyxCb[cbId];
                  if (res && res.ok) resolve(res.plugins || []);
                  else reject(new Error((res && res.error) || 'list failed'));
                };
                try { iappyxPlugins.list(cbId); }
                catch (e) { delete window._iappyxCb[cbId]; reject(e); }
              });
            };
          }
          // PLUGINS: END
        })();
    """
}
