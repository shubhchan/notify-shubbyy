/* ==========================================================================
   NOTIFY SHUBBYY — Pixel Signal web client
   MQTT over WebSockets · ACK tracking · chiptune synth · arcade UI
   ========================================================================== */

'use strict';

/* --------------------------------------------------------------------------
   Config
   -------------------------------------------------------------------------- */

const BROKER_URL = 'wss://broker.emqx.io:8084/mqtt';
const TOPIC = 'shubbyy/notify/instant';
const ACK_TOPIC = TOPIC + '/ack';
const MQTT_CDN = 'https://unpkg.com/mqtt@4.3.7/dist/mqtt.min.js';

const MODES = ['beep', 'urgent', 'vibrate'];
const ACK_TIMEOUT_MS = 5000;
const COOLDOWN_MS = 900;
const MAX_IN_FLIGHT = 3;
const MAX_LOG_LINES = 60;

const KEYS = {
  signals: 'notify_signals',
  mode: 'notify_mode',
  muted: 'notify_muted'
};

/* --------------------------------------------------------------------------
   Tiny helpers
   -------------------------------------------------------------------------- */

const $ = (id) => document.getElementById(id);

const store = {
  get(key, fallback) {
    try {
      const value = window.localStorage.getItem(key);
      return value === null ? fallback : value;
    } catch (err) {
      return fallback;
    }
  },
  set(key, value) {
    try {
      window.localStorage.setItem(key, String(value));
    } catch (err) {
      /* storage can be blocked; ignore */
    }
  }
};

function randomId() {
  try {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
      return window.crypto.randomUUID();
    }
  } catch (err) { /* ignore */ }
  return 'tx_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 8);
}

function timeStamp() {
  return new Date().toLocaleTimeString([], {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  });
}

/* --------------------------------------------------------------------------
   DOM
   -------------------------------------------------------------------------- */

const dom = {
  triggerBtn: $('triggerBtn'),
  triggerSubtext: $('triggerSubtext'),
  triggerAura: $('triggerAura'),
  connectionChip: $('connectionChip'),
  connectionLed: $('connectionLed'),
  connectionText: $('connectionText'),
  signalCountText: $('signalCountText'),
  ackText: $('ackText'),
  modeText: $('modeText'),
  stage: document.querySelector('.stage'),
  segments: Array.from(document.querySelectorAll('.mode-btn')),
  soundToggle: $('soundToggle'),
  soundIcon: $('soundIcon'),
  helpBtn: $('helpBtn'),
  openGuideBtn: $('openGuideBtn'),
  helpModal: $('helpModal'),
  helpClose: $('helpClose'),
  eventLog: $('eventLog'),
  clearLog: $('clearLog'),
  copyChannel: $('copyChannel'),
  copyChannelModal: $('copyChannelModal'),
  toastStack: $('toastStack'),
  liveRegion: $('liveRegion')
};

const SOUND_ON_ICON =
  '<path d="M11 5 6 9H3v6h3l5 4z"></path>' +
  '<path d="M15.5 8.5a5 5 0 0 1 0 7"></path>' +
  '<path d="M18.5 5.5a9 9 0 0 1 0 13"></path>';

const SOUND_OFF_ICON =
  '<path d="M11 5 6 9H3v6h3l5 4z"></path>' +
  '<path d="M16 9l6 6"></path>' +
  '<path d="M22 9l-6 6"></path>';

/* --------------------------------------------------------------------------
   State
   -------------------------------------------------------------------------- */

const state = {
  client: null,
  connected: false,
  statusText: 'LINKING',
  mode: 'beep',
  muted: false,
  signals: 0,
  pending: new Map(),
  cooldownUntil: 0,
  logs: 0,
  connectAttempt: 0,
  lastAck: null
};

let subTimer = null;
let audioCtx = null;
let masterGain = null;
let lastFocused = null;

/* --------------------------------------------------------------------------
   Boot
   -------------------------------------------------------------------------- */

function init() {
  hydratePreferences();
  bindEvents();
  dom.eventLog.innerHTML = '';
  state.logs = 0;
  logLine('sys', 'PIXEL SIGNAL OS v1.0 — BOOT SEQUENCE');
  logLine('sys', 'CHANNEL LOCK: ' + TOPIC);
  logLine('sys', 'WAITING FOR BROKER HANDSHAKE...');
  updateSignalCount();
  updateAckDisplay();
  connectBroker();
}

function hydratePreferences() {
  const savedMode = store.get(KEYS.mode, 'beep');
  setMode(MODES.includes(savedMode) ? savedMode : 'beep', { silent: true });

  state.muted = store.get(KEYS.muted, '0') === '1';
  applyMuted();

  const savedSignals = parseInt(store.get(KEYS.signals, '0'), 10);
  state.signals = Number.isFinite(savedSignals) && savedSignals >= 0 ? savedSignals : 0;
}

function updateSignalCount() {
  dom.signalCountText.textContent = String(state.signals);
}

function updateAckDisplay() {
  dom.ackText.textContent = state.lastAck === null ? '--' : state.lastAck + 'ms';
}

/* --------------------------------------------------------------------------
   Status + connection
   -------------------------------------------------------------------------- */

function setConnection(kind, label) {
  state.connected = kind === 'online';
  state.statusText = label;

  dom.connectionText.textContent = label;
  dom.connectionLed.className =
    'led ' + (kind === 'online' ? 'led--ok' : kind === 'connecting' ? 'led--warn' : 'led--off');

  dom.connectionChip.classList.toggle('px-chip--ok', kind === 'online');
  dom.connectionChip.classList.toggle('px-chip--warn', kind === 'connecting');
  dom.connectionChip.classList.toggle('px-chip--err', kind === 'offline');

  dom.triggerBtn.disabled = !state.connected;
  dom.triggerAura.classList.toggle('active', state.connected);
  if (dom.stage) {
    dom.stage.classList.toggle('is-live', state.connected);
  }

  clearTimeout(subTimer);

  if (!state.connected) {
    clearInFlight(label);
    dom.triggerSubtext.textContent = label;
  } else {
    dom.triggerSubtext.textContent = idleSub();
  }

  dom.liveRegion.textContent = 'Connection: ' + label;
}

function connectBroker() {
  state.connectAttempt += 1;
  setConnection('connecting', state.connectAttempt > 1 ? 'RECONNECTING' : 'LINKING');

  loadMqtt()
    .then(() => {
      if (state.client) {
        return;
      }

      const client = window.mqtt.connect(BROKER_URL, {
        clientId: 'web_' + Math.random().toString(16).slice(2, 10),
        clean: true,
        connectTimeout: 8000,
        keepalive: 30,
        reconnectPeriod: 3000,
        protocolVersion: 4
      });

      state.client = client;
      wireClient(client);
    })
    .catch(() => {
      setConnection('offline', 'NO MQTT');
      logLine('err', 'MQTT LIBRARY FAILED TO LOAD.');
      toast('MQTT LIBRARY FAILED TO LOAD', 'err');
      setTimeout(connectBroker, 5000);
    });
}

function loadMqtt() {
  return new Promise((resolve, reject) => {
    if (window.mqtt && typeof window.mqtt.connect === 'function') {
      resolve();
      return;
    }

    const script = document.createElement('script');
    script.src = MQTT_CDN;
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error('script load failed'));
    document.head.appendChild(script);
  });
}

function wireClient(client) {
  client.on('connect', () => {
    setConnection('online', 'ONLINE');
    client.subscribe(ACK_TOPIC, { qos: 1 });
    logLine('ok', 'BROKER LINK ESTABLISHED — RECEIVER READY');
    logLine('sys', 'LISTENING FOR ACK ON ' + ACK_TOPIC);
    toast('LINK ONLINE — FIRE WHEN READY', 'ok');
  });

  client.on('reconnect', () => {
    setConnection('connecting', 'RECONNECTING');
    logLine('warn', 'LINK DROPPED — ATTEMPTING RECONNECT...');
  });

  client.on('close', () => {
    if (state.connected) {
      logLine('warn', 'BROKER LINK CLOSED.');
    }
    setConnection('offline', 'OFFLINE');
  });

  client.on('offline', () => {
    setConnection('offline', 'OFFLINE');
  });

  client.on('error', (err) => {
    const message = err && err.message ? err.message : 'UNKNOWN';
    setConnection('offline', 'LINK ERROR');
    logLine('err', 'LINK ERROR: ' + message.toUpperCase());
  });

  client.on('message', (topic, payload) => {
    if (topic === ACK_TOPIC) {
      handleAck(payload);
    }
  });
}

/* --------------------------------------------------------------------------
   Fire!
   -------------------------------------------------------------------------- */

function fire() {
  const client = state.client;

  if (!state.connected || !client || !client.connected) {
    toast('LINK OFFLINE — WAIT FOR ONLINE', 'warn');
    shakeTrigger();
    return;
  }

  const now = Date.now();
  if (now < state.cooldownUntil) {
    toast('RECHARGING — HOLD ON', 'warn');
    shakeTrigger();
    return;
  }

  if (state.pending.size >= MAX_IN_FLIGHT) {
    toast('TOO MANY SIGNALS IN FLIGHT', 'warn');
    shakeTrigger();
    return;
  }

  unlockAudio();
  playTone(state.mode);
  copyVibrate(state.mode);

  state.cooldownUntil = now + COOLDOWN_MS;

  const timestamp = now;
  const key = String(timestamp);
  const payload = {
    action: 'beep',
    type: state.mode,
    channel: TOPIC,
    source: 'web-arcade',
    id: randomId(),
    timestamp
  };

  const pending = {
    timestamp,
    counted: false,
    timer: setTimeout(() => ackTimeout(key), ACK_TIMEOUT_MS)
  };
  state.pending.set(key, pending);

  clearTimeout(subTimer);
  dom.triggerSubtext.textContent = 'SENDING...';
  dom.triggerBtn.classList.remove('is-error');
  dom.triggerBtn.classList.add('is-firing');
  window.setTimeout(() => dom.triggerBtn.classList.remove('is-firing'), 190);

  logLine('sys', 'TX > ' + state.mode.toUpperCase() + ' PACKET #' + (state.signals + 1));

  client.publish(TOPIC, JSON.stringify(payload), { qos: 1 }, (err) => {
    if (err) {
      const entry = state.pending.get(key);
      if (!entry) {
        return; // an ACK already confirmed delivery
      }
      clearTimeout(entry.timer);
      state.pending.delete(key);

      if (!entry.counted) {
        flashSub('FAILED', 1600);
      }
      logLine('err', 'TX FAILED: ' + String(err.message || 'UNKNOWN').toUpperCase());
      toast('TRANSMISSION FAILED', 'err');
      shakeTrigger();
      return;
    }

    if (!state.pending.has(key)) {
      return; // ACK beat the publish callback
    }

    countSignal(state.pending.get(key));
    clearTimeout(subTimer);
    dom.triggerSubtext.textContent = 'WAITING ACK';
    logLine('ok', 'TX OK — SIGNAL #' + state.signals + ' DISPATCHED');
    dom.triggerBtn.classList.add('is-success');
    window.setTimeout(() => dom.triggerBtn.classList.remove('is-success'), 460);
  });
}

function handleAck(buffer) {
  let data = null;
  try {
    data = JSON.parse(buffer.toString());
  } catch (err) {
    logLine('warn', 'ACK RECEIVED BUT PAYLOAD WAS INVALID.');
    return;
  }

  const sentAt = Number(data && data.sentAt);
  if (!Number.isFinite(sentAt)) {
    return;
  }

  const entry = state.pending.get(String(sentAt));
  if (!entry) {
    return; // not ours (shared public topic)
  }

  const rtt = Math.max(1, Date.now() - sentAt);
  countSignal(entry);
  clearTimeout(entry.timer);
  state.pending.delete(String(sentAt));

  state.lastAck = rtt;
  updateAckDisplay();
  flashSub('DELIVERED', 1400);

  logLine('ack', 'ACK ' + rtt + 'MS — RECEIVER CONFIRMED DELIVERY');
  toast('ACK ' + rtt + 'MS — DELIVERED', 'ok');
  dom.liveRegion.textContent = 'Alert delivered in ' + rtt + ' milliseconds';

  dom.triggerBtn.classList.add('is-success');
  window.setTimeout(() => dom.triggerBtn.classList.remove('is-success'), 460);
}

function ackTimeout(key) {
  const entry = state.pending.get(key);
  if (!entry) {
    return;
  }

  countSignal(entry);
  state.pending.delete(key);

  flashSub('NO ACK', 1800);

  logLine('warn', 'NO ACK — RECEIVER MAY BE CLOSED OR OFFLINE.');
  toast('NO ACK — CHECK RECEIVER', 'warn');
}

function countSignal(entry) {
  if (!entry || entry.counted) {
    return;
  }
  entry.counted = true;
  state.signals += 1;
  updateSignalCount();
  store.set(KEYS.signals, state.signals);
}

function clearInFlight(label) {
  if (!state.pending.size) {
    return;
  }

  const dropped = state.pending.size;
  state.pending.forEach((entry) => clearTimeout(entry.timer));
  state.pending.clear();
  logLine('warn', dropped + ' PENDING SIGNAL(S) DROPPED — ' + label);
}

function idleSub() {
  if (state.pending.size > 0) {
    return 'WAITING ACK';
  }
  return state.connected ? 'READY' : state.statusText;
}

function flashSub(text, duration) {
  clearTimeout(subTimer);
  dom.triggerSubtext.textContent = text;
  subTimer = window.setTimeout(() => {
    dom.triggerSubtext.textContent = idleSub();
  }, duration);
}

function shakeTrigger() {
  dom.triggerBtn.classList.remove('is-error');
  // force reflow so the animation can restart
  void dom.triggerBtn.offsetWidth;
  dom.triggerBtn.classList.add('is-error');
  window.setTimeout(() => dom.triggerBtn.classList.remove('is-error'), 400);
}

/* --------------------------------------------------------------------------
   Modes
   -------------------------------------------------------------------------- */

function setMode(mode, options) {
  const opts = options || {};
  if (!MODES.includes(mode)) {
    return;
  }

  state.mode = mode;
  document.body.dataset.mode = mode;
  dom.modeText.textContent = mode.toUpperCase();

  dom.segments.forEach((btn) => {
    const active = btn.dataset.type === mode;
    btn.classList.toggle('active', active);
    btn.setAttribute('aria-pressed', active ? 'true' : 'false');
  });

  store.set(KEYS.mode, mode);

  if (opts.silent) {
    return;
  }

  logLine('sys', 'MODE SET: ' + mode.toUpperCase());
  toast('MODE: ' + mode.toUpperCase(), 'info');

  if (!state.muted) {
    unlockAudio();
    playTone(mode, true);
  }
  copyVibrate(mode, true);
}

/* --------------------------------------------------------------------------
   Audio — 90s chiptune synth
   -------------------------------------------------------------------------- */

function unlockAudio() {
  if (state.muted) {
    return;
  }
  try {
    if (!audioCtx) {
      const Ctx = window.AudioContext || window.webkitAudioContext;
      if (!Ctx) {
        return;
      }
      audioCtx = new Ctx();
      masterGain = audioCtx.createGain();
      masterGain.gain.value = 0.28;
      masterGain.connect(audioCtx.destination);
    }
    if (audioCtx.state === 'suspended') {
      audioCtx.resume();
    }
  } catch (err) {
    audioCtx = null;
  }
}

function blip(frequency, start, duration, wave, volume) {
  if (!audioCtx || !masterGain) {
    return;
  }

  const osc = audioCtx.createOscillator();
  const gain = audioCtx.createGain();
  osc.type = wave || 'square';
  osc.frequency.setValueAtTime(frequency, start);

  gain.gain.setValueAtTime(0.0001, start);
  gain.gain.linearRampToValueAtTime(volume || 0.22, start + 0.006);
  gain.gain.exponentialRampToValueAtTime(0.0001, start + duration);

  osc.connect(gain);
  gain.connect(masterGain);
  osc.start(start);
  osc.stop(start + duration + 0.02);
}

function playTone(mode, preview) {
  if (state.muted) {
    return;
  }

  unlockAudio();
  if (!audioCtx) {
    return;
  }

  const now = audioCtx.currentTime + 0.01;

  if (mode === 'urgent') {
    // Sawtooth emergency wail
    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();
    osc.type = 'sawtooth';
    osc.frequency.setValueAtTime(640, now);
    osc.frequency.exponentialRampToValueAtTime(1600, now + (preview ? 0.28 : 0.4));
    osc.frequency.exponentialRampToValueAtTime(640, now + (preview ? 0.56 : 0.8));
    if (!preview) {
      osc.frequency.exponentialRampToValueAtTime(1600, now + 1.2);
      osc.frequency.exponentialRampToValueAtTime(640, now + 1.6);
    }

    gain.gain.setValueAtTime(0.0001, now);
    gain.gain.linearRampToValueAtTime(0.24, now + 0.02);
    gain.gain.setValueAtTime(0.24, now + (preview ? 0.46 : 1.4));
    gain.gain.exponentialRampToValueAtTime(0.0001, now + (preview ? 0.6 : 1.68));

    osc.connect(gain);
    gain.connect(masterGain);
    osc.start(now);
    osc.stop(now + (preview ? 0.62 : 1.7));
    return;
  }

  if (mode === 'vibrate') {
    if (!preview) {
      return; // haptics only
    }
    blip(220, now, 0.07, 'square', 0.12);
    blip(180, now + 0.1, 0.07, 'square', 0.12);
    return;
  }

  // Beep — quick ascending coin chirp
  const notes = preview ? [880, 1174.7] : [880, 1174.7, 1568, 2093];
  notes.forEach((freq, index) => {
    blip(freq, now + index * 0.075, preview ? 0.08 : 0.1, 'square', 0.2);
  });
}

function copyVibrate(mode, preview) {
  if (!('vibrate' in navigator)) {
    return;
  }
  try {
    if (mode === 'vibrate') {
      navigator.vibrate(preview ? [40, 40, 40] : [220, 90, 220, 90, 420]);
    } else if (preview) {
      navigator.vibrate(20);
    }
  } catch (err) { /* ignore */ }
}

function applyMuted() {
  dom.soundToggle.setAttribute('aria-pressed', state.muted ? 'false' : 'true');
  dom.soundToggle.setAttribute('aria-label', state.muted ? 'Unmute sound (M)' : 'Mute sound (M)');
  dom.soundToggle.title = state.muted ? 'Unmute sound (M)' : 'Mute sound (M)';
  dom.soundIcon.innerHTML = state.muted ? SOUND_OFF_ICON : SOUND_ON_ICON;
}

function toggleSound() {
  state.muted = !state.muted;
  store.set(KEYS.muted, state.muted ? '1' : '0');
  applyMuted();

  if (!state.muted) {
    unlockAudio();
    playTone('beep', true);
    toast('SOUND ON', 'ok');
  } else {
    toast('SOUND MUTED', 'info');
  }
}

/* --------------------------------------------------------------------------
   Log
   -------------------------------------------------------------------------- */

function logLine(type, message) {
  if (!dom.eventLog) {
    return;
  }

  const line = document.createElement('li');
  line.className = 'px-log-line px-log-line--' + type;

  const time = document.createElement('span');
  time.className = 'px-log-line__time';
  time.textContent = timeStamp();

  const msg = document.createElement('span');
  msg.className = 'px-log-line__msg';
  msg.textContent = message;

  line.appendChild(time);
  line.appendChild(msg);
  dom.eventLog.appendChild(line);

  state.logs += 1;
  while (dom.eventLog.children.length > MAX_LOG_LINES) {
    dom.eventLog.removeChild(dom.eventLog.firstChild);
  }

  dom.eventLog.scrollTop = dom.eventLog.scrollHeight;
}

function clearLog() {
  dom.eventLog.innerHTML = '';
  state.logs = 0;
  logLine('sys', 'LOG CLEARED — AWAITING SIGNALS...');
  toast('LOG CLEARED', 'info');
}

/* --------------------------------------------------------------------------
   Toast
   -------------------------------------------------------------------------- */

function toast(message, type) {
  if (!dom.toastStack) {
    return;
  }

  while (dom.toastStack.children.length >= 3) {
    dom.toastStack.removeChild(dom.toastStack.firstChild);
  }

  const el = document.createElement('div');
  el.className = 'px-toast px-toast--' + (type || 'info');
  el.setAttribute('role', 'status');
  el.textContent = message;
  dom.toastStack.appendChild(el);

  window.setTimeout(() => {
    el.classList.add('is-leaving');
    window.setTimeout(() => el.remove(), 240);
  }, 2600);
}

/* --------------------------------------------------------------------------
   Help modal
   -------------------------------------------------------------------------- */

function openHelp() {
  lastFocused = document.activeElement;
  dom.helpModal.hidden = false;
  document.body.classList.add('modal-open');
  window.setTimeout(() => dom.helpClose.focus(), 40);
}

function closeHelp() {
  dom.helpModal.hidden = true;
  document.body.classList.remove('modal-open');
  if (lastFocused && typeof lastFocused.focus === 'function') {
    lastFocused.focus();
  }
}

function trapFocus(event) {
  if (dom.helpModal.hidden) {
    return;
  }

  if (event.key === 'Escape') {
    event.preventDefault();
    closeHelp();
    return;
  }

  if (event.key !== 'Tab') {
    return;
  }

  const focusable = Array.from(
    dom.helpModal.querySelectorAll('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])')
  ).filter((el) => !el.disabled && el.offsetParent !== null);

  if (!focusable.length) {
    return;
  }

  const first = focusable[0];
  const last = focusable[focusable.length - 1];

  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

/* --------------------------------------------------------------------------
   Clipboard
   -------------------------------------------------------------------------- */

async function copyText(text) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
    } else {
      const area = document.createElement('textarea');
      area.value = text;
      area.setAttribute('readonly', '');
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      document.execCommand('copy');
      area.remove();
    }
    toast('TOPIC COPIED TO CLIPBOARD', 'ok');
    logLine('sys', 'TOPIC COPIED: ' + text);
  } catch (err) {
    toast('COPY FAILED — SELECT MANUALLY', 'err');
  }
}

/* --------------------------------------------------------------------------
   Events
   -------------------------------------------------------------------------- */

function bindEvents() {
  dom.triggerBtn.addEventListener('click', fire);
  dom.clearLog.addEventListener('click', clearLog);
  dom.soundToggle.addEventListener('click', toggleSound);
  dom.helpBtn.addEventListener('click', openHelp);
  dom.openGuideBtn.addEventListener('click', openHelp);
  dom.copyChannel.addEventListener('click', () => copyText(TOPIC));
  dom.copyChannelModal.addEventListener('click', () => copyText(TOPIC));

  dom.segments.forEach((btn) => {
    btn.addEventListener('click', () => setMode(btn.dataset.type));
  });

  dom.helpModal.addEventListener('click', (event) => {
    if (event.target.closest('[data-close-modal]')) {
      closeHelp();
    }
  });

  document.addEventListener('keydown', onKeyDown);

  window.addEventListener('online', () => {
    toast('NETWORK BACK ONLINE', 'ok');
    logLine('ok', 'BROWSER NETWORK RESTORED.');
  });

  window.addEventListener('offline', () => {
    toast('NETWORK OFFLINE', 'err');
    logLine('err', 'BROWSER NETWORK LOST.');
  });

  window.addEventListener('beforeunload', () => {
    if (state.client) {
      try {
        state.client.end(true);
      } catch (err) { /* ignore */ }
    }
  });
}

function onKeyDown(event) {
  if (!dom.helpModal.hidden) {
    trapFocus(event);
    return;
  }

  const tag = (event.target.tagName || '').toLowerCase();
  if (tag === 'input' || tag === 'textarea' || tag === 'select') {
    return;
  }

  if (event.key === 'Escape') {
    return;
  }

  const active = document.activeElement;
  const activeIsControl =
    active && (active.closest('button, a, input, select, textarea, [role="button"]'));

  if ((event.code === 'Space' || event.key === 'Enter') && !activeIsControl) {
    event.preventDefault();
    if (!event.repeat) {
      fire();
    }
    return;
  }

  if (event.key === '1') {
    setMode('beep');
  } else if (event.key === '2') {
    setMode('urgent');
  } else if (event.key === '3') {
    setMode('vibrate');
  } else if (event.key === 'm' || event.key === 'M') {
    toggleSound();
  }
}

/* --------------------------------------------------------------------------
   Go
   -------------------------------------------------------------------------- */

document.addEventListener('DOMContentLoaded', init);
