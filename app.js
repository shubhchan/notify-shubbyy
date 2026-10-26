// Notify Shubbyy - Material 3 Client

const BROKER_URL = "wss://broker.emqx.io:8084/mqtt";
const FIXED_CHANNEL = "shubbyy/notify/instant"; // LOCKED PERMANENT CHANNEL

// State
let client = null;
let currentAlertType = "beep";
let signalCount = parseInt(localStorage.getItem("notify_signals") || "0", 10);
let audioCtx = null;

// DOM Elements
const triggerBtn = document.getElementById("triggerBtn");
const triggerSubtext = document.getElementById("triggerSubtext");
const triggerAura = document.getElementById("triggerAura");
const connectionChip = document.getElementById("connectionChip");
const connectionText = document.getElementById("connectionText");
const signalCountText = document.getElementById("signalCountText");
const segments = document.querySelectorAll(".m3-segment");

function init() {
  signalCountText.textContent = signalCount;
  setupEventListeners();
  connectToBroker();
}

// Web Audio API Synthesizer
function playLocalTone(type) {
  try {
    if (!audioCtx) {
      audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    }
    if (audioCtx.state === "suspended") {
      audioCtx.resume();
    }

    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();
    osc.connect(gain);
    gain.connect(audioCtx.destination);

    if (type === "urgent") {
      // Authentic emergency siren wail (multi-cycle sweeps)
      const now = audioCtx.currentTime;
      osc.type = "sawtooth";
      osc.frequency.setValueAtTime(650, now);
      // Sweep 1
      osc.frequency.exponentialRampToValueAtTime(1550, now + 0.35);
      osc.frequency.exponentialRampToValueAtTime(650, now + 0.70);
      // Sweep 2
      osc.frequency.exponentialRampToValueAtTime(1550, now + 1.05);
      osc.frequency.exponentialRampToValueAtTime(650, now + 1.40);

      gain.gain.setValueAtTime(0.3, now);
      gain.gain.setValueAtTime(0.3, now + 1.30);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 1.45);

      osc.start(now);
      osc.stop(now + 1.45);
    } else if (type === "vibrate") {
      if ("vibrate" in navigator) {
        navigator.vibrate([200, 100, 200, 100, 400]);
      }
    } else {
      // Piercing high-decibel double-chime alert
      const now = audioCtx.currentTime;
      osc.type = "sine";
      osc.frequency.setValueAtTime(1800, now);
      osc.frequency.setValueAtTime(1800, now + 0.12);
      osc.frequency.setValueAtTime(2600, now + 0.16);
      osc.frequency.setValueAtTime(2600, now + 0.35);
      osc.frequency.setValueAtTime(1800, now + 0.45);
      osc.frequency.setValueAtTime(1800, now + 0.57);
      osc.frequency.setValueAtTime(2600, now + 0.61);

      gain.gain.setValueAtTime(0.35, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.95);

      osc.start(now);
      osc.stop(now + 0.95);
    }
  } catch (err) {}
}

// Connect to MQTT Broker
function connectToBroker() {
  const mqttLib = window.mqtt || (typeof mqtt !== "undefined" ? mqtt : null);
  if (!mqttLib || typeof mqttLib.connect !== "function") {
    updateUIStatus("connecting", "Loading...");
    setTimeout(connectToBroker, 500);
    return;
  }

  updateUIStatus("connecting", "Connecting...");

  const clientId = "web_" + Math.random().toString(16).substring(2, 10);
  const options = {
    clientId: clientId,
    clean: true,
    connectTimeout: 8000,
    keepalive: 60,
    reconnectPeriod: 4000
  };

  try {
    client = mqttLib.connect(BROKER_URL, options);

    client.on("connect", () => {
      updateUIStatus("ready", "Connected");
    });

    client.on("reconnect", () => {
      updateUIStatus("connecting", "Reconnecting...");
    });

    client.on("close", () => {
      updateUIStatus("disconnected", "Offline");
    });

    client.on("error", () => {
      updateUIStatus("disconnected", "Error");
    });
  } catch (err) {
    updateUIStatus("disconnected", "Offline");
  }
}

function updateUIStatus(state, message) {
  if (state === "ready") {
    connectionChip.classList.add("connected");
    connectionText.textContent = "Connected";
    triggerBtn.disabled = false;
    triggerSubtext.textContent = "READY";
    triggerAura.classList.add("active");
  } else if (state === "connecting") {
    connectionChip.classList.remove("connected");
    connectionText.textContent = message;
    triggerBtn.disabled = true;
    triggerSubtext.textContent = "CONNECTING";
    triggerAura.classList.remove("active");
  } else {
    connectionChip.classList.remove("connected");
    connectionText.textContent = "Offline";
    triggerBtn.disabled = true;
    triggerSubtext.textContent = "OFFLINE";
    triggerAura.classList.remove("active");
  }
}

// Dispatch Trigger
function dispatchAlert() {
  if (!client || !client.connected) return;

  playLocalTone(currentAlertType);

  triggerSubtext.textContent = "SENDING...";
  triggerBtn.style.transform = "scale(0.94)";
  setTimeout(() => { triggerBtn.style.transform = ""; }, 150);

  const payload = JSON.stringify({
    action: "beep",
    type: currentAlertType,
    channel: FIXED_CHANNEL,
    timestamp: Date.now()
  });

  client.publish(FIXED_CHANNEL, payload, { qos: 1 }, (err) => {
    if (!err) {
      signalCount++;
      signalCountText.textContent = signalCount;
      localStorage.setItem("notify_signals", signalCount.toString());

      triggerSubtext.textContent = "DISPATCHED!";
      setTimeout(() => {
        if (client && client.connected) {
          triggerSubtext.textContent = "READY";
        }
      }, 1200);
    } else {
      triggerSubtext.textContent = "RETRY";
    }
  });
}

function setupEventListeners() {
  triggerBtn.addEventListener("click", dispatchAlert);

  segments.forEach((seg) => {
    seg.addEventListener("click", () => {
      segments.forEach((s) => s.classList.remove("active"));
      seg.classList.add("active");
      currentAlertType = seg.dataset.type;
    });
  });
}

document.addEventListener("DOMContentLoaded", init);
