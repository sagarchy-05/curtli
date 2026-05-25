/* ============================================================
   curtli – landing page logic
   Vanilla JS, no build step. Talks to /api/shorten (always as
   an array) and /api/links/{id}/stats. Persists "Your links" in
   localStorage; clicking a saved short link opens a stats modal.
   ============================================================ */

(() => {
  'use strict';

  // ----- Constants -----------------------------------------------
  const MAX_ROWS      = 20;
  const HISTORY_KEY   = 'curtli.history.v1';
  const HISTORY_LIMIT = 200;
  const TOAST_MS      = 1800;

  // ----- DOM -----------------------------------------------------
  const $form          = document.getElementById('shorten-form');
  const $rows          = document.getElementById('rows');
  const $addRow        = document.getElementById('add-row');
  const $rowCounter    = document.getElementById('row-counter');
  const $submitBtn     = document.getElementById('submit-btn');
  const $banner        = document.getElementById('banner');
  const $history       = document.getElementById('history');
  const $historyEmpty  = document.getElementById('history-empty');
  const $toast         = document.getElementById('toast');

  // Modal
  const $modal        = document.getElementById('stats-modal');
  const $modalCode    = $modal.querySelector('[data-modal-code]');
  const $modalLong    = $modal.querySelector('[data-modal-long]');
  const $modalTotal   = $modal.querySelector('[data-modal-total]');
  const $modalActive  = $modal.querySelector('[data-modal-active]');
  const $modalChart   = $modal.querySelector('[data-modal-chart]');
  const $modalEmpty   = $modal.querySelector('[data-modal-empty]');
  const $modalLoading = $modal.querySelector('[data-modal-loading]');
  const $modalError   = $modal.querySelector('[data-modal-error]');

  const tplRow      = document.getElementById('tpl-row');
  const tplHistory  = document.getElementById('tpl-history-item');
  const tplChartRow = document.getElementById('tpl-chart-row');

  // ----- Init ----------------------------------------------------
  addRow();
  renderHistory();

  $form.addEventListener('submit', onSubmit);
  $addRow.addEventListener('click', () => addRow());

  // Modal close handlers (X button + backdrop click + ESC)
  $modal.querySelectorAll('[data-modal-close]').forEach(el =>
      el.addEventListener('click', closeModal)
  );
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && !$modal.classList.contains('hidden')) closeModal();
  });

  // ----- Row management -----------------------------------------

  function addRow() {
    const count = $rows.children.length;
    if (count >= MAX_ROWS) return;

    const node = tplRow.content.firstElementChild.cloneNode(true);
    $rows.appendChild(node);

    // Wire the trash icon to remove this row.
    node.querySelector('.row-remove')
        .addEventListener('click', () => removeRow(node));

    // Permanent checkbox disables (and clears) the days input.
    const $check = node.querySelector('.check-input');
    const $days  = node.querySelector('.input-days');
    $check.addEventListener('change', () => {
      $days.disabled = $check.checked;
      if ($check.checked) {
        $days.value = '';
        $days.classList.remove('invalid');
      }
    });

    // Focus the URL input of the just-added row (skip the first auto-add)
    if (count > 0) node.querySelector('.input-url').focus();

    updateRowChrome();
  }

  function removeRow(node) {
    // Always keep at least one row in the form.
    if ($rows.children.length <= 1) return;

    node.classList.add('removing');
    node.addEventListener('animationend', () => {
      node.remove();
      updateRowChrome();
    }, { once: true });
  }

  function updateRowChrome() {
    const rows = $rows.querySelectorAll('.row');
    const count = rows.length;

    // The single remaining row's trash icon is hidden (CSS) by disabling it.
    rows.forEach(r => {
      r.querySelector('.row-remove').disabled = count <= 1;
    });

    $rowCounter.textContent = `${count} / ${MAX_ROWS}`;
    $addRow.disabled = count >= MAX_ROWS;
  }

  // ----- Validation ---------------------------------------------

  function readRow(rowEl) {
    const longUrl     = rowEl.querySelector('.input-url').value.trim();
    const customAlias = rowEl.querySelector('.input-alias').value.trim();
    const isPermanent = rowEl.querySelector('.check-input').checked;
    const daysVal     = rowEl.querySelector('.input-days').value.trim();

    let expiresInDays = null;
    if (!isPermanent && daysVal !== '') {
      expiresInDays = Number(daysVal);  // could be NaN if input is garbage
    }

    return {
      rowEl,
      longUrl,
      customAlias: customAlias || null,
      expiresInDays,
    };
  }

  function validateRow(row) {
    clearRowError(row.rowEl);
    if (!row.longUrl) {
      return { msg: 'Please enter a URL.', target: 'url' };
    }
    if (!/^https?:\/\//i.test(row.longUrl)) {
      return { msg: 'URL must start with http:// or https://', target: 'url' };
    }
    if (row.longUrl.length > 2048) {
      return { msg: 'URL is too long (max 2048 chars).', target: 'url' };
    }
    if (row.customAlias && !/^[a-zA-Z0-9_-]{3,16}$/.test(row.customAlias)) {
      return { msg: 'Alias must be 3–16 chars (letters, digits, _ or -).', target: 'alias' };
    }
    if (row.expiresInDays !== null) {
      const n = row.expiresInDays;
      if (!Number.isFinite(n) || !Number.isInteger(n)) {
        return { msg: 'Expiry must be a whole number of days.', target: 'days' };
      }
      if (n < 1) {
        return { msg: 'Expiry must be at least 1 day.', target: 'days' };
      }
      if (n > 3650) {
        return { msg: "Expiry can't exceed 3650 days (10 years).", target: 'days' };
      }
    }
    return null;
  }

  function setRowError(rowEl, msg, target = 'url') {
    const $err = rowEl.querySelector('[data-error]');
    $err.textContent = msg;
    $err.hidden = false;
    let $field;
    switch (target) {
      case 'alias': $field = rowEl.querySelector('.input-alias'); break;
      case 'days':  $field = rowEl.querySelector('.input-days');  break;
      default:      $field = rowEl.querySelector('.input-url');
    }
    if ($field) $field.classList.add('invalid');
  }

  function clearRowError(rowEl) {
    const $err = rowEl.querySelector('[data-error]');
    $err.textContent = '';
    $err.hidden = true;
    rowEl.querySelector('.input-url').classList.remove('invalid');
    rowEl.querySelector('.input-alias').classList.remove('invalid');
    rowEl.querySelector('.input-days').classList.remove('invalid');
  }

  // ----- Submission ---------------------------------------------

  async function onSubmit(event) {
    event.preventDefault();
    hideBanner();

    const rows = Array.from($rows.querySelectorAll('.row')).map(readRow);
    const nonEmpty = rows.filter(r => r.longUrl || r.customAlias);

    if (nonEmpty.length === 0) {
      showBanner('Add at least one URL before shortening.');
      return;
    }

    let hasError = false;
    for (const row of nonEmpty) {
      const err = validateRow(row);
      if (err) {
        setRowError(row.rowEl, err.msg, err.target);
        hasError = true;
      }
    }
    if (hasError) return;

    setLoading(true);

    try {
      const payload = nonEmpty.map(r => ({
        longUrl: r.longUrl,
        customAlias: r.customAlias,
        expiresInDays: r.expiresInDays,
      }));

      // Always-array endpoint – single or bulk are the same call now.
      const { successful, failed } = await shortenAll(payload);

      // Persist successes to history (now including id for stats lookup)
      successful.forEach(s => addToHistory({
        id:        s.id,
        shortCode: s.shortCode,
        shortUrl:  s.shortUrl,
        longUrl:   s.longUrl,
        createdAt: Date.now(),
      }));
      renderHistory();

      // Reset form: remove all rows but the first, clear inputs
      Array.from($rows.querySelectorAll('.row')).forEach((r, i) => {
        if (i === 0) {
          r.querySelector('.input-url').value = '';
          r.querySelector('.input-alias').value = '';
          const $days  = r.querySelector('.input-days');
          const $check = r.querySelector('.check-input');
          $days.value = '';
          $days.disabled = false;
          $check.checked = false;
          clearRowError(r);
        } else {
          r.remove();
        }
      });
      updateRowChrome();

      // Failures surface as a banner instead of a separate "Just shortened"
      // section. Successes show up silently in "Your links".
      if (failed.length > 0) {
        showFailuresBanner(failed, successful.length);
      }

    } catch (err) {
      handleSubmitError(err, nonEmpty);
    } finally {
      setLoading(false);
    }
  }

  // ----- API calls ----------------------------------------------

  async function shortenAll(bodyArray) {
    const res = await fetch('/api/shorten', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(bodyArray),
    });
    // 200 = at least one success; 400 = all failed (both have a JSON body)
    if (res.status === 200 || res.status === 400) {
      return await res.json();
    }
    throw await buildHttpError(res);
  }

  async function fetchStats(id) {
    const res = await fetch(`/api/links/${id}/stats`);
    if (!res.ok) throw await buildHttpError(res);
    return await res.json();
  }

  async function buildHttpError(res) {
    let body = null;
    try { body = await res.json(); } catch (_) {}
    const err = new Error(body?.message || body?.error || `HTTP ${res.status}`);
    err.status = res.status;
    err.retryAfter = parseInt(res.headers.get('Retry-After') || '0', 10);
    err.body = body;
    return err;
  }

  function handleSubmitError(err, rows) {
    if (!err.status) {
      showBanner('Could not reach the server. Check your connection and try again.');
      return;
    }
    if (err.status === 429) {
      const secs = err.retryAfter || 60;
      showBanner(`You're going a bit fast. Try again in ${secs} second${secs === 1 ? '' : 's'}.`);
      return;
    }
    if (err.status === 400 && rows.length === 1) {
      const msg = err.message || 'Invalid request.';
      let target = 'url';
      if (/alias/i.test(msg))                     target = 'alias';
      else if (/expires?|days|expiry/i.test(msg)) target = 'days';
      setRowError(rows[0].rowEl, msg, target);
      return;
    }
    showBanner(err.message || `Unexpected error (${err.status}).`);
  }

  // ----- Failures banner ----------------------------------------

  function showFailuresBanner(failed, successCount) {
    const total = failed.length + successCount;
    const intro = successCount > 0
        ? `${failed.length} of ${total} link${total === 1 ? '' : 's'} couldn't be shortened`
        : `Couldn't shorten ${failed.length} link${failed.length === 1 ? '' : 's'}`;

    // Cap to 3 distinct error messages to keep the banner short.
    const seen = new Set();
    const messages = [];
    for (const f of failed) {
      const m = f.errorMessage || 'Unknown error';
      if (!seen.has(m)) { seen.add(m); messages.push(m); }
      if (messages.length === 3) break;
    }
    const tail = (failed.length > messages.length) ? ` (+${failed.length - messages.length} more)` : '';
    showBanner(`${intro}: ${messages.join('; ')}${tail}`);
  }

  // ----- History (localStorage) ---------------------------------

  function loadHistory() {
    try {
      const raw = localStorage.getItem(HISTORY_KEY);
      const list = raw ? JSON.parse(raw) : [];
      return Array.isArray(list) ? list : [];
    } catch (_) {
      return [];
    }
  }

  function saveHistory(list) {
    try {
      localStorage.setItem(HISTORY_KEY, JSON.stringify(list));
    } catch (_) {
      // Storage full / disabled – silently skip
    }
  }

  function addToHistory(item) {
    const list = loadHistory();
    // Dedupe by shortCode – newest wins
    const filtered = list.filter(x => x.shortCode !== item.shortCode);
    filtered.unshift(item);
    saveHistory(filtered.slice(0, HISTORY_LIMIT));
  }

  function renderHistory() {
    const list = loadHistory();
    $history.innerHTML = '';

    if (list.length === 0) {
      $historyEmpty.classList.remove('hidden');
      return;
    }

    $historyEmpty.classList.add('hidden');

    list.forEach((item, i) => {
      const node = tplHistory.content.firstElementChild.cloneNode(true);
      const $short = node.querySelector('[data-short]');
      const $long  = node.querySelector('[data-long]');
      const $meta  = node.querySelector('[data-meta]');
      const $copy  = node.querySelector('[data-copy]');

      $short.textContent = stripProtocol(item.shortUrl);
      $long.textContent  = item.longUrl;
      $meta.textContent  = formatRelative(item.createdAt);

      // Clicking the short URL opens the stats modal (modal-only behavior).
      // To actually visit the link, the user copies and pastes.
      $short.addEventListener('click', () => openStatsModal(item));
      $copy.addEventListener('click',  () => copyToClipboard(item.shortUrl, $copy));

      node.style.animationDelay = `${Math.min(i, 10) * 35}ms`;
      $history.appendChild(node);
    });
  }

  // ----- Stats modal --------------------------------------------

  async function openStatsModal(item) {
    if (!item.id) {
      // Legacy history entry created before the id field existed.
      showToast('Stats unavailable for older links');
      return;
    }

    // Reset modal state – title shows the short *link* (host + code),
    // subtitle shows the long URL it points to.
    $modalCode.textContent = stripProtocol(item.shortUrl);
    $modalLong.textContent = item.longUrl;
    $modalTotal.textContent = '…';
    $modalActive.textContent = '…';
    $modalChart.innerHTML = '';
    $modalEmpty.hidden = true;
    $modalError.hidden = true;
    $modalLoading.hidden = false;

    showModal();

    try {
      const stats = await fetchStats(item.id);
      renderStats(stats);
    } catch (err) {
      $modalLoading.hidden = true;
      $modalError.hidden = false;
      $modalError.textContent = err.status === 404
          ? 'This link no longer exists.'
          : (err.message || 'Could not load stats.');
    }
  }

  function renderStats(stats) {
    $modalLoading.hidden = true;
    $modalTotal.textContent = formatNumber(stats.totalClicks);

    // Only hours with clicks
    const hourly = (stats.hourly ?? [])
        .filter(h => Number(h.clicks) > 0)
        .sort((a, b) => new Date(a.hour) - new Date(b.hour));

    $modalActive.textContent = String(hourly.length);

    $modalChart.innerHTML = '';

    if (hourly.length === 0) {
      $modalEmpty.hidden = false;
      return;
    }

    $modalEmpty.hidden = true;

    const max = Math.max(...hourly.map(h => h.clicks), 1);

    for (const h of hourly) {
      const row = tplChartRow.content.firstElementChild.cloneNode(true);

      row.querySelector('[data-time]').textContent =
          formatHour(new Date(h.hour));

      row.querySelector('[data-count]').textContent =
          formatNumber(h.clicks);

      const $bar = row.querySelector('[data-bar]');

      requestAnimationFrame(() => {
        $bar.style.width =
            ((h.clicks / max) * 100).toFixed(1) + '%';
      });

      $modalChart.appendChild(row);
    }
  }

  function showModal() {
    $modal.classList.remove('hidden');
    // Preserve scrollbar width so the page doesn't shift when we lock scroll
    const sbw = window.innerWidth - document.documentElement.clientWidth;
    if (sbw > 0) document.documentElement.style.setProperty('--scrollbar-w', sbw + 'px');
    document.body.classList.add('modal-open');
    requestAnimationFrame(() => $modal.classList.add('show'));
  }

  function closeModal() {
    $modal.classList.remove('show');
    document.body.classList.remove('modal-open');
    document.documentElement.style.removeProperty('--scrollbar-w');
    setTimeout(() => $modal.classList.add('hidden'), 300);
  }

  // ----- UI helpers ---------------------------------------------

  function setLoading(loading) {
    if (loading) {
      $submitBtn.classList.add('loading');
      $submitBtn.disabled = true;
      $submitBtn.querySelector('.btn-label').textContent = 'Shortening…';
    } else {
      $submitBtn.classList.remove('loading');
      $submitBtn.disabled = false;
      $submitBtn.querySelector('.btn-label').textContent = 'Shorten';
    }
  }

  function showBanner(msg) {
    $banner.textContent = msg;
    $banner.classList.remove('hidden');
  }

  function hideBanner() {
    $banner.classList.add('hidden');
  }

  let toastTimer;
  function showToast(msg) {
    $toast.textContent = msg;
    $toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => $toast.classList.remove('show'), TOAST_MS);
  }

  async function copyToClipboard(text, btnEl) {
    try {
      await navigator.clipboard.writeText(text);
      btnEl.classList.add('copied');
      const $svg = btnEl.querySelector('svg');
      const originalHTML = $svg.innerHTML;
      $svg.innerHTML = '<path d="M20 6L9 17l-5-5" />';
      showToast('Copied');
      setTimeout(() => {
        $svg.innerHTML = originalHTML;
        btnEl.classList.remove('copied');
      }, 1200);
    } catch (_) {
      // Fallback for non-secure contexts
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand('copy'); showToast('Copied'); } catch (_) {}
      ta.remove();
    }
  }

  function stripProtocol(url) {
    return url.replace(/^https?:\/\//i, '');
  }

  function formatRelative(ts) {
    const diff = Date.now() - ts;
    const sec = Math.floor(diff / 1000);
    if (sec < 60) return 'just now';
    const min = Math.floor(sec / 60);
    if (min < 60) return `${min} min ago`;
    const hr = Math.floor(min / 60);
    if (hr < 24)  return `${hr} hour${hr === 1 ? '' : 's'} ago`;
    const day = Math.floor(hr / 24);
    if (day < 30) return `${day} day${day === 1 ? '' : 's'} ago`;
    return new Date(ts).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
  }

  /**
   * Formats the time bucket strictly to zero-padded 12-hour format
   * outputting a consistent string length (e.g. "01:30 AM – 02:30 AM").
   */
  function formatHour(date) {
    const fmt = (d) => {
      let hours = d.getHours();
      const minutes = d.getMinutes().toString().padStart(2, '0');
      const ampm = hours >= 12 ? 'PM' : 'AM';

      hours = hours % 12 || 12; // 0 becomes 12
      const paddedHours = String(hours).padStart(2, '0');

      // Always outputting minutes ensures the string length is identical
      return `${paddedHours}:${minutes} ${ampm}`;
    };

    const endDate = new Date(date.getTime() + 3600000);
    return `${fmt(date)} – ${fmt(endDate)}`;
  }

  function formatNumber(n) {
    return Number(n).toLocaleString();
  }

})();