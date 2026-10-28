'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const storageKey = 'tape.tracked-orders.v1';
  let side = 'BUY';
  let refreshing = false;
  let submitting = false;
  let ids = [];
  const cache = new Map();
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey) || '[]');
    if (Array.isArray(saved)) ids = saved.filter(validId).slice(0, 12);
  } catch { /* A blocked or unavailable browser store should not prevent trading. */ }
  function validId(value) { return typeof value === 'string' && /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(value); }
  function money(ticks) {
    if (ticks === null || ticks === undefined) return '—';
    const value = BigInt(ticks);
    return '$' + (value / 100n).toLocaleString('en-US') + '.' + (value % 100n).toString().padStart(2, '0');
  }
  function priceTicks(text) {
    if (!/^\d+(?:\.\d{1,2})?$/.test(text.trim())) throw new Error('Enter a positive price with at most two decimals.');
    const [whole, cents = ''] = text.trim().split('.');
    const value = BigInt(whole) * 100n + BigInt(cents.padEnd(2, '0'));
    if (value < 1n || value > 100000000n) throw new Error('Price must be between $0.01 and $1,000,000.00.');
    return Number(value);
  }
  async function api(path, options = {}) {
    const response = await fetch(path, { ...options, signal: AbortSignal.timeout(7000), headers: { 'Content-Type': 'application/json', ...(options.headers || {}) } });
    const body = await response.json().catch(() => null);
    if (!response.ok) throw new Error(body?.message || `Request failed (HTTP ${response.status}).`);
    return body;
  }
  function cell(row, value, className) {
    const node = row.insertCell();
    node.textContent = String(value);
    if (className) node.className = className;
    return node;
  }
  function empty(tbody, columns, message) {
    tbody.replaceChildren();
    const row = tbody.insertRow();
    const node = cell(row, message, 'empty');
    node.colSpan = columns;
  }
  function levels(name, data) {
    const body = $(name);
    if (!data.length) { empty(body, 3, `No resting ${name === 'bids' ? 'buy' : 'sell'} orders.`); return; }
    body.replaceChildren();
    const max = Math.max(...data.map(level => level.quantity));
    for (const level of data) {
      const row = body.insertRow();
      row.style.setProperty('--depth', (level.quantity / max * 100).toFixed(2) + '%');
      cell(row, money(level.priceTicks));
      cell(row, level.quantity.toLocaleString('en-US'));
      cell(row, level.orderCount);
    }
  }
  function renderBook(book, metrics) {
    levels('bids', book.bids); levels('asks', book.asks);
    $('best-bid').textContent = money(book.bids[0]?.priceTicks);
    $('best-ask').textContent = money(book.asks[0]?.priceTicks);
    $('spread').textContent = money(book.spreadTicks);
    $('resting').textContent = metrics.restingOrders.toLocaleString('en-US');
    $('accepted').textContent = metrics.acceptedOrders.toLocaleString('en-US');
    $('executions').textContent = metrics.executedTrades.toLocaleString('en-US');
    $('levels').textContent = `${metrics.bidLevels} bid / ${metrics.askLevels} ask`;
  }
  function renderTrades(trades) {
    const body = $('trades');
    if (!trades.length) { empty(body, 4, 'No executions yet. Cross a resting order to make the first trade.'); return; }
    body.replaceChildren();
    for (const trade of trades) {
      const row = body.insertRow();
      cell(row, new Date(trade.timestamp).toLocaleTimeString('en-US', { hour12: false }));
      cell(row, money(trade.priceTicks)); cell(row, trade.quantity);
      const node = cell(row, `${trade.makerOrderId.slice(0, 8)} / ${trade.takerOrderId.slice(0, 8)}`);
      node.title = `Maker ${trade.makerOrderId} / Taker ${trade.takerOrderId}`;
    }
  }
  function remember(order) {
    cache.set(order.id, order);
    ids = [order.id, ...ids.filter(id => id !== order.id)].slice(0, 12);
    try { localStorage.setItem(storageKey, JSON.stringify(ids)); } catch { /* Optional convenience only. */ }
  }
  function renderOrders() {
    const body = $('orders');
    if (!ids.length) { empty(body, 4, 'Submit an order to follow its status here.'); return; }
    body.replaceChildren();
    for (const id of ids) {
      const order = cache.get(id);
      const row = body.insertRow();
      const label = cell(row, id.slice(0, 8)); label.title = id;
      if (!order) { cell(row, '—'); cell(row, 'Unavailable', 'status'); cell(row, '—'); continue; }
      const detail = document.createElement('span');
      detail.className = 'order-side ' + (order.side === 'BUY' ? 'buy-text' : 'sell-text');
      detail.textContent = `${order.side} · ${money(order.priceTicks)}`; label.append(detail);
      const remaining = cell(row, `${order.remainingQuantity} / ${order.originalQuantity}`);
      remaining.title = `Filled ${order.cumulativeFilledQuantity}; remaining ${order.remainingQuantity}. Cancelled remainder is not executable.`;
      cell(row, order.status.replaceAll('_', ' '), 'status ' + order.status);
      const action = row.insertCell();
      if (order.status === 'OPEN' || order.status === 'PARTIALLY_FILLED') {
        const button = document.createElement('button'); button.type = 'button'; button.className = 'cancel-button';
        button.textContent = 'Cancel'; button.setAttribute('aria-label', `Cancel order ${id}`);
        button.addEventListener('click', () => cancelOrder(id, button)); action.append(button);
      } else action.textContent = '—';
    }
  }
  async function refreshOrders() {
    const selectedIds = [...ids];
    const results = await Promise.allSettled(selectedIds.map(id => api('/api/orders/' + id)));
    results.forEach((result, index) => { if (result.status === 'fulfilled') cache.set(selectedIds[index], result.value); else cache.delete(selectedIds[index]); });
    renderOrders();
  }
  async function refresh() {
    if (refreshing) return;
    refreshing = true;
    try {
      const [book, metrics, trades] = await Promise.all([api('/api/book?depth=10'), api('/api/metrics'), api('/api/trades?limit=12')]);
      renderBook(book, metrics); renderTrades(trades); await refreshOrders();
      $('global-error').hidden = true;
      $('connection').textContent = 'Connected · refreshed ' + new Date().toLocaleTimeString('en-US', { hour12: false });
      $('connection').className = 'connection connected';
    } catch (error) {
      $('global-error').textContent = `Snapshot unavailable. ${error.message} Start the local engine with docker compose up --build.`;
      $('global-error').hidden = false;
      $('connection').textContent = 'Disconnected · displayed values may be stale';
      $('connection').className = 'connection disconnected';
      for (const name of ['bids', 'asks']) if ($(name).querySelector('.empty')) empty($(name), 3, 'Engine unavailable.');
      if ($('trades').querySelector('.empty')) empty($('trades'), 4, 'Trade history unavailable.');
    } finally { refreshing = false; }
  }
  function receipt(message, failed = false) {
    $('action-result').textContent = message;
    $('action-result').className = 'action-result ' + (failed ? 'failure' : 'success');
  }
  async function cancelOrder(id, button) {
    button.disabled = true;
    try { const result = await api('/api/orders/' + id, { method: 'DELETE' }); remember(result.order); renderOrders(); receipt(`Cancelled ${id}. Unexecuted quantity: ${result.order.remainingQuantity}.`); await refresh(); }
    catch (error) { receipt(error.message, true); }
    finally { button.disabled = false; }
  }
  for (const button of document.querySelectorAll('[data-side]')) button.addEventListener('click', () => {
    side = button.dataset.side;
    for (const item of document.querySelectorAll('[data-side]')) { item.classList.toggle('selected', item === button); item.setAttribute('aria-pressed', String(item === button)); }
    $('submit-order').className = 'submit ' + side.toLowerCase();
    $('submit-order').replaceChildren(document.createTextNode(`Submit ${side.toLowerCase()} order `));
    const arrow = document.createElement('span'); arrow.textContent = '↗'; arrow.setAttribute('aria-hidden', 'true'); $('submit-order').append(arrow);
  });
  $('order-form').addEventListener('submit', async event => {
    event.preventDefault(); if (submitting) return;
    submitting = true; $('submit-order').disabled = true;
    try {
      const quantity = Number($('quantity').value);
      if (!Number.isInteger(quantity) || quantity < 1 || quantity > 1000000) throw new Error('Quantity must be an integer between 1 and 1,000,000.');
      const result = await api('/api/orders', { method: 'POST', body: JSON.stringify({ side, priceTicks: priceTicks($('price').value), quantity }) });
      remember(result.order); renderOrders();
      const fills = result.executions.map(t => `${t.quantity} at ${money(t.priceTicks)}`).join('; ');
      receipt(`${result.order.id} · ${result.order.status.replaceAll('_', ' ')} · ${result.order.remainingQuantity} remaining.` + (fills ? ' Executions: ' + fills + '.' : ' Waiting on the book.'));
      await refresh();
    } catch (error) { receipt(error.message, true); }
    finally { submitting = false; $('submit-order').disabled = false; }
  });
  $('lookup-form').addEventListener('submit', async event => {
    event.preventDefault();
    const id = $('lookup-id').value.trim();
    if (!validId(id)) { receipt('Enter a complete order UUID.', true); return; }
    try { const order = await api('/api/orders/' + id); remember(order); renderOrders(); $('lookup-id').value = ''; receipt('Tracking ' + order.id + '.'); }
    catch (error) { receipt(error.message, true); }
  });
  renderOrders(); refresh();
  window.setInterval(() => { if (!document.hidden) refresh(); }, 2000);
})();
