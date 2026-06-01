// REMOTE EDIT FEATURE — browser editor SPA.
// Plain JS, no framework, no bundler. Direct DOM manipulation.

(function(){
  'use strict';

  // ── State ──────────────────────────────────────────────────
  var state = {
    layout: null,              // browser-shaped layout JSON
    widgets: [],               // [{id, title, ...}]
    apps: [],                  // [{pkg, activity, label}]
    currentPage: 0,
    pendingTarget: null,       // {page, row, col} when popover is open from an empty cell
    chat: [],                  // [{role, text, toolCalls?}]
    streaming: false,
    mode: 'edit',              // 'edit' | 'run' — toggled by #mode-toggle
    badgeCounts: {},           // pkg → unread-notification count. Empty
                               // when the launcher's notificationBadges-
                               // Enabled pref is off. Pushed live via
                               // /api/state/stream `type:"badges"` events.
    selectedIds: {},           // map of placement.id → true. Single-click
                               // replaces; ⇧/⌘-click extend; ⌘A select-all.
                               // Distinct from `inspectId` (which is the
                               // single cell whose detail pane is open).
  };

  // ── DOM refs ───────────────────────────────────────────────
  var $ = function(s){ return document.querySelector(s); };
  var els = {
    board: $('#board'),
    grid: $('#grid'),
    dock: $('#dock'),
    pages: $('#pages-tabs'),
    modeToggle: $('#mode-toggle'),
    messages: $('#messages'),
    prompt: $('#prompt'),
    send: $('#send'),
    chatClear: $('#chat-clear'),
    thinking: $('#thinking'),
    composer: $('#composer'),
    attachBtn: $('#attach'),
    attachFile: $('#attach-file'),
    attachPreview: $('#attach-preview'),
    attachThumb: $('#attach-thumb'),
    attachName: $('#attach-name'),
    attachClear: $('#attach-clear'),
    app: $('#app'),
    inspect: $('#inspect'),
    inspectTitle: $('#inspect-title'),
    inspectBody: $('#inspect-body'),
    inspectClose: $('#inspect-close'),
    statusDot: $('#status-dot'),
    statusText: $('#status-text'),
    disconnect: $('#disconnect'),
    popoverBg: $('#popover-bg'),
    popoverTabs: document.querySelectorAll('#popover-tabs button'),
    popoverList: $('#popover-list'),
    popoverQ: $('#popover-q'),
    popoverFoot: $('#popover-foot'),
    popoverGen: $('#popover-gen-input'),
    popoverClose: $('#popover-close'),
    popoverSearch: $('#popover-search'),
  };

  // ── Icon URLs ──────────────────────────────────────────────
  // Counter that increments every time we know the active icon filter
  // (or icon-related state) changed. Appending it to /api/icons/{pkg}
  // URLs as `?v=N` forces the browser to re-fetch — without this, a
  // filter swap wouldn't show in the editor until the cached PNG
  // expired (60s). Bumped in setMode (refresh on tab return),
  // bumpIconVersion() callers after settings PATCH / icon filter
  // activation, etc.
  var iconVersion = 0;
  // Latest active icon filter slug. Threaded through /api/icons/{pkg}
  // as `?filter=<slug>` so an OPTIMISTIC swap (tile-clicked before the
  // server has committed prefs) renders with the new filter on the
  // very next icon fetch — no race against the in-flight POST.
  // Initialised from state.viewPrefs once the first /api/state lands.
  var activeIconFilter = '';
  function bumpIconVersion(){ iconVersion++; }
  function iconUrl(pkg){
    var url = '/api/icons/' + encodeURIComponent(pkg) + '?v=' + iconVersion;
    if (activeIconFilter) url += '&filter=' + encodeURIComponent(activeIconFilter);
    return url;
  }

  // ── HTTP ───────────────────────────────────────────────────
  function api(method, path, body){
    return fetch(path, {
      method: method,
      headers: body ? {'Content-Type': 'application/json'} : {},
      body: body ? JSON.stringify(body) : undefined,
    }).then(function(r){
      if (r.status === 401) { window.location = '/pair'; throw new Error('unauthorized'); }
      return r.json().then(function(j){
        if (!r.ok) throw new Error(j.error || ('HTTP ' + r.status));
        return j;
      });
    });
  }

  // ── Initial load ───────────────────────────────────────────
  function bootstrap(){
    Promise.all([
      api('GET', '/api/state'),
      api('GET', '/api/apps'),
      api('GET', '/api/chat'),
    ]).then(function(results){
      state.layout = results[0].layout;
      state.widgets = results[0].widgets;
      state.apps = results[1];
      state.chat = results[2];
      state.badgeCounts = results[0].badgeCounts || {};
      // viewPrefs.showDockLabels controls whether dock cells get a
      // caption under the icon. With labels off, the dock collapses
      // to a tight square strip — matching the on-device default.
      state.viewPrefs = results[0].viewPrefs || {};
      activeIconFilter = state.viewPrefs.iconFilter || '';
      applyViewPrefs();
      // Default to Run mode — using the editor mostly means using the
      // widgets, not rearranging the grid. ⌘E to flip into Edit.
      setMode('run');
      renderChat();
      openEventStream();
      openStateStream();
      setStatus(true, 'connected');
      refreshWallpaper();
    }).catch(function(err){
      setStatus(false, 'failed: ' + err.message);
    });
  }

  // ── Status bar ─────────────────────────────────────────────
  function setStatus(ok, text){
    els.statusDot.classList.toggle('bad', !ok);
    els.statusText.textContent = text;
  }

  // ── Pages tabs ─────────────────────────────────────────────
  function pageLabel(page, idx){
    return (page && page.name && page.name.trim()) || ('Page ' + (idx + 1));
  }
  function renderPages(){
    els.pages.innerHTML = '';
    state.layout.pages.forEach(function(page, idx){
      var t = document.createElement('div');
      t.className = 'page-tab' + (idx === state.currentPage ? ' active' : '');
      // Tab is draggable only in edit mode — non-edit users shouldn't
      // accidentally re-order their home screen by mis-clicking.
      if (state.mode === 'edit') {
        t.draggable = true;
        t.dataset.pageIdx = idx;
      }
      var label = document.createElement('span');
      label.className = 'lbl';
      label.textContent = pageLabel(page, idx);
      t.appendChild(label);
      t.addEventListener('click', function(){
        state.currentPage = idx;
        // Selection is page-local in the user's mental model — clear
        // on page change so a stale off-screen selection doesn't
        // surprise the next Cmd-A or Delete.
        state.selectedIds = {};
        renderAll();
      });
      if (state.mode === 'edit') {
        // Double-click → inline rename. Cheap UX, no extra chrome.
        t.addEventListener('dblclick', function(e){
          e.stopPropagation();
          var current = (page.name || '').trim();
          var v = window.prompt('Rename page (empty to clear)', current);
          if (v == null) return;
          api('POST', '/api/layout/page_rename', { idx: idx, name: v.trim() })
            .then(function(r){
              state.layout = r.layout; renderAll();
            }).catch(toastError);
        });
        // HTML5 drag-and-drop reordering. The drag image is the tab
        // itself; dropping onto another tab swaps via page_reorder.
        t.addEventListener('dragstart', function(e){
          try { e.dataTransfer.setData('text/iappyx-page', String(idx)); } catch (_) {}
          e.dataTransfer.effectAllowed = 'move';
          t.classList.add('dragging');
        });
        t.addEventListener('dragend', function(){ t.classList.remove('dragging'); });
        t.addEventListener('dragover', function(e){
          if (e.dataTransfer && Array.prototype.indexOf.call(
              e.dataTransfer.types || [], 'text/iappyx-page') >= 0) {
            e.preventDefault(); t.classList.add('drop-target');
          }
        });
        t.addEventListener('dragleave', function(){ t.classList.remove('drop-target'); });
        t.addEventListener('drop', function(e){
          t.classList.remove('drop-target');
          var fromIdx = parseInt(e.dataTransfer.getData('text/iappyx-page'), 10);
          if (isNaN(fromIdx) || fromIdx === idx) return;
          e.preventDefault();
          // Build new order: take the existing indices, splice the
          // dragged one out and re-insert before the drop target.
          var n = state.layout.pages.length;
          var order = [];
          for (var i = 0; i < n; i++) order.push(i);
          var moved = order.splice(fromIdx, 1)[0];
          order.splice(idx, 0, moved);
          api('POST', '/api/layout/page_reorder', { order: order }).then(function(r){
            state.layout = r.layout;
            // currentPage was tracking the OLD index — translate it
            // through the permutation so the user stays on the same
            // page they were viewing.
            var prev = state.currentPage;
            var newCur = order.indexOf(prev);
            if (newCur >= 0) state.currentPage = newCur;
            renderAll();
          }).catch(toastError);
        });
      }
      // Edit-only: page-rename chip (✎). Dblclick on a draggable tab
      // gets swallowed by Chrome's drag-detection — an explicit chip
      // is the reliable affordance.
      if (state.mode === 'edit') {
        var ren = document.createElement('span');
        ren.className = 'ren'; ren.textContent = '✎';
        ren.title = 'Rename page';
        ren.addEventListener('mousedown', function(e){ e.stopPropagation(); });
        ren.addEventListener('click', function(e){
          e.stopPropagation();
          var current = (page.name || '').trim();
          var v = window.prompt('Rename page (empty to clear)', current);
          if (v == null) return;
          api('POST', '/api/layout/page_rename', { idx: idx, name: v.trim() })
            .then(function(r){ state.layout = r.layout; renderAll(); })
            .catch(toastError);
        });
        t.appendChild(ren);
      }
      // Edit-only: page-delete affordance (× chip) and add-page button.
      if (state.mode === 'edit' && state.layout.pages.length > 1) {
        var del = document.createElement('span');
        del.className = 'del'; del.textContent = '✕';
        del.addEventListener('click', function(e){
          e.stopPropagation();
          var n = page.placements.length;
          var msg = n > 0
            ? '"' + pageLabel(page, idx) + '" contains ' + n + ' cell' + (n === 1 ? '' : 's') + '. Delete the page and all of them?'
            : 'Delete "' + pageLabel(page, idx) + '"?';
          confirmDestroy({ title: 'Delete page?', message: msg }).then(function(yes){
            if (!yes) return;
            api('POST', '/api/layout/page_delete', { idx: idx, force: true }).then(function(r){
              state.layout = r.layout;
              if (state.currentPage >= state.layout.pages.length) state.currentPage = state.layout.pages.length - 1;
              renderAll();
            }).catch(toastError);
          });
        });
        t.appendChild(del);
      }
      els.pages.appendChild(t);
    });
    if (state.mode === 'edit') {
      var add = document.createElement('div');
      add.id = 'add-page'; add.textContent = '+ Add page';
      add.addEventListener('click', function(){
        api('POST', '/api/layout/page_add').then(function(r){
          state.layout = r.layout;
          state.currentPage = state.layout.pages.length - 1;
          renderAll();
        }).catch(toastError);
      });
      els.pages.appendChild(add);
    }
  }

  // ── Grid ───────────────────────────────────────────────────
  // Cell-node cache keyed by placement id. Lets renderGrid REUSE iframes
  // across renders so widget JS state, sensor subscriptions, and live
  // data don't reset on every drag / AI tool / state change. Without
  // this, the editor "flapped" — every iframe destroyed and recreated
  // multiple times per second, restarting the compass, re-fetching
  // weather, breaking SSE subscriptions.
  //
  // Stored shape per id: { node, type, widgetId, pkg, page }.
  // `type/widgetId/pkg/page` form a recreation key — if any of those
  // change, the cell is rebuilt (different identity → fresh iframe).
  // Otherwise we update only grid position + dataset and reuse.
  var cellNodes = {};

  // Look up the live placement object for a cell (handlers attached
  // ONCE at create-time use this to read the latest position / size /
  // metadata at click-time, not the stale closure-captured value).
  function placementById(id) {
    if (!state.layout) return null;
    for (var i = 0; i < state.layout.pages.length; i++) {
      var found = state.layout.pages[i].placements.find(function(x){ return x.id === id; });
      if (found) return found;
    }
    var d = (state.layout.dock || []).find(function(x){ return x.id === id; });
    return d || null;
  }

  function renderGrid(){
    var L = state.layout;
    var page = L.pages[state.currentPage] || { placements: [] };
    // Grid sizing is finalized in sizeGridSquareCells() after the
    // browser computes els.grid.clientWidth. Setting the columns to
    // 1fr first lets the grid expand to fill the row; the sizing
    // pass then replaces both columns and rows with explicit px so
    // every cell is exactly square (aspect-ratio on the grid was
    // close but the row-gap/column-gap asymmetry made dock cells
    // skew at non-equal aspect ratios).
    els.grid.style.gridTemplateColumns = 'repeat(' + L.cols + ', 1fr)';
    els.grid.style.gridTemplateRows = 'repeat(' + L.rows + ', 1fr)';

    var occupied = {};
    page.placements.forEach(function(p){
      for (var r = p.row; r < p.row + p.h; r++)
        for (var c = p.col; c < p.col + p.w; c++)
          occupied[r + ',' + c] = p.id;
    });

    // Build the set of placements that should be visible in the grid
    // right now (this page only). Cells not in this set get evicted.
    var liveIds = {};
    page.placements.forEach(function(p){ liveIds[p.id] = true; });

    // Evict cells that are no longer on this page (page change, deleted,
    // moved to dock, moved to a different page). DOM removal also
    // triggers iframe unload → unsubscribe_all on the phone.
    for (var id in cellNodes) {
      if (cellNodes[id].page !== state.currentPage || !liveIds[id]) {
        try { cellNodes[id].node.remove(); } catch(_){}
        delete cellNodes[id];
      }
    }

    // Reuse-or-create per placement.
    page.placements.forEach(function(p){
      var existing = cellNodes[p.id];
      // Recreation key: if any of these change, the cell's identity is
      // different and we rebuild from scratch.
      var typeChanged = !existing || existing.type !== p.type;
      var widgetChanged = !existing || existing.widgetId !== p.widgetId;
      var pkgChanged = !existing || existing.pkg !== p.pkg;
      var folderChanged = !existing || existing.folderName !== p.folderName;
      if (typeChanged || widgetChanged || pkgChanged || folderChanged) {
        if (existing) try { existing.node.remove(); } catch(_){}
        var fresh = createCellNode(p);
        cellNodes[p.id] = {
          node: fresh, type: p.type, widgetId: p.widgetId,
          pkg: p.pkg, folderName: p.folderName, page: state.currentPage,
        };
        els.grid.appendChild(fresh);
      } else {
        // Reuse — keep the iframe alive, just update geometry + state.
        existing.page = state.currentPage;
      }
      var node = cellNodes[p.id].node;
      // Always-fresh: grid position, dataset, draggability, mode classes,
      // selection outline. Cheap.
      var typeClass = p.type === 'GENERATED_WIDGET' ? 'widget' : (p.type === 'FOLDER' ? 'folder' : 'icon');
      var selectedClass = state.selectedIds[p.id] ? ' selected' : '';
      node.className = 'cell ' + typeClass + selectedClass;
      node.style.gridRow = (p.row + 1) + ' / span ' + p.h;
      node.style.gridColumn = (p.col + 1) + ' / span ' + p.w;
      node.draggable = (state.mode === 'edit');
      node.dataset.id = p.id;
      node.dataset.row = p.row;
      node.dataset.col = p.col;
      node.dataset.page = state.currentPage;
    });

    // Empty cells: cheap to recreate every render — no iframes to lose.
    // Remove old empty cells first.
    var emptyOld = els.grid.querySelectorAll('.cell.empty');
    for (var i = 0; i < emptyOld.length; i++) emptyOld[i].remove();
    for (var r = 0; r < L.rows; r++) for (var c = 0; c < L.cols; c++) {
      if (occupied[r + ',' + c]) continue;
      var emp = document.createElement('div');
      emp.className = 'cell empty';
      emp.style.gridRow = (r + 1); emp.style.gridColumn = (c + 1);
      emp.dataset.row = r; emp.dataset.col = c;
      emp.dataset.page = state.currentPage;
      emp.addEventListener('click', function(e){
        var t = e.currentTarget;
        openPopover({ page: parseInt(t.dataset.page,10), row: parseInt(t.dataset.row,10), col: parseInt(t.dataset.col,10) });
      });
      emp.addEventListener('dragover', function(e){
        // ALWAYS preventDefault so the drop event will fire on
        // release. Earlier we gated this on a client-side fit
        // check, but that silently blocked valid drops in cases
        // where the math disagreed with the server. Server is
        // authoritative — let it accept or reject, surface any
        // error via toastError. The ghost still tints by validity
        // (green when client thinks fit, faded-red when not), but
        // doesn't block the drop attempt.
        e.preventDefault();
        if (!dragData) return;
        var row = parseInt(e.currentTarget.dataset.row, 10);
        var col = parseInt(e.currentTarget.dataset.col, 10);
        var fits = dropFitsAt(dragData.id, row, col, dragData.w, dragData.h);
        ensureDropGhost(dragData.w, dragData.h, e.currentTarget);
        if (dropGhost) {
          dropGhost.classList.toggle('invalid', !fits);
        }
      });
      emp.addEventListener('drop', onDropEmpty);
      els.grid.appendChild(emp);
    }
  }

  // Create a fresh DOM node for a placement. Event handlers are attached
  // ONCE here — they look up the current placement via [placementById]
  // every fire so they stay correct after subsequent reuses.
  function createCellNode(p){
    var cell = document.createElement('div');
    cell.dataset.id = p.id;
    fillCell(cell, p);

    var del = document.createElement('div');
    del.className = 'delete-btn'; del.textContent = '×';
    del.addEventListener('click', function(e){
      e.stopPropagation();
      var live = placementById(cell.dataset.id) || p;
      confirmDestroy({
        title: 'Delete cell?',
        message: 'Remove "' + labelForCell(live) + '" from page ' + (state.currentPage + 1) + '?',
      }).then(function(yes){
        if (!yes) return;
        api('POST', '/api/layout/delete', { id: live.id }).then(function(r){
          state.layout = r.layout;
          if (state.inspectId === live.id) closeInspect();
          renderAll();
        }).catch(toastError);
      });
    });
    cell.appendChild(del);

    // Resize handle on widgets + folders. Looks up live placement at
    // pointerdown time so size info is current.
    if (p.type === 'GENERATED_WIDGET' || p.type === 'FOLDER') {
      var rh = document.createElement('div');
      rh.className = 'resize-handle';
      rh.addEventListener('pointerdown', function(e){
        var live = placementById(cell.dataset.id);
        if (live) startResize(e, live);
      });
      cell.appendChild(rh);
    }

    // Drag / click handlers — gated by state.mode at fire time, not at
    // creation time, so toggling Edit/Run doesn't require recreation.
    cell.addEventListener('dragstart', function(e){
      if (state.mode !== 'edit') { e.preventDefault(); return; }
      onDragStart(e);
    });
    cell.addEventListener('dragend', onDragEnd);
    cell.addEventListener('dragover', function(e){
      if (state.mode !== 'edit') return;
      onCellDragOver(e);
    });
    cell.addEventListener('drop', function(e){
      if (state.mode !== 'edit') return;
      onCellDrop(e);
    });
    cell.addEventListener('click', function(e){
      if (state.mode !== 'edit') return;
      if (e.target.classList && (e.target.classList.contains('delete-btn') ||
          e.target.classList.contains('resize-handle'))) return;
      var id = cell.dataset.id;
      // ⇧-click / ⌘-click / ctrl-click → multi-select, NO inspect.
      // Plain click → replace selection with [id] and open inspect.
      if (e.shiftKey || e.metaKey || e.ctrlKey) {
        if (state.selectedIds[id]) delete state.selectedIds[id];
        else state.selectedIds[id] = true;
        // Inspect panel is single-cell only; close it once we have a
        // multi-selection so the user isn't confused about which cell
        // the inspect panel is targeting.
        if (countSelected() !== 1) closeInspect();
        renderGrid();
        return;
      }
      state.selectedIds = {};
      state.selectedIds[id] = true;
      openInspect(id);
      renderGrid();
    });
    return cell;
  }

  function countSelected(){
    var n = 0; for (var k in state.selectedIds) if (state.selectedIds[k]) n++; return n;
  }
  function selectedIdList(){
    var out = []; for (var k in state.selectedIds) if (state.selectedIds[k]) out.push(k); return out;
  }

  function fillCell(node, p){
    var icon = document.createElement('div'); icon.className = 'icon';
    var label = document.createElement('div'); label.className = 'label';
    if (p.type === 'ICON' && p.pkg) {
      icon.style.backgroundImage = 'url(' + iconUrl(p.pkg) + ')';
      var app = state.apps.find(function(a){ return a.pkg === p.pkg; });
      label.textContent = app ? app.label : p.pkg.split('.').pop();
    } else if (p.type === 'GENERATED_WIDGET') {
      var w = state.widgets.find(function(w){ return w.id === p.widgetId; });
      // Live iframe — renders the widget HTML against a real bridge proxy.
      // Each placement gets its own session so saveFile / sqlite are
      // isolated per widget instance, matching the phone's WidgetSandbox.
      var iframe = document.createElement('iframe');
      iframe.src = '/api/widgets/' + encodeURIComponent(p.widgetId) +
        '/preview.html?session=' + encodeURIComponent('cell-' + p.id);
      // position:absolute + inset:0 sidesteps the cell's flex layout so the
      // iframe fills the cell rather than getting flex-shrunk to zero.
      iframe.style.cssText = 'position:absolute; inset:0; width:100%; height:100%; border:0; border-radius:12px; background:transparent; pointer-events:none;';
      iframe.setAttribute('sandbox', 'allow-scripts allow-same-origin allow-forms');
      iframe.setAttribute('allow', 'camera; microphone; geolocation; clipboard-read; clipboard-write');
      iframe.setAttribute('loading', 'lazy');
      // Replace the icon div placeholder entirely.
      icon.style.display = 'none';
      node.appendChild(iframe);
      // Title chip in a corner so the widget identity is still visible.
      label.textContent = w ? w.title : 'widget';
      label.style.position = 'absolute'; label.style.bottom = '4px'; label.style.left = '6px'; label.style.right = '6px';
      label.style.background = 'rgba(0,0,0,0.55)'; label.style.padding = '2px 6px';
      label.style.borderRadius = '6px'; label.style.fontSize = '10px';
      label.style.zIndex = '2'; label.style.pointerEvents = 'none';
    } else if (p.type === 'FOLDER') {
      // Square dark panel with a 2×2 peek of the first 4 member-app
      // icons. Sizing chrome lives in the `.cell.folder .icon` CSS
      // rule so it stays in sync with `.cell .icon` (icons + folders
      // share the same 78%-of-min-dimension formula → visually
      // matched, like on the phone).
      var items = (p.folderItems || []).slice(0, 4);
      for (var fi = 0; fi < 4; fi++) {
        var slot = document.createElement('div');
        slot.style.cssText = 'min-width:0; min-height:0; border-radius:4px;';
        var item = items[fi];
        if (item && item.pkg) {
          slot.style.backgroundImage = 'url(' + iconUrl(item.pkg) + ')';
          slot.style.backgroundSize = 'contain';
          slot.style.backgroundPosition = 'center';
          slot.style.backgroundRepeat = 'no-repeat';
        }
        icon.appendChild(slot);
      }
      label.textContent = p.folderName || 'Folder';
    } else if (p.type === 'APP_DRAWER') {
      // Mirror the on-device AppDrawerCell.GlyphView: a rounded
      // squircle tile (22% corner radius) in the accent colour
      // with a 3×3 grid of rounded-square dots painted on top.
      // Rendered as inline SVG inside the .icon container so it
      // scales with --icon-size automatically; preserveAspectRatio
      // keeps it square. The on-device glyph sizes at 72% of cell
      // min-dim (vs 82% for app/folder icons) — we get there by
      // letting the SVG fill 88% of the icon area (0.72/0.82). No
      // label below: AppDrawerCell.showLabel is a no-op on phone.
      var dots = '';
      for (var rr = -1; rr <= 1; rr++) for (var cc = -1; cc <= 1; cc++) {
        var dx = 50 + cc * 27, dy = 50 + rr * 27;
        dots += '<rect x="' + (dx - 8) + '" y="' + (dy - 8) +
          '" width="16" height="16" rx="3" ry="3"/>';
      }
      icon.innerHTML =
        '<svg viewBox="0 0 100 100" preserveAspectRatio="xMidYMid meet"' +
        ' style="width:88%; height:88%; display:block; overflow:visible;">' +
        '<rect width="100" height="100" rx="22" ry="22" fill="var(--accent)"/>' +
        '<g fill="#0D0D1A">' + dots + '</g>' +
        '</svg>';
      // Keep the .icon as a transparent container — the SVG owns
      // the visual. Centre the SVG via flex.
      icon.style.background = 'transparent';
      icon.style.border = '0';
      icon.style.display = 'flex';
      icon.style.alignItems = 'center';
      icon.style.justifyContent = 'center';
      label.style.display = 'none';
    }
    node.appendChild(icon);
    node.appendChild(label);
    applyBadge(node, p);
  }

  /** Stamp / remove the red notification-badge pill on this cell's
   *  icon based on `state.badgeCounts`. Mirrors the on-device
   *  IconCell + FolderCell dispatchDraw behaviour:
   *   - icon cells use their own pkg's count
   *   - folder cells sum the counts of all member packages
   *   - widget / app-drawer cells never get a badge
   *  Counts ≤ 0 produce no badge; > 99 renders as "99+". */
  function applyBadge(node, p){
    var icon = node.querySelector('.icon');
    if (!icon) return;
    var existing = icon.querySelector('.badge');
    var count = 0;
    if (p.type === 'ICON' && p.pkg) {
      count = state.badgeCounts[p.pkg] || 0;
    } else if (p.type === 'FOLDER' && p.folderItems) {
      for (var i = 0; i < p.folderItems.length; i++) {
        count += state.badgeCounts[p.folderItems[i].pkg] || 0;
      }
    }
    if (count <= 0) {
      if (existing) existing.remove();
      return;
    }
    var badge = existing || document.createElement('div');
    badge.className = 'badge';
    badge.textContent = count > 99 ? '99+' : String(count);
    if (!existing) icon.appendChild(badge);
  }

  /** Refresh badges on every visible cell after a state.badgeCounts
   *  change. Updates in-place via [applyBadge] so we don't tear down
   *  cellNodes — icon-cell DOM keeps its identity, the badge pill
   *  just appears/updates/disappears. */
  function refreshAllBadges(){
    if (!state.layout) return;
    var page = state.layout.pages[state.currentPage];
    if (page) for (var i = 0; i < page.placements.length; i++) {
      var p = page.placements[i];
      var entry = cellNodes[p.id];
      if (entry) applyBadge(entry.node, p);
    }
    var dock = state.layout.dock || [];
    var dockChildren = els.dock.children;
    for (var d = 0; d < dock.length; d++) {
      // Dock cells are rebuilt on every renderDock — find by id.
      var dp = dock[d];
      for (var c = 0; c < dockChildren.length; c++) {
        if (dockChildren[c].dataset.id === dp.id) {
          applyBadge(dockChildren[c], dp);
          break;
        }
      }
    }
  }

  // ── Drag and drop ──────────────────────────────────────────
  var dragData = null;
  function onDragStart(e){
    var c = e.currentTarget;
    // Capture the source cell's footprint so the drop-ghost can
    // size itself to the same w×h while dragging. Look the
    // placement up by id rather than reading inline styles (which
    // are CSS values like `1 / span 2` and need parsing).
    var p = placementById(c.dataset.id);
    dragData = {
      id: c.dataset.id,
      fromPage: parseInt(c.dataset.page, 10),
      w: p ? p.w : 1,
      h: p ? p.h : 1,
    };
    e.dataTransfer.setData('text/plain', dragData.id);
    e.dataTransfer.effectAllowed = 'move';
    c.classList.add('dragging');
  }
  function onDragEnd(e){
    e.currentTarget.classList.remove('dragging');
    dragData = null;
    hideDropGhost();
  }

  // ── Drop-target ghost ─────────────────────────────────────
  // Mirrors the resize-ghost: a dashed accent rectangle that
  // shows the exact cells the dragged item would land on,
  // sized to the source's w×h span. Without this, only the
  // source cell faded (`.dragging { opacity:.4 }`) — the user
  // couldn't tell where it would actually drop until release.
  //
  // The ghost lives in <body> as a fixed-position overlay
  // computed from the target cell's getBoundingClientRect. An
  // earlier in-grid implementation broke drop events on some
  // browsers (the ghost ended up as the topmost element under
  // the cursor and routed drop to itself instead of the cell).
  var dropGhost = null;
  function ensureDropGhost(w, h, targetCell){
    if (!targetCell) return;
    var rect = targetCell.getBoundingClientRect();
    var cellW = rect.width;
    var cellH = rect.height;
    if (cellW <= 0 || cellH <= 0) return;
    // Grid gap is set in CSS via `gap:8px` on #grid. Hard-code
    // here so the math is self-contained; if the gap ever
    // changes, change both places (or read it back from
    // getComputedStyle, but for one constant it's fine inline).
    var GAP = 8;
    var width = w * cellW + (w - 1) * GAP;
    var height = h * cellH + (h - 1) * GAP;
    if (!dropGhost) {
      dropGhost = document.createElement('div');
      dropGhost.className = 'drop-ghost';
      document.body.appendChild(dropGhost);
    }
    dropGhost.style.left = rect.left + 'px';
    dropGhost.style.top = rect.top + 'px';
    dropGhost.style.width = width + 'px';
    dropGhost.style.height = height + 'px';
  }
  function hideDropGhost(){
    if (!dropGhost) return;
    try { dropGhost.remove(); } catch(_){}
    dropGhost = null;
  }

  /** Client-side feasibility check for a move/place at (row, col)
   *  on [state.currentPage]. Mirrors the server's `fitsOnPage`
   *  rule: source's w×h must stay inside the grid AND not overlap
   *  any other placement on the page. ignoreId lets the source
   *  cell pass its own bounds (so dragging in-place is a no-op,
   *  not an "overlap with self"). Returns true when the drop
   *  would succeed; we use the result to gate both the drop-ghost
   *  visibility AND the preventDefault that allows the browser
   *  drop event to fire. */
  function dropFitsAt(srcId, row, col, w, h){
    if (!state.layout) return false;
    var L = state.layout;
    if (row < 0 || col < 0) return false;
    if (col + w > L.cols) return false;
    if (row + h > L.rows) return false;
    var page = L.pages[state.currentPage];
    if (!page) return false;
    for (var i = 0; i < page.placements.length; i++) {
      var pp = page.placements[i];
      if (pp.id === srcId) continue;
      if (row < pp.row + pp.h && row + h > pp.row &&
          col < pp.col + pp.w && col + w > pp.col) {
        return false;
      }
    }
    return true;
  }
  /** Cursor-to-grid-cell converter. Given clientX/clientY from a
   *  drag event, returns {row, col} based on cell sizes the grid
   *  is currently rendering at. The per-cell drop handlers cover
   *  the common case (release-over-cell), but if the user happens
   *  to release in the 8px gap between cells, no cell receives
   *  the drop → widget silently stays put. The grid-level
   *  listeners below use this to fall back to the nearest cell. */
  function cellFromCursor(clientX, clientY){
    if (!state.layout) return null;
    var rect = els.grid.getBoundingClientRect();
    var GAP = 8;
    // contentWidth/height already account for grid padding (we set
    // it to 0, but kept the helper for future-proofing).
    var gw = contentWidth(els.grid);
    var gh = contentHeight(els.grid);
    var L = state.layout;
    if (gw <= 0 || gh <= 0) return null;
    var cellW = (gw - (L.cols - 1) * GAP) / L.cols;
    var cellH = (gh - (L.rows - 1) * GAP) / L.rows;
    if (cellW <= 0 || cellH <= 0) return null;
    var x = clientX - rect.left;
    var y = clientY - rect.top;
    // Use (cell + gap) as the step so positions in gaps round to
    // the nearest cell on either side. clamp to grid range.
    var col = Math.max(0, Math.min(L.cols - 1,
      Math.floor(x / (cellW + GAP))));
    var row = Math.max(0, Math.min(L.rows - 1,
      Math.floor(y / (cellH + GAP))));
    return { row: row, col: col };
  }

  // Grid-level dragover + drop fallback. The per-cell handlers
  // still fire first (and stopPropagation in their drop handler),
  // so this only kicks in when the user releases in a gap or on
  // the grid background.
  //
  // IMPORTANT — defer the API call. Chrome/Webkit pause async
  // microtasks (including fetch().then) for the duration of a
  // drag-and-drop operation; the response handler sits frozen
  // until the next unrelated input event flushes the queue. The
  // user-visible symptom was "I drop a widget, nothing happens,
  // then I switch pages and the move suddenly takes effect."
  // Wrapping the API call in setTimeout breaks out of the drag
  // microtask freeze and lets the response process immediately.
  els.grid.addEventListener('dragover', function(e){
    if (!dragData) return;
    e.preventDefault();
  });
  els.grid.addEventListener('drop', function(e){
    if (!dragData) return;
    e.preventDefault();
    hideDropGhost();
    var spot = cellFromCursor(e.clientX, e.clientY);
    if (!spot) return;
    var page = state.layout.pages[state.currentPage];
    var hitId = null;
    if (page) {
      for (var i = 0; i < page.placements.length; i++) {
        var pp = page.placements[i];
        if (spot.row >= pp.row && spot.row < pp.row + pp.h &&
            spot.col >= pp.col && spot.col < pp.col + pp.w) {
          hitId = pp.id; break;
        }
      }
    }
    var dragId = dragData.id;
    setTimeout(function(){
      if (hitId && hitId !== dragId) {
        api('POST', '/api/layout/swap', { a: dragId, b: hitId })
          .then(function(r){ state.layout = r.layout; renderAll(); })
          .catch(toastError);
      } else if (!hitId) {
        api('POST', '/api/layout/move', {
          id: dragId, page: state.currentPage,
          row: spot.row, col: spot.col,
        }).then(function(r){ state.layout = r.layout; renderAll(); })
          .catch(toastError);
      }
    }, 0);
  });

  function onDropEmpty(e){
    e.preventDefault();
    e.stopPropagation();
    hideDropGhost();
    var t = e.currentTarget;
    var targetPage = parseInt(t.dataset.page,10);
    var row = parseInt(t.dataset.row,10);
    var col = parseInt(t.dataset.col,10);
    // External drop from popover (app or widget pickitem)? Check the
    // custom MIME first; falls through to in-grid move if absent.
    var pickRaw = '';
    try { pickRaw = e.dataTransfer.getData('application/x-iappyx-pick'); } catch(_){}
    if (pickRaw) {
      var pick;
      try { pick = JSON.parse(pickRaw); } catch(_){ pick = null; }
      if (!pick) return;
      if (pick.kind === 'app') {
        api('POST', '/api/layout/place_app', {
          pkg: pick.pkg, activity: pick.activity || null,
          page: targetPage, row: row, col: col,
        }).then(function(r){ state.layout = r.layout; renderAll(); }).catch(toastError);
      } else if (pick.kind === 'widget') {
        api('POST', '/api/layout/place_widget', {
          widgetId: pick.widgetId, widgetAsset: pick.widgetAsset || undefined,
          page: targetPage, row: row, col: col,
        }).then(function(r){ state.layout = r.layout; renderAll(); }).catch(toastError);
      }
      return;
    }
    if (!dragData) return;
    // Defer the fetch out of the drop handler — browsers (Chrome/
    // Webkit) stall fetch().then microtasks until the drag context
    // fully clears, which left the move POST mid-flight with no
    // response ever processed.
    var dragId = dragData.id;
    setTimeout(function(){
      api('POST', '/api/layout/move', { id: dragId, page: targetPage, row: row, col: col })
        .then(function(r){ state.layout = r.layout; renderAll(); })
        .catch(toastError);
    }, 0);
  }

  function onCellDragOver(e){
    if (!dragData) return;
    var tgt = e.currentTarget;
    if (tgt.dataset.id === dragData.id) { hideDropGhost(); return; }
    // Cell-to-cell drop is always a swap (server tolerates any
    // pair), so it's always allowed. Show ghost over the hovered
    // target cell.
    e.preventDefault();
    ensureDropGhost(dragData.w, dragData.h, tgt);
  }
  function onCellDrop(e){
    e.preventDefault();
    e.stopPropagation();
    hideDropGhost();
    if (!dragData) return;
    var targetId = e.currentTarget.dataset.id;
    if (!targetId || targetId === dragData.id) return;
    // Defer out of the drop handler — browsers stall fetch
    // microtasks during drag-and-drop.
    var dragId = dragData.id;
    setTimeout(function(){
      api('POST', '/api/layout/swap', { a: dragId, b: targetId })
        .then(function(r){ state.layout = r.layout; renderAll(); })
        .catch(toastError);
    }, 0);
  }

  // ── Resize ─────────────────────────────────────────────────
  function startResize(e, p){
    e.preventDefault();
    e.stopPropagation();
    var startX = e.clientX, startY = e.clientY;
    var L = state.layout;
    var slot = 96 + 10; // cell + gap; matches CSS --cell + grid gap
    var origW = p.w, origH = p.h;
    var lastW = origW, lastH = origH;

    // Build a ghost rectangle inside the grid that previews the proposed
    // size as the user drags. Uses the same grid template positions so it
    // tracks the exact cells the resize would occupy.
    var ghost = document.createElement('div');
    ghost.className = 'resize-ghost';
    ghost.style.gridRow = (p.row + 1) + ' / span ' + origH;
    ghost.style.gridColumn = (p.col + 1) + ' / span ' + origW;
    ghost.style.position = 'relative';
    var ghostLabel = document.createElement('div');
    ghostLabel.className = 'resize-ghost-label';
    ghostLabel.textContent = origW + '×' + origH;
    ghost.appendChild(ghostLabel);
    els.grid.appendChild(ghost);

    function onMove(ev){
      var dx = ev.clientX - startX;
      var dy = ev.clientY - startY;
      var w = Math.max(1, Math.min(L.cols - p.col, origW + Math.round(dx / slot)));
      var h = Math.max(1, Math.min(L.rows - p.row, origH + Math.round(dy / slot)));
      if (w !== lastW || h !== lastH) {
        lastW = w; lastH = h;
        ghost.style.gridRow = (p.row + 1) + ' / span ' + h;
        ghost.style.gridColumn = (p.col + 1) + ' / span ' + w;
        ghostLabel.textContent = w + '×' + h;
      }
    }
    function onUp(){
      document.removeEventListener('pointermove', onMove);
      document.removeEventListener('pointerup', onUp);
      try { ghost.remove(); } catch(_){}
      if (lastW === origW && lastH === origH) return;
      api('POST', '/api/layout/resize', { id: p.id, w: lastW, h: lastH })
        .then(function(r){ state.layout = r.layout; renderAll(); })
        .catch(toastError);
    }
    document.addEventListener('pointermove', onMove);
    document.addEventListener('pointerup', onUp);
  }

  // ── Inspect panel ─────────────────────────────────────────
  function findCellAcrossPages(id){
    if (!state.layout) return null;
    for (var i = 0; i < state.layout.pages.length; i++) {
      var p = state.layout.pages[i].placements.find(function(x){ return x.id === id; });
      if (p) return { page: i, cell: p, location: 'page' };
    }
    var d = (state.layout.dock || []).find(function(x){ return x.id === id; });
    if (d) return { page: -1, cell: d, location: 'dock' };
    return null;
  }
  function openInspect(id){
    state.inspectId = id;
    els.app.classList.add('with-inspect');
    renderInspect();
  }
  function closeInspect(){
    state.inspectId = null;
    els.app.classList.remove('with-inspect');
  }
  els.inspectClose.addEventListener('click', closeInspect);

  function renderInspect(){
    if (!state.inspectId) return;
    var found = findCellAcrossPages(state.inspectId);
    if (!found) { closeInspect(); return; }
    var p = found.cell;
    els.inspectTitle.textContent = labelForCell(p);
    var body = els.inspectBody;
    body.innerHTML = '';

    // (Live preview removed: the grid cell itself is now persistent and
    // fully interactive in Run mode, so a duplicated iframe in the
    // inspect panel just doubled the bridge load and the visual noise.)

    // Properties
    var sec = document.createElement('div'); sec.className = 'insp-section';
    appendProp(sec, 'Type', p.type);
    appendProp(sec, 'Location', found.location === 'dock' ? 'Dock' : ('Page ' + (found.page + 1)));
    if (found.location !== 'dock') appendProp(sec, 'Position', 'row ' + p.row + ', col ' + p.col);
    appendProp(sec, 'Size', p.w + ' × ' + p.h);
    if (p.pkg) appendProp(sec, 'Package', p.pkg);
    if (p.widgetId) appendProp(sec, 'Widget', p.widgetId);
    if (p.folderName) appendProp(sec, 'Name', p.folderName);
    appendProp(sec, 'ID', p.id);
    body.appendChild(sec);

    // Resize controls (widgets + folders)
    if (p.type === 'GENERATED_WIDGET' || p.type === 'FOLDER') {
      var rsec = document.createElement('div'); rsec.className = 'insp-section';
      var h3 = document.createElement('h3'); h3.textContent = 'Resize'; rsec.appendChild(h3);
      var row = document.createElement('div'); row.className = 'insp-resize';
      var wIn = makeNumInput(p.w);
      var sep = document.createElement('span'); sep.textContent = '×'; sep.style.color = 'var(--hint)';
      var hIn = makeNumInput(p.h);
      var btn = document.createElement('button'); btn.textContent = 'Apply';
      btn.addEventListener('click', function(){
        var w = parseInt(wIn.value,10), h = parseInt(hIn.value,10);
        if (!w || !h) return;
        api('POST', '/api/layout/resize', { id: p.id, w: w, h: h })
          .then(function(r){ state.layout = r.layout; renderAll(); renderInspect(); })
          .catch(toastError);
      });
      row.appendChild(wIn); row.appendChild(sep); row.appendChild(hIn); row.appendChild(btn);
      rsec.appendChild(row);
      body.appendChild(rsec);
    }

    // Folder editor
    if (p.type === 'FOLDER') {
      var fsec = document.createElement('div'); fsec.className = 'insp-section';
      var h3 = document.createElement('h3'); h3.textContent = 'Folder contents'; fsec.appendChild(h3);
      var list = document.createElement('div'); list.className = 'insp-folder-list';
      (p.folderItems || []).forEach(function(fi){
        var item = document.createElement('div'); item.className = 'insp-folder-item';
        var icon = document.createElement('div'); icon.className = 'icon';
        icon.style.backgroundImage = 'url(' + iconUrl(fi.pkg) + ')';
        var lab = document.createElement('div'); lab.className = 'label';
        var app = state.apps.find(function(a){ return a.pkg === fi.pkg; });
        lab.textContent = app ? app.label : fi.pkg;
        var rm = document.createElement('button'); rm.textContent = '×'; rm.title = 'Remove';
        rm.addEventListener('click', function(){
          api('POST', '/api/layout/folder_remove', { id: p.id, pkg: fi.pkg })
            .then(function(r){ state.layout = r.layout; renderAll(); renderInspect(); })
            .catch(toastError);
        });
        item.appendChild(icon); item.appendChild(lab); item.appendChild(rm);
        list.appendChild(item);
      });
      if (!(p.folderItems || []).length) {
        var empty = document.createElement('div'); empty.style.color = 'var(--hint)';
        empty.style.fontSize = '13px'; empty.style.padding = '12px 0';
        empty.textContent = 'Empty folder. Use "Add app" to add one.';
        list.appendChild(empty);
      }
      var addBtn = document.createElement('button'); addBtn.textContent = '+ Add app'; addBtn.className = 'ghost';
      addBtn.style.marginTop = '12px';
      addBtn.addEventListener('click', function(){ openFolderAddPicker(p.id); });
      fsec.appendChild(list); fsec.appendChild(addBtn);

      // Rename
      var renameRow = document.createElement('div'); renameRow.style.marginTop = '14px';
      var nameIn = document.createElement('input'); nameIn.value = p.folderName || '';
      nameIn.placeholder = 'Folder name';
      nameIn.style.cssText = 'width:100%; background:#0a0a10; border:1px solid var(--line); color:var(--text); padding:8px; border-radius:6px; margin-bottom:8px;';
      var renameBtn = document.createElement('button'); renameBtn.textContent = 'Rename'; renameBtn.className = 'ghost';
      renameBtn.addEventListener('click', function(){
        api('POST', '/api/layout/folder_rename', { id: p.id, name: nameIn.value })
          .then(function(r){ state.layout = r.layout; renderAll(); renderInspect(); })
          .catch(toastError);
      });
      renameRow.appendChild(nameIn); renameRow.appendChild(renameBtn);
      fsec.appendChild(renameRow);
      body.appendChild(fsec);
    }

    // Actions
    var asec = document.createElement('div'); asec.className = 'insp-section';
    var ah3 = document.createElement('h3'); ah3.textContent = 'Actions'; asec.appendChild(ah3);
    var del = document.createElement('button'); del.textContent = 'Delete'; del.className = 'danger';
    del.addEventListener('click', function(){
      confirmDestroy({
        title: 'Delete cell?',
        message: 'Remove "' + labelForCell(p) + '"? This cannot be undone.',
      }).then(function(yes){
        if (!yes) return;
        api('POST', '/api/layout/delete', { id: p.id })
          .then(function(r){ state.layout = r.layout; closeInspect(); renderAll(); })
          .catch(toastError);
      });
    });
    asec.appendChild(del);
    body.appendChild(asec);
  }
  function appendProp(parent, k, v){
    var row = document.createElement('div'); row.className = 'insp-row';
    var ks = document.createElement('span'); ks.className = 'k'; ks.textContent = k;
    var vs = document.createElement('span'); vs.className = 'v'; vs.textContent = v;
    row.appendChild(ks); row.appendChild(vs); parent.appendChild(row);
  }
  function makeNumInput(v){
    var i = document.createElement('input'); i.type = 'number'; i.min = '1'; i.max = '10'; i.value = v;
    return i;
  }
  function labelForCell(p){
    if (p.type === 'ICON' && p.pkg) {
      var a = state.apps.find(function(a){ return a.pkg === p.pkg; });
      return a ? a.label : p.pkg;
    }
    if (p.type === 'GENERATED_WIDGET') {
      var w = state.widgets.find(function(w){ return w.id === p.widgetId; });
      return w ? w.title : 'Widget';
    }
    if (p.type === 'FOLDER') return p.folderName || 'Folder';
    return p.type;
  }

  function openFolderAddPicker(folderId){
    state.pendingFolderAdd = folderId;
    state.pendingTarget = null;
    els.popoverBg.classList.add('open');
    setPopoverTab('apps');
    els.popoverQ.value = '';
    els.popoverQ.focus();
  }

  // ── Dock ───────────────────────────────────────────────────
  // The launcher supports multi-page dock (HomeLayout.dockPages).
  // state.currentDockPage tracks which page the editor is showing.
  // Clamped to the available range on every render so e.g. deleting
  // the current page doesn't leave the indicator pointing at nothing.
  state.currentDockPage = state.currentDockPage || 0;
  function activeDockPlacements(){
    var L = state.layout || {};
    var dockPages = L.dockPages || (L.dock ? [L.dock] : []);
    if (!dockPages.length) return [];
    if (state.currentDockPage >= dockPages.length) {
      state.currentDockPage = dockPages.length - 1;
    }
    if (state.currentDockPage < 0) state.currentDockPage = 0;
    return dockPages[state.currentDockPage] || [];
  }
  function renderDockDots(){
    var dots = document.getElementById('dock-dots');
    if (!dots) return;
    var L = state.layout || {};
    var dockPages = L.dockPages || (L.dock ? [L.dock] : []);
    dots.innerHTML = '';
    // Single dock page = hide the indicator entirely so users with no
    // multi-dock setup don't see chrome they don't need. The "+ add"
    // chip still appears in edit mode so the multi-dock affordance is
    // discoverable.
    if (dockPages.length <= 1 && state.mode !== 'edit') return;
    dockPages.forEach(function(_, idx){
      var d = document.createElement('div');
      d.className = 'dot' + (idx === state.currentDockPage ? ' active' : '');
      d.title = 'Dock page ' + (idx + 1);
      d.addEventListener('click', function(){
        state.currentDockPage = idx;
        renderDock();
        renderDockDots();
      });
      dots.appendChild(d);
    });
    if (state.mode === 'edit') {
      // Per-page delete chip (only when more than one dock page).
      if (dockPages.length > 1) {
        var del = document.createElement('span');
        del.className = 'del'; del.textContent = '✕';
        del.title = 'Delete current dock page';
        del.addEventListener('click', function(e){
          e.stopPropagation();
          var curDock = activeDockPlacements();
          var msg = curDock.length > 0
            ? 'Dock page ' + (state.currentDockPage + 1) + ' has ' + curDock.length + ' cell' + (curDock.length === 1 ? '' : 's') + '. Delete the page and all of them?'
            : 'Delete empty dock page ' + (state.currentDockPage + 1) + '?';
          confirmDestroy({ title: 'Delete dock page?', message: msg }).then(function(yes){
            if (!yes) return;
            api('POST', '/api/layout/dock_page_delete',
                { idx: state.currentDockPage, force: true }).then(function(r){
              state.layout = r.layout;
              if (state.currentDockPage >= (r.layout.dockPages || []).length) {
                state.currentDockPage = (r.layout.dockPages || []).length - 1;
              }
              renderAll();
            }).catch(toastError);
          });
        });
        dots.appendChild(del);
      }
      // Trailing + chip — append a fresh dock page.
      var add = document.createElement('span');
      add.className = 'add'; add.textContent = '+';
      add.title = 'Add another dock page';
      add.addEventListener('click', function(){
        api('POST', '/api/layout/dock_page_add').then(function(r){
          state.layout = r.layout;
          state.currentDockPage = (r.layout.dockPages || []).length - 1;
          renderAll();
        }).catch(toastError);
      });
      dots.appendChild(add);
    }
  }
  function renderDock(){
    var L = state.layout;
    // Initial track shapes — sizeGridSquareCells replaces these with
    // explicit `${cellSize}px` after the grid lays out, so each dock
    // slot is a true square at the same size as a grid cell. We do
    // NOT stretch the dock to width:100%: instead it gets its
    // natural width (dockSlots × cellSize + gaps + padding), and
    // dock-wrap's flex justify-content:center centers it. That
    // matches the on-device dock which sits as a discrete pill, not
    // an edge-to-edge bar.
    els.dock.style.gridTemplateColumns = 'repeat(' + L.dockSlots + ', auto)';
    els.dock.style.gridTemplateRows = 'auto';
    els.dock.style.width = '';
    els.dock.innerHTML = '';
    var occupied = {};
    activeDockPlacements().forEach(function(p){ occupied[p.col] = p; });
    for (var s = 0; s < L.dockSlots; s++) {
      var p = occupied[s];
      var cell;
      if (p) {
        cell = document.createElement('div');
        cell.className = 'cell ' + (p.type === 'FOLDER' ? 'folder' : 'icon');
        cell.dataset.id = p.id;
        // Slot is stamped on the cell so a drop on the cell knows
        // which dock position to claim. Without this, dropping onto
        // an OCCUPIED dock cell fell through to no-op.
        cell.dataset.slot = s;
        cell.dataset.dock = '1';
        fillCell(cell, p);
        var del = document.createElement('div'); del.className = 'delete-btn'; del.textContent = '×';
        del.addEventListener('click', function(id, label){ return function(e){
          e.stopPropagation();
          confirmDestroy({
            title: 'Remove from dock?',
            message: 'Remove "' + label + '" from the dock?',
          }).then(function(yes){
            if (!yes) return;
            api('POST', '/api/layout/delete', { id: id })
              .then(function(r){ state.layout = r.layout; renderAll(); })
              .catch(toastError);
          });
        };}(p.id, labelForCell(p)));
        cell.appendChild(del);
        // Edit-mode interactions: click → open inspect (rename / change
        // folder name / etc.); drag = move within dock or back to grid.
        cell.draggable = (state.mode === 'edit');
        cell.addEventListener('click', function(e){
          if (state.mode !== 'edit') return;
          if (e.target.classList && e.target.classList.contains('delete-btn')) return;
          openInspect(cell.dataset.id);
        });
        cell.addEventListener('dragstart', function(e){
          if (state.mode !== 'edit') { e.preventDefault(); return; }
          dragData = { id: cell.dataset.id, fromDock: true };
          e.dataTransfer.setData('text/plain', cell.dataset.id);
          e.dataTransfer.effectAllowed = 'move';
          cell.classList.add('dragging');
        });
        cell.addEventListener('dragend', function(){
          cell.classList.remove('dragging');
          dragData = null;
        });
        // Accept drops on this occupied dock cell — server's
        // move_to_dock handles both grid→dock and dock→dock by
        // displacing whatever sits in the target slot.
        cell.addEventListener('dragover', function(e){
          if (!dragData) return;
          if (dragData.id === cell.dataset.id) return;
          e.preventDefault();
        });
        cell.addEventListener('drop', function(e){
          e.preventDefault(); e.stopPropagation();
          if (!dragData) return;
          if (dragData.id === cell.dataset.id) return;
          var slot = parseInt(cell.dataset.slot, 10);
          api('POST', '/api/layout/move_to_dock',
              { id: dragData.id, slot: slot, dock_page: state.currentDockPage })
            .then(function(r){ state.layout = r.layout; renderAll(); }).catch(toastError);
        });
      } else {
        cell = document.createElement('div');
        cell.className = 'cell empty';
        cell.dataset.slot = s;
        cell.addEventListener('dragover', function(e){ e.preventDefault(); });
        cell.addEventListener('drop', function(e){
          e.preventDefault();
          if (!dragData) return;
          var slot = parseInt(e.currentTarget.dataset.slot,10);
          api('POST', '/api/layout/move_to_dock',
              { id: dragData.id, slot: slot, dock_page: state.currentDockPage })
            .then(function(r){ state.layout = r.layout; renderAll(); }).catch(toastError);
        });
      }
      els.dock.appendChild(cell);
    }
  }

  function renderAll(){
    renderPages();
    renderGrid();
    renderDock();
    renderDockDots();
    renderPageDots();
    if (state.inspectId) renderInspect();
    // Schedule sizing passes. Why two consecutive RAFs:
    //  pass 1 — measure with the dock at its initial 1fr/auto
    //           shape and size cells based on grid-wrap's *current*
    //           clientHeight. After this, the dock has explicit
    //           row height, which makes it taller than it was.
    //           Phone-screen's flex column then redistributes →
    //           grid-wrap shrinks vertically.
    //  pass 2 — re-measure with the dock at its final height, so
    //           cellH gets a value that actually fits the smaller
    //           grid-wrap, and grid-wrap stops overflowing.
    // Without pass 2, certain home pages (especially those holding
    // a widget that triggered a paint reflow) showed a vertical
    // scrollbar inside grid-wrap.
    requestAnimationFrame(function(){
      sizeGridSquareCells();
      requestAnimationFrame(sizeGridSquareCells);
    });
  }

  /** Read the inner content width of an element (clientWidth minus
   *  horizontal padding). clientWidth excludes borders by spec, so
   *  we don't have to subtract those. */
  function contentWidth(el) {
    if (!el) return 0;
    var cs = window.getComputedStyle(el);
    var pl = parseFloat(cs.paddingLeft) || 0;
    var pr = parseFloat(cs.paddingRight) || 0;
    return el.clientWidth - pl - pr;
  }
  /** Sum of horizontal box-edge contributions inside an element's
   *  clientWidth: padding + border. Useful when the element has
   *  border-box sizing and we want to know how much room is lost
   *  to chrome before the content area. */
  function horizontalChrome(el) {
    if (!el) return 0;
    var cs = window.getComputedStyle(el);
    return (parseFloat(cs.paddingLeft) || 0) + (parseFloat(cs.paddingRight) || 0)
      + (parseFloat(cs.borderLeftWidth) || 0) + (parseFloat(cs.borderRightWidth) || 0);
  }

  /** Read the inner content height (clientHeight minus vertical
   *  padding) of an element. */
  function contentHeight(el) {
    if (!el) return 0;
    var cs = window.getComputedStyle(el);
    var pt = parseFloat(cs.paddingTop) || 0;
    var pb = parseFloat(cs.paddingBottom) || 0;
    return el.clientHeight - pt - pb;
  }

  /** Compute icon size for a cell exactly the way the on-device
   *  IconCell does in onMeasure. Returns 0 if the cell is too small
   *  to host anything meaningful. */
  function iconSizeForCell(cellW, cellH, dpInPx, showLabel){
    var pad = 6 * dpInPx;            // IconCell.padPx = 6dp
    var labelH = 16 * dpInPx;        // IconCell.labelH = 16dp
    var labelTopPad = 2 * dpInPx;    // IconCell.labelTopPad = 2dp
    var minIconForLabel = 32 * dpInPx;
    var iconMin = 28 * dpInPx;
    var iconMax = 72 * dpInPx;
    var innerW = cellW - 2 * pad;
    var innerH = cellH - 2 * pad;
    if (innerW <= 0 || innerH <= 0) return 0;
    var effShowLabel = showLabel &&
      (innerH - labelH - labelTopPad) >= minIconForLabel;
    var availableForIcon = effShowLabel
      ? innerH - labelH - labelTopPad
      : innerH;
    var target = Math.min(innerW, availableForIcon) * 0.82;
    return Math.max(iconMin, Math.min(iconMax, target));
  }

  /** Convert phone-dp into editor CSS-px. The editor's phone-frame
   *  is a SCALED rendition of the real phone display, so a value
   *  the phone draws at e.g. 48 px (16dp × density 3) needs to be
   *  scaled by (editorFrameWidth / phoneScreenWidth) when we mirror
   *  it here, otherwise everything would render at phone-pixel size
   *  and look out-of-proportion in the smaller editor frame. */
  function dpInEditorCssPx() {
    var frame = document.getElementById('phone-frame');
    var vp = state.viewPrefs || {};
    if (!frame || !vp.screenWidth || !vp.density) return 1;
    var frameW = frame.clientWidth;
    if (frameW <= 0) return 1;
    // CSS px / phone-px ratio × phone-px / phone-dp = CSS px per dp
    return (frameW / vp.screenWidth) * vp.density;
  }

  /** Size the home grid + dock to match the on-device HomeGrid:
   *    cellW = (W − gaps) / cols
   *    cellH = (H − gaps) / rows
   *  Cells aren't square — on a tall phone they're taller than
   *  wide, same as the phone. Widgets that span N×M cells inherit
   *  the cell aspect ratio so their internal HTML lays out the
   *  same shape it does on-device.
   *
   *  Then computes the per-cell icon size via [iconSizeForCell]
   *  using the EXACT IconCell.onMeasure formula and sets it as
   *  a CSS variable so .cell .icon picks it up.
   *
   *  The dock keeps square slots (matching the phone's slotSize
   *  coercion) but uses the same iconSizeForCell math for its
   *  icons, so dock icons size proportionally to dock slots. */
  function sizeGridSquareCells(){
    if (!state.layout) return;
    var L = state.layout;
    var GAP = 8;
    var dpInPx = dpInEditorCssPx();
    var showLabels = !!(state.viewPrefs && state.viewPrefs.showDockLabels);

    if (L.cols > 0 && L.rows > 0) {
      var gridW = contentWidth(els.grid);
      var gridH = contentHeight(els.grid);
      var cellW = 0, cellH = 0;
      if (gridW > 0) {
        // Math.floor so N×cellW + (N-1)×gap never exceeds gridW by
        // a sub-pixel — without this, browser rounding tipped the
        // grid into overflow on certain widths and grid-wrap's
        // `overflow:auto` showed an unwanted scrollbar after each
        // re-render (e.g. after an icon-filter change).
        cellW = Math.max(1, Math.floor((gridW - (L.cols - 1) * GAP) / L.cols));
        els.grid.style.gridTemplateColumns =
          'repeat(' + L.cols + ', ' + cellW + 'px)';
      }
      if (gridH > 0) {
        cellH = Math.max(1, Math.floor((gridH - (L.rows - 1) * GAP) / L.rows));
        els.grid.style.gridTemplateRows =
          'repeat(' + L.rows + ', ' + cellH + 'px)';
      }
      // Grid icons always show labels (showLabel = true on phone
      // home grid). Per-cell icon size goes into a CSS variable so
      // every .icon inside the grid renders at the EXACT size the
      // on-device IconCell would.
      if (cellW > 0 && cellH > 0) {
        var iconSize = iconSizeForCell(cellW, cellH, dpInPx, true);
        els.grid.style.setProperty('--icon-size', iconSize + 'px');
        els.grid.style.setProperty('--cell-pad', (6 * dpInPx) + 'px');
        els.grid.style.setProperty('--label-h', (16 * dpInPx) + 'px');
        els.grid.style.setProperty('--label-top-pad', (2 * dpInPx) + 'px');
        els.grid.style.setProperty('--label-font-size', (11 * dpInPx) + 'px');
      }
    }

    // Dock: square slot tracks, with the same per-cell icon-sizing
    // formula. showLabel arg respects the launcher's dock-labels
    // pref so a dock with labels-off uses the full slot height for
    // its icon (matching the on-device dock rendering).
    if (L.dockSlots > 0) {
      var dockWrap = els.dock.parentElement;
      var wrapInner = contentWidth(dockWrap);
      if (wrapInner > 0) {
        var dockChrome = horizontalChrome(els.dock);
        var avail = wrapInner - dockChrome;
        var dockCell = Math.max(
          1,
          Math.floor((avail - (L.dockSlots - 1) * GAP) / L.dockSlots),
        );
        els.dock.style.gridTemplateColumns =
          'repeat(' + L.dockSlots + ', ' + dockCell + 'px)';
        els.dock.style.gridTemplateRows = dockCell + 'px';
        var dockIconSize = iconSizeForCell(dockCell, dockCell, dpInPx, showLabels);
        els.dock.style.setProperty('--icon-size', dockIconSize + 'px');
        els.dock.style.setProperty('--cell-pad', (6 * dpInPx) + 'px');
        els.dock.style.setProperty('--label-h', (16 * dpInPx) + 'px');
        els.dock.style.setProperty('--label-top-pad', (2 * dpInPx) + 'px');
        els.dock.style.setProperty('--label-font-size', (11 * dpInPx) + 'px');
      }
    }
  }
  // Mirror the active LauncherPrefs values that affect home-tab
  // rendering. Includes:
  //   - showDockLabels → body class so CSS can hide dock labels
  //   - screenWidth/Height → phone-frame aspect-ratio matches the
  //     real device (a tablet on landscape-dominant orientation
  //     would otherwise look weird inside a fixed 9:19.5 portrait
  //     frame).
  function applyViewPrefs(){
    var vp = state.viewPrefs || {};
    document.body.classList.toggle('show-dock-labels', !!vp.showDockLabels);
    var frame = document.getElementById('phone-frame');
    if (frame && vp.screenWidth && vp.screenHeight) {
      frame.style.aspectRatio = vp.screenWidth + ' / ' + vp.screenHeight;
      // When the device is landscape-dominant the frame becomes
      // wider than tall; cap the max-width at something reasonable
      // (say 720px) so a landscape tablet doesn't fill the whole
      // editor column. The frame still scales down responsively
      // via the inherited max-width: 100% of its stage.
      var isPortrait = vp.screenHeight >= vp.screenWidth;
      frame.style.maxWidth = isPortrait ? '420px' : '720px';
    }
    // Re-size after class/ratio toggle: hiding labels makes dock
    // cells visually flush, and a new aspect-ratio changes the grid
    // width → cells need to re-measure.
    requestAnimationFrame(sizeGridSquareCells);
  }

  // Re-size cells whenever the phone-frame's width changes (window
  // resize, inspect-panel toggle changing grid column width) OR
  // grid-wrap's height changes (dock settling at its final size
  // after a renderDock pass). Observing both catches both classes
  // of layout shift; the recompute is idempotent so a double-fire
  // is harmless.
  if (window.ResizeObserver) {
    var ro = new ResizeObserver(function(){ sizeGridSquareCells(); });
    var frame = document.getElementById('phone-frame');
    if (frame) ro.observe(frame);
    var gw = document.getElementById('grid-wrap');
    if (gw) ro.observe(gw);
  } else {
    // Fallback for older browsers — coarse but reliable.
    window.addEventListener('resize', sizeGridSquareCells);
  }

  // Page-dots indicator — phone-style row beneath the grid that
  // shows N dots, one per page, with the current one highlighted.
  // Always rendered (even in edit mode) for orientation; in edit
  // mode the rich page tabs above the phone frame remain the
  // primary affordance for add/delete/rename.
  function renderPageDots(){
    var el = document.getElementById('phone-dots');
    if (!el || !state.layout) return;
    el.innerHTML = '';
    var n = state.layout.pages.length;
    for (var i = 0; i < n; i++) {
      var d = document.createElement('div');
      d.className = 'dot' + (i === state.currentPage ? ' active' : '');
      d.onclick = (function(idx){ return function(){
        state.currentPage = idx; state.selectedIds = {}; renderAll();
      };})(i);
      d.style.cursor = 'pointer';
      el.appendChild(d);
    }
  }

  // Wallpaper iframe — loads the active wallpaper's HTML into the
  // background layer of the phone-frame so the home tab looks like
  // an actual phone screen. Called once at bootstrap and again on
  // wallpaper change / profile activation. We avoid setting src
  // unless it actually differs so the iframe doesn't restart its
  // JS state on every renderAll.
  var lastWallpaperId = null;
  var wallpaperReloadCounter = 0;
  function applyWallpaperIfNeeded(id){
    var iframe = document.getElementById('phone-wallpaper');
    if (!iframe || !id) return;
    // Skip the reload only if the id hasn't changed AND a force-
    // reload wasn't requested. Without this, switching A → A
    // (re-setting the same wallpaper) was a silent no-op.
    if (id === lastWallpaperId) return;
    lastWallpaperId = id;
    wallpaperReloadCounter++;
    iframe.src = '/api/wallpapers/' + encodeURIComponent(id)
      + '/preview.html?v=' + wallpaperReloadCounter;
  }
  function refreshWallpaper(){
    // Cheap GET — server cached the list; we just need the active id.
    api('GET', '/api/wallpapers').then(function(r){
      applyWallpaperIfNeeded(r.activeId);
    }).catch(function(){ /* silent — phone-frame works without wallpaper */ });
  }

  // ── Mode toggle (Edit ↔ Run) ──────────────────────────────
  function setMode(mode) {
    if (mode !== 'edit' && mode !== 'run') return;
    state.mode = mode;
    els.board.classList.toggle('run-mode', mode === 'run');
    // Update toggle button visuals.
    var btns = els.modeToggle.querySelectorAll('button');
    for (var i = 0; i < btns.length; i++) {
      btns[i].classList.toggle('active', btns[i].dataset.mode === mode);
    }
    // Run mode: close any open inspect panel (it has its own iframe;
    // run mode is for using the live cells directly).
    if (mode === 'run' && state.inspectId) closeInspect();
    // Re-render so cell drag/click handlers reflect the new mode.
    renderAll();
  }
  Array.prototype.forEach.call(els.modeToggle.querySelectorAll('button'), function(b){
    b.addEventListener('click', function(){ setMode(b.dataset.mode); });
  });
  // Keyboard shortcut: ⌘E / Ctrl-E flips modes.
  document.addEventListener('keydown', function(e){
    if ((e.metaKey || e.ctrlKey) && e.key === 'e') {
      e.preventDefault();
      setMode(state.mode === 'edit' ? 'run' : 'edit');
    }
    // ⌘Z / Ctrl-Z — undo last layout mutation. Only in edit mode (run
    // mode is for using widgets, not rearranging). Skip if focus is in
    // an editable element (chat input, popover search) so the user can
    // still undo their typing.
    if ((e.metaKey || e.ctrlKey) && e.key === 'z' && !e.shiftKey) {
      var t = e.target;
      var inText = t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable);
      if (state.mode !== 'edit' || inText) return;
      e.preventDefault();
      undo();
    }

    // Esc — close any open overlay, then clear selection. Order
    // matters: confirm dialog is owned by confirmDestroy and listens
    // for its own Esc, so we only handle the popover / inspect panel
    // / multi-select clear here.
    if (e.key === 'Escape') {
      if (els.popoverBg.classList.contains('open')) {
        e.preventDefault(); closePopover(); return;
      }
      if (state.inspectId) {
        e.preventDefault(); closeInspect();
        state.selectedIds = {};
        renderGrid();
        return;
      }
      if (countSelected() > 0) {
        e.preventDefault();
        state.selectedIds = {};
        renderGrid();
        return;
      }
    }

    // Delete / Backspace — remove every selected cell. Edit mode only;
    // require a confirm. Skip when focus is in any editable so users
    // can backspace text. 1 selected → single delete; N selected →
    // batch delete via /api/layout/delete_many (single undo step).
    if ((e.key === 'Delete' || e.key === 'Backspace') && state.mode === 'edit') {
      var tgt = e.target;
      var typing = tgt && (tgt.tagName === 'INPUT' || tgt.tagName === 'TEXTAREA' || tgt.isContentEditable);
      if (typing) return;
      var ids = selectedIdList();
      if (!ids.length && state.inspectId) ids = [state.inspectId];
      if (!ids.length) return;
      e.preventDefault();
      var msg, single = (ids.length === 1) ? placementById(ids[0]) : null;
      if (single) msg = 'Remove "' + labelForCell(single) + '"?';
      else msg = 'Remove ' + ids.length + ' selected cells?';
      confirmDestroy({
        title: ids.length > 1 ? 'Delete selected cells?' : 'Delete cell?',
        message: msg,
      }).then(function(yes){
        if (!yes) return;
        var url = ids.length > 1 ? '/api/layout/delete_many' : '/api/layout/delete';
        var body = ids.length > 1 ? { ids: ids } : { id: ids[0] };
        api('POST', url, body).then(function(r){
          state.layout = r.layout;
          state.selectedIds = {};
          if (state.inspectId && ids.indexOf(state.inspectId) >= 0) closeInspect();
          renderAll();
        }).catch(toastError);
      });
    }

    // ⌘A / Ctrl-A — select all placements on the current page. (Skip
    // when typing — let the browser handle text select-all there.)
    if ((e.metaKey || e.ctrlKey) && e.key === 'a') {
      var tgtA = e.target;
      var typingA = tgtA && (tgtA.tagName === 'INPUT' || tgtA.tagName === 'TEXTAREA' || tgtA.isContentEditable);
      if (typingA) return;
      if (state.mode !== 'edit') return;
      e.preventDefault();
      state.selectedIds = {};
      var pageNow = state.layout && state.layout.pages[state.currentPage];
      if (pageNow) pageNow.placements.forEach(function(pp){ state.selectedIds[pp.id] = true; });
      if (countSelected() !== 1) closeInspect();
      renderGrid();
    }

    // Arrow keys — move the inspect-target cell one slot in the arrow
    // direction. Edit mode only. Server validates collision; we just
    // clamp to grid bounds so the keypress never wastes a round-trip.
    if ((e.key === 'ArrowUp' || e.key === 'ArrowDown' ||
         e.key === 'ArrowLeft' || e.key === 'ArrowRight') &&
        state.mode === 'edit' && state.inspectId) {
      var tgt2 = e.target;
      var typing2 = tgt2 && (tgt2.tagName === 'INPUT' || tgt2.tagName === 'TEXTAREA' || tgt2.isContentEditable);
      if (typing2) return;
      var sel = placementById(state.inspectId);
      if (!sel) return;
      // Only on-page placements — dock cells use their own slot model.
      var onPage = state.layout.pages[state.currentPage].placements
        .some(function(x){ return x.id === sel.id; });
      if (!onPage) return;
      var dr = 0, dc = 0;
      if (e.key === 'ArrowUp') dr = -1;
      else if (e.key === 'ArrowDown') dr = 1;
      else if (e.key === 'ArrowLeft') dc = -1;
      else if (e.key === 'ArrowRight') dc = 1;
      var newRow = sel.row + dr;
      var newCol = sel.col + dc;
      if (newRow < 0 || newCol < 0) return;
      if (newRow + sel.h > state.layout.rows) return;
      if (newCol + sel.w > state.layout.cols) return;
      e.preventDefault();
      api('POST', '/api/layout/move', {
        id: sel.id, page: state.currentPage, row: newRow, col: newCol,
      }).then(function(r){
        state.layout = r.layout;
        renderAll();
      }).catch(function(err){
        // 'target occupied' is the common case — silent. Other errors
        // surface so the user notices unexpected failures.
        if (!/target occupied/i.test(err.message || '')) toastError(err);
      });
    }
  });

  // ── Undo ───────────────────────────────────────────────────
  // Server holds the snapshot stack (one entry per successful mutation).
  // We just POST and apply the returned layout, mirroring how every
  // other layout API call updates state.layout from the response.
  function undo(){
    api('POST', '/api/layout/undo').then(function(r){
      state.layout = r.layout;
      // If the inspect target is gone after undo (cell deleted is
      // re-created — fine, but cell created is now deleted), close it.
      if (state.inspectId && !placementById(state.inspectId)) closeInspect();
      renderAll();
    }).catch(function(err){
      // 400 = nothing to undo. Don't toast — silent no-op feels more
      // natural than a "nothing to undo" popup at the top of every
      // empty undo stack.
      if (!/nothing to undo/i.test(err.message || '')) toastError(err);
    });
  }

  // ── Add popover ────────────────────────────────────────────
  var popoverTab = 'apps';
  function openPopover(target){
    state.pendingTarget = target;
    els.popoverBg.classList.add('open');
    setPopoverTab('apps');
    els.popoverQ.value = '';
    els.popoverQ.focus();
  }
  function closePopover(){
    els.popoverBg.classList.remove('open');
    state.pendingTarget = null;
    state.pendingFolderAdd = null;
  }
  function setPopoverTab(tab){
    popoverTab = tab;
    els.popoverTabs.forEach(function(b){ b.classList.toggle('active', b.dataset.tab === tab); });
    // The Generate tab uses the bottom textarea; Folder has its own
    // form rendered into the list area, so hide both the search field
    // (irrelevant) and the generate textarea.
    var hideSearch = (tab === 'generate' || tab === 'folder');
    els.popoverFoot.style.display = (tab === 'generate') ? 'block' : 'none';
    els.popoverSearch.style.display = hideSearch ? 'none' : 'block';
    renderPopover();
  }
  function renderPopover(){
    els.popoverList.innerHTML = '';
    if (popoverTab === 'apps') {
      var q = els.popoverQ.value.toLowerCase();
      state.apps
        .filter(function(a){ return !q || (a.label||'').toLowerCase().includes(q) || a.pkg.includes(q); })
        .slice(0, 200)
        .forEach(function(a){
          var item = document.createElement('div'); item.className = 'pickitem';
          var ic = document.createElement('div'); ic.className = 'icon';
          ic.style.backgroundImage = 'url(' + iconUrl(a.pkg) + ')';
          var lab = document.createElement('div'); lab.className = 'label';
          var top = document.createElement('div'); top.textContent = a.label;
          var pkg = document.createElement('div'); pkg.className = 'pkg'; pkg.textContent = a.pkg;
          lab.appendChild(top); lab.appendChild(pkg);
          item.appendChild(ic); item.appendChild(lab);
          // Drag-from-popover: drop onto an empty cell to place there.
          // We close the popover on dragstart so its overlay doesn't
          // sit between the pointer and the cells.
          item.draggable = true;
          item.addEventListener('dragstart', function(e){
            e.dataTransfer.effectAllowed = 'copy';
            e.dataTransfer.setData('application/x-iappyx-pick',
              JSON.stringify({ kind: 'app', pkg: a.pkg, activity: a.activity }));
            // Hide instead of fully close so the in-flight drag isn't
            // cancelled by DOM teardown. Close fully on dragend.
            els.popoverBg.style.visibility = 'hidden';
          });
          item.addEventListener('dragend', function(){
            els.popoverBg.style.visibility = '';
            closePopover();
          });
          item.addEventListener('click', function(){
            if (state.pendingFolderAdd) {
              api('POST', '/api/layout/folder_add', { id: state.pendingFolderAdd, pkg: a.pkg })
                .then(function(r){ state.layout = r.layout; closePopover(); renderAll(); renderInspect(); })
                .catch(toastError);
              return;
            }
            var t = state.pendingTarget || {};
            api('POST', '/api/layout/place_app', {
              pkg: a.pkg, activity: a.activity,
              page: t.page, row: t.row, col: t.col,
            }).then(function(r){
              state.layout = r.layout;
              closePopover(); renderAll();
            }).catch(toastError);
          });
          els.popoverList.appendChild(item);
        });
    } else if (popoverTab === 'widgets') {
      var q = els.popoverQ.value.toLowerCase();
      // Match title + subtitle for parity with the apps tab (which
      // matches label + package). Subtitle often carries the category
      // ("Clock", "Audio", "Productivity") that users actually search by.
      state.widgets
        .filter(function(w){
          if (!q) return true;
          if ((w.title||'').toLowerCase().indexOf(q) >= 0) return true;
          if ((w.subtitle||'').toLowerCase().indexOf(q) >= 0) return true;
          return false;
        })
        .forEach(function(w){
          var item = document.createElement('div'); item.className = 'pickitem';
          var ic = document.createElement('div'); ic.className = 'icon';
          ic.textContent = '🧩'; ic.style.fontSize = '22px'; ic.style.lineHeight = '36px'; ic.style.textAlign = 'center';
          var lab = document.createElement('div'); lab.className = 'label';
          var top = document.createElement('div'); top.textContent = w.title;
          var pkg = document.createElement('div'); pkg.className = 'pkg'; pkg.textContent = w.subtitle || '';
          lab.appendChild(top); lab.appendChild(pkg);
          item.appendChild(ic); item.appendChild(lab);
          item.draggable = true;
          item.addEventListener('dragstart', function(e){
            e.dataTransfer.effectAllowed = 'copy';
            e.dataTransfer.setData('application/x-iappyx-pick',
              JSON.stringify({ kind: 'widget', widgetId: w.id, widgetAsset: w.assetPath || null }));
            els.popoverBg.style.visibility = 'hidden';
          });
          item.addEventListener('dragend', function(){
            els.popoverBg.style.visibility = '';
            closePopover();
          });
          item.addEventListener('click', function(){
            var t = state.pendingTarget || {};
            api('POST', '/api/layout/place_widget', {
              widgetId: w.id, widgetAsset: w.assetPath || undefined,
              page: t.page, row: t.row, col: t.col,
            }).then(function(r){
              state.layout = r.layout;
              closePopover(); renderAll();
            }).catch(toastError);
          });
          els.popoverList.appendChild(item);
        });
    } else if (popoverTab === 'generate') {
      els.popoverList.innerHTML = '<div style="padding:14px; color:var(--hint); font-size:13px">Type a description below, then press Enter. The AI will generate a widget and place it here.</div>';
    } else if (popoverTab === 'folder') {
      // Folder creation form. Name + a multi-select app list. The list
      // is checkbox-based: the user picks zero or more apps to seed
      // into the new folder. Click "Create" places the folder at the
      // pendingTarget cell (or first empty if pendingTarget is null).
      var wrap = document.createElement('div');
      wrap.style.cssText = 'padding:14px; display:flex; flex-direction:column; gap:10px;';
      wrap.innerHTML =
        '<div style="font-size:13px; color:var(--hint)">Create a folder. Optionally pick apps to add right away.</div>' +
        '<input id="folder-name-in" type="text" placeholder="Folder name (optional)" maxlength="40" ' +
        '  style="background:#0a0a10; color:var(--text); border:1px solid var(--line); border-radius:8px; padding:8px 10px; font-size:13px;" />' +
        '<div style="font-size:12px; color:var(--hint); margin-top:4px;">Apps to add</div>' +
        '<div id="folder-app-search" style="display:flex; gap:6px;">' +
        '  <input id="folder-app-q" type="text" placeholder="Search apps…" ' +
        '    style="flex:1; background:#0a0a10; color:var(--text); border:1px solid var(--line); border-radius:8px; padding:6px 10px; font-size:12px;" />' +
        '</div>' +
        '<div id="folder-app-list" style="max-height:240px; overflow:auto; border:1px solid var(--line); border-radius:8px; padding:6px;"></div>' +
        '<div style="display:flex; gap:8px; justify-content:flex-end;">' +
        '  <button id="folder-cancel" style="background:transparent; color:var(--hint); border:1px solid var(--line); border-radius:8px; padding:6px 14px; cursor:pointer;">Cancel</button>' +
        '  <button id="folder-create" style="background:transparent; color:var(--accent); border:1px solid var(--accent); border-radius:8px; padding:6px 14px; cursor:pointer;">Create folder</button>' +
        '</div>';
      els.popoverList.appendChild(wrap);
      var listEl = wrap.querySelector('#folder-app-list');
      var qIn = wrap.querySelector('#folder-app-q');
      var picked = {};
      function drawApps(){
        var q = qIn.value.toLowerCase();
        listEl.innerHTML = '';
        var visible = state.apps
          .filter(function(a){ return !q || (a.label||'').toLowerCase().indexOf(q) >= 0 || a.pkg.indexOf(q) >= 0; })
          .slice(0, 100);
        visible.forEach(function(a){
          var row = document.createElement('label');
          row.style.cssText = 'display:flex; gap:8px; align-items:center; padding:5px 4px; cursor:pointer; font-size:12px; color:var(--text);';
          var cb = document.createElement('input'); cb.type = 'checkbox';
          cb.checked = !!picked[a.pkg];
          cb.onchange = function(){ if (cb.checked) picked[a.pkg] = true; else delete picked[a.pkg]; };
          var ic = document.createElement('div');
          ic.style.cssText = 'width:24px; height:24px; border-radius:6px; background:#0a0a10 url(' + iconUrl(a.pkg) + ') center/cover no-repeat; flex:0 0 24px;';
          var lab = document.createElement('div'); lab.textContent = a.label;
          lab.style.cssText = 'flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;';
          row.appendChild(cb); row.appendChild(ic); row.appendChild(lab);
          listEl.appendChild(row);
        });
        if (!visible.length) {
          listEl.innerHTML = '<div style="padding:14px; color:var(--hint); font-size:12px; text-align:center;">No matches</div>';
        }
      }
      qIn.addEventListener('input', drawApps);
      drawApps();
      wrap.querySelector('#folder-cancel').onclick = closePopover;
      wrap.querySelector('#folder-create').onclick = function(){
        var t = state.pendingTarget || {};
        var apps = Object.keys(picked);
        api('POST', '/api/layout/folder_create', {
          name: wrap.querySelector('#folder-name-in').value.trim(),
          page: t.page, row: t.row, col: t.col,
          apps: apps,
        }).then(function(r){
          state.layout = r.layout;
          closePopover(); renderAll();
        }).catch(toastError);
      };
    }
  }
  els.popoverTabs.forEach(function(b){ b.addEventListener('click', function(){ setPopoverTab(b.dataset.tab); }); });
  els.popoverQ.addEventListener('input', renderPopover);
  els.popoverClose.addEventListener('click', closePopover);
  els.popoverBg.addEventListener('click', function(e){ if (e.target === els.popoverBg) closePopover(); });
  els.popoverGen.addEventListener('keydown', function(e){
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      var prompt = els.popoverGen.value.trim();
      if (!prompt) return;
      els.popoverGen.value = '';
      var t = state.pendingTarget || {};
      var fullPrompt = 'Generate a widget — ' + prompt + ' — and place it on page ' + ((t.page||0)+1) + (t.row != null ? ' at row ' + t.row + ' col ' + t.col : '') + '.';
      closePopover();
      sendChatPrompt(fullPrompt);
    }
  });

  // ── Chat ───────────────────────────────────────────────────
  function renderChat(){
    els.messages.innerHTML = '';
    state.chat.forEach(function(m){
      var div = document.createElement('div');
      div.className = 'msg ' + m.role;
      var role = document.createElement('div'); role.className = 'role'; role.textContent = m.role;
      div.appendChild(role);
      if (m.imageDataUrl) {
        var img = document.createElement('img');
        img.src = m.imageDataUrl;
        img.style.cssText = 'max-width:240px; max-height:160px; border-radius:8px; display:block; margin-bottom:6px;';
        div.appendChild(img);
      }
      var text = document.createElement('div'); text.className = 'text'; text.textContent = m.text || '';
      div.appendChild(text);
      if (m.toolCalls && m.toolCalls.length) {
        var chips = document.createElement('div');
        m.toolCalls.forEach(function(tc){
          var chip = document.createElement('span');
          chip.className = 'toolchip';
          var result = parseJsonSafe(tc.result);
          if (result && result.ok === false) chip.classList.add('fail');
          chip.innerHTML = '🔧 <code>' + escapeHtml(tc.name) + '</code>';
          chip.title = tc.args + ' → ' + tc.result;
          chips.appendChild(chip);
        });
        div.appendChild(chips);
      }
      els.messages.appendChild(div);
    });
    els.messages.scrollTop = els.messages.scrollHeight;
  }

  function parseJsonSafe(s){ try { return JSON.parse(s); } catch(_){ return null; } }

  // ── Chat image attach ──────────────────────────────────
  // pendingImage: { base64 (raw, no data: prefix), mime, dataUrl, name }
  var pendingImage = null;
  function setPendingImage(img){
    pendingImage = img;
    if (img) {
      els.attachThumb.src = img.dataUrl;
      els.attachName.textContent = img.name || 'image';
      els.attachPreview.style.display = 'flex';
      els.attachBtn.classList.add('has-image');
    } else {
      els.attachPreview.style.display = 'none';
      els.attachBtn.classList.remove('has-image');
      els.attachFile.value = '';
    }
    refreshSendButton();
  }
  function fileToPendingImage(file){
    if (!file || !/^image\//.test(file.type)) return;
    // Cap at ~5 MB raw; Anthropic accepts up to ~20MB but the launcher's
    // serialized cache + the SSE pipe is happier with smaller payloads.
    if (file.size > 5 * 1024 * 1024) {
      window.alert('Image too large (5MB max).'); return;
    }
    var fr = new FileReader();
    fr.onload = function(){
      var dataUrl = fr.result;
      var comma = String(dataUrl).indexOf(',');
      if (comma < 0) return;
      setPendingImage({
        base64: String(dataUrl).slice(comma + 1),
        mime: file.type,
        dataUrl: dataUrl,
        name: file.name || 'image',
      });
    };
    fr.readAsDataURL(file);
  }

  function sendChatPrompt(prompt){
    var imgForBubble = pendingImage ? pendingImage.dataUrl : null;
    state.chat.push({ role: 'user', text: prompt, imageDataUrl: imgForBubble });
    renderChat();
    state.streaming = true;
    var body = { prompt: prompt };
    if (pendingImage) {
      body.imageBase64 = pendingImage.base64;
      body.imageMime = pendingImage.mime;
    }
    setPendingImage(null);
    refreshSendButton();
    api('POST', '/api/chat', body).catch(function(err){
      state.chat.push({ role: 'error', text: err.message });
      state.streaming = false;
      refreshSendButton();
      renderChat();
    });
  }

  function refreshSendButton(){
    var emptyText = els.prompt.value.trim() === '';
    els.send.disabled = state.streaming || (emptyText && !pendingImage);
    els.thinking.style.display = state.streaming ? 'block' : 'none';
  }

  els.prompt.addEventListener('input', refreshSendButton);
  els.prompt.addEventListener('keydown', function(e){
    if (e.key === 'Enter' && !e.shiftKey && !els.send.disabled) {
      e.preventDefault();
      sendChatPrompt(els.prompt.value.trim());
      els.prompt.value = '';
      refreshSendButton();
    }
  });
  els.send.addEventListener('click', function(){
    if (els.send.disabled) return;
    sendChatPrompt(els.prompt.value.trim());
    els.prompt.value = '';
    refreshSendButton();
  });

  els.attachBtn.addEventListener('click', function(){ els.attachFile.click(); });
  els.attachFile.addEventListener('change', function(){
    if (els.attachFile.files && els.attachFile.files[0]) {
      fileToPendingImage(els.attachFile.files[0]);
    }
  });
  els.attachClear.addEventListener('click', function(){ setPendingImage(null); });
  // Paste an image directly into the prompt
  els.prompt.addEventListener('paste', function(e){
    if (!e.clipboardData) return;
    var items = e.clipboardData.items;
    for (var i = 0; i < items.length; i++) {
      if (items[i].kind === 'file' && /^image\//.test(items[i].type)) {
        var f = items[i].getAsFile();
        if (f) { fileToPendingImage(f); e.preventDefault(); return; }
      }
    }
  });
  // Drag-and-drop image onto the composer
  ['dragenter', 'dragover'].forEach(function(ev){
    els.composer.addEventListener(ev, function(e){
      if (!e.dataTransfer || !Array.prototype.some.call(e.dataTransfer.types || [],
          function(t){ return t === 'Files'; })) return;
      e.preventDefault(); els.composer.classList.add('dragover');
    });
  });
  ['dragleave', 'drop'].forEach(function(ev){
    els.composer.addEventListener(ev, function(e){
      els.composer.classList.remove('dragover');
      if (ev !== 'drop') return;
      if (!e.dataTransfer || !e.dataTransfer.files || !e.dataTransfer.files.length) return;
      e.preventDefault();
      fileToPendingImage(e.dataTransfer.files[0]);
    });
  });

  els.chatClear.addEventListener('click', function(){
    confirmDestroy({
      title: 'Clear conversation?',
      message: 'Discard the AI chat history. Layout edits already applied stay; only the conversation is cleared.',
      confirmLabel: 'Clear',
    }).then(function(yes){
      if (!yes) return;
      api('POST', '/api/chat/clear').then(function(){
        state.chat = []; renderChat();
      }).catch(toastError);
    });
  });

  // ── State sync SSE stream ──────────────────────────────────
  // Subscribes to /api/state/stream and reacts to phone-side
  // changes (layout edits, share-target clippings, profile swap,
  // wallpaper change). Decouples the editor from manual polling —
  // anything that fires CLIPPINGS_CHANGED / LAYOUT_CHANGED /
  // WALLPAPER_CHANGED on the phone pushes here within ~200ms.
  //
  // Strategy per event:
  //  - layout / state change → refetch /api/state (cheap), update
  //    state.layout, re-render. cellNodes map persists so widget
  //    iframes that didn't change DO NOT restart (phase 1.1).
  //  - wallpaper change → refreshWallpaper() (already debounced
  //    via lastWallpaperId).
  //
  // Reconnect on drop: exponential backoff (1s, 2s, 5s, capped 10s).
  // No-op when the current tab isn't Home and the change is layout-
  // only (we still update state.layout so the next switchSection
  // ('home') renders the fresh data — no point burning paint
  // cycles on an invisible tab).
  function openStateStream(){
    var backoff = 1000;
    function connect(){
      var es;
      try { es = new EventSource('/api/state/stream'); }
      catch (_){ scheduleReconnect(); return; }
      es.addEventListener('hello', function(){
        backoff = 1000;  // reset on successful connect
      });
      es.addEventListener('state-change', function(e){
        var data; try { data = JSON.parse(e.data); } catch (_){ return; }
        if (data.type === 'wallpaper') { refreshWallpaper(); return; }
        if (data.type === 'badges') {
          // Update in-place — no need to refetch /api/state. Badge
          // pills appear/update/disappear on every cell that already
          // exists in cellNodes; widget cells stay untouched.
          state.badgeCounts = data.counts || {};
          refreshAllBadges();
          return;
        }
        if (data.type === 'backup-progress') {
          if (currentImportUi) {
            currentImportUi.apply(data.phase || '', data.done || 0, data.total || 0);
          }
          return;
        }
        if (data.type === 'layout' && data.layoutJson) {
          // Mirror the on-device iappyx.onLayoutChanged push event
          // into the wallpaper preview iframe — wallpapers that
          // recompute their composition when icons move (e.g.
          // layout-aware blur/ripple) keep up live instead of
          // freezing on the snapshot they got at iframe-load.
          var wpFrame = document.getElementById('phone-wallpaper');
          if (wpFrame && wpFrame.contentWindow) {
            try {
              wpFrame.contentWindow.postMessage({
                __iappyx: true,
                kind: 'layout-changed',
                json: data.layoutJson,
              }, '*');
            } catch (_) {}
          }
        }
        // 'layout' or generic 'state' → refetch layout + widgets.
        api('GET', '/api/state').then(function(s){
          state.layout = s.layout;
          state.widgets = s.widgets;
          state.viewPrefs = s.viewPrefs || state.viewPrefs;
          activeIconFilter = (state.viewPrefs && state.viewPrefs.iconFilter) || '';
          state.badgeCounts = s.badgeCounts || {};
          // Re-render the home grid only if it's the active tab,
          // and also refresh the tab that's currently visible so
          // e.g. a new clipping appears in Clippings live.
          if (section === 'home') {
            renderAll();
            applyViewPrefs();
          } else if (section === 'clippings') {
            renderClippingsTab();
          } else if (section === 'widgets') {
            renderWidgetsTab();
          }
          // Profiles tab may have a new active row — re-list.
          else if (section === 'profiles') {
            renderProfilesTab();
          }
        }).catch(function(){ /* silent — next event will retry */ });
      });
      es.onerror = function(){
        try { es.close(); } catch (_){}
        scheduleReconnect();
      };
    }
    function scheduleReconnect(){
      setTimeout(connect, backoff);
      backoff = Math.min(10000, backoff * 2);
    }
    connect();
  }

  // ── SSE event stream ───────────────────────────────────────
  function openEventStream(){
    var es = new EventSource('/api/chat/stream');
    var current = null;  // current assistant message being streamed

    function ensureAssistant(){
      if (!current || current.role !== 'assistant' || current._closed) {
        current = { role: 'assistant', text: '', toolCalls: [] };
        state.chat.push(current);
      }
      return current;
    }

    es.addEventListener('user-message', function(e){
      // Already added on send; ignore. (Prevents duplicates from SSE replay.)
      // If this came from another source we'd add it.
    });
    es.addEventListener('ai-text-chunk', function(e){
      var data = JSON.parse(e.data);
      var m = ensureAssistant();
      m.text += data.text;
      renderChat();
    });
    es.addEventListener('tool-call', function(e){
      var data = JSON.parse(e.data);
      var m = ensureAssistant();
      m.toolCalls.push({ name: data.name, args: JSON.stringify(data.args || {}), result: '' });
      renderChat();
    });
    es.addEventListener('tool-result', function(e){
      var data = JSON.parse(e.data);
      var m = ensureAssistant();
      var last = m.toolCalls[m.toolCalls.length - 1];
      if (last && !last.result) last.result = JSON.stringify(data.result);
      // Pull the new layout/state — most tools mutate.
      api('GET', '/api/state').then(function(s){
        state.layout = s.layout;
        state.widgets = s.widgets;
        renderAll();
      }).catch(function(){});
      renderChat();
    });
    es.addEventListener('done', function(){
      if (current) current._closed = true;
      state.streaming = false;
      refreshSendButton();
    });
    // NOTE: must be 'ai-error' not 'error' — EventSource's native 'error'
    // event fires on connection drops with no e.data, which would mask
    // real AI errors. Keep server-emitted error events under a distinct name.
    es.addEventListener('ai-error', function(e){
      try {
        var data = JSON.parse(e.data || '{}');
        state.chat.push({ role: 'error', text: data.message || 'AI error' });
        renderChat();
      } catch(_){}
      state.streaming = false;
      refreshSendButton();
    });
    es.addEventListener('cleared', function(){
      state.chat = []; renderChat();
    });
    es.onerror = function(){ /* auto-reconnect by EventSource */ };
  }

  els.disconnect.addEventListener('click', function(e){
    e.preventDefault();
    api('POST', '/api/disconnect').finally(function(){ window.location = '/pair'; });
  });

  // ── Confirm dialog ─────────────────────────────────────────
  // Promise-returning replacement for window.confirm. Returns true if the
  // user confirmed, false if cancelled or dismissed. Esc + click-outside +
  // Cancel all resolve false; Delete resolves true. Used by every
  // destructive action in the editor.
  function confirmDestroy(opts) {
    var bg = document.getElementById('confirm-bg');
    var titleEl = document.getElementById('confirm-title');
    var msgEl = document.getElementById('confirm-message');
    var cancelBtn = document.getElementById('confirm-cancel');
    var okBtn = document.getElementById('confirm-confirm');
    titleEl.textContent = opts.title || 'Delete?';
    msgEl.textContent = opts.message || 'This cannot be undone.';
    okBtn.textContent = opts.confirmLabel || 'Delete';
    bg.classList.add('open');
    return new Promise(function(resolve){
      function cleanup(result){
        bg.classList.remove('open');
        cancelBtn.onclick = null;
        okBtn.onclick = null;
        bg.onclick = null;
        document.removeEventListener('keydown', onKey);
        resolve(result);
      }
      function onKey(e){
        if (e.key === 'Escape') { e.preventDefault(); cleanup(false); }
        if (e.key === 'Enter') { e.preventDefault(); cleanup(true); }
      }
      cancelBtn.onclick = function(){ cleanup(false); };
      okBtn.onclick = function(){ cleanup(true); };
      bg.onclick = function(e){ if (e.target === bg) cleanup(false); };
      document.addEventListener('keydown', onKey);
      okBtn.focus();
    });
  }

  function toastError(err){
    var msg = err && err.message ? err.message : String(err);
    console.error('toastError:', err);
    setStatus(false, msg);
    setTimeout(function(){ setStatus(true, 'connected'); }, 4000);
  }

  // Bubble diagnostic messages from iframe shims into the main console.
  window.addEventListener('message', function(ev){
    var d = ev.data;
    if (d && d.__iappyxDiag) {
      console.log('[shim:' + d.widgetId + ']', d.kind, d.payload);
    }
  });

  // ── Section tabs (Home / Profiles / Wallpapers / Clippings / Settings / Showcase) ──
  // Each non-Home section is rendered on first switch (no upfront fetch
  // burn). The Home tab keeps owning #board / #grid / #dock; switching
  // away just hides it visually — iframes inside stay alive (same
  // pattern as 1.1's renderGrid persistence).
  var section = 'home';
  function switchSection(name){
    if (name === section) return;
    section = name;
    var tabs = document.querySelectorAll('#main-tabs button');
    for (var i = 0; i < tabs.length; i++) {
      tabs[i].classList.toggle('active', tabs[i].dataset.section === name);
    }
    var panes = document.querySelectorAll('.tab-pane');
    for (var j = 0; j < panes.length; j++) {
      panes[j].classList.toggle('active', panes[j].dataset.tab === name);
    }
    // Hide the inspect panel for non-home sections — its content is
    // tied to the home grid only.
    if (name !== 'home' && state.inspectId) closeInspect();
    if (name === 'profiles') renderProfilesTab();
    else if (name === 'widgets') renderWidgetsTab();
    else if (name === 'wallpapers') renderWallpapersTab();
    else if (name === 'transitions') renderTransitionsTab();
    else if (name === 'icons') renderIconsTab();
    else if (name === 'clippings') renderClippingsTab();
    else if (name === 'settings') renderSettingsTab();
    else if (name === 'theme') renderThemeTab();
    /* PLUGINS: BEGIN */
    else if (name === 'plugins') renderPluginsTab();
    /* PLUGINS: END */
    else if (name === 'showcase') renderShowcaseTab();
  }
  Array.prototype.forEach.call(document.querySelectorAll('#main-tabs button'), function(b){
    b.addEventListener('click', function(){ switchSection(b.dataset.section); });
  });

  // ── Profiles tab ───────────────────────────────────────────
  function renderProfilesTab(){
    var list = document.getElementById('profiles-list');
    list.innerHTML = '<div class="empty-state">Loading…</div>';
    api('GET', '/api/profiles').then(function(r){
      document.getElementById('profiles-pause-autoswitch').checked = !!r.autoswitchPaused;
      list.innerHTML = '';
      if (!r.profiles.length) {
        list.innerHTML = '<div class="empty-state">No profiles yet. Use "Save current as profile" to make your first one.</div>';
        return;
      }
      r.profiles.forEach(function(p){
        var row = document.createElement('div');
        row.className = 'row-item' + (p.active ? ' active' : '');
        var main = document.createElement('div'); main.className = 'row-main';
        var title = document.createElement('div'); title.className = 'row-title'; title.textContent = p.name;
        var sub = document.createElement('div'); sub.className = 'row-sub'; sub.textContent = p.trigger;
        main.appendChild(title); main.appendChild(sub);
        row.appendChild(main);
        if (!p.active) {
          var actBtn = document.createElement('button'); actBtn.textContent = 'Activate';
          actBtn.onclick = function(){
            api('POST', '/api/profiles/' + encodeURIComponent(p.slug) + '/activate')
              .then(function(){ renderProfilesTab(); reloadHomeAfterProfileSwap(); })
              .catch(toastError);
          };
          row.appendChild(actBtn);
        }
        var editBtn = document.createElement('button'); editBtn.textContent = 'Edit';
        editBtn.onclick = function(){ openProfileEditor(p); };
        row.appendChild(editBtn);
        var cloneBtn = document.createElement('button'); cloneBtn.textContent = 'Clone';
        cloneBtn.title = 'Make a copy of this profile with a new name';
        cloneBtn.onclick = function(){
          var name = window.prompt('Name for the clone:', p.name + ' (copy)');
          if (!name || !name.trim()) return;
          api('POST', '/api/profiles/' + encodeURIComponent(p.slug) + '/duplicate',
            { name: name.trim() })
            .then(function(){ renderProfilesTab(); }).catch(toastError);
        };
        row.appendChild(cloneBtn);
        var delBtn = document.createElement('button');
        delBtn.textContent = 'Delete'; delBtn.className = 'danger';
        delBtn.disabled = !!p.active;
        if (!p.active) {
          delBtn.onclick = function(){
            confirmDestroy({
              title: 'Delete profile?',
              message: 'Remove "' + p.name + '"? The current live state stays unchanged.',
            }).then(function(yes){
              if (!yes) return;
              api('DELETE', '/api/profiles/' + encodeURIComponent(p.slug))
                .then(function(){ renderProfilesTab(); }).catch(toastError);
            });
          };
        }
        row.appendChild(delBtn);
        list.appendChild(row);
      });
    }).catch(function(err){ list.innerHTML = '<div class="empty-state">Failed: ' + err.message + '</div>'; });
  }
  document.getElementById('profiles-pause-autoswitch').addEventListener('change', function(e){
    api('POST', '/api/profiles/autoswitch', { paused: e.target.checked }).catch(toastError);
  });
  document.getElementById('profiles-new-blank').addEventListener('click', function(){
    var name = window.prompt('Name this blank profile (empty layout + default wallpaper/icon-filter/transition):');
    if (!name || !name.trim()) return;
    api('POST', '/api/profiles/create_blank', { name: name.trim() })
      .then(function(){ renderProfilesTab(); }).catch(toastError);
  });
  document.getElementById('profiles-save-current').addEventListener('click', function(){
    var name = window.prompt('Name this profile (e.g. "Home", "Work"):');
    if (!name || !name.trim()) return;
    api('POST', '/api/profiles/save_current', { name: name.trim() })
      .then(function(){ renderProfilesTab(); }).catch(toastError);
  });
  /** Slide-in sub-sheet that lives inside the profile-editor card.
   *  Used for the "Pick an app" picker and the "Custom intent action"
   *  editor — both used to be raw `position:absolute; inset:0` overlays
   *  that swapped the modal contents instantly, which felt like the
   *  whole modal had become a different full-screen layout. Wrapping
   *  them in a slide-in shell with a "← Back" header makes the
   *  transition read as navigation into a sub-step instead.
   *
   *  Returns { content, close }: `content` is the flex-column body
   *  the caller fills with form HTML; `close()` animates out and
   *  removes the sheet from the DOM. */
  function openProfileSubSheet(card, title){
    var sheet = document.createElement('div');
    sheet.style.cssText =
      'position:absolute; inset:0; background:var(--panel); border-radius:14px;' +
      'display:flex; flex-direction:column;' +
      // iOS-style "stack push": sheet starts fully off the card's right
      // edge (clipped by the card's overflow:hidden) and slides in. The
      // overhang is what makes the transition read as "navigated to a
      // sub-step", not "the modal swapped contents".
      'transform: translateX(100%);' +
      'transition: transform 260ms cubic-bezier(.2,.8,.2,1);';
    var header = document.createElement('div');
    header.style.cssText =
      'display:flex; align-items:center; gap:8px; padding:14px 18px;' +
      'border-bottom:1px solid var(--line);';
    var back = document.createElement('button');
    back.innerHTML = '&larr;';
    back.setAttribute('aria-label', 'Back');
    back.style.cssText =
      'background:transparent; color:var(--text); border:0; cursor:pointer;' +
      'font-size:18px; padding:2px 8px; line-height:1;';
    var titleEl = document.createElement('div');
    titleEl.style.cssText = 'flex:1; font-weight:600; font-size:15px;';
    titleEl.textContent = title;
    header.appendChild(back); header.appendChild(titleEl);
    var content = document.createElement('div');
    content.style.cssText =
      'flex:1; padding:18px; overflow:auto;' +
      'display:flex; flex-direction:column; gap:10px;';
    sheet.appendChild(header); sheet.appendChild(content);
    card.appendChild(sheet);
    // Force the browser to commit the initial transform:translateX(100%)
    // BEFORE we set the end state. Without this, both transform values
    // are coalesced into the same paint cycle and the transition is
    // skipped entirely — leaving the sheet stuck at translateX(100%),
    // fully clipped by the card's overflow:hidden (the "edit doesn't
    // show anything" symptom). Reading offsetWidth forces a sync layout
    // pass that flushes the pending style. requestAnimationFrame alone
    // wasn't reliable for this — the rAF callback occasionally fires
    // before the initial style is committed.
    /* eslint-disable no-unused-expressions */
    sheet.offsetWidth;
    /* eslint-enable no-unused-expressions */
    sheet.style.transform = 'translateX(0)';
    var closed = false;
    function close(){
      if (closed) return; closed = true;
      sheet.style.transform = 'translateX(100%)';
      setTimeout(function(){ try { sheet.remove(); } catch(_){} }, 280);
    }
    back.addEventListener('click', close);
    return { sheet: sheet, content: content, close: close };
  }

  function openProfileEditor(p){
    // Inline editor modal that reuses the confirm-card chrome — keeps
    // the editor minimal. Trigger kind picker + per-kind form,
    // "Launch apps on activation" multi-select, and a custom-intent-
    // action list editor for the Tasker-style integrations (WireGuard
    // toggle, Home Assistant webhook trigger, etc.).
    var bg = document.getElementById('confirm-bg');
    var card = document.getElementById('confirm-card');
    var origHtml = card.innerHTML;
    var origMaxWidth = card.style.maxWidth;
    card.style.maxWidth = '620px';
    card.innerHTML =
      '<div id="confirm-title">Edit profile</div>' +
      '<div style="margin:14px 0; max-height:65vh; overflow:auto;">' +
      '  <div class="field"><label>Name</label><input type="text" id="pe-name" maxlength="60" /></div>' +
      '  <div class="field"><label>Trigger</label>' +
      '    <select id="pe-kind">' +
      '      <option value="manual">Manual only</option>' +
      '      <option value="wifi_ssid">When connected to WiFi…</option>' +
      '      <option value="wifi_disconnected">When not connected to WiFi</option>' +
      '      <option value="charger">When charging</option>' +
      '      <option value="bt_device">When Bluetooth device connected</option>' +
      '      <option value="android_auto">In Android Auto / car mode</option>' +
      '      <option value="time_of_day">At a time of day</option>' +
      '      <option value="geofence">At a location (geofence)</option>' +
      '    </select>' +
      '  </div>' +
      '  <div id="pe-fields"></div>' +
      '  <hr style="border:none; border-top:1px solid var(--line); margin:18px 0;">' +
      '  <div class="field">' +
      '    <label>Launch apps on activation</label>' +
      '    <div id="pe-launch-list" style="display:flex; flex-direction:column; gap:6px; margin-bottom:8px;"></div>' +
      '    <button id="pe-launch-add" style="background:transparent; border:1px dashed var(--line); color:var(--hint); border-radius:8px; padding:6px 12px; cursor:pointer; font-size:12px; align-self:flex-start;">+ Add app</button>' +
      '    <p class="sub" style="margin-top:6px;">Each app launches in the listed order when this profile activates.</p>' +
      '  </div>' +
      '  <hr style="border:none; border-top:1px solid var(--line); margin:18px 0;">' +
      '  <div class="field">' +
      '    <label>Custom intent actions</label>' +
      '    <div id="pe-ca-list" style="display:flex; flex-direction:column; gap:6px; margin-bottom:8px;"></div>' +
      '    <button id="pe-ca-add" style="background:transparent; border:1px dashed var(--line); color:var(--hint); border-radius:8px; padding:6px 12px; cursor:pointer; font-size:12px; align-self:flex-start;">+ Add action</button>' +
      '    <p class="sub" style="margin-top:6px;">Broadcasts, activities or services fired before launchPackages — Tasker hooks, WireGuard toggles, Home Assistant webhooks.</p>' +
      '  </div>' +
      '</div>' +
      '<div id="confirm-actions">' +
      '  <button id="pe-cancel">Cancel</button>' +
      '  <button id="pe-save" class="primary">Save</button>' +
      '</div>';
    bg.classList.add('open');
    document.getElementById('pe-name').value = p.name;
    var kindSel = document.getElementById('pe-kind');
    kindSel.value = p.triggerKind || 'manual';
    var fields = document.getElementById('pe-fields');

    // Launch-packages state — start with what the server returned.
    var launchPkgs = (p.launchPackages || []).slice();

    function renderLaunchList(){
      var list = document.getElementById('pe-launch-list');
      list.innerHTML = '';
      if (!launchPkgs.length) {
        list.innerHTML = '<div style="color:var(--hint); font-size:12px;">No apps yet — none will be launched.</div>';
        return;
      }
      launchPkgs.forEach(function(pkg, i){
        var row = document.createElement('div');
        row.style.cssText = 'display:flex; gap:8px; align-items:center; background:#0a0a10; border:1px solid var(--line); border-radius:8px; padding:6px 10px;';
        var ic = document.createElement('div');
        ic.style.cssText = 'width:24px; height:24px; border-radius:6px; background:#0a0a10 url(' + iconUrl(pkg) + ') center/cover no-repeat; flex:0 0 24px;';
        var app = state.apps.find(function(a){ return a.pkg === pkg; });
        var lab = document.createElement('div');
        lab.style.cssText = 'flex:1; font-size:12px; color:var(--text); overflow:hidden; text-overflow:ellipsis; white-space:nowrap;';
        lab.textContent = (app ? app.label : pkg);
        var upBtn = document.createElement('button'); upBtn.textContent = '↑';
        upBtn.style.cssText = 'background:transparent; border:0; color:var(--hint); cursor:pointer; padding:2px 6px; font-size:12px;';
        upBtn.disabled = i === 0;
        upBtn.onclick = function(){
          if (i === 0) return;
          launchPkgs.splice(i, 1); launchPkgs.splice(i - 1, 0, pkg); renderLaunchList();
        };
        var rmBtn = document.createElement('button'); rmBtn.textContent = '×';
        rmBtn.style.cssText = 'background:transparent; border:0; color:var(--error); cursor:pointer; padding:2px 8px; font-size:14px;';
        rmBtn.onclick = function(){ launchPkgs.splice(i, 1); renderLaunchList(); };
        row.appendChild(ic); row.appendChild(lab); row.appendChild(upBtn); row.appendChild(rmBtn);
        list.appendChild(row);
      });
    }
    renderLaunchList();

    // ── customActions state + list ───────────────────────────────
    var customActions = (p.customActions || []).map(function(a){
      // shallow clone so cancel doesn't dirty the source list
      return JSON.parse(JSON.stringify(a));
    });
    function renderCustomActionList(){
      var listEl = document.getElementById('pe-ca-list');
      listEl.innerHTML = '';
      if (!customActions.length) {
        listEl.innerHTML = '<div style="color:var(--hint); font-size:12px;">No custom actions.</div>';
        return;
      }
      customActions.forEach(function(a, i){
        var row = document.createElement('div');
        row.style.cssText = 'display:flex; gap:8px; align-items:center; background:#0a0a10; border:1px solid var(--line); border-radius:8px; padding:6px 10px;';
        var main = document.createElement('div');
        main.style.cssText = 'flex:1; overflow:hidden;';
        var t = document.createElement('div'); t.style.cssText = 'font-size:13px; color:var(--text); overflow:hidden; text-overflow:ellipsis; white-space:nowrap;';
        t.textContent = a.label || '(unnamed)';
        var s = document.createElement('div'); s.style.cssText = 'font-size:11px; color:var(--hint); overflow:hidden; text-overflow:ellipsis; white-space:nowrap;';
        var bits = [(a.verb||'BROADCAST').toLowerCase()];
        if (a.action) bits.push(a.action);
        if (a['package']) bits.push(a['package']);
        s.textContent = bits.join(' · ');
        main.appendChild(t); main.appendChild(s);
        var upBtn = document.createElement('button'); upBtn.textContent = '↑';
        upBtn.style.cssText = 'background:transparent; border:0; color:var(--hint); cursor:pointer; padding:2px 6px; font-size:12px;';
        upBtn.disabled = i === 0;
        upBtn.onclick = function(){
          if (i === 0) return;
          customActions.splice(i, 1); customActions.splice(i - 1, 0, a); renderCustomActionList();
        };
        var editBtn = document.createElement('button'); editBtn.textContent = '✎';
        editBtn.style.cssText = 'background:transparent; border:0; color:var(--accent); cursor:pointer; padding:2px 8px; font-size:13px;';
        editBtn.onclick = function(){ openCustomActionEditor(a, function(updated){
          if (updated) { customActions[i] = updated; renderCustomActionList(); }
        }); };
        var rmBtn = document.createElement('button'); rmBtn.textContent = '×';
        rmBtn.style.cssText = 'background:transparent; border:0; color:var(--error); cursor:pointer; padding:2px 8px; font-size:14px;';
        rmBtn.onclick = function(){ customActions.splice(i, 1); renderCustomActionList(); };
        row.appendChild(main); row.appendChild(upBtn); row.appendChild(editBtn); row.appendChild(rmBtn);
        listEl.appendChild(row);
      });
    }
    renderCustomActionList();
    document.getElementById('pe-ca-add').onclick = function(){
      var blank = { label: '', verb: 'BROADCAST', categories: [], flags: 0, extras: [] };
      openCustomActionEditor(blank, function(added){
        if (added) { customActions.push(added); renderCustomActionList(); }
      });
    };

    function openCustomActionEditor(action, done){
      // Slide-in sub-sheet (same shell as the app picker). Edits a
      // copy and only commits via done(updated) so Cancel discards.
      // The shared helper provides the header bar + "← Back" chevron
      // so the transition feels like navigation into a sub-step.
      var draft = JSON.parse(JSON.stringify(action));
      var sheet = openProfileSubSheet(card, 'Custom intent action');
      var sub = sheet.content;
      sub.innerHTML =
        '<div class="field"><label>Label</label><input type="text" id="ca-label" placeholder="e.g. Toggle VPN" /></div>' +
        '<div class="field"><label>Verb</label>' +
        '  <select id="ca-verb">' +
        '    <option value="BROADCAST">sendBroadcast</option>' +
        '    <option value="ACTIVITY">startActivity</option>' +
        '    <option value="SERVICE">startService</option>' +
        '    <option value="FOREGROUND_SERVICE">startForegroundService</option>' +
        '  </select></div>' +
        '<div class="field"><label>Action</label><input type="text" id="ca-action" placeholder="com.example.ACTION" /></div>' +
        '<div class="field"><label>Package</label><input type="text" id="ca-pkg" placeholder="com.example.app (optional)" /></div>' +
        '<div class="field"><label>Class</label><input type="text" id="ca-class" placeholder="com.example.app.Receiver (optional)" /></div>' +
        '<div class="field"><label>Data URI</label><input type="text" id="ca-data" placeholder="e.g. content://… (optional)" /></div>' +
        '<div class="field"><label>MIME</label><input type="text" id="ca-mime" placeholder="text/plain (optional)" /></div>' +
        '<div class="field"><label>Categories (comma-separated)</label><input type="text" id="ca-cats" placeholder="android.intent.category.DEFAULT" /></div>' +
        '<div class="field"><label>Flags (int)</label><input type="number" id="ca-flags" min="0" /></div>' +
        '<label style="display:flex; gap:8px; align-items:center; font-size:13px;"><input type="checkbox" id="ca-warm"> Warm up target before firing</label>' +
        '<div class="field"><label>Extras</label>' +
        '  <div id="ca-extras" style="display:flex; flex-direction:column; gap:6px;"></div>' +
        '  <button id="ca-extra-add" style="margin-top:6px; background:transparent; border:1px dashed var(--line); color:var(--hint); border-radius:8px; padding:4px 10px; cursor:pointer; font-size:12px; align-self:flex-start;">+ Add extra</button>' +
        '</div>' +
        '<div style="display:flex; justify-content:flex-end; gap:8px; margin-top:auto;">' +
        '  <button id="ca-cancel" style="background:transparent; color:var(--hint); border:1px solid var(--line); border-radius:8px; padding:6px 14px; cursor:pointer;">Cancel</button>' +
        '  <button id="ca-save" class="primary" style="border-radius:8px; padding:6px 14px; cursor:pointer;">Save</button>' +
        '</div>';
      // (sub IS sheet.content here — already mounted inside sheet inside
      // card by openProfileSubSheet. A stray card.appendChild(sub) here
      // would re-parent the content out from under the sheet, leaving the
      // sheet empty and the form floating bare in the card — that was
      // the "edit shows blank" bug.)
      sub.querySelector('#ca-label').value = draft.label || '';
      sub.querySelector('#ca-verb').value = draft.verb || 'BROADCAST';
      sub.querySelector('#ca-action').value = draft.action || '';
      sub.querySelector('#ca-pkg').value = draft['package'] || '';
      sub.querySelector('#ca-class').value = draft['class'] || '';
      sub.querySelector('#ca-data').value = draft.dataUri || '';
      sub.querySelector('#ca-mime').value = draft.mime || '';
      sub.querySelector('#ca-cats').value = (draft.categories || []).join(', ');
      sub.querySelector('#ca-flags').value = draft.flags || 0;
      sub.querySelector('#ca-warm').checked = !!draft.warmup;
      var extras = (draft.extras || []).map(function(e){ return { k: e.k, t: e.t || 'STRING', v: e.v }; });
      var extrasEl = sub.querySelector('#ca-extras');
      function drawExtras(){
        extrasEl.innerHTML = '';
        extras.forEach(function(ex, i){
          var r = document.createElement('div');
          r.style.cssText = 'display:flex; gap:6px; align-items:center;';
          var k = document.createElement('input'); k.type = 'text'; k.placeholder = 'key'; k.value = ex.k || '';
          k.style.cssText = 'flex:1; background:#0a0a10; color:var(--text); border:1px solid var(--line); border-radius:6px; padding:6px; font-size:12px;';
          k.oninput = function(){ ex.k = k.value; };
          var t = document.createElement('select');
          t.style.cssText = 'background:#0a0a10; color:var(--text); border:1px solid var(--line); border-radius:6px; padding:6px; font-size:12px;';
          ['STRING','INT','LONG','BOOL','FLOAT'].forEach(function(tn){
            var o = document.createElement('option'); o.value = tn; o.textContent = tn;
            if (ex.t === tn) o.selected = true;
            t.appendChild(o);
          });
          t.onchange = function(){ ex.t = t.value; };
          var v = document.createElement('input'); v.type = 'text'; v.placeholder = 'value'; v.value = ex.v || '';
          v.style.cssText = 'flex:1.5; background:#0a0a10; color:var(--text); border:1px solid var(--line); border-radius:6px; padding:6px; font-size:12px;';
          v.oninput = function(){ ex.v = v.value; };
          var rm = document.createElement('button'); rm.textContent = '×';
          rm.style.cssText = 'background:transparent; border:0; color:var(--error); cursor:pointer; padding:2px 6px; font-size:14px;';
          rm.onclick = function(){ extras.splice(i, 1); drawExtras(); };
          r.appendChild(k); r.appendChild(t); r.appendChild(v); r.appendChild(rm);
          extrasEl.appendChild(r);
        });
      }
      drawExtras();
      sub.querySelector('#ca-extra-add').onclick = function(){
        extras.push({ k: '', t: 'STRING', v: '' }); drawExtras();
      };
      sub.querySelector('#ca-cancel').onclick = function(){ sheet.close(); done(null); };
      sub.querySelector('#ca-save').onclick = function(){
        var out = {
          label: sub.querySelector('#ca-label').value.trim() || 'Action',
          verb: sub.querySelector('#ca-verb').value,
          action: sub.querySelector('#ca-action').value.trim() || null,
          'package': sub.querySelector('#ca-pkg').value.trim() || null,
          'class': sub.querySelector('#ca-class').value.trim() || null,
          dataUri: sub.querySelector('#ca-data').value.trim() || null,
          mime: sub.querySelector('#ca-mime').value.trim() || null,
          categories: sub.querySelector('#ca-cats').value.split(',')
            .map(function(s){ return s.trim(); }).filter(function(s){ return !!s; }),
          flags: parseInt(sub.querySelector('#ca-flags').value, 10) || 0,
          warmup: sub.querySelector('#ca-warm').checked,
          extras: extras.filter(function(e){ return e.k; }).map(function(e){
            return { k: e.k, t: e.t || 'STRING', v: e.v || '' };
          }),
        };
        sheet.close(); done(out);
      };
    }

    document.getElementById('pe-launch-add').onclick = function(){
      // Slide-in app picker — the "← Back" chevron in the sub-sheet
      // header replaces the previous inline Cancel button, so the
      // close path is consistent with the custom-action sub-sheet.
      var sheet = openProfileSubSheet(card, 'Pick an app');
      sheet.content.innerHTML =
        '<input type="text" id="pe-pick-q" placeholder="Search…" style="background:#0a0a10; color:var(--text); border:1px solid var(--line); border-radius:8px; padding:8px 10px; font-size:13px;">' +
        '<div id="pe-pick-list" style="flex:1; overflow:auto; border:1px solid var(--line); border-radius:8px; padding:6px;"></div>';
      var qIn = sheet.content.querySelector('#pe-pick-q');
      var listEl = sheet.content.querySelector('#pe-pick-list');
      function drawApps(){
        var q = qIn.value.toLowerCase();
        listEl.innerHTML = '';
        state.apps
          .filter(function(a){
            if (launchPkgs.indexOf(a.pkg) >= 0) return false; // already added
            return !q || (a.label||'').toLowerCase().indexOf(q) >= 0 || a.pkg.indexOf(q) >= 0;
          })
          .slice(0, 80)
          .forEach(function(a){
            var row = document.createElement('div');
            row.style.cssText = 'display:flex; gap:8px; align-items:center; padding:5px 4px; cursor:pointer; font-size:12px; color:var(--text); border-radius:6px;';
            row.onmouseenter = function(){ row.style.background = 'rgba(255,255,255,.04)'; };
            row.onmouseleave = function(){ row.style.background = ''; };
            var ic = document.createElement('div');
            ic.style.cssText = 'width:24px; height:24px; border-radius:6px; background:#0a0a10 url(' + iconUrl(a.pkg) + ') center/cover no-repeat; flex:0 0 24px;';
            var lab = document.createElement('div'); lab.textContent = a.label;
            lab.style.cssText = 'flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;';
            row.appendChild(ic); row.appendChild(lab);
            row.onclick = function(){
              launchPkgs.push(a.pkg); renderLaunchList(); sheet.close();
            };
            listEl.appendChild(row);
          });
      }
      qIn.addEventListener('input', drawApps);
      drawApps();
      qIn.focus();
    };

    function renderFields(){
      fields.innerHTML = '';
      var k = kindSel.value;
      var payload = p.triggerPayload || {};
      if (k === 'wifi_ssid') {
        fields.innerHTML = '<div class="field"><label>SSID</label><input type="text" id="pe-ssid" /></div>';
        document.getElementById('pe-ssid').value = payload.ssid || '';
      } else if (k === 'bt_device') {
        fields.innerHTML =
          '<div class="field"><label>Device label</label><input type="text" id="pe-bt-label" placeholder="e.g. Car stereo" /></div>' +
          '<div class="field"><label>MAC address</label><input type="text" id="pe-bt-mac" placeholder="AA:BB:CC:DD:EE:FF" /></div>';
        document.getElementById('pe-bt-label').value = payload.label || '';
        document.getElementById('pe-bt-mac').value = payload.address || '';
      } else if (k === 'geofence') {
        fields.innerHTML =
          '<div class="field"><label>Label</label><input type="text" id="pe-gf-label" placeholder="Home / Work" /></div>' +
          '<div class="field-row">' +
          '  <div class="field"><label>Latitude</label><input type="number" id="pe-gf-lat" step="0.000001" /></div>' +
          '  <div class="field"><label>Longitude</label><input type="number" id="pe-gf-lng" step="0.000001" /></div>' +
          '  <div class="field"><label>Radius (m)</label><input type="number" id="pe-gf-rad" min="20" max="2000" /></div>' +
          '</div>' +
          '<div id="pe-gf-map" style="height:280px; margin-top:8px; border-radius:8px; overflow:hidden; border:1px solid var(--line); background:#0a0a10;"></div>' +
          '<p class="sub" style="margin-top:6px;">Click the map to drop a pin, or type coordinates above. Drag the pin to fine-tune.</p>';
        document.getElementById('pe-gf-label').value = payload.label || '';
        document.getElementById('pe-gf-lat').value = payload.latitude || '';
        document.getElementById('pe-gf-lng').value = payload.longitude || '';
        document.getElementById('pe-gf-rad').value = payload.radiusM || 150;
        // Defer map init until Leaflet has loaded — the modal already
        // mounted the field DOM, so the container is sized correctly
        // when invalidateSize() runs in the callback.
        loadLeaflet().then(function(){ initGeofenceMap(payload); })
          .catch(function(){
            // CDN unreachable (offline / locked-down LAN) — leave the
            // numeric inputs as the working fallback.
            var box = document.getElementById('pe-gf-map');
            if (box) box.innerHTML = '<div style="padding:18px; color:var(--hint); font-size:12px;">Map unavailable (no network). Use the lat/long fields above.</div>';
          });
      } else if (k === 'charger') {
        fields.innerHTML =
          '<div class="field"><label>Kind</label>' +
          '  <select id="pe-charger-kind">' +
          '    <option value="ANY">Any charger</option>' +
          '    <option value="WIRED">Wired only</option>' +
          '    <option value="WIRELESS">Wireless only</option>' +
          '  </select>' +
          '</div>';
        document.getElementById('pe-charger-kind').value = payload.chargerKind || 'ANY';
      } else if (k === 'time_of_day') {
        // startMinuteOfDay / endMinuteOfDay are minutes-since-midnight;
        // present as <input type="time"> which natively renders HH:MM
        // in the user's locale + a 7-bit daysOfWeek bitmask as chips.
        // Cross-midnight semantics (end < start) are valid — the
        // launcher's ProfileMatcher handles 22:00–06:00 windows by
        // checking the bit for the START day.
        fields.innerHTML =
          '<div class="field-row">' +
          '  <div class="field"><label>Start</label><input type="time" id="pe-tod-start" /></div>' +
          '  <div class="field"><label>End</label><input type="time" id="pe-tod-end" /></div>' +
          '</div>' +
          '<div class="field"><label>Days</label>' +
          '  <div id="pe-tod-days" style="display:flex; gap:4px; flex-wrap:wrap;"></div>' +
          '</div>' +
          '<div class="field-row">' +
          '  <div class="field"><label>Active from (optional)</label><input type="date" id="pe-tod-from" /></div>' +
          '  <div class="field"><label>Active until (optional)</label><input type="date" id="pe-tod-until" /></div>' +
          '</div>' +
          '<p class="sub" style="margin-top:-4px;">Leave both blank for an always-on time window. Set both for a vacation-style date range — the profile only fires inside it.</p>';
        var startMin = payload.startMinute || 0;
        var endMin = payload.endMinute || 0;
        document.getElementById('pe-tod-start').value = minToHHMM(startMin);
        document.getElementById('pe-tod-end').value = minToHHMM(endMin);
        document.getElementById('pe-tod-from').value = msToDateInput(payload.activeFrom);
        document.getElementById('pe-tod-until').value = msToDateInput(payload.activeUntil);
        var days = (typeof payload.daysOfWeek === 'number') ? payload.daysOfWeek : 0x7F;
        var dayRow = document.getElementById('pe-tod-days');
        var labels = ['Mon','Tue','Wed','Thu','Fri','Sat','Sun'];
        labels.forEach(function(name, idx){
          var chip = document.createElement('button');
          chip.type = 'button';
          chip.textContent = name;
          chip.style.cssText = 'background:transparent; border:1px solid var(--line); border-radius:999px; padding:4px 10px; color:var(--hint); cursor:pointer; font-size:12px;';
          var mask = 1 << idx;
          function paint(){
            var on = (days & mask) !== 0;
            chip.style.borderColor = on ? 'var(--accent)' : 'var(--line)';
            chip.style.color = on ? 'var(--accent)' : 'var(--hint)';
          }
          paint();
          chip.onclick = function(){ days ^= mask; paint(); chip.dataset.days = days; };
          chip.dataset.mask = mask;
          dayRow.appendChild(chip);
        });
        dayRow.dataset.days = days;
        // Track changes by reading from button state on save (simpler
        // than threading a closure here). We stamp every click onto a
        // single source-of-truth attribute.
        Array.prototype.forEach.call(dayRow.children, function(chip){
          var prev = chip.onclick;
          chip.onclick = function(){
            prev();
            // Recompute mask sum from chip colours so reordering / bugs
            // can't silently desync.
            var sum = 0;
            Array.prototype.forEach.call(dayRow.children, function(c){
              if (c.style.color !== 'var(--hint)' && c.style.color !== 'rgb(140, 140, 140)') {
                // Trust the data-mask we stamped at creation.
                sum |= parseInt(c.dataset.mask, 10);
              }
            });
            dayRow.dataset.days = sum;
          };
        });
      }
    }
    kindSel.addEventListener('change', renderFields);
    renderFields();
    function close(){
      bg.classList.remove('open');
      card.innerHTML = origHtml;
      card.style.maxWidth = origMaxWidth;
    }
    document.getElementById('pe-cancel').onclick = close;
    document.getElementById('pe-save').onclick = function(){
      var newName = document.getElementById('pe-name').value.trim();
      var k = kindSel.value;
      var trigger = { kind: k };
      if (k === 'wifi_ssid') trigger.ssid = (document.getElementById('pe-ssid').value || '').trim();
      else if (k === 'bt_device') {
        trigger.address = (document.getElementById('pe-bt-mac').value || '').trim().toUpperCase();
        trigger.label = (document.getElementById('pe-bt-label').value || '').trim() || trigger.address;
      } else if (k === 'geofence') {
        trigger.label = (document.getElementById('pe-gf-label').value || '').trim() || 'Place';
        trigger.latitude = parseFloat(document.getElementById('pe-gf-lat').value);
        trigger.longitude = parseFloat(document.getElementById('pe-gf-lng').value);
        trigger.radiusM = parseFloat(document.getElementById('pe-gf-rad').value) || 150;
      } else if (k === 'charger') {
        trigger.chargerKind = document.getElementById('pe-charger-kind').value;
      } else if (k === 'time_of_day') {
        trigger.startMinute = hhmmToMin(document.getElementById('pe-tod-start').value);
        trigger.endMinute = hhmmToMin(document.getElementById('pe-tod-end').value);
        var dayRow = document.getElementById('pe-tod-days');
        trigger.daysOfWeek = parseInt(dayRow.dataset.days, 10);
        if (isNaN(trigger.daysOfWeek)) trigger.daysOfWeek = 0x7F;
        // Vacation range — both fields optional. 0 sentinel = no bound
        // (the launcher's ProfileMatcher treats 0 as "always" on each side).
        var fromMs = dateInputToMs(document.getElementById('pe-tod-from').value);
        var untilMs = dateInputToMs(document.getElementById('pe-tod-until').value, /*endOfDay=*/true);
        if (fromMs) trigger.activeFrom = fromMs;
        if (untilMs) trigger.activeUntil = untilMs;
      }
      api('PUT', '/api/profiles/' + encodeURIComponent(p.slug),
        { name: newName, trigger: trigger, launchPackages: launchPkgs, customActions: customActions })
        .then(function(){ close(); renderProfilesTab(); })
        .catch(toastError);
    };
  }
  // ── Leaflet lazy-load + geofence map ───────────────────────
  // Loaded on first geofence trigger pick; cached forever after.
  // Offline / blocked LAN → the promise rejects and the editor
  // falls back to the numeric inputs (which still drive the save).
  var leafletPromise = null;
  function loadLeaflet(){
    if (leafletPromise) return leafletPromise;
    leafletPromise = new Promise(function(resolve, reject){
      if (window.L && typeof window.L.map === 'function') { resolve(window.L); return; }
      var css = document.createElement('link');
      css.rel = 'stylesheet';
      css.href = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css';
      document.head.appendChild(css);
      var sc = document.createElement('script');
      sc.src = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js';
      sc.onload = function(){
        if (window.L && typeof window.L.map === 'function') resolve(window.L);
        else reject(new Error('Leaflet failed to initialize'));
      };
      sc.onerror = function(){ reject(new Error('Leaflet CDN unreachable')); };
      document.head.appendChild(sc);
    });
    return leafletPromise;
  }
  function initGeofenceMap(payload){
    var box = document.getElementById('pe-gf-map');
    if (!box || !window.L) return;
    var latIn = document.getElementById('pe-gf-lat');
    var lngIn = document.getElementById('pe-gf-lng');
    var radIn = document.getElementById('pe-gf-rad');
    var startLat = parseFloat(latIn.value) || (payload.latitude || 52.3676);
    var startLng = parseFloat(lngIn.value) || (payload.longitude || 4.9041);
    var startRad = parseFloat(radIn.value) || (payload.radiusM || 150);
    var zoom = (parseFloat(latIn.value) || payload.latitude) ? 15 : 11;
    var map = window.L.map(box).setView([startLat, startLng], zoom);
    window.L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '© OpenStreetMap',
    }).addTo(map);
    var marker = window.L.marker([startLat, startLng], { draggable: true }).addTo(map);
    var circle = window.L.circle([startLat, startLng], {
      radius: startRad, color: 'var(--accent)', weight: 1.5,
      fillColor: '#4FC3F7', fillOpacity: 0.12,
    }).addTo(map);
    function syncFromLatLng(ll){
      latIn.value = ll.lat.toFixed(6);
      lngIn.value = ll.lng.toFixed(6);
      circle.setLatLng(ll);
    }
    map.on('click', function(e){ marker.setLatLng(e.latlng); syncFromLatLng(e.latlng); });
    marker.on('dragend', function(){ syncFromLatLng(marker.getLatLng()); });
    function syncRadius(){
      var r = parseFloat(radIn.value) || 150;
      circle.setRadius(r);
    }
    radIn.addEventListener('input', syncRadius);
    function syncFromInputs(){
      var la = parseFloat(latIn.value), ln = parseFloat(lngIn.value);
      if (!isFinite(la) || !isFinite(ln)) return;
      var ll = window.L.latLng(la, ln);
      marker.setLatLng(ll); circle.setLatLng(ll);
      map.panTo(ll);
    }
    latIn.addEventListener('change', syncFromInputs);
    lngIn.addEventListener('change', syncFromInputs);
    // Modal layout settles after fields are rendered — invalidate so
    // tiles fill the container instead of stacking in the corner.
    setTimeout(function(){ try { map.invalidateSize(); } catch (_) {} }, 60);
  }

  // ── Backup import progress ─────────────────────────────────
  // Modal with two phases: upload (driven by XHR's upload.onprogress)
  // and apply (driven by state-stream backup-progress events). The
  // helper returns an object with upload(done,total), apply(phase,
  // done,total), done() and fail() — the caller wires those up to
  // XHR + the global listener installed at boot.
  var currentImportUi = null;
  function openImportProgress(fileName, fileSize){
    var bg = document.getElementById('confirm-bg');
    var card = document.getElementById('confirm-card');
    var origHtml = card.innerHTML;
    var origMaxWidth = card.style.maxWidth;
    card.style.maxWidth = '460px';
    card.innerHTML =
      '<div id="confirm-title">Importing backup</div>' +
      '<div style="margin:12px 0;">' +
      '  <p class="sub" id="ip-name" style="word-break:break-all;"></p>' +
      '  <div style="margin:14px 0;">' +
      '    <div id="ip-phase" style="font-size:12px; color:var(--hint); margin-bottom:6px;">Preparing…</div>' +
      '    <div style="height:8px; background:#0a0a10; border-radius:999px; overflow:hidden; border:1px solid var(--line);">' +
      '      <div id="ip-bar" style="height:100%; width:0%; background:var(--accent); transition: width 200ms ease;"></div>' +
      '    </div>' +
      '  </div>' +
      '</div>';
    bg.classList.add('open');
    document.getElementById('ip-name').textContent = fileName + ' (' + Math.round(fileSize/1024) + ' KB)';
    function close(){
      bg.classList.remove('open');
      card.innerHTML = origHtml;
      card.style.maxWidth = origMaxWidth;
      currentImportUi = null;
    }
    function setPhase(label, pct){
      var p = document.getElementById('ip-phase');
      var b = document.getElementById('ip-bar');
      if (p) p.textContent = label;
      if (b && typeof pct === 'number') b.style.width = Math.max(0, Math.min(100, pct)) + '%';
    }
    var ui = {
      upload: function(done, total){
        // Upload is the first ~50% of the visual bar; apply is the
        // second half so the user sees forward motion through both
        // halves.
        var pct = total ? (done / total) * 50 : 0;
        setPhase('Uploading… ' + Math.round(done/1024) + ' / ' + Math.round((total||0)/1024) + ' KB', pct);
      },
      apply: function(phase, done, total){
        // Translate Importer phase identifiers to a single visual bar
        // that walks 50%→100% over the apply steps.
        var phases = ['starting','extracting','applying-widgets','applying-wallpapers',
          'applying-transitions','applying-icon-filters','applying-profiles',
          'applying-layout','applying-prefs','finalising','done'];
        var idx = phases.indexOf(phase);
        if (idx < 0) idx = 0;
        var apply01 = idx / (phases.length - 1);
        if (phase === 'extracting' && total > 0) {
          apply01 = Math.min(1, (idx + Math.min(1, done / total)) / (phases.length - 1));
        }
        var pct = 50 + apply01 * 50;
        var label = {
          'starting': 'Starting…',
          'extracting': 'Extracting files…',
          'applying-widgets': 'Restoring widgets…',
          'applying-wallpapers': 'Restoring wallpapers…',
          'applying-transitions': 'Restoring transitions…',
          'applying-icon-filters': 'Restoring icon filters…',
          'applying-profiles': 'Restoring profiles…',
          'applying-layout': 'Restoring home layout…',
          'applying-prefs': 'Restoring preferences…',
          'finalising': 'Finalising…',
          'done': 'Done.',
          'failed': 'Failed.',
        }[phase] || phase;
        setPhase(label, pct);
      },
      done: function(){ setPhase('Done.', 100); setTimeout(close, 600); },
      fail: function(err){ setPhase('Failed: ' + (err && err.message || err), 100); setTimeout(close, 1800); },
    };
    currentImportUi = ui;
    return ui;
  }

  // ── Showcase submit ────────────────────────────────────────
  // Opens a small form pre-filled with the artefact's title /
  // description, then POSTs to /api/showcase/submit/{kind}/{id}.
  // The server-side flow mirrors ShowcaseSubmitDialog on the phone:
  // pre-flight GitHub token (Settings → Showcase integration on the
  // phone), open a PR via GithubClient.submitArtefact. The PR URL
  // comes back in `prUrl`.
  // ── Widget storage inspector ───────────────────────────────
  // GET /api/widgets/{id}/storage → list keys; DELETE removes one or
  // all. Per-widget bucket (`widget_<id>_iappyx_store` SharedPreferences
  // on disk). Useful when a widget remembers something the user wants
  // gone (timezone, location, last-fetched value).
  function openWidgetStorageInspector(w){
    var bg = document.getElementById('confirm-bg');
    var card = document.getElementById('confirm-card');
    var origHtml = card.innerHTML;
    var origMaxWidth = card.style.maxWidth;
    card.style.maxWidth = '560px';
    card.innerHTML =
      '<div id="confirm-title">Storage — ' + (w.title || w.id) + '</div>' +
      '<div style="margin:12px 0; max-height:60vh; overflow:auto;" id="ws-body">' +
      '  <div class="empty-state">Loading…</div>' +
      '</div>' +
      '<div id="confirm-actions">' +
      '  <button id="ws-close">Close</button>' +
      '  <button id="ws-clear-all" class="danger">Clear all</button>' +
      '</div>';
    bg.classList.add('open');
    function close(){
      bg.classList.remove('open');
      card.innerHTML = origHtml;
      card.style.maxWidth = origMaxWidth;
    }
    document.getElementById('ws-close').onclick = close;
    function render(){
      api('GET', '/api/widgets/' + encodeURIComponent(w.id) + '/storage').then(function(r){
        var body = document.getElementById('ws-body');
        if (!body) return;
        var clearAll = document.getElementById('ws-clear-all');
        if (!r.entries || !r.entries.length) {
          body.innerHTML = '<div class="empty-state">Empty — this widget hasn\'t saved anything via <code>iappyx.storage.set</code>.</div>';
          if (clearAll) clearAll.disabled = true;
          return;
        }
        if (clearAll) clearAll.disabled = false;
        body.innerHTML = '';
        r.entries.forEach(function(e){
          var row = document.createElement('div');
          row.style.cssText = 'display:flex; gap:8px; align-items:flex-start; padding:8px 0; border-bottom:1px solid var(--line);';
          var main = document.createElement('div');
          main.style.cssText = 'flex:1; overflow:hidden; min-width:0;';
          var k = document.createElement('div');
          k.style.cssText = 'font-family: "SF Mono", Menlo, monospace; font-size:12px; color:var(--accent); margin-bottom:2px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;';
          k.textContent = e.key + (e.type && e.type !== 'string' ? ' (' + e.type + ')' : '');
          var v = document.createElement('div');
          v.style.cssText = 'font-family: "SF Mono", Menlo, monospace; font-size:12px; color:var(--text); word-break:break-all; max-height:6em; overflow:auto;';
          v.textContent = e.value;
          main.appendChild(k); main.appendChild(v);
          var rm = document.createElement('button'); rm.textContent = '×';
          rm.style.cssText = 'background:transparent; border:0; color:var(--error); cursor:pointer; padding:4px 10px; font-size:16px; flex:0 0 auto;';
          rm.title = 'Remove this key';
          rm.onclick = function(){
            api('DELETE', '/api/widgets/' + encodeURIComponent(w.id) + '/storage', { key: e.key })
              .then(render).catch(toastError);
          };
          row.appendChild(main); row.appendChild(rm);
          body.appendChild(row);
        });
      }).catch(function(err){
        var body = document.getElementById('ws-body');
        if (body) body.innerHTML = '<div class="empty-state">Failed: ' + err.message + '</div>';
      });
    }
    document.getElementById('ws-clear-all').onclick = function(){
      confirmDestroy({
        title: 'Clear all storage?',
        message: 'Remove EVERY key/value from "' + (w.title || w.id) + '"\'s storage. The widget will start fresh next time it runs.',
        confirmLabel: 'Clear all',
      }).then(function(yes){
        if (!yes) return;
        api('DELETE', '/api/widgets/' + encodeURIComponent(w.id) + '/storage')
          .then(render).catch(toastError);
      });
    };
    render();
  }

  function submitToShowcase(kind, id, defaultTitle, defaultDescription){
    var bg = document.getElementById('confirm-bg');
    var card = document.getElementById('confirm-card');
    var origHtml = card.innerHTML;
    var origMaxWidth = card.style.maxWidth;
    card.style.maxWidth = '480px';
    var defaultSlug = (defaultTitle || '').toLowerCase()
      .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60);
    card.innerHTML =
      '<div id="confirm-title">Submit to showcase</div>' +
      '<div style="margin:12px 0;">' +
      '  <p class="sub">Open a pull request against the public showcase. Needs a GitHub token (set it once on the phone under Settings → Showcase integration).</p>' +
      '  <div class="field"><label>Title</label><input type="text" id="sub-title" maxlength="80" /></div>' +
      '  <div class="field"><label>Slug</label><input type="text" id="sub-slug" maxlength="60" placeholder="kebab-case-folder-name" /></div>' +
      '  <div class="field"><label>Description</label><textarea id="sub-desc" rows="3"></textarea></div>' +
      '</div>' +
      '<div id="confirm-actions">' +
      '  <button id="sub-cancel">Cancel</button>' +
      '  <button id="sub-go" class="primary">Submit</button>' +
      '</div>';
    bg.classList.add('open');
    document.getElementById('sub-title').value = defaultTitle || '';
    document.getElementById('sub-slug').value = defaultSlug || '';
    document.getElementById('sub-desc').value = defaultDescription || '';
    function close(){
      bg.classList.remove('open');
      card.innerHTML = origHtml;
      card.style.maxWidth = origMaxWidth;
    }
    document.getElementById('sub-cancel').onclick = close;
    document.getElementById('sub-go').onclick = function(){
      var go = document.getElementById('sub-go');
      go.disabled = true; go.textContent = 'Submitting…';
      var body = {
        title: document.getElementById('sub-title').value.trim() || defaultTitle,
        slug: document.getElementById('sub-slug').value.trim() || defaultSlug,
        description: document.getElementById('sub-desc').value.trim() || defaultDescription || '',
      };
      api('POST', '/api/showcase/submit/' + encodeURIComponent(kind) + '/' + encodeURIComponent(id), body)
        .then(function(r){
          close();
          window.alert('Pull request opened!\n\n' + r.prUrl);
          try { window.open(r.prUrl, '_blank', 'noopener'); } catch (_) {}
        })
        .catch(function(err){
          go.disabled = false; go.textContent = 'Submit';
          toastError(err);
        });
    };
  }

  function minToHHMM(min){
    var m = Math.max(0, Math.min(1439, min|0));
    var h = Math.floor(m / 60), mm = m % 60;
    return (h < 10 ? '0' : '') + h + ':' + (mm < 10 ? '0' : '') + mm;
  }
  function hhmmToMin(s){
    if (!s || s.indexOf(':') < 0) return 0;
    var parts = s.split(':');
    var h = parseInt(parts[0], 10) || 0;
    var m = parseInt(parts[1], 10) || 0;
    return Math.max(0, Math.min(1439, h * 60 + m));
  }
  /** epoch ms → "YYYY-MM-DD" in the editor's local timezone (matches
   *  the <input type="date"> contract). Empty string for 0 / missing
   *  so the field renders blank. */
  function msToDateInput(ms){
    if (!ms) return '';
    var d = new Date(ms);
    if (isNaN(d.getTime())) return '';
    var y = d.getFullYear();
    var m = d.getMonth() + 1;
    var dd = d.getDate();
    return y + '-' + (m<10?'0':'') + m + '-' + (dd<10?'0':'') + dd;
  }
  /** "YYYY-MM-DD" → epoch ms. Optional [endOfDay] anchors to 23:59:59.999
   *  so an inclusive "active until" date covers the whole day. */
  function dateInputToMs(s, endOfDay){
    if (!s) return 0;
    var parts = s.split('-');
    if (parts.length !== 3) return 0;
    var y = parseInt(parts[0], 10), m = parseInt(parts[1], 10) - 1, d = parseInt(parts[2], 10);
    if (isNaN(y) || isNaN(m) || isNaN(d)) return 0;
    var dt = endOfDay ? new Date(y, m, d, 23, 59, 59, 999) : new Date(y, m, d, 0, 0, 0, 0);
    var t = dt.getTime();
    return isNaN(t) ? 0 : t;
  }
  function reloadHomeAfterProfileSwap(){
    // Activating a profile rewrites the layout, wallpaper, etc.
    // Refresh state.layout + re-render so the home grid mirrors the
    // new active profile. Every cell may have changed identity, so
    // wipe the cell-node cache + the grid DOM so iframes from the
    // OLD profile aren't left as orphans.
    api('GET', '/api/state').then(function(s){
      state.layout = s.layout;
      state.widgets = s.widgets;
      els.grid.innerHTML = '';
      els.dock.innerHTML = '';
      cellNodes = {};
      state.selectedIds = {};
      // Profile activation can change the icon filter — bust icon
      // URLs so the new shape/colour treatment shows immediately.
      // It can also change the active wallpaper, so re-fetch that too.
      bumpIconVersion();
      refreshWallpaper();
      if (state.inspectId) closeInspect();
      renderAll();
    }).catch(toastError);
  }

  // ── Widgets tab ────────────────────────────────────────────
  var widgetsFilter = 'all';
  function renderWidgetsTab(){
    var list = document.getElementById('widgets-list');
    list.innerHTML = '<div class="empty-state">Loading…</div>';
    Array.prototype.forEach.call(document.querySelectorAll('#widgets-filter button'), function(b){
      b.classList.toggle('primary', b.dataset.filter === widgetsFilter);
    });
    var qEl = document.getElementById('widgets-q');
    var q = (qEl && qEl.value || '').toLowerCase();
    api('GET', '/api/widgets').then(function(arr){
      // Update the global cache so popover widget-search stays current
      // without forcing a separate /api/state refresh.
      state.widgets = arr;
      list.innerHTML = '';
      var visible = arr.filter(function(w){
        if (widgetsFilter === 'user' && !w.isUserGenerated) return false;
        if (widgetsFilter === 'bundled' && w.isUserGenerated) return false;
        if (widgetsFilter === 'in-use' && !w.inUse) return false;
        if (!q) return true;
        return ((w.title||'').toLowerCase().indexOf(q) >= 0) ||
               ((w.subtitle||'').toLowerCase().indexOf(q) >= 0);
      });
      if (!visible.length) {
        list.innerHTML = '<div class="empty-state">No widgets match.</div>';
        return;
      }
      visible.forEach(function(w){
        var row = document.createElement('div'); row.className = 'row-item';
        var thumb = document.createElement('div');
        // Thumbnail = miniature live iframe, same wire path the popover
        // uses. Lazy-load so a 50-widget library doesn't fire 50 iframes
        // upfront.
        thumb.style.cssText = 'width:64px; height:96px; border-radius:8px; overflow:hidden; background:#0a0a10; flex:0 0 64px; position:relative;';
        var iframe = document.createElement('iframe');
        iframe.src = '/api/widgets/' + encodeURIComponent(w.id) +
          '/preview.html?session=lib-' + encodeURIComponent(w.id);
        iframe.setAttribute('sandbox', 'allow-scripts allow-same-origin');
        iframe.setAttribute('loading', 'lazy');
        iframe.style.cssText = 'position:absolute; inset:0; width:100%; height:100%; border:0; pointer-events:none;';
        thumb.appendChild(iframe);
        row.appendChild(thumb);

        var main = document.createElement('div'); main.className = 'row-main';
        var title = document.createElement('div'); title.className = 'row-title';
        var badges = [];
        if (!w.isUserGenerated) badges.push('bundled');
        if (w.inUse) badges.push('in use');
        title.textContent = w.title + (badges.length ? ' (' + badges.join(', ') + ')' : '');
        var sub = document.createElement('div'); sub.className = 'row-sub';
        sub.textContent = w.subtitle || '';
        main.appendChild(title); main.appendChild(sub);
        row.appendChild(main);

        if (w.isUserGenerated) {
          var renameBtn = document.createElement('button'); renameBtn.textContent = 'Rename';
          renameBtn.onclick = function(){
            var t = window.prompt('New title', w.title);
            if (!t || !t.trim()) return;
            api('POST', '/api/widgets/' + encodeURIComponent(w.id) + '/rename', { title: t.trim() })
              .then(function(){ renderWidgetsTab(); }).catch(toastError);
          };
          row.appendChild(renameBtn);
          var descBtn = document.createElement('button'); descBtn.textContent = 'Edit description';
          descBtn.onclick = function(){
            var d = window.prompt('Description (the original AI prompt — stored in meta.json, no AI call):', w.subtitle || '');
            if (d == null) return;
            api('POST', '/api/widgets/' + encodeURIComponent(w.id) + '/description', { description: d })
              .then(function(){ renderWidgetsTab(); }).catch(toastError);
          };
          row.appendChild(descBtn);
          var subBtn = document.createElement('button'); subBtn.textContent = 'Submit';
          subBtn.title = 'Submit to public showcase';
          subBtn.onclick = function(){
            submitToShowcase('widget', w.id, w.title, w.subtitle);
          };
          row.appendChild(subBtn);
        }
        // Storage inspector — available for ALL widgets (bundled + user)
        // so debugging the clock widget's "remembered timezone" works
        // even on built-ins.
        var stoBtn = document.createElement('button'); stoBtn.textContent = 'Storage';
        stoBtn.title = 'Inspect per-widget storage (iappyx.storage.*)';
        stoBtn.onclick = function(){ openWidgetStorageInspector(w); };
        row.appendChild(stoBtn);
        var delBtn = document.createElement('button');
        delBtn.textContent = 'Delete'; delBtn.className = 'danger';
        if (!w.isUserGenerated || w.inUse) delBtn.disabled = true;
        if (!delBtn.disabled) {
          delBtn.onclick = function(){
            confirmDestroy({
              title: 'Delete widget?',
              message: 'Permanently remove "' + w.title + '"? Bundled and in-use widgets are kept.',
            }).then(function(yes){
              if (!yes) return;
              api('DELETE', '/api/widgets/' + encodeURIComponent(w.id))
                .then(function(){ renderWidgetsTab(); }).catch(toastError);
            });
          };
        }
        row.appendChild(delBtn);
        list.appendChild(row);
      });
    }).catch(function(err){
      list.innerHTML = '<div class="empty-state">Failed: ' + err.message + '</div>';
    });
  }
  Array.prototype.forEach.call(document.querySelectorAll('#widgets-filter button'), function(b){
    b.addEventListener('click', function(){ widgetsFilter = b.dataset.filter; renderWidgetsTab(); });
  });
  (function(){ var i = document.getElementById('widgets-q');
    if (i) i.addEventListener('input', renderWidgetsTab); })();

  /** Battery usage section for the Settings tab. Mirrors what
   *  WidgetUsageActivity shows on the device: window duration, per-widget
   *  GPS / tracking / sensors / audio / network time, sorted by the same
   *  drainScore the device uses. Always renders the section header so the
   *  user knows where it lives; the per-widget cards only appear once
   *  something has accumulated. Returns the section element to append. */
  function renderBatteryUsageSection(){
    var sec = makeSection('Battery usage by widget');
    var status = document.createElement('p'); status.className = 'sub';
    status.style.cssText = 'display:flex; align-items:center; gap:10px; flex-wrap:wrap;';
    status.textContent = 'Loading…';
    sec.appendChild(status);
    var listBox = document.createElement('div');
    sec.appendChild(listBox);
    function load(){
      status.textContent = 'Loading…';
      listBox.innerHTML = '';
      api('GET', '/api/widgets/usage').then(function(j){
        var rows = j && j.rows || [];
        status.innerHTML = '';
        var span = document.createElement('span');
        span.textContent = rows.length
          ? 'Since last reset · ' + formatDurationShort(j.durationMs || 0)
          : 'No widget has used a battery-relevant resource yet.';
        status.appendChild(span);
        var resetBtn = document.createElement('button');
        resetBtn.textContent = 'Reset counters';
        resetBtn.style.cssText = 'background:transparent; color:var(--hint); border:1px solid var(--line); border-radius:6px; padding:3px 10px; cursor:pointer; font-size:12px;';
        resetBtn.disabled = !rows.length;
        if (!rows.length) resetBtn.style.opacity = '0.5';
        resetBtn.onclick = function(){
          if (!window.confirm('Reset all widget battery counters?')) return;
          resetBtn.disabled = true;
          api('POST', '/api/widgets/usage/reset', {}).then(load).catch(function(err){
            resetBtn.disabled = false; toastError(err);
          });
        };
        status.appendChild(resetBtn);

        listBox.innerHTML = '';
        rows.forEach(function(r){
          var card = document.createElement('div');
          card.style.cssText = 'background:#0a0a10; border:1px solid var(--line); border-radius:12px; padding:12px 14px; margin-top:10px;';
          var title = document.createElement('div');
          title.style.cssText = 'font-weight:600; color:var(--text); margin-bottom:6px;';
          title.textContent = r.title || r.id;
          card.appendChild(title);
          function metric(label, val, isMs){
            if (!val) return; // hide zero rows — matches device's "primary" guard
            var row = document.createElement('div');
            row.style.cssText = 'display:flex; gap:8px; font-size:13px; padding:3px 0;';
            var l = document.createElement('div');
            l.style.cssText = 'flex:1; color:var(--hint);'; l.textContent = label;
            var v = document.createElement('div');
            v.style.cssText = 'color:var(--text); font-variant-numeric:tabular-nums;';
            v.textContent = isMs ? formatDurationShort(val) : formatBytes(val);
            row.appendChild(l); row.appendChild(v); card.appendChild(row);
          }
          metric('GPS (watchPosition)', r.gpsMs, true);
          metric('GPS tracking (foreground)', r.trackingMs, true);
          metric('Sensors (compass / accel / etc.)', r.sensorMs, true);
          metric('Audio playback', r.audioMs, true);
          metric('Visible on screen', r.visibleMs, true);
          metric('Network bytes received', r.bytesIn, false);
          listBox.appendChild(card);
        });
      }).catch(function(err){
        status.textContent = 'Couldn’t load usage data: ' + (err && err.message || err);
      });
    }
    load();
    return sec;
  }
  function formatDurationShort(ms){
    if (!ms || ms <= 0) return '—';
    var s = Math.floor(ms / 1000);
    var h = Math.floor(s / 3600);
    var m = Math.floor((s % 3600) / 60);
    var sec = s % 60;
    if (h > 0) return h + 'h ' + m + 'm ' + sec + 's';
    if (m > 0) return m + 'm ' + sec + 's';
    return sec + 's';
  }

  // ── Wallpapers tab ─────────────────────────────────────────
  function renderWallpapersTab(){
    var grid = document.getElementById('wallpapers-grid');
    grid.innerHTML = '<div class="empty-state">Loading…</div>';
    var qEl = document.getElementById('wallpapers-q');
    var q = (qEl && qEl.value || '').toLowerCase();
    api('GET', '/api/wallpapers').then(function(r){
      grid.innerHTML = '';
      var visible = r.wallpapers.filter(function(w){
        if (!q) return true;
        return ((w.title||'').toLowerCase().indexOf(q) >= 0) ||
               ((w.subtitle||'').toLowerCase().indexOf(q) >= 0);
      });
      if (!visible.length) {
        grid.innerHTML = '<div class="empty-state">No wallpapers match.</div>';
        return;
      }
      visible.forEach(function(w){
        var card = document.createElement('div');
        card.className = 'wp-card' + (w.active ? ' active' : '');
        var frame = document.createElement('div'); frame.className = 'frame';
        var iframe = document.createElement('iframe');
        // Each card preview is its own iframe — wallpaper bridges
        // are a smaller subset; most short-circuit cleanly when
        // iappyx isn't injected. We intentionally don't inject the
        // shim here (read-only thumbnail), so anything that fetches
        // bridge data shows fallback visuals.
        iframe.src = '/api/wallpapers/' + encodeURIComponent(w.id) + '/preview.html';
        iframe.setAttribute('sandbox', 'allow-scripts allow-same-origin');
        iframe.setAttribute('loading', 'lazy');
        frame.appendChild(iframe);
        card.appendChild(frame);
        var meta = document.createElement('div'); meta.className = 'meta';
        var t = document.createElement('div'); t.className = 't'; t.textContent = w.title;
        var s = document.createElement('div'); s.className = 's'; s.textContent = w.subtitle || '';
        meta.appendChild(t); meta.appendChild(s);
        card.appendChild(meta);
        var actions = document.createElement('div'); actions.className = 'actions';
        if (!w.active) {
          var setBtn = document.createElement('button'); setBtn.textContent = 'Set';
          setBtn.onclick = function(e){
            e.stopPropagation();
            // Optimistic: swap the iframe BEFORE the POST so the
            // phone-frame starts loading the new HTML immediately
            // instead of after two server round-trips. The POST is
            // fire-and-forget — on failure we toast and the next
            // refreshWallpaper() reconciles.
            applyWallpaperIfNeeded(w.id);
            api('POST', '/api/wallpapers/active', { id: w.id })
              .then(function(){ renderWallpapersTab(); })
              .catch(toastError);
          };
          actions.appendChild(setBtn);
        }
        if (w.isUserGenerated) {
          // Rename + Edit description for user-generated wallpapers
          // (bundled wallpapers' metadata comes from the build's
          // BUNDLED constant — overriding it would get reverted by
          // any APK update, so we don't expose the option).
          var renBtn = document.createElement('button'); renBtn.textContent = 'Rename';
          renBtn.onclick = function(e){
            e.stopPropagation();
            var t = window.prompt('New title', w.title);
            if (!t || !t.trim()) return;
            api('POST', '/api/wallpapers/' + encodeURIComponent(w.id) + '/rename', { title: t.trim() })
              .then(function(){ renderWallpapersTab(); }).catch(toastError);
          };
          actions.appendChild(renBtn);
          var dscBtn = document.createElement('button'); dscBtn.textContent = 'Description';
          dscBtn.onclick = function(e){
            e.stopPropagation();
            var d = window.prompt('Description (the AI prompt — metadata only, no AI call):', w.subtitle || '');
            if (d == null) return;
            api('POST', '/api/wallpapers/' + encodeURIComponent(w.id) + '/description', { description: d })
              .then(function(){ renderWallpapersTab(); }).catch(toastError);
          };
          actions.appendChild(dscBtn);
          var subBtn = document.createElement('button'); subBtn.textContent = 'Submit';
          subBtn.title = 'Submit to public showcase';
          subBtn.onclick = function(e){
            e.stopPropagation();
            submitToShowcase('wallpaper', w.id, w.title, w.subtitle);
          };
          actions.appendChild(subBtn);
        }
        if (w.isUserGenerated && !w.active) {
          var delBtn = document.createElement('button');
          delBtn.textContent = 'Delete'; delBtn.className = 'danger';
          delBtn.onclick = function(e){
            e.stopPropagation();
            confirmDestroy({
              title: 'Delete wallpaper?',
              message: 'Remove "' + w.title + '"? Bundled wallpapers can\'t be deleted.',
            }).then(function(yes){
              if (!yes) return;
              api('DELETE', '/api/wallpapers/' + encodeURIComponent(w.id))
                .then(function(){ renderWallpapersTab(); }).catch(toastError);
            });
          };
          actions.appendChild(delBtn);
        }
        card.appendChild(actions);
        // Single-tap activation only when the click landed on the
        // card body — buttons stop propagation explicitly. Active
        // card ignores clicks to avoid a confusing double-set.
        // Optimistic swap: kick off the iframe load instantly so
        // the phone-frame doesn't sit on the old wallpaper for
        // ~400ms while the POST and follow-up GET round-trip.
        card.onclick = function(){
          if (w.active) return;
          applyWallpaperIfNeeded(w.id);
          api('POST', '/api/wallpapers/active', { id: w.id })
            .then(function(){ renderWallpapersTab(); })
            .catch(toastError);
        };
        grid.appendChild(card);
      });
    }).catch(function(err){
      grid.innerHTML = '<div class="empty-state">Failed: ' + err.message + '</div>';
    });
  }
  (function(){ var i = document.getElementById('wallpapers-q');
    if (i) i.addEventListener('input', renderWallpapersTab); })();

  // ── Transitions tab ────────────────────────────────────────
  function renderTransitionsTab(){
    var list = document.getElementById('transitions-list');
    list.innerHTML = '<div class="empty-state">Loading…</div>';
    var qEl = document.getElementById('transitions-q');
    var q = (qEl && qEl.value || '').toLowerCase();
    api('GET', '/api/transitions').then(function(r){
      list.innerHTML = '';
      var grid = document.createElement('div'); grid.className = 'tile-grid';
      var visible = r.transitions.filter(function(t){
        if (!q) return true;
        return ((t.title||'').toLowerCase().indexOf(q) >= 0) ||
               ((t.subtitle||'').toLowerCase().indexOf(q) >= 0);
      });
      if (!visible.length) {
        list.innerHTML = '<div class="empty-state">No transitions match.</div>';
        return;
      }
      visible.forEach(function(t){
        var tile = document.createElement('div');
        tile.className = 'tile' + (t.active ? ' active' : '');
        tile.style.position = 'relative';
        var hd = document.createElement('div'); hd.className = 't';
        hd.textContent = t.title + (t.isUserGenerated ? '' : '');
        var sub = document.createElement('div'); sub.className = 's';
        sub.textContent = t.subtitle || '';
        tile.appendChild(hd); tile.appendChild(sub);
        // Tap the tile body to apply. Optimistic active-tile swap so
        // the highlight follows the click instantly; the POST + re-render
        // reconcile in the background. (Page transitions only manifest
        // during an actual page swipe, so there's no iframe to swap —
        // the highlight is the entire visible effect.)
        tile.onclick = function(){
          if (t.active) return;
          // Flip active class on the just-clicked tile and clear it
          // off all siblings.
          Array.prototype.forEach.call(tile.parentNode.children, function(sib){
            sib.classList.remove('active');
          });
          tile.classList.add('active');
          api('POST', '/api/transitions/active', { id: t.id })
            .then(function(){ renderTransitionsTab(); }).catch(toastError);
        };
        // User-generated → action chips (rename / description / delete).
        if (t.isUserGenerated) {
          var actions = document.createElement('div');
          actions.style.cssText = 'display:flex; gap:6px; margin-top:10px; flex-wrap:wrap;';
          var renBtn = document.createElement('button'); renBtn.textContent = 'Rename';
          renBtn.style.cssText = miniBtnStyle(false);
          renBtn.onclick = function(e){
            e.stopPropagation();
            var v = window.prompt('New title', t.title);
            if (!v || !v.trim()) return;
            api('POST', '/api/transitions/' + encodeURIComponent(t.id) + '/rename', { title: v.trim() })
              .then(function(){ renderTransitionsTab(); }).catch(toastError);
          };
          var dscBtn = document.createElement('button'); dscBtn.textContent = 'Description';
          dscBtn.style.cssText = miniBtnStyle(false);
          dscBtn.onclick = function(e){
            e.stopPropagation();
            var d = window.prompt('Description', t.subtitle || '');
            if (d == null) return;
            api('POST', '/api/transitions/' + encodeURIComponent(t.id) + '/description', { description: d })
              .then(function(){ renderTransitionsTab(); }).catch(toastError);
          };
          actions.appendChild(renBtn); actions.appendChild(dscBtn);
          var subBtn = document.createElement('button'); subBtn.textContent = 'Submit';
          subBtn.title = 'Submit to public showcase';
          subBtn.style.cssText = miniBtnStyle(false);
          subBtn.onclick = function(e){
            e.stopPropagation();
            submitToShowcase('transition', t.id, t.title, t.subtitle);
          };
          actions.appendChild(subBtn);
          if (!t.active) {
            var delBtn = document.createElement('button'); delBtn.textContent = 'Delete';
            delBtn.style.cssText = miniBtnStyle(true);
            delBtn.onclick = function(e){
              e.stopPropagation();
              confirmDestroy({
                title: 'Delete transition?',
                message: 'Remove "' + t.title + '"? Bundled and active transitions are kept.',
              }).then(function(yes){
                if (!yes) return;
                api('DELETE', '/api/transitions/' + encodeURIComponent(t.id))
                  .then(function(){ renderTransitionsTab(); }).catch(toastError);
              });
            };
            actions.appendChild(delBtn);
          }
          tile.appendChild(actions);
        }
        grid.appendChild(tile);
      });
      list.appendChild(grid);
    }).catch(function(err){
      list.innerHTML = '<div class="empty-state">Failed: ' + err.message + '</div>';
    });
  }
  (function(){ var i = document.getElementById('transitions-q');
    if (i) i.addEventListener('input', renderTransitionsTab); })();

  // ── Icons tab (icon filters) ──────────────────────────────
  function renderIconsTab(){
    var list = document.getElementById('icons-list');
    list.innerHTML = '<div class="empty-state">Loading…</div>';
    var qEl = document.getElementById('icons-q');
    var q = (qEl && qEl.value || '').toLowerCase();
    api('GET', '/api/icon_filters').then(function(r){
      list.innerHTML = '';
      var grid = document.createElement('div'); grid.className = 'tile-grid';
      var visible = r.iconFilters.filter(function(f){
        if (!q) return true;
        return ((f.title||'').toLowerCase().indexOf(q) >= 0) ||
               ((f.subtitle||'').toLowerCase().indexOf(q) >= 0);
      });
      if (!visible.length) {
        list.innerHTML = '<div class="empty-state">No icon filters match.</div>';
        return;
      }
      visible.forEach(function(f){
        var tile = document.createElement('div');
        tile.className = 'tile' + (f.active ? ' active' : '');
        var hd = document.createElement('div'); hd.className = 't'; hd.textContent = f.title;
        var sub = document.createElement('div'); sub.className = 's'; sub.textContent = f.subtitle || '';
        tile.appendChild(hd); tile.appendChild(sub);
        tile.onclick = function(){
          if (f.active) return;
          // Optimistic: update the slug we thread into icon URLs and
          // re-render NOW so the home grid swaps to the new filter
          // immediately. Icon API resolves `?filter=<slug>` ahead of
          // the active prefs value, so the in-flight POST doesn't
          // gate the visible change.
          activeIconFilter = f.slug;
          bumpIconVersion();
          Array.prototype.forEach.call(tile.parentNode.children, function(sib){
            sib.classList.remove('active');
          });
          tile.classList.add('active');
          cellNodes = {}; els.grid.innerHTML = ''; els.dock.innerHTML = '';
          renderAll();
          api('POST', '/api/icon_filters/active', { slug: f.slug })
            .then(function(){ renderIconsTab(); }).catch(toastError);
        };
        if (f.isUserGenerated) {
          var actions = document.createElement('div');
          actions.style.cssText = 'display:flex; gap:6px; margin-top:10px; flex-wrap:wrap;';
          var renBtn = document.createElement('button'); renBtn.textContent = 'Rename';
          renBtn.style.cssText = miniBtnStyle(false);
          renBtn.onclick = function(e){
            e.stopPropagation();
            var v = window.prompt('New title', f.title);
            if (!v || !v.trim()) return;
            api('POST', '/api/icon_filters/' + encodeURIComponent(f.slug) + '/rename', { title: v.trim() })
              .then(function(){ renderIconsTab(); }).catch(toastError);
          };
          var dscBtn = document.createElement('button'); dscBtn.textContent = 'Description';
          dscBtn.style.cssText = miniBtnStyle(false);
          dscBtn.onclick = function(e){
            e.stopPropagation();
            var d = window.prompt('Description', f.subtitle || '');
            if (d == null) return;
            api('POST', '/api/icon_filters/' + encodeURIComponent(f.slug) + '/description', { description: d })
              .then(function(){ renderIconsTab(); }).catch(toastError);
          };
          actions.appendChild(renBtn); actions.appendChild(dscBtn);
          var subBtn = document.createElement('button'); subBtn.textContent = 'Submit';
          subBtn.title = 'Submit to public showcase';
          subBtn.style.cssText = miniBtnStyle(false);
          subBtn.onclick = function(e){
            e.stopPropagation();
            submitToShowcase('icon_filter', f.slug, f.title, f.subtitle);
          };
          actions.appendChild(subBtn);
          if (!f.active) {
            var delBtn = document.createElement('button'); delBtn.textContent = 'Delete';
            delBtn.style.cssText = miniBtnStyle(true);
            delBtn.onclick = function(e){
              e.stopPropagation();
              confirmDestroy({
                title: 'Delete icon filter?',
                message: 'Remove "' + f.title + '"? Bundled and active filters are kept.',
              }).then(function(yes){
                if (!yes) return;
                api('DELETE', '/api/icon_filters/' + encodeURIComponent(f.slug))
                  .then(function(){ renderIconsTab(); }).catch(toastError);
              });
            };
            actions.appendChild(delBtn);
          }
          tile.appendChild(actions);
        }
        grid.appendChild(tile);
      });
      list.appendChild(grid);
    }).catch(function(err){
      list.innerHTML = '<div class="empty-state">Failed: ' + err.message + '</div>';
    });
  }
  (function(){ var i = document.getElementById('icons-q');
    if (i) i.addEventListener('input', renderIconsTab); })();
  function miniBtnStyle(danger){
    var c = danger ? 'var(--error)' : 'var(--text)';
    return 'background:transparent; color:' + c + '; border:1px solid ' + (danger ? 'var(--error)' : 'var(--line)') +
      '; border-radius:6px; padding:4px 10px; font-size:11px; cursor:pointer;';
  }

  // ── Clippings tab ──────────────────────────────────────────
  var clippingFilter = null;
  function renderClippingsTab(){
    var list = document.getElementById('clippings-list');
    var filterRow = document.getElementById('clippings-filter');
    list.innerHTML = '<div class="empty-state">Loading…</div>';
    api('GET', '/api/clippings').then(function(r){
      // Build filter chips from the kinds present.
      var kinds = {};
      r.clippings.forEach(function(c){ kinds[c.kind] = (kinds[c.kind]||0) + 1; });
      filterRow.innerHTML = '';
      function addChip(label, value){
        var chip = document.createElement('button');
        chip.textContent = label;
        chip.className = (clippingFilter === value) ? 'primary' : '';
        chip.style.cssText = 'background:transparent; border:1px solid var(--line); border-radius:999px; padding:4px 12px; color:var(--text); cursor:pointer; font-size:12px;';
        if (clippingFilter === value) {
          chip.style.borderColor = 'var(--accent)'; chip.style.color = 'var(--accent)';
        }
        chip.onclick = function(){ clippingFilter = value; renderClippingsTab(); };
        filterRow.appendChild(chip);
      }
      addChip('All (' + r.clippings.length + ')', null);
      Object.keys(kinds).sort().forEach(function(k){
        addChip(k + ' (' + kinds[k] + ')', k);
      });
      var qEl = document.getElementById('clippings-q');
      var q = (qEl && qEl.value || '').toLowerCase();
      var visible = r.clippings.filter(function(c){
        if (clippingFilter && c.kind !== clippingFilter) return false;
        if (!q) return true;
        return ((c.title||'').toLowerCase().indexOf(q) >= 0) ||
               ((c.kind||'').toLowerCase().indexOf(q) >= 0) ||
               ((c.sourceHost||'').toLowerCase().indexOf(q) >= 0);
      });
      list.innerHTML = '';
      if (!visible.length) {
        list.innerHTML = '<div class="empty-state">No clippings.</div>';
        return;
      }
      visible.forEach(function(c){
        var row = document.createElement('div');
        row.className = 'row-item';
        var main = document.createElement('div'); main.className = 'row-main';
        var title = document.createElement('div'); title.className = 'row-title';
        title.textContent = (c.locked ? '🔒 ' : '') + c.title;
        var sub = document.createElement('div'); sub.className = 'row-sub';
        var subBits = [c.kind];
        if (c.sourceHost) subBits.push(c.sourceHost);
        if (c.expiresAt > 0) {
          var rem = c.expiresAt - Date.now();
          subBits.push(rem > 0 ? formatRemaining(rem) : 'expired');
        } else if (!c.locked) {
          subBits.push('never expires');
        }
        sub.textContent = subBits.join(' • ');
        main.appendChild(title); main.appendChild(sub);
        row.appendChild(main);
        // "Open" — for clippings that came from a shared URL (Chrome
        // share → iappyx etc.). Opens the original page in a new tab.
        // Hidden when there's no sourceUrl (text-only clippings, files).
        if (c.sourceUrl) {
          var openBtn = document.createElement('button');
          openBtn.textContent = 'Open';
          openBtn.className = 'primary';
          openBtn.onclick = function(){
            // noopener so the new tab can't reference the editor window.
            try { window.open(c.sourceUrl, '_blank', 'noopener'); } catch (_) {}
          };
          row.appendChild(openBtn);
        }
        var lockBtn = document.createElement('button');
        lockBtn.textContent = c.locked ? 'Unlock' : 'Lock';
        lockBtn.onclick = function(){
          api('PATCH', '/api/clippings/' + encodeURIComponent(c.widgetId),
            { locked: !c.locked })
            .then(function(){ renderClippingsTab(); }).catch(toastError);
        };
        row.appendChild(lockBtn);
        if (!c.locked) {
          var ttlBtn = document.createElement('button'); ttlBtn.textContent = 'Reset TTL';
          ttlBtn.onclick = function(){
            api('PATCH', '/api/clippings/' + encodeURIComponent(c.widgetId),
              { resetTtl: true })
              .then(function(){ renderClippingsTab(); }).catch(toastError);
          };
          row.appendChild(ttlBtn);
        }
        var delBtn = document.createElement('button');
        delBtn.textContent = 'Delete'; delBtn.className = 'danger';
        delBtn.onclick = function(){
          confirmDestroy({
            title: 'Delete clipping?',
            message: 'Remove "' + c.title + '"? This permanently deletes the clipping content.',
          }).then(function(yes){
            if (!yes) return;
            api('DELETE', '/api/clippings/' + encodeURIComponent(c.widgetId))
              .then(function(){ renderClippingsTab(); }).catch(toastError);
          });
        };
        row.appendChild(delBtn);
        list.appendChild(row);
      });
    }).catch(function(err){
      list.innerHTML = '<div class="empty-state">Failed: ' + err.message + '</div>';
    });
  }
  (function(){ var i = document.getElementById('clippings-q');
    if (i) i.addEventListener('input', renderClippingsTab); })();
  function formatRemaining(ms){
    var hours = Math.floor(ms / 3600000);
    if (hours < 1) return Math.floor(ms / 60000) + ' min left';
    if (hours < 48) return hours + ' h left';
    return Math.floor(hours / 24) + ' d left';
  }

  // ── Theme tab ──────────────────────────────────────────────
  // Reads/writes the SAME --iappyx-* override map as the on-device theme
  // editor (via /api/theme), so the two stay in sync. Mirrors the native
  // controls: preset, accent, font (live-previewed from Google Fonts),
  // text size, density, corner radius, glass blur.
  function ensureGoogleFont(fam){
    var id = 'gf-' + fam.replace(/[^a-z0-9]/gi, '');
    if (document.getElementById(id)) return;
    var l = document.createElement('link');
    l.id = id; l.rel = 'stylesheet';
    l.href = 'https://fonts.googleapis.com/css2?family=' +
      encodeURIComponent(fam).replace(/%20/g, '+') + '&display=swap';
    document.head.appendChild(l);
  }
  function renderThemeTab(){
    var body = document.getElementById('theme-body');
    body.innerHTML = '<div class="empty-state">Loading…</div>';
    api('GET', '/api/theme').then(function(t){
      var working = {}; var ov = t.overrides || {};
      for (var k in ov) working[k] = ov[k];
      var accents = t.accents || [];
      var fonts = t.fonts || { bundled: [], catalog: [] };
      var fontList = [].concat(fonts.bundled || [], fonts.catalog || []);

      function fallbackStack(fb){
        return fb === 'serif' ? 'Georgia, "Noto Serif", serif'
          : fb === 'mono' ? 'ui-monospace, "Roboto Mono", monospace'
          : '-apple-system, "Roboto", system-ui, sans-serif';
      }
      function lum(hex){
        var c = hex.replace('#', '');
        var r = parseInt(c.substr(0,2),16), g = parseInt(c.substr(2,2),16), b = parseInt(c.substr(4,2),16);
        return (0.299*r + 0.587*g + 0.114*b) / 255;
      }
      var commitT = null;
      function commit(){
        clearTimeout(commitT);
        commitT = setTimeout(function(){
          api('POST', '/api/theme', { overrides: working }).catch(toastError);
        }, 120);
      }
      function setKeys(o){ for (var k in o){ if (o[k] == null) delete working[k]; else working[k] = o[k]; } }
      function fontSel(){
        var f = working['--iappyx-font']; if (!f) return 'System';
        for (var i=0;i<fontList.length;i++){ if (f.indexOf('"'+fontList[i].family+'"') >= 0) return fontList[i].family; }
        return f.indexOf('condensed') >= 0 ? 'Condensed' : 'System';
      }
      function sizeSel(){ var x = working['--iappyx-text-xl']; return x==='24px'?'Compact':x==='34px'?'Large':'Normal'; }
      function densSel(){ var s = working['--iappyx-space-sm']; return s==='4px'?'Compact':s==='10px'?'Spacious':'Cozy'; }
      function radiusVal(){ var r = working['--iappyx-radius']; return r?parseInt(r,10):20; }
      function glassVal(){ var g = working['--iappyx-glass-blur']; return g?parseInt(g,10):14; }

      function chips(label, opts, sel, onPick){
        var sec = makeSection(label);
        var row = document.createElement('div');
        row.style.cssText = 'display:flex;flex-wrap:wrap;gap:8px;';
        opts.forEach(function(o){
          var b = document.createElement('button');
          b.textContent = o;
          var active = (o === sel);
          b.style.cssText = 'padding:8px 14px;border-radius:10px;border:1px solid ' +
            (active ? 'var(--accent)' : 'var(--line)') + ';background:' +
            (active ? 'rgba(79,195,247,0.12)' : 'transparent') + ';color:var(--text);cursor:pointer;font-size:13px;';
          b.onclick = function(){ onPick(o); commit(); render(); };
          row.appendChild(b);
        });
        sec.appendChild(row);
        body.appendChild(sec);
      }

      function render(){
        body.innerHTML = '';

        chips('Preset', ['Material You','Glass','Sharp','Bold'],
          (Object.keys(working).length === 0 ? 'Material You' : null), function(p){
            if (p === 'Material You') { for (var k in working) delete working[k]; }
            else if (p === 'Glass') setKeys({'--iappyx-glass-blur':'22px','--iappyx-glass-opacity':'0.14','--iappyx-radius':'24px','--iappyx-radius-sm':'14px'});
            else if (p === 'Sharp') setKeys({'--iappyx-radius':'6px','--iappyx-radius-sm':'4px','--iappyx-glass-blur':'0px'});
            else if (p === 'Bold') setKeys({'--iappyx-weight-normal':'600','--iappyx-text-xl':'34px','--iappyx-text-lg':'24px','--iappyx-text-md':'17px'});
          });

        var asec = makeSection('Accent');
        var arow = document.createElement('div'); arow.style.cssText = 'display:flex;flex-wrap:wrap;gap:10px;';
        accents.forEach(function(hex){
          var sw = document.createElement('button');
          var on = working['--iappyx-primary'] && working['--iappyx-primary'].toLowerCase() === hex.toLowerCase();
          sw.style.cssText = 'width:34px;height:34px;border-radius:50%;background:'+hex+';cursor:pointer;border:'+(on?'3px solid #fff':'1px solid rgba(255,255,255,0.27)')+';';
          sw.onclick = function(){
            working['--iappyx-primary'] = hex;
            working['--iappyx-on-primary'] = lum(hex) > 0.6 ? '#11131a' : '#ffffff';
            commit(); render();
          };
          arow.appendChild(sw);
        });
        asec.appendChild(arow); body.appendChild(asec);

        var fsec = makeSection('Font');
        var fsel = document.createElement('select');
        fsel.style.cssText = 'width:100%;padding:8px;border-radius:8px;background:var(--bg);color:var(--text);border:1px solid var(--line);';
        fsel.appendChild(new Option('System', 'System'));
        fontList.forEach(function(f){ fsel.appendChild(new Option(f.family + (f.downloaded === false ? ' (downloads on apply)' : ''), f.family)); });
        fsel.value = fontSel();
        var prev = document.createElement('div');
        prev.textContent = 'The quick brown fox 0123';
        prev.style.cssText = 'margin-top:12px;font-size:24px;color:var(--text);min-height:30px;';
        function applyPrev(fam){ if (fam === 'System'){ prev.style.fontFamily = ''; } else { ensureGoogleFont(fam); prev.style.fontFamily = '"'+fam+'"'; } }
        applyPrev(fsel.value);
        fsel.onchange = function(){
          var fam = fsel.value;
          if (fam === 'System') { delete working['--iappyx-font']; }
          else {
            var e = fontList.filter(function(x){ return x.family === fam; })[0];
            working['--iappyx-font'] = '"'+fam+'", ' + fallbackStack(e ? e.fallback : 'sans');
          }
          applyPrev(fam); commit();
        };
        fsec.appendChild(fsel); fsec.appendChild(prev); body.appendChild(fsec);

        chips('Text size', ['Compact','Normal','Large'], sizeSel(), function(s){
          if (s === 'Normal') { ['xl','lg','md','sm'].forEach(function(x){ delete working['--iappyx-text-'+x]; }); }
          else { var v = s === 'Compact' ? [24,17,13,11] : [34,24,17,13];
            working['--iappyx-text-xl']=v[0]+'px'; working['--iappyx-text-lg']=v[1]+'px';
            working['--iappyx-text-md']=v[2]+'px'; working['--iappyx-text-sm']=v[3]+'px'; }
        });
        chips('Density', ['Compact','Cozy','Spacious'], densSel(), function(s){
          if (s === 'Cozy') { ['sm','md','lg'].forEach(function(x){ delete working['--iappyx-space-'+x]; }); }
          else { var v = s === 'Compact' ? [4,8,14] : [10,18,28];
            working['--iappyx-space-sm']=v[0]+'px'; working['--iappyx-space-md']=v[1]+'px'; working['--iappyx-space-lg']=v[2]+'px'; }
        });

        function rangeCtl(label, val, onInput){
          var sec = makeSection(label);
          var r = document.createElement('input');
          r.type = 'range'; r.min = 0; r.max = 28; r.value = val; r.style.cssText = 'width:100%;';
          r.oninput = function(){ onInput(parseInt(r.value, 10)); }; // no re-render — keep slider grab
          r.onchange = function(){ commit(); };
          sec.appendChild(r); body.appendChild(sec);
        }
        rangeCtl('Corner radius', radiusVal(), function(v){ working['--iappyx-radius']=v+'px'; working['--iappyx-radius-sm']=Math.round(v*0.6)+'px'; });
        rangeCtl('Glass blur', glassVal(), function(v){ working['--iappyx-glass-blur']=v+'px'; });

        var rb = document.createElement('button');
        rb.textContent = 'Reset to Material You';
        rb.style.cssText = 'margin-top:18px;color:var(--accent);background:transparent;border:0;cursor:pointer;font-size:14px;';
        rb.onclick = function(){ for (var k in working) delete working[k]; commit(); render(); };
        body.appendChild(rb);
      }
      render();
    }).catch(function(){ body.innerHTML = '<div class="empty-state">Failed to load theme.</div>'; });
  }

  // ── Settings tab ───────────────────────────────────────────
  function renderSettingsTab(){
    var body = document.getElementById('settings-body');
    body.innerHTML = '<div class="empty-state">Loading…</div>';
    api('GET', '/api/settings').then(function(s){
      body.innerHTML = '';
      // Grid section — destructive controls. Changes are STAGED (held in
      // local state) and only committed when the user taps "Apply grid",
      // mirroring the on-device pattern. Stray scroll-wheel or accidental
      // select-change cannot trigger a layout-destroying patch. Shrinking
      // cols/rows drops placements that no longer fit; flipping dominant
      // orientation rotates the layout — both confirmed before apply.
      var sec1 = makeSection('Grid');
      var fr = document.createElement('div'); fr.className = 'field-row';
      fr.appendChild(numField('Columns', 'cols', s.cols, 2, 12));
      fr.appendChild(numField('Rows', 'rows', s.rows, 2, 12));
      fr.appendChild(numField('Dock slots', 'dockSlots', s.dockSlots, 0, 12));
      sec1.appendChild(fr);

      var orientField = document.createElement('div'); orientField.className = 'field';
      orientField.innerHTML = '<label>Dominant orientation</label>' +
        '<select id="set-orient">' +
        '  <option value="portrait">Portrait</option>' +
        '  <option value="landscape">Landscape</option>' +
        '</select>';
      orientField.style.cssText = 'margin-top:12px;';
      sec1.appendChild(orientField);
      var orientSel = orientField.querySelector('select');
      orientSel.value = s.dominantOrientation;

      var applyRow = document.createElement('div');
      applyRow.style.cssText = 'display:flex; gap:10px; align-items:center; margin-top:14px;';
      var applyBtn = document.createElement('button');
      applyBtn.textContent = 'Apply grid';
      applyBtn.className = 'primary';
      applyBtn.style.cssText = 'background:var(--accent); color:#001220; border:0; border-radius:8px; padding:8px 16px; cursor:pointer; font-weight:600; font-size:13px; opacity:0.5;';
      applyBtn.disabled = true;
      var applyStatus = document.createElement('span');
      applyStatus.className = 'sub';
      applyStatus.style.cssText = 'font-size:12px;';
      applyStatus.textContent = 'No pending changes.';
      applyRow.appendChild(applyBtn); applyRow.appendChild(applyStatus);
      sec1.appendChild(applyRow);

      // Watch all inputs + the select. Apply enables when anything
      // differs from the current server values.
      var pending = {};
      function recomputePending(){
        pending = {};
        var inputs = sec1.querySelectorAll('input[type=number]');
        Array.prototype.forEach.call(inputs, function(i){
          var v = parseInt(i.value, 10);
          if (!isNaN(v) && v !== s[i.dataset.key]) pending[i.dataset.key] = v;
        });
        if (orientSel.value !== s.dominantOrientation) {
          pending.dominantOrientation = orientSel.value;
        }
        var keys = Object.keys(pending);
        if (!keys.length) {
          applyBtn.disabled = true;
          applyBtn.style.opacity = '0.5';
          applyStatus.textContent = 'No pending changes.';
        } else {
          applyBtn.disabled = false;
          applyBtn.style.opacity = '1';
          applyStatus.textContent = 'Pending: ' + keys.join(', ');
        }
      }
      Array.prototype.forEach.call(sec1.querySelectorAll('input[type=number]'), function(i){
        i.addEventListener('input', recomputePending);
        i.addEventListener('change', recomputePending);
      });
      orientSel.addEventListener('change', recomputePending);

      applyBtn.onclick = function(){
        // Build a confirmation message that lists what's about to change
        // and warns about destructive sides (shrinking grid drops cells,
        // orientation flip rotates the whole layout).
        var lines = ['About to change:'];
        if ('cols' in pending) lines.push('  Columns: ' + s.cols + ' → ' + pending.cols);
        if ('rows' in pending) lines.push('  Rows: ' + s.rows + ' → ' + pending.rows);
        if ('dockSlots' in pending) lines.push('  Dock slots: ' + s.dockSlots + ' → ' + pending.dockSlots);
        if ('dominantOrientation' in pending) lines.push('  Dominant orientation: ' + s.dominantOrientation + ' → ' + pending.dominantOrientation);
        var shrink = ('cols' in pending && pending.cols < s.cols) ||
                     ('rows' in pending && pending.rows < s.rows) ||
                     ('dockSlots' in pending && pending.dockSlots < s.dockSlots);
        if (shrink) lines.push('', 'WARNING: shrinking will permanently DROP icons that no longer fit.');
        if ('dominantOrientation' in pending) lines.push('', 'Dominant orientation changes affect how the layout is interpreted. Make a backup first if unsure.');
        lines.push('', 'Proceed?');
        if (!window.confirm(lines.join('\n'))) return;
        applyBtn.disabled = true; applyBtn.textContent = 'Applying…';
        api('PATCH', '/api/settings', pending).then(function(){
          // Re-render so the server's authoritative values become the
          // new baseline and the Apply button resets.
          renderSettingsTab();
        }).catch(function(err){
          applyBtn.disabled = false; applyBtn.textContent = 'Apply grid';
          toastError(err);
        });
      };
      body.appendChild(sec1);

      // Toggles section — non-destructive, immediate commit is fine.
      var sec2 = makeSection('Behaviour');
      sec2.appendChild(toggleRow('Notification badges', 'Red dot on icons of apps with notifications', 'notificationBadgesEnabled', s.notificationBadgesEnabled));
      sec2.appendChild(toggleRow('Allow rotation', 'Let the launcher follow device rotation', 'allowRotation', s.allowRotation));
      sec2.appendChild(toggleRow('Long-press = menu', 'Long-pressing a cell shows the app menu (off = jumps to edit mode)', 'useLongPressMenu', s.useLongPressMenu));
      sec2.appendChild(toggleRow('Show dock labels', 'Caption text under dock icons', 'showDockLabels', s.showDockLabels));
      body.appendChild(sec2);

      // (Page transitions + Icon filters used to live here as inline
      // pickers; they're now full Transitions and Icons tabs with
      // rename / description / delete affordances. Settings keeps
      // only the cross-cutting toggles + credentials + backup.)

      // Credentials (sensitive)
      var sec5 = makeSection('Credentials');
      sec5.appendChild(credField('AI key (Anthropic)', 'ai', s.credentials.ai));
      sec5.appendChild(credField('GitHub token', 'github', s.credentials.github));
      var note = document.createElement('p'); note.className = 'sub';
      note.style.marginTop = '8px';
      note.textContent = 'Stored encrypted on the phone via Android Keystore. Sent over plain HTTP on this LAN — same trade as the rest of this editor.';
      sec5.appendChild(note);
      body.appendChild(sec5);

      // AI model — comes after Credentials because the dropdown needs
      // an API key on file to populate (and Refresh hits the Anthropic
      // /v1/models endpoint with that key).
      body.appendChild(renderAiModelSection(s));

      // Battery usage by widget — mirrors on-device Settings → Battery
      // usage. Lives in Settings because it's diagnostic info, not part
      // of widget browsing.
      body.appendChild(renderBatteryUsageSection());

      // Backup (3.3)
      var sec6 = makeSection('Backup');
      var actions = document.createElement('div'); actions.style.cssText = 'display:flex; gap:10px; flex-wrap:wrap;';
      var includeKey = document.createElement('label');
      includeKey.style.cssText = 'display:flex; gap:6px; align-items:center; font-size:12px; color:var(--hint);';
      includeKey.innerHTML = '<input type="checkbox" id="bk-include-key" /> Include API key';
      var includeRuntime = document.createElement('label');
      includeRuntime.style.cssText = 'display:flex; gap:6px; align-items:center; font-size:12px; color:var(--hint);';
      includeRuntime.innerHTML = '<input type="checkbox" id="bk-include-runtime" checked /> Include widget data';
      sec6.appendChild(includeKey); sec6.appendChild(includeRuntime);
      var exportBtn = document.createElement('button');
      exportBtn.textContent = 'Export backup'; exportBtn.className = 'primary';
      exportBtn.style.cssText = 'background:transparent; color:var(--accent); border:1px solid var(--accent); border-radius:8px; padding:8px 14px; cursor:pointer; margin-top:10px; margin-right:8px;';
      exportBtn.onclick = function(){
        var qs = '?' +
          'includeApiKey=' + (document.getElementById('bk-include-key').checked) +
          '&includeRuntimeData=' + (document.getElementById('bk-include-runtime').checked);
        // Browser-native download via a hidden anchor — auth cookie is
        // sent automatically because we're same-origin.
        var a = document.createElement('a');
        a.href = '/api/backup/export' + qs;
        a.download = '';
        document.body.appendChild(a); a.click(); a.remove();
      };
      var importBtn = document.createElement('button');
      importBtn.textContent = 'Import backup…';
      importBtn.style.cssText = exportBtn.style.cssText.replace('var(--accent)', 'var(--text)');
      importBtn.onclick = function(){
        var input = document.createElement('input');
        input.type = 'file'; input.accept = '.iappyxbackup,.zip';
        input.onchange = function(){
          var file = input.files[0]; if (!file) return;
          var mode = window.confirm('Replace current state? Click Cancel to merge instead.')
            ? 'replace' : 'merge';
          var ui = openImportProgress(file.name, file.size);
          file.arrayBuffer().then(function(buf){
            // Phase 1 (upload): fetch's body is buffered in memory before
            // the request fires, so we don't get progress events for the
            // upload over fetch(). XHR's onprogress works though — use it
            // so the progress bar advances during the LAN upload too.
            return new Promise(function(resolve, reject){
              var xhr = new XMLHttpRequest();
              xhr.open('POST', '/api/backup/import?mode=' + mode);
              xhr.setRequestHeader('Content-Type', 'application/zip');
              xhr.upload.onprogress = function(e){
                if (e.lengthComputable) ui.upload(e.loaded, e.total);
              };
              xhr.upload.onload = function(){ ui.upload(file.size, file.size); };
              xhr.onload = function(){
                var j; try { j = JSON.parse(xhr.responseText); } catch (_) { j = null; }
                if (xhr.status >= 200 && xhr.status < 300 && j && j.ok) resolve(j);
                else reject(new Error((j && j.error) || 'import failed (' + xhr.status + ')'));
              };
              xhr.onerror = function(){ reject(new Error('upload network error')); };
              xhr.send(buf);
            });
          }).then(function(j){
            ui.done();
            window.alert('Imported: ' + j.widgetCount + ' widgets, ' +
              j.wallpaperCount + ' wallpapers, ' +
              j.profileCount + ' profiles.');
            reloadHomeAfterProfileSwap();
          }).catch(function(err){ ui.fail(err); toastError(err); });
        };
        input.click();
      };
      sec6.appendChild(exportBtn); sec6.appendChild(importBtn);

      // Orphan widget cleanup — counterpart to the on-device "Cleanup"
      // row. Lazy-loads the count so the Settings tab doesn't pay a
      // disk-scan cost on every open; the row appears as soon as the
      // request resolves.
      var orphanRow = document.createElement('div');
      orphanRow.style.cssText = 'margin-top:14px; padding:10px 12px; border:1px solid var(--line); border-radius:8px; display:flex; align-items:center; gap:12px;';
      var orphanLabel = document.createElement('div');
      orphanLabel.style.cssText = 'flex:1; font-size:13px; color:var(--hint);';
      orphanLabel.textContent = 'Checking for orphan widget data…';
      var orphanBtn = document.createElement('button');
      orphanBtn.textContent = 'Clean up';
      orphanBtn.style.cssText = 'background:transparent; color:var(--accent); border:1px solid var(--accent); border-radius:8px; padding:5px 12px; cursor:pointer; font-size:12px;';
      orphanBtn.style.display = 'none';
      orphanRow.appendChild(orphanLabel); orphanRow.appendChild(orphanBtn);
      sec6.appendChild(orphanRow);
      api('GET', '/api/backup/orphans').then(function(o){
        if (!o || !o.count) {
          orphanLabel.textContent = 'No orphan widget data — storage clean.';
          return;
        }
        orphanLabel.textContent = 'Orphan widget data: ' + o.count + ' folder' +
          (o.count === 1 ? '' : 's') + ' · ' + formatBytes(o.bytes || 0);
        orphanBtn.style.display = '';
        orphanBtn.onclick = function(){
          if (!window.confirm('Delete ' + o.count + ' orphan widget folder(s)?')) return;
          orphanBtn.disabled = true; orphanBtn.textContent = 'Cleaning…';
          api('POST', '/api/backup/cleanup', {}).then(function(r){
            orphanBtn.style.display = 'none';
            orphanLabel.textContent = 'Deleted ' + (r.deleted || 0) + ' orphan folder' +
              ((r.deleted || 0) === 1 ? '' : 's') + '.';
          }).catch(function(err){
            orphanBtn.disabled = false; orphanBtn.textContent = 'Clean up';
            toastError(err);
          });
        };
      }).catch(function(){
        orphanLabel.textContent = 'Cleanup status unavailable.';
      });

      // Clear chat history — mirrors the device BackupSettings row.
      // Single confirmation; the endpoint clears every conversation
      // turn from the on-device ChatDatabase.
      var clearChat = document.createElement('button');
      clearChat.textContent = 'Clear chat history';
      clearChat.style.cssText = 'background:transparent; color:var(--error); border:1px solid var(--error); border-radius:8px; padding:6px 12px; cursor:pointer; margin-top:14px; font-size:12px;';
      clearChat.onclick = function(){
        if (!window.confirm('Delete all chat history? This cannot be undone.')) return;
        clearChat.disabled = true;
        api('POST', '/api/chat/clear', {}).then(function(){
          clearChat.textContent = 'Chat history cleared'; clearChat.disabled = true;
          // Refresh the chat tab if it's currently in memory so it reflects empty state.
          if (typeof renderChat === 'function') { state.chat = []; renderChat(); }
        }).catch(function(err){
          clearChat.disabled = false; clearChat.textContent = 'Clear chat history';
          toastError(err);
        });
      };
      sec6.appendChild(clearChat);

      body.appendChild(sec6);

      // About — last section, version + build info from /api/about.
      body.appendChild(renderAboutSection());
    }).catch(function(err){
      body.innerHTML = '<div class="empty-state">Failed: ' + err.message + '</div>';
    });
  }
  function makeSection(title){
    var sec = document.createElement('div'); sec.className = 'pane-section';
    var h = document.createElement('h2'); h.textContent = title;
    sec.appendChild(h);
    return sec;
  }
  function formatBytes(n){
    if (!n) return '0 B';
    var units = ['B','KB','MB','GB'];
    var i = 0, v = n;
    while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
    return (i === 0 ? v.toFixed(0) : v.toFixed(1)) + ' ' + units[i];
  }
  function renderAboutSection(){
    // Lazy GET so a slow /api/about doesn't stall the Settings tab.
    // Same defensive pattern the orphan row uses.
    var sec = makeSection('About');
    var box = document.createElement('div');
    box.style.cssText = 'font-size:13px; line-height:1.5;';
    box.textContent = 'Loading…';
    sec.appendChild(box);
    api('GET', '/api/about').then(function(a){
      box.innerHTML = '';

      // Header: name + version + tagline.
      var header = document.createElement('div');
      header.style.cssText = 'margin-bottom:14px;';
      var name = document.createElement('div');
      name.style.cssText = 'font-size:16px; font-weight:600; color:var(--text);';
      name.textContent = a.appName || 'iappyxOS Launcher';
      header.appendChild(name);
      var ver = document.createElement('div');
      ver.style.cssText = 'color:var(--hint); font-size:12px; margin-top:2px;';
      ver.textContent = 'Version ' + (a.version || '?') +
        (a.versionCode ? ' (build ' + a.versionCode + ')' : '');
      header.appendChild(ver);
      if (a.tagline) {
        var tag = document.createElement('div');
        tag.style.cssText = 'color:var(--hint); margin-top:10px; line-height:1.45;';
        tag.textContent = a.tagline;
        header.appendChild(tag);
      }
      box.appendChild(header);

      // Rows card: License · Source · Acknowledgements. Each tap-able.
      var card = document.createElement('div');
      card.style.cssText = 'background:#0a0a10; border:1px solid var(--line); border-radius:10px; overflow:hidden;';

      if (a.license) {
        card.appendChild(aboutRow(
          a.license.label || 'License',
          a.license.value || 'MIT',
          function(){ showLicenseDialog(a.license.value || 'MIT', a.license.text || ''); },
        ));
        card.appendChild(aboutRowDivider());
      }
      if (a.sourceUrl) {
        card.appendChild(aboutRow(
          'Source code',
          a.sourceUrl,
          function(){
            try {
              window.open('https://' + a.sourceUrl, '_blank', 'noopener');
            } catch (_) {}
          },
        ));
        card.appendChild(aboutRowDivider());
      }
      var acks = a.acknowledgements || [];
      if (acks.length) {
        card.appendChild(aboutRow(
          'Open-source libraries',
          acks.length + ' projects',
          function(){ showAcknowledgementsDialog(acks); },
        ));
      }
      if (a.support && a.support.url) {
        if (acks.length) card.appendChild(aboutRowDivider());
        card.appendChild(aboutRow(
          a.support.label || 'Support development',
          a.support.value || 'Buy me a coffee',
          function(){
            try { window.open(a.support.url, '_blank', 'noopener'); } catch (_) {}
          },
        ));
      }
      box.appendChild(card);

      // Privacy section.
      if (a.privacy) {
        var pr = document.createElement('div');
        pr.style.cssText = 'background:#0a0a10; border:1px solid var(--line); border-radius:10px; padding:14px; margin-top:14px;';
        var pt = document.createElement('div');
        pt.style.cssText = 'font-weight:600; color:var(--text); margin-bottom:6px;';
        pt.textContent = a.privacy.title || 'Privacy';
        pr.appendChild(pt);
        var pb = document.createElement('div');
        pb.style.cssText = 'color:var(--hint); font-size:13px; line-height:1.55;';
        pb.textContent = a.privacy.body || '';
        pr.appendChild(pb);
        box.appendChild(pr);
      }

      // System / debug facts (collapsed style).
      var facts = document.createElement('div');
      facts.style.cssText = 'margin-top:14px; padding:10px 12px; background:#0a0a10; border:1px solid var(--line); border-radius:10px; font-family: "SF Mono", ui-monospace, monospace; font-size:11px; color:var(--hint); line-height:1.7;';
      function f(k, v){
        var line = document.createElement('div');
        line.textContent = k + ': ' + v;
        facts.appendChild(line);
      }
      if (a.package) f('package', a.package);
      if (a.buildType) f('build', a.buildType);
      if (a.abi) f('abi', a.abi);
      if (a.sdkInt) f('android', 'SDK ' + a.sdkInt);
      if (a.device) f('device', a.device);
      box.appendChild(facts);

      // Footer.
      if (a.footer) {
        var foot = document.createElement('div');
        foot.style.cssText = 'text-align:center; color:var(--hint); font-size:12px; margin-top:18px;';
        foot.textContent = a.footer;
        box.appendChild(foot);
      }
    }).catch(function(){
      box.textContent = 'Version info unavailable.';
    });
    return sec;
  }

  function aboutRow(label, value, onClick){
    var r = document.createElement('div');
    r.style.cssText = 'display:flex; align-items:center; gap:12px; padding:12px 14px; cursor:pointer;';
    r.addEventListener('mouseenter', function(){ r.style.background = 'rgba(255,255,255,.03)'; });
    r.addEventListener('mouseleave', function(){ r.style.background = ''; });
    r.onclick = onClick;
    var l = document.createElement('div');
    l.style.cssText = 'flex:1; color:var(--text); font-size:13px;';
    l.textContent = label;
    var v = document.createElement('div');
    v.style.cssText = 'color:var(--accent); font-size:12px; max-width:55%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;';
    v.textContent = value;
    r.appendChild(l); r.appendChild(v);
    return r;
  }
  function aboutRowDivider(){
    var d = document.createElement('div');
    d.style.cssText = 'height:1px; background:var(--line);';
    return d;
  }

  /** Generic full-screen-ish text dialog used for licenses + the
   *  acknowledgements list. Uses the same fixed-overlay pattern the
   *  plugin Configure / Network dialogs use. */
  function showTextDialog(title, contentNode){
    var bg = document.createElement('div');
    bg.style.cssText = 'position:fixed; inset:0; background:rgba(0,0,0,0.7); z-index:1000; display:flex; align-items:center; justify-content:center;';
    var card = document.createElement('div');
    card.style.cssText = 'background:var(--bg); border:1px solid var(--line); border-radius:14px; width:min(600px,94vw); max-height:85vh; display:flex; flex-direction:column; overflow:hidden;';
    var head = document.createElement('div');
    head.style.cssText = 'padding:12px 16px; border-bottom:1px solid var(--line); display:flex; align-items:center; gap:12px;';
    var ttl = document.createElement('div');
    ttl.style.cssText = 'flex:1; font-weight:600; font-size:14px;';
    ttl.textContent = title;
    var close = document.createElement('button');
    close.textContent = '✕';
    close.style.cssText = 'background:transparent; color:var(--text); border:1px solid var(--line); border-radius:8px; padding:4px 10px; cursor:pointer; font-size:14px;';
    head.appendChild(ttl); head.appendChild(close);
    card.appendChild(head);
    var body = document.createElement('div');
    body.style.cssText = 'padding:16px 18px; overflow:auto; flex:1;';
    body.appendChild(contentNode);
    card.appendChild(body);
    bg.appendChild(card);
    document.body.appendChild(bg);
    function teardown(){ try { document.body.removeChild(bg); } catch (_) {} }
    close.addEventListener('click', teardown);
    bg.addEventListener('click', function(e){ if (e.target === bg) teardown(); });
  }

  function showLicenseDialog(label, fullText){
    var pre = document.createElement('pre');
    pre.style.cssText = 'white-space:pre-wrap; font-family: "SF Mono", ui-monospace, monospace; font-size:12px; color:var(--text); line-height:1.5; margin:0;';
    pre.textContent = fullText || '(license text unavailable)';
    showTextDialog(label + ' license', pre);
  }

  function showAcknowledgementsDialog(acks){
    var wrap = document.createElement('div');
    var intro = document.createElement('p');
    intro.className = 'sub';
    intro.style.marginTop = '0';
    intro.textContent = acks.length + ' open-source projects this launcher depends on. Tap a license tag to view its full text.';
    wrap.appendChild(intro);
    acks.forEach(function(a){
      var entry = document.createElement('div');
      entry.style.cssText = 'padding:12px 0; border-top:1px solid var(--line);';
      var n = document.createElement('div');
      n.style.cssText = 'font-weight:600; color:var(--text); font-size:13px;';
      n.textContent = a.name;
      entry.appendChild(n);
      if (a.description) {
        var d = document.createElement('div');
        d.style.cssText = 'color:var(--hint); font-size:12px; line-height:1.5; margin-top:4px;';
        d.textContent = a.description;
        entry.appendChild(d);
      }
      var meta = document.createElement('div');
      meta.style.cssText = 'display:flex; align-items:center; gap:10px; margin-top:6px;';
      var cp = document.createElement('div');
      cp.style.cssText = 'flex:1; color:var(--hint); font-size:11px;';
      cp.textContent = a.copyrightLine || '';
      meta.appendChild(cp);
      if (a.licenseKind) {
        var tag = document.createElement('button');
        tag.textContent = a.licenseKind.toUpperCase();
        tag.style.cssText = 'background:transparent; color:var(--accent); border:1px solid var(--accent); border-radius:999px; padding:2px 10px; font-size:11px; cursor:pointer;';
        tag.onclick = function(){
          api('GET', '/api/about/license/' + encodeURIComponent(a.licenseKind))
            .then(function(r){ showLicenseDialog(a.licenseKind.toUpperCase(), r.text || ''); })
            .catch(function(err){ toastError(err); });
        };
        meta.appendChild(tag);
      }
      entry.appendChild(meta);
      wrap.appendChild(entry);
    });
    showTextDialog('Open-source libraries', wrap);
  }
  function renderAiModelSection(s){
    // Two dropdowns (chat / iterate) + a Refresh link that hits
    // /api/settings/refresh_models. If the model catalog is cold
    // (no API key OR /v1/models never fetched), the dropdowns fall
    // back to a single option showing the currently-saved value so
    // the user always sees what's in effect.
    var sec = makeSection('AI model');
    var hasKey = !!(s.credentials && s.credentials.ai && s.credentials.ai.set);
    var models = s.models || [];

    function ensureOption(arr, id) {
      // If the saved model isn't in the live list, surface it anyway
      // so the dropdown doesn't silently flip to whatever's first.
      if (!id) return arr;
      for (var i = 0; i < arr.length; i++) if (arr[i].id === id) return arr;
      return [{ id: id, displayName: id + ' (saved)' }].concat(arr);
    }

    function buildSelect(id, current) {
      var sel = document.createElement('select');
      sel.id = id;
      var list = ensureOption(models, current);
      if (!list.length) {
        var opt = document.createElement('option');
        opt.value = ''; opt.textContent = '(no models — set an API key and Refresh)';
        sel.appendChild(opt);
        sel.disabled = true;
      } else {
        list.forEach(function(m){
          var opt = document.createElement('option');
          opt.value = m.id; opt.textContent = m.displayName || m.id;
          if (m.id === current) opt.selected = true;
          sel.appendChild(opt);
        });
      }
      return sel;
    }

    var chatField = document.createElement('div'); chatField.className = 'field';
    chatField.innerHTML = '<label>Chat / create model</label>';
    var chatSel = buildSelect('set-chat-model', s.anthropicModel);
    chatField.appendChild(chatSel);
    sec.appendChild(chatField);

    var iterField = document.createElement('div'); iterField.className = 'field';
    iterField.innerHTML = '<label>Iterate / edit model</label>';
    var iterSel = buildSelect('set-iter-model', s.iterateModel);
    iterField.appendChild(iterSel);
    sec.appendChild(iterField);

    chatSel.onchange = function(){
      if (!chatSel.value) return;
      api('PATCH', '/api/settings', { anthropicModel: chatSel.value }).catch(toastError);
    };
    iterSel.onchange = function(){
      if (!iterSel.value) return;
      api('PATCH', '/api/settings', { iterateModel: iterSel.value }).catch(toastError);
    };

    var refresh = document.createElement('button');
    refresh.textContent = s.modelsCached ? 'Refresh model list' : 'Load model list from Anthropic';
    refresh.style.cssText = 'background:transparent; color:var(--accent); border:1px solid var(--accent); border-radius:8px; padding:6px 12px; cursor:pointer; margin-top:6px; font-size:12px;';
    refresh.disabled = !hasKey;
    if (!hasKey) refresh.style.opacity = '0.5';
    refresh.onclick = function(){
      refresh.disabled = true;
      var prev = refresh.textContent;
      refresh.textContent = 'Refreshing…';
      api('POST', '/api/settings/refresh_models', {}).then(function(){
        // Re-render the whole section so the new options + count appear.
        renderSettingsTab();
      }).catch(function(err){
        refresh.textContent = prev; refresh.disabled = false;
        toastError(err);
      });
    };
    sec.appendChild(refresh);

    if (!hasKey) {
      var hint = document.createElement('p'); hint.className = 'sub';
      hint.style.marginTop = '8px';
      hint.textContent = 'Set an Anthropic API key above to enable the dropdowns.';
      sec.appendChild(hint);
    }
    return sec;
  }
  function numField(label, key, value, min, max){
    var f = document.createElement('div'); f.className = 'field';
    f.innerHTML = '<label>' + escapeHtml(label) + '</label>';
    var input = document.createElement('input');
    input.type = 'number'; input.min = min; input.max = max;
    input.value = value; input.dataset.key = key;
    f.appendChild(input);
    return f;
  }
  function toggleRow(label, sub, key, value){
    var row = document.createElement('div'); row.className = 'toggle-row';
    var stack = document.createElement('div'); stack.className = 'label-stack';
    stack.innerHTML = '<div class="l">' + escapeHtml(label) + '</div>' +
      '<div class="s">' + escapeHtml(sub) + '</div>';
    var input = document.createElement('input');
    input.type = 'checkbox'; input.checked = !!value;
    input.onchange = function(){
      var patch = {}; patch[key] = input.checked;
      api('PATCH', '/api/settings', patch).then(function(){
        // If the patched key is one of the view prefs the home tab
        // mirrors (currently showDockLabels), reflect it in state +
        // re-apply so the dock relayouts immediately.
        if (key === 'showDockLabels') {
          state.viewPrefs = state.viewPrefs || {};
          state.viewPrefs.showDockLabels = input.checked;
          applyViewPrefs();
        }
      }).catch(toastError);
    };
    row.appendChild(stack); row.appendChild(input);
    return row;
  }
  function credField(label, kind, val){
    var f = document.createElement('div'); f.className = 'field';
    f.innerHTML = '<label>' + escapeHtml(label) + (val.set ? ' (set: ' + escapeHtml(val.masked) + ')' : ' (not set)') + '</label>';
    var inputRow = document.createElement('div');
    inputRow.style.cssText = 'display:flex; gap:6px;';
    var input = document.createElement('input');
    input.type = 'password'; input.placeholder = val.set ? 'Replace…' : 'Paste key';
    input.style.flex = '1';
    inputRow.appendChild(input);
    var saveBtn = document.createElement('button'); saveBtn.textContent = 'Save';
    saveBtn.style.cssText = 'background:transparent; color:var(--text); border:1px solid var(--line); border-radius:8px; padding:6px 12px; cursor:pointer;';
    saveBtn.onclick = function(){
      api('POST', '/api/settings/credentials', { kind: kind, value: input.value })
        .then(function(){ input.value = ''; renderSettingsTab(); }).catch(toastError);
    };
    inputRow.appendChild(saveBtn);
    if (val.set) {
      var clrBtn = document.createElement('button'); clrBtn.textContent = 'Clear';
      clrBtn.style.cssText = 'background:transparent; color:var(--error); border:1px solid var(--error); border-radius:8px; padding:6px 12px; cursor:pointer;';
      clrBtn.onclick = function(){
        confirmDestroy({
          title: 'Clear ' + label + '?',
          message: 'The launcher will need it again to use that feature.',
        }).then(function(yes){
          if (!yes) return;
          api('POST', '/api/settings/credentials', { kind: kind, value: '' })
            .then(function(){ renderSettingsTab(); }).catch(toastError);
        });
      };
      inputRow.appendChild(clrBtn);
    }
    f.appendChild(inputRow);
    return f;
  }
  function escapeHtml(s){
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  // ── Showcase tab ──────────────────────────────────────────
  /* PLUGINS: BEGIN ── Plugins tab ─────────────────────────────── */
  function renderPluginsTab(){
    var list = document.getElementById('plugins-list');
    list.innerHTML = '<div class="empty-state">Loading…</div>';
    api('GET', '/api/plugins/installed').then(function(r){
      list.innerHTML = '';
      var plugins = (r && r.plugins) || [];
      if (!plugins.length) {
        list.innerHTML = '<div class="empty-state">No plugins installed yet. Tap "Browse showcase" to install one.</div>';
        return;
      }
      plugins.forEach(function(p){ list.appendChild(buildPluginRow(p)); });
    }).catch(function(err){
      list.innerHTML = '<div class="empty-state">Couldn\'t load plugins: ' + (err && err.message || err) + '</div>';
    });
    document.getElementById('plugins-browse-btn').onclick = function(){
      // Reuse the existing showcase tab; pre-select the plugins sub-tab.
      showcaseKind = 'plugins';
      switchSection('showcase');
      // Reflect the active sub-tab visually.
      Array.prototype.forEach.call(document.querySelectorAll('#showcase-tabs button'), function(b){
        b.classList.toggle('active', b.dataset.kind === 'plugins');
      });
    };
  }

  function buildPluginRow(p){
    var row = document.createElement('div'); row.className = 'row-item';
    row.style.flexDirection = 'column'; row.style.alignItems = 'stretch';

    var head = document.createElement('div');
    head.style.cssText = 'display:flex;align-items:center;gap:12px;';
    var main = document.createElement('div'); main.className = 'row-main';
    var t = document.createElement('div'); t.className = 'row-title';
    t.textContent = p.name;
    var s = document.createElement('div'); s.className = 'row-sub';
    s.textContent = 'v' + (p.version || '1.0.0') + ' · ' +
      (p.source === 'BUNDLED' ? 'bundled' : 'installed');
    main.appendChild(t); main.appendChild(s);
    head.appendChild(main);

    var sw = document.createElement('input');
    sw.type = 'checkbox';
    sw.checked = !!p.enabled;
    sw.style.cssText = 'width:auto;cursor:pointer;transform:scale(1.3);accent-color:var(--accent);';
    sw.title = 'Enabled';
    sw.addEventListener('change', function(){
      sw.disabled = true;
      api('POST', '/api/plugins/' + encodeURIComponent(p.id) + '/enable',
          { enabled: sw.checked })
        .then(function(r){
          sw.checked = !!(r && r.enabled);
        })
        .catch(function(err){
          alert('Toggle failed: ' + (err && err.message || err));
          sw.checked = !sw.checked;
        })
        .finally(function(){ sw.disabled = false; });
    });
    head.appendChild(sw);
    row.appendChild(head);

    if (p.description) {
      var d = document.createElement('div');
      d.style.cssText = 'color:var(--text);font-size:13px;margin-top:8px;';
      d.textContent = p.description;
      row.appendChild(d);
    }

    // Network restriction summary — always rendered, including the
    // "always on" baseline. Subdued for "always", green/red for restricted.
    if (p.networkMode) {
      var summary = document.createElement('div');
      var text, color;
      if (p.networkMode === 'always') {
        text = '🌐 Always on (no network restriction)';
        color = 'var(--hint)';
      } else {
        var modeLabel = ({
          'trusted_wifi':        'Trusted Wi-Fi only',
          'vpn':                 'VPN only',
          'trusted_wifi_or_vpn': 'Trusted Wi-Fi or VPN',
        })[p.networkMode] || p.networkMode;
        var parts = [modeLabel];
        if (p.networkMode === 'trusted_wifi' || p.networkMode === 'trusted_wifi_or_vpn') {
          parts.push(p.trustedSsidCount === 1 ? '1 network' : (p.trustedSsidCount || 0) + ' networks');
        }
        parts.push(p.networkAllowedNow ? 'allowed now' : 'blocked now');
        text = '🔒 ' + parts.join(' · ');
        color = p.networkAllowedNow ? 'var(--ok)' : 'var(--error)';
      }
      summary.style.cssText = 'margin-top:8px;font-size:12px;color:' + color + ';';
      summary.textContent = text;
      row.appendChild(summary);
    }

    if (p.capabilities && p.capabilities.length) {
      var caps = document.createElement('div');
      caps.style.cssText = 'display:flex;gap:6px;margin-top:10px;flex-wrap:wrap;';
      p.capabilities.forEach(function(c){
        var chip = document.createElement('span');
        chip.textContent = capLabel(c);
        chip.style.cssText = 'font-size:11px;color:var(--accent);' +
          'border:1px solid rgba(79,195,247,0.4);background:rgba(79,195,247,0.07);' +
          'border-radius:999px;padding:3px 10px;white-space:nowrap;';
        caps.appendChild(chip);
      });
      row.appendChild(caps);
    }

    // Universal-search exposure toggle — only for plugins that declare
    // `universalSearch` in their manifest. Mirrors the on-device
    // PluginDetailActivity.buildSearchExposureCard so the laptop has
    // parity over which plugins participate in the home-screen search.
    if (p.exposes && p.exposes.indexOf('universalSearch') >= 0) {
      var seRow = document.createElement('div');
      seRow.style.cssText = 'display:flex;align-items:center;gap:12px;margin-top:12px;padding-top:12px;border-top:1px solid var(--line-soft, rgba(255,255,255,0.06));';
      var seStack = document.createElement('div'); seStack.style.flex = '1';
      var seTitle = document.createElement('div');
      seTitle.textContent = 'Expose to universal search';
      seTitle.style.cssText = 'font-size:13px;color:var(--text);';
      var seSub = document.createElement('div');
      seSub.textContent = 'Allow this plugin’s items to appear when you type in the home-screen search panel.';
      seSub.style.cssText = 'font-size:11px;color:var(--hint);margin-top:2px;';
      seStack.appendChild(seTitle); seStack.appendChild(seSub);
      var seSw = document.createElement('input');
      seSw.type = 'checkbox';
      seSw.checked = (p.searchExposed !== false); // default true if missing
      seSw.style.cssText = 'cursor:pointer;transform:scale(1.2);accent-color:var(--accent);';
      seSw.addEventListener('change', function(){
        seSw.disabled = true;
        api('POST', '/api/plugins/' + encodeURIComponent(p.id) + '/search_exposed',
            { exposed: seSw.checked })
          .then(function(r){
            seSw.checked = !!(r && r.exposed);
          })
          .catch(function(err){
            seSw.checked = !seSw.checked;
            alert('Toggle failed: ' + (err && err.message || err));
          })
          .finally(function(){ seSw.disabled = false; });
      });
      seRow.appendChild(seStack); seRow.appendChild(seSw);
      row.appendChild(seRow);
    }

    var actions = document.createElement('div');
    actions.style.cssText = 'display:flex;gap:8px;margin-top:12px;';
    if (p.hasSettingsUi) {
      var cfg = document.createElement('button');
      cfg.textContent = 'Configure';
      cfg.className = 'primary';
      cfg.addEventListener('click', function(){ openPluginSettings(p); });
      actions.appendChild(cfg);
    }
    var net = document.createElement('button');
    net.textContent = 'Network';
    net.addEventListener('click', function(){ openPluginNetworkDialog(p); });
    actions.appendChild(net);
    if (p.source === 'USER') {
      var spacer = document.createElement('div'); spacer.style.flex = '1';
      actions.appendChild(spacer);
      var del = document.createElement('button');
      del.textContent = 'Uninstall'; del.className = 'danger';
      del.addEventListener('click', function(){
        if (!confirm('Uninstall ' + p.name + '? This wipes its credentials and stored data.')) return;
        del.disabled = true;
        api('DELETE', '/api/plugins/' + encodeURIComponent(p.id))
          .then(function(){ renderPluginsTab(); })
          .catch(function(err){
            del.disabled = false;
            alert('Uninstall failed: ' + (err && err.message || err));
          });
      });
      actions.appendChild(del);
    }
    if (actions.childNodes.length > 0) row.appendChild(actions);
    return row;
  }

  /** Short labels for capability chips — matches the launcher's Settings
   *  chip labels so the experience is consistent across phone and web. */
  function capLabel(c){
    return ({
      'http':              'Network',
      'storage':           'Storage',
      'secureStore':       'Credentials',
      'scheduler':         'Background',
      'notification:read': 'Notifications',
      'push':              'Push',
    })[c] || c;
  }

  /** Open the plugin's settings.html inside a modal iframe. The launcher
   *  serves a preloaded shim that mimics iappyx.secureStore/storage/
   *  httpClient using cached preload data + HTTP-proxied writes. */
  function openPluginSettings(p){
    openPluginSettingsModal(p);
  }

  function openPluginNetworkDialog(p){
    api('GET', '/api/plugins/' + encodeURIComponent(p.id) + '/network').then(function(r){
      var n = r.network || {};
      renderNetworkDialog(p, n);
    }).catch(function(err){
      alert('Could not load network config: ' + (err && err.message || err));
    });
  }

  function renderNetworkDialog(p, n){
    var bg = document.createElement('div');
    bg.style.cssText =
      'position:fixed;inset:0;background:rgba(0,0,0,0.7);z-index:1000;' +
      'display:flex;align-items:center;justify-content:center;';
    var card = document.createElement('div');
    card.style.cssText =
      'background:var(--bg);border:1px solid var(--line);border-radius:14px;' +
      'width:min(520px,94vw);max-height:85vh;display:flex;flex-direction:column;' +
      'overflow:hidden;';
    var head = document.createElement('div');
    head.style.cssText =
      'padding:12px 16px;border-bottom:1px solid var(--line);' +
      'display:flex;align-items:center;gap:12px;';
    var title = document.createElement('div');
    title.style.cssText = 'flex:1;font-weight:600;font-size:14px;';
    title.textContent = p.name + ' — Network restrictions';
    var close = document.createElement('button');
    close.textContent = '✕';
    close.style.cssText =
      'background:transparent;color:var(--text);border:1px solid var(--line);' +
      'border-radius:8px;padding:4px 10px;cursor:pointer;font-size:14px;';
    head.appendChild(title); head.appendChild(close);
    card.appendChild(head);

    var body = document.createElement('div');
    body.style.cssText = 'padding:18px;overflow:auto;';
    card.appendChild(body);

    // Mode radio group.
    var modes = [
      ['always',              'Always (no restriction)'],
      ['trusted_wifi',        'Trusted Wi-Fi only'],
      ['vpn',                 'VPN connection active'],
      ['trusted_wifi_or_vpn', 'Trusted Wi-Fi or VPN'],
    ];
    var selectedMode = n.mode || 'always';
    var modeLabel = document.createElement('div');
    modeLabel.textContent = 'Run on';
    modeLabel.style.cssText = 'color:var(--hint);font-size:12px;text-transform:uppercase;letter-spacing:0.07em;margin-bottom:8px;';
    body.appendChild(modeLabel);
    modes.forEach(function(pair){
      var row = document.createElement('label');
      row.style.cssText = 'display:flex;align-items:center;gap:10px;padding:6px 0;cursor:pointer;font-size:14px;';
      var rb = document.createElement('input');
      rb.type = 'radio'; rb.name = 'plugin-net-mode'; rb.value = pair[0];
      rb.checked = pair[0] === selectedMode;
      rb.addEventListener('change', function(){ if (rb.checked) selectedMode = pair[0]; });
      row.appendChild(rb);
      row.appendChild(document.createTextNode(pair[1]));
      body.appendChild(row);
    });

    // Trusted SSIDs section.
    var ssidLabel = document.createElement('div');
    ssidLabel.textContent = 'Trusted Wi-Fi networks';
    ssidLabel.style.cssText = 'color:var(--hint);font-size:12px;text-transform:uppercase;letter-spacing:0.07em;margin:18px 0 8px;';
    body.appendChild(ssidLabel);

    var ssidList = document.createElement('div');
    body.appendChild(ssidList);
    var ssids = (n.trustedSsids || []).slice();

    function renderSsids(){
      ssidList.innerHTML = '';
      if (!ssids.length){
        var empty = document.createElement('div');
        empty.textContent = 'No trusted networks yet.';
        empty.style.cssText = 'color:var(--hint);font-size:12px;padding:4px 0;';
        ssidList.appendChild(empty);
        return;
      }
      ssids.forEach(function(s){
        var row = document.createElement('div');
        row.style.cssText = 'display:flex;align-items:center;justify-content:space-between;padding:6px 0;border-bottom:1px solid var(--line-soft);';
        var name = document.createElement('span');
        name.textContent = s;
        name.style.cssText = 'font-size:14px;color:var(--text);';
        var rm = document.createElement('button');
        rm.textContent = 'Remove';
        rm.style.cssText = 'background:transparent;color:var(--error);border:1px solid rgba(255,82,82,0.4);border-radius:6px;padding:4px 10px;cursor:pointer;font-size:12px;';
        rm.addEventListener('click', function(){
          ssids = ssids.filter(function(x){ return x !== s; });
          renderSsids();
        });
        row.appendChild(name); row.appendChild(rm);
        ssidList.appendChild(row);
      });
    }
    renderSsids();

    // Add SSID controls — current SSID button + manual entry input.
    var addRow = document.createElement('div');
    addRow.style.cssText = 'display:flex;gap:8px;margin-top:14px;';
    var input = document.createElement('input');
    input.type = 'text';
    input.placeholder = 'Type an SSID';
    input.style.cssText = 'flex:1;background:#0a0a10;color:var(--text);border:1px solid var(--line);border-radius:8px;padding:8px 12px;font:inherit;';
    function pushSsid(s){
      s = (s || '').trim();
      if (!s || ssids.indexOf(s) !== -1) return;
      ssids.push(s); renderSsids(); input.value = '';
    }
    input.addEventListener('keydown', function(e){
      if (e.key === 'Enter'){ e.preventDefault(); pushSsid(input.value); }
    });
    addRow.appendChild(input);
    var addManualBtn = document.createElement('button');
    addManualBtn.textContent = 'Add';
    addManualBtn.style.cssText = 'background:transparent;color:var(--text);border:1px solid var(--line);border-radius:8px;padding:8px 14px;cursor:pointer;font-size:13px;';
    addManualBtn.addEventListener('click', function(){ pushSsid(input.value); });
    addRow.appendChild(addManualBtn);
    if (n.currentSsid){
      var addCur = document.createElement('button');
      addCur.textContent = 'Add ' + n.currentSsid;
      addCur.style.cssText = 'background:transparent;color:var(--accent);border:1px solid var(--accent);border-radius:8px;padding:8px 14px;cursor:pointer;font-size:13px;white-space:nowrap;';
      addCur.addEventListener('click', function(){ pushSsid(n.currentSsid); });
      addRow.appendChild(addCur);
    }
    body.appendChild(addRow);

    // Live network state hint (the phone's current state — useful on the
    // laptop because the laptop has NO idea what Wi-Fi the phone is on).
    var hint = document.createElement('div');
    hint.style.cssText = 'margin-top:18px;padding:10px 12px;border-radius:8px;background:#0a0a10;border:1px solid var(--line);font-size:12px;line-height:1.6;color:var(--hint);';
    var lines = [];
    lines.push('<strong style="color:var(--text);">Phone’s current state:</strong>');
    lines.push('Wi-Fi: ' + (n.currentSsid ? '<code>' + escapeHtml(n.currentSsid) + '</code>' : 'not connected'));
    lines.push('VPN: ' + (n.onVpn ? '<span style="color:var(--ok);">active</span>' : '<span style="color:var(--hint);">inactive</span>'));
    lines.push('Plugin allowed now: ' + (n.allowedNow ? '<span style="color:var(--ok);">yes</span>' : '<span style="color:var(--error);">no</span>'));
    hint.innerHTML = lines.join('<br>');
    body.appendChild(hint);

    // Footer buttons.
    var foot = document.createElement('div');
    foot.style.cssText = 'padding:12px 16px;border-top:1px solid var(--line);display:flex;gap:10px;';
    var cancelBtn = document.createElement('button');
    cancelBtn.textContent = 'Cancel';
    cancelBtn.style.cssText = 'flex:1;background:transparent;color:var(--text);border:1px solid var(--line);border-radius:8px;padding:10px;cursor:pointer;';
    cancelBtn.addEventListener('click', teardown);
    var saveBtn = document.createElement('button');
    saveBtn.textContent = 'Save';
    saveBtn.style.cssText = 'flex:1;background:var(--accent);color:#001220;border:0;border-radius:8px;padding:10px;cursor:pointer;font-weight:600;';
    saveBtn.addEventListener('click', function(){
      saveBtn.disabled = true; cancelBtn.disabled = true;
      api('POST', '/api/plugins/' + encodeURIComponent(p.id) + '/network', {
        mode: selectedMode, trustedSsids: ssids,
      }).then(function(){
        teardown();
        renderPluginsTab();
      }).catch(function(err){
        alert('Save failed: ' + (err && err.message || err));
        saveBtn.disabled = false; cancelBtn.disabled = false;
      });
    });
    foot.appendChild(cancelBtn); foot.appendChild(saveBtn);
    card.appendChild(foot);

    bg.appendChild(card);
    document.body.appendChild(bg);

    function teardown(){
      try { document.body.removeChild(bg); } catch (_) {}
    }
    close.addEventListener('click', teardown);
    bg.addEventListener('click', function(e){ if (e.target === bg) teardown(); });
  }

  function escapeHtml(s){
    return String(s || '').replace(/[&<>"']/g, function(c){
      return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
    });
  }

  function openPluginSettingsModal(p){
    var bg = document.createElement('div');
    bg.style.cssText =
      'position:fixed;inset:0;background:rgba(0,0,0,0.7);z-index:1000;' +
      'display:flex;align-items:center;justify-content:center;';
    var card = document.createElement('div');
    card.style.cssText =
      'background:var(--bg);border:1px solid var(--line);border-radius:14px;' +
      'width:min(700px,94vw);height:min(85vh,820px);' +
      'display:flex;flex-direction:column;overflow:hidden;';
    var head = document.createElement('div');
    head.style.cssText =
      'padding:12px 16px;border-bottom:1px solid var(--line);' +
      'display:flex;align-items:center;gap:12px;';
    var title = document.createElement('div');
    title.style.cssText = 'flex:1;font-weight:600;font-size:14px;';
    title.textContent = 'Configure ' + p.name;
    var close = document.createElement('button');
    close.textContent = '✕';
    close.style.cssText =
      'background:transparent;color:var(--text);border:1px solid var(--line);' +
      'border-radius:8px;padding:4px 10px;cursor:pointer;font-size:14px;';
    head.appendChild(title); head.appendChild(close);
    var iframe = document.createElement('iframe');
    iframe.src = '/api/plugins/' + encodeURIComponent(p.id) + '/settings.html';
    iframe.style.cssText = 'flex:1;border:0;background:#0d0d12;';
    // Sandbox without `allow-same-origin`: an XSS in plugin settings.html
    // (e.g. raw {{token}} interpolation) executes at opaque origin and
    // can't read the parent's `iax_edit` cookie or scrape state from
    // `window.parent`. The shim authenticates its bridge/fetch calls via
    // a plugin-scoped Bearer token instead of the parent's cookie.
    iframe.setAttribute('sandbox',
      'allow-scripts allow-popups allow-forms ' +
      'allow-clipboard-read allow-clipboard-write');
    card.appendChild(head);
    card.appendChild(iframe);
    bg.appendChild(card);
    document.body.appendChild(bg);

    function teardown(){
      try { window.removeEventListener('message', onMsg); } catch (_) {}
      try { document.body.removeChild(bg); } catch (_) {}
      // Re-render to reflect any state changes the user may have made.
      renderPluginsTab();
    }
    function onMsg(e){
      if (!e.data || e.data.type !== 'iappyx-plugin-close') return;
      if (e.data.pluginId && e.data.pluginId !== p.id) return;
      teardown();
    }
    close.addEventListener('click', teardown);
    bg.addEventListener('click', function(e){ if (e.target === bg) teardown(); });
    window.addEventListener('message', onMsg);
  }
  /* PLUGINS: END */

  var showcaseKind = 'widgets';
  var showcaseCache = null;
  function renderShowcaseTab(){
    var list = document.getElementById('showcase-list');
    if (!showcaseCache) {
      list.innerHTML = '<div class="empty-state">Loading showcase…</div>';
      api('GET', '/api/showcase').then(function(r){
        showcaseCache = r;
        renderShowcaseTab();
      }).catch(function(err){
        list.innerHTML = '<div class="empty-state">Failed to reach showcase: ' + err.message + '</div>';
      });
      return;
    }
    Array.prototype.forEach.call(document.querySelectorAll('#showcase-tabs button'), function(b){
      if (b.dataset.kind) b.classList.toggle('active', b.dataset.kind === showcaseKind);
    });
    var entries = showcaseCache[showcaseKind] || [];
    var qEl = document.getElementById('showcase-q');
    var q = (qEl && qEl.value || '').toLowerCase();
    var visible = entries.filter(function(e){
      if (!q) return true;
      return ((e.title||'').toLowerCase().indexOf(q) >= 0) ||
             ((e.description||'').toLowerCase().indexOf(q) >= 0) ||
             ((e.author||'').toLowerCase().indexOf(q) >= 0);
    });
    list.innerHTML = '';
    if (!visible.length) {
      list.innerHTML = '<div class="empty-state">No entries.</div>';
      return;
    }
    visible.forEach(function(e){
      var row = document.createElement('div'); row.className = 'row-item';
      var main = document.createElement('div'); main.className = 'row-main';
      var title = document.createElement('div'); title.className = 'row-title';
      title.textContent = e.title + (e.installed ? ' ✓' : '');
      var sub = document.createElement('div'); sub.className = 'row-sub';
      sub.textContent = (e.description || '') + (e.author ? ' — ' + e.author : '');
      main.appendChild(title); main.appendChild(sub);
      row.appendChild(main);
      var btn = document.createElement('button');
      btn.textContent = e.installed ? 'Reinstall' : 'Install';
      btn.className = e.installed ? '' : 'primary';
      btn.onclick = function(){
        btn.disabled = true; btn.textContent = 'Installing…';
        // Convert "widgets" → "widget" for the install path. The
        // server accepts either form but per-kind singular reads
        // better in the URL.
        var kindSlug = ({widgets:'widget', wallpapers:'wallpaper',
                         transitions:'transition', iconFilters:'icon_filter',
                         plugins:'plugin'})[showcaseKind] || showcaseKind;
        api('POST', '/api/showcase/install/' + kindSlug + '/' + encodeURIComponent(e.slug))
          .then(function(){
            btn.textContent = 'Installed ✓'; btn.disabled = false;
            // Mark in cache so re-render shows the badge.
            e.installed = true;
          })
          .catch(function(err){
            btn.textContent = e.installed ? 'Reinstall' : 'Install';
            btn.disabled = false; toastError(err);
          });
      };
      row.appendChild(btn);
      list.appendChild(row);
    });
  }
  Array.prototype.forEach.call(document.querySelectorAll('#showcase-tabs button[data-kind]'), function(b){
    b.addEventListener('click', function(){ showcaseKind = b.dataset.kind; renderShowcaseTab(); });
  });
  (function(){ var i = document.getElementById('showcase-q');
    if (i) i.addEventListener('input', renderShowcaseTab); })();
  document.getElementById('showcase-reload').addEventListener('click', function(){
    api('POST', '/api/showcase/reload').then(function(){
      showcaseCache = null; renderShowcaseTab();
    }).catch(toastError);
  });

  // ── Boot ───────────────────────────────────────────────────
  bootstrap();
})();
