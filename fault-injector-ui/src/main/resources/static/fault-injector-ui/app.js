/*
 * Bundled UI for the Spring Boot Fault Injector starter.
 *
 * Runs against the REST API exposed by FaultInjectorUiController. The page is
 * served from the same prefix as the API, so all fetch URLs are computed
 * relative to the page itself (`./api/...`) — no hard-coded host or path.
 */

const API_BASE = './api';

const state = {
    pollMs: 2000,
    config: null,
    metrics: null,
    timeseries: null,
    events: null,
    chart: null,
    pollTimer: null,
    /** suspended while the user is editing in the rule modal */
    pollSuspended: false,
};

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

async function api(method, path, body) {
    const opts = { method, headers: {} };
    if (body !== undefined) {
        opts.headers['content-type'] = 'application/json';
        opts.body = JSON.stringify(body);
    }
    const res = await fetch(`${API_BASE}${path}`, opts);
    if (!res.ok) {
        let detail = `${res.status} ${res.statusText}`;
        try {
            const j = await res.json();
            if (j && (j.message || j.error)) detail = j.message || j.error;
        } catch (_) { /* fall through */ }
        throw new Error(detail);
    }
    if (res.status === 204) return null;
    const ct = res.headers.get('content-type') || '';
    if (ct.includes('application/json')) return res.json();
    return res.text();
}

// ---------------------------------------------------------------------------
// DOM helpers
// ---------------------------------------------------------------------------

const $  = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

function el(tag, attrs = {}, children = []) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
        if (v == null || v === false) continue;
        if (k === 'className') node.className = v;
        else if (k === 'dataset') Object.assign(node.dataset, v);
        else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2), v);
        else if (v === true) node.setAttribute(k, '');
        else node.setAttribute(k, v);
    }
    for (const c of [].concat(children)) {
        if (c == null) continue;
        node.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
    }
    return node;
}

function fmtNumber(n) {
    if (n == null) return '—';
    if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(1) + 'k';
    return String(n);
}

function fmtTime(ms) {
    if (!ms) return '—';
    const d = new Date(ms);
    return d.toLocaleTimeString(undefined, { hour12: false }) + '.' + String(d.getMilliseconds()).padStart(3, '0');
}

function fmtPct(num, denom) {
    if (!denom) return '—';
    return ((num / denom) * 100).toFixed(1) + '%';
}

function toast(message, kind = 'ok', timeoutMs = 3000) {
    const host = $('#toast-host');
    const t = el('div', { className: `toast ${kind}` }, message);
    host.appendChild(t);
    setTimeout(() => {
        t.style.transition = 'opacity 150ms ease, transform 150ms ease';
        t.style.opacity = '0';
        t.style.transform = 'translateY(0.25rem)';
        setTimeout(() => t.remove(), 200);
    }, timeoutMs);
}

// ---------------------------------------------------------------------------
// Tab switching
// ---------------------------------------------------------------------------

function selectTab(name) {
    $$('.tab-btn').forEach(b => b.setAttribute('aria-selected', b.dataset.tab === name));
    $$('.tab-panel').forEach(p => p.classList.toggle('hidden', p.id !== `tab-${name}`));
    if (name === 'reports') ensureChart();
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

function renderConfig(cfg) {
    state.config = cfg;
    $('#kpi-rules-total').textContent = String(cfg.rules.length);
    $('#kpi-rules-active').textContent = String(cfg.rules.filter(r => r.enabled).length);
    $('#kpi-mount-path').textContent = cfg.ui?.path || '/fault-injector';
    $('#event-cap').textContent = cfg.ui?.eventBufferSize ?? '?';

    const pill = $('#global-status-pill');
    const btn  = $('#global-toggle');
    if (cfg.enabled) {
        pill.textContent = 'Global: on';
        pill.classList.remove('status-off');
        pill.classList.add('status-on');
        btn.textContent = 'Disable';
    } else {
        pill.textContent = 'Global: off';
        pill.classList.remove('status-on');
        pill.classList.add('status-off');
        btn.textContent = 'Enable';
    }

    renderDefaults(cfg.defaults);
    renderRules(cfg.rules);
}

function renderDefaults(defaults) {
    const grid = $('#defaults-grid');
    grid.innerHTML = '';
    const fields = [
        ['Delay (ms)', defaults.delayMs],
        ['Error status', defaults.errorStatus],
        ['Error message', defaults.errorMessage],
        ['Mode', defaults.mode],
        ['Probability', defaults.probability],
        ['Every N', defaults.everyN],
    ];
    for (const [label, value] of fields) {
        grid.appendChild(el('div', { className: 'kpi-card' }, [
            el('div', { className: 'kpi-label' }, label),
            el('div', { className: 'kpi-value text-base font-mono' }, String(value)),
        ]));
    }
}

function renderRules(rules) {
    const list = $('#rules-list');
    list.innerHTML = '';
    if (!rules.length) {
        list.appendChild(el('div', { className: 'p-6 text-center text-sm text-slate-500 dark:text-slate-400' },
            'No rules configured. Click "+ Add rule" to create one.'));
        return;
    }
    for (const rule of rules) {
        const pills = [];
        if (rule.fault) pills.push(el('span', { className: `rule-pill ${rule.fault.toLowerCase()}` }, rule.fault));
        if (rule.mode)  pills.push(el('span', { className: 'rule-pill mode' }, rule.mode));

        const meta = [];
        if (rule.hostPattern) meta.push(`host=${rule.hostPattern}`);
        if (rule.urlPattern)  meta.push(`url=${rule.urlPattern}`);
        if (rule.methods?.length) meta.push(`methods=${rule.methods.join(',')}`);
        if (rule.mode === 'PROBABILITY' && rule.probability != null) meta.push(`p=${rule.probability}`);
        if (rule.mode === 'EVERY_N' && rule.everyN) meta.push(`every=${rule.everyN}`);

        const row = el('div', {
            className: 'rule-row' + (rule.enabled ? '' : ' disabled'),
            onclick: () => openRuleModal(rule),
        }, [
            el('span', { className: rule.enabled ? 'status-pill status-on' : 'status-pill status-off' },
                rule.enabled ? 'on' : 'off'),
            el('div', { className: 'flex-1 min-w-0' }, [
                el('div', { className: 'rule-name' }, rule.name || '(unnamed)'),
                el('div', { className: 'rule-meta' }, meta.join('  ·  ') || 'matches any request'),
            ]),
            el('div', { className: 'flex items-center gap-1' }, pills),
            el('div', { className: 'text-right text-xs text-slate-500 dark:text-slate-400 hidden sm:block' }, [
                el('div', {}, `matches: ${rule.matchCount ?? 0}`),
                el('div', {}, `triggers: ${rule.triggerCount ?? 0}`),
            ]),
        ]);
        list.appendChild(row);
    }
}

function renderMetrics(m) {
    state.metrics = m;
    $('#kpi-matches').textContent = fmtNumber(m.totals.matchCount);
    $('#kpi-triggers').textContent = fmtNumber(m.totals.triggerCount);
    $('#kpi-trigger-rate').textContent = fmtPct(m.totals.triggerCount, m.totals.matchCount);

    const tbody = $('#metrics-tbody');
    tbody.innerHTML = '';
    for (const r of m.rules) {
        tbody.appendChild(el('tr', {}, [
            el('td', {}, [
                el('span', { className: r.enabled ? 'status-pill status-on' : 'status-pill status-off' },
                    r.enabled ? 'on' : 'off'),
                el('span', { className: 'ml-2 font-medium' }, r.name || '(unnamed)'),
            ]),
            el('td', { className: 'text-right tabular-nums' }, fmtNumber(r.matchCount)),
            el('td', { className: 'text-right tabular-nums' }, fmtNumber(r.triggerCount)),
            el('td', { className: 'text-right tabular-nums' }, fmtPct(r.triggerCount, r.matchCount)),
            el('td', { className: 'text-right' }, [
                el('button', {
                    className: 'text-xs px-2 py-1 rounded-md border border-slate-200 dark:border-slate-600 hover:bg-slate-100 dark:hover:bg-slate-700',
                    onclick: () => resetMetrics(r.name),
                }, 'Reset'),
            ]),
        ]));
    }
}

function renderEvents(payload) {
    state.events = payload;
    const tbody = $('#events-tbody');
    tbody.innerHTML = '';
    if (!payload.events?.length) {
        tbody.appendChild(el('tr', {}, [
            el('td', { colspan: '9', className: 'text-center text-slate-500 dark:text-slate-400 py-6' },
                'No decisions recorded yet. Drive some traffic through your HTTP clients.'),
        ]));
        return;
    }
    let lastFired = null;
    for (const e of payload.events) {
        if (e.outcome === 'FIRED' && (!lastFired || e.timestampMs > lastFired)) lastFired = e.timestampMs;
        tbody.appendChild(el('tr', {}, [
            el('td', { className: 'tabular-nums whitespace-nowrap' }, fmtTime(e.timestampMs)),
            el('td', {}, e.ruleName || '—'),
            el('td', {}, [el('span', { className: e.outcome === 'FIRED' ? 'status-pill status-fired' : 'status-pill status-off' }, e.outcome === 'FIRED' ? 'fired' : 'matched')]),
            el('td', { className: 'tabular-nums' }, e.method || '—'),
            el('td', { className: 'truncate max-w-[10rem]' }, e.host || '—'),
            el('td', { className: 'truncate max-w-[20rem] font-mono text-xs' }, e.url || '—'),
            el('td', {}, e.faultType || '—'),
            el('td', { className: 'text-right tabular-nums' }, e.delayMs ? `${e.delayMs} ms` : '—'),
            el('td', { className: 'text-right tabular-nums' }, e.errorStatus ? String(e.errorStatus) : '—'),
        ]));
    }
    $('#kpi-last-fired').textContent = lastFired ? fmtTime(lastFired) : 'never';
}

// ---------------------------------------------------------------------------
// Chart
// ---------------------------------------------------------------------------

function ensureChart() {
    if (state.chart) return state.chart;
    const ctx = $('#ts-chart').getContext('2d');
    state.chart = new Chart(ctx, {
        type: 'line',
        data: { labels: [], datasets: [
                { label: 'Matches',  data: [], borderColor: '#6366f1', backgroundColor: 'rgba(99,102,241,0.15)', tension: 0.25, fill: true },
                { label: 'Triggers', data: [], borderColor: '#f43f5e', backgroundColor: 'rgba(244,63,94,0.15)',  tension: 0.25, fill: true },
            ] },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false },
            scales: {
                y: { beginAtZero: true, ticks: { precision: 0 } },
            },
            plugins: { legend: { position: 'bottom' } },
        },
    });
    return state.chart;
}

function renderTimeSeries(ts) {
    state.timeseries = ts;
    const chart = ensureChart();
    chart.data.labels = ts.buckets.map(b => fmtTime(b.startEpochMs));
    chart.data.datasets[0].data = ts.buckets.map(b => b.matches);
    chart.data.datasets[1].data = ts.buckets.map(b => b.triggers);
    chart.update('none');
}

// ---------------------------------------------------------------------------
// Mutations
// ---------------------------------------------------------------------------

async function toggleGlobal() {
    try {
        const next = !state.config?.enabled;
        await api('POST', '/enabled', { enabled: next });
        await refreshAll();
        toast(`Fault injection ${next ? 'enabled' : 'disabled'}.`);
    } catch (e) { toast(e.message, 'error'); }
}

async function resetMetrics(name) {
    try {
        await api('POST', '/metrics/reset', name ? { name } : {});
        await refreshAll();
        toast(name ? `Reset metrics for "${name}".` : 'Reset all metrics.');
    } catch (e) { toast(e.message, 'error'); }
}

// ---------------------------------------------------------------------------
// Rule modal (add / edit)
// ---------------------------------------------------------------------------

let editingRuleName = null;

function openRuleModal(rule) {
    editingRuleName = rule?.name ?? null;
    state.pollSuspended = true;

    const form = $('#rule-form');
    form.reset();
    $('#rule-form-error').classList.add('hidden');
    $('#rule-modal-title').textContent = rule ? `Edit rule: ${rule.name}` : 'Add rule';

    // Disable name field when editing.
    form.elements.name.disabled = !!rule;
    if (rule) {
        form.elements.name.value         = rule.name ?? '';
        form.elements.fault.value        = rule.fault ?? 'DELAY';
        form.elements.mode.value         = rule.mode ?? '';
        form.elements.hostPattern.value  = rule.hostPattern ?? '';
        form.elements.urlPattern.value   = rule.urlPattern ?? '';
        form.elements.methods.value      = (rule.methods || []).join(', ');
        form.elements.probability.value  = rule.probability ?? 0;
        form.elements.everyN.value       = rule.everyN ?? '';
        form.elements.delayMs.value      = rule.delayMs ?? '';
        form.elements.errorStatus.value  = rule.errorStatus ?? '';
        form.elements.errorMessage.value = rule.errorMessage ?? '';
    } else {
        form.elements.fault.value       = 'DELAY';
        form.elements.probability.value = 0;
    }
    syncProbabilityDisplay();
    $('#rule-form-delete').classList.toggle('hidden', !rule);
    $('#rule-modal').classList.remove('hidden');
}

function closeRuleModal() {
    $('#rule-modal').classList.add('hidden');
    state.pollSuspended = false;
    editingRuleName = null;
}

function syncProbabilityDisplay() {
    const v = parseFloat($('#rule-form').elements.probability.value || '0');
    $('#prob-display').textContent = v.toFixed(2);
}

function readRuleForm() {
    const f = $('#rule-form').elements;
    const dto = {
        name:        f.name.value.trim() || null,
        fault:       f.fault.value || null,
        mode:        f.mode.value || null,
        hostPattern: f.hostPattern.value.trim() || null,
        urlPattern:  f.urlPattern.value.trim() || null,
        methods:     f.methods.value.split(',').map(s => s.trim()).filter(Boolean),
        probability: parseFloat(f.probability.value),
        everyN:      f.everyN.value === '' ? null : parseInt(f.everyN.value, 10),
        delayMs:     f.delayMs.value === '' ? null : parseInt(f.delayMs.value, 10),
        errorStatus: f.errorStatus.value === '' ? null : parseInt(f.errorStatus.value, 10),
        errorMessage: f.errorMessage.value || null,
    };
    if (Number.isNaN(dto.probability)) dto.probability = null;
    if (!dto.methods.length) dto.methods = null;
    return dto;
}

async function submitRuleForm(e) {
    e.preventDefault();
    const dto = readRuleForm();
    const errBox = $('#rule-form-error');
    errBox.classList.add('hidden');
    try {
        if (editingRuleName) {
            await api('PUT', `/rules/${encodeURIComponent(editingRuleName)}`, dto);
            toast(`Saved "${editingRuleName}".`);
        } else {
            if (!dto.name) {
                throw new Error('Name is required.');
            }
            await api('POST', '/rules', dto);
            toast(`Created "${dto.name}".`);
        }
        closeRuleModal();
        await refreshAll();
    } catch (err) {
        errBox.textContent = err.message;
        errBox.classList.remove('hidden');
    }
}

async function deleteCurrentRule() {
    if (!editingRuleName) return;
    if (!confirm(`Delete rule "${editingRuleName}"?`)) return;
    try {
        await api('DELETE', `/rules/${encodeURIComponent(editingRuleName)}`);
        toast(`Deleted "${editingRuleName}".`);
        closeRuleModal();
        await refreshAll();
    } catch (err) {
        toast(err.message, 'error');
    }
}

// ---------------------------------------------------------------------------
// Polling
// ---------------------------------------------------------------------------

async function refreshAll() {
    try {
        const [cfg, metrics, ts, events] = await Promise.all([
            api('GET', '/config'),
            api('GET', '/metrics'),
            api('GET', '/metrics/timeseries'),
            api('GET', '/events?limit=200'),
        ]);
        renderConfig(cfg);
        renderMetrics(metrics);
        renderTimeSeries(ts);
        renderEvents(events);
        state.pollMs = cfg.ui?.pollMs ?? state.pollMs;
    } catch (err) {
        // Don't spam on transient failures, but surface persistent ones.
        console.warn('[fault-injector] refresh failed:', err);
    }
}

function startPolling() {
    if (state.pollTimer) clearInterval(state.pollTimer);
    state.pollTimer = setInterval(() => {
        if (!state.pollSuspended && !document.hidden) refreshAll();
    }, state.pollMs);
}

// ---------------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------------

function wireUp() {
    $$('.tab-btn').forEach(b => b.addEventListener('click', () => selectTab(b.dataset.tab)));
    selectTab('config');

    $('#global-toggle').addEventListener('click', toggleGlobal);
    $('#add-rule-btn').addEventListener('click', () => openRuleModal(null));
    $('#reset-metrics-btn').addEventListener('click', () => resetMetrics(null));
    $('#export-csv-btn').addEventListener('click', () => { window.location.href = `${API_BASE}/export?format=csv`; });
    $('#export-json-btn').addEventListener('click', () => { window.location.href = `${API_BASE}/export?format=json`; });

    $$('#rule-modal [data-modal-close]').forEach(b => b.addEventListener('click', closeRuleModal));
    $('#rule-form').addEventListener('submit', submitRuleForm);
    $('#rule-form-delete').addEventListener('click', deleteCurrentRule);
    $('#rule-form').elements.probability.addEventListener('input', syncProbabilityDisplay);

    document.addEventListener('keydown', e => {
        if (e.key === 'Escape' && !$('#rule-modal').classList.contains('hidden')) closeRuleModal();
    });
}

async function boot() {
    wireUp();
    await refreshAll();
    startPolling();
}

boot();
