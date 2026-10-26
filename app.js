// Notify Shubbyy - Client Application Logic

const DEFAULT_BROKER = "wss://broker.hivemq.com:8884/mqtt";
const DEFAULT_TOPIC = "shubbyy/alert/" + Math.random().toString(36).substring(2, 10);

// State
let client = null;
let currentTopic = localStorage.getItem("notify_topic") || DEFAULT_TOPIC;
let currentBroker = localStorage.getItem("notify_broker") || DEFAULT_BROKER;
let currentAlertType = "beep";
let signalCount = parseInt(localStorage.getItem("notify_signals") || "0", 10);
let audioCtx = null;

// DOM Elements
const triggerBtn = document.getElementById("triggerBtn");
const triggerSubtext = document.getElementById("triggerSubtext");
const rippleRing = document.getElementById("rippleRing");
const connectionBadgeText = document.getElementById("connectionBadgeText");
const badgeEl = document.querySelector(".badge");
const brokerStatusText = document.getElementById("brokerStatusText");
const brokerIndicator = document.getElementById("brokerIndicator");
const latencyStat = document.getElementById("latencyStat");
const signalCounter = document.getElementById("signalCounter");
const topicInput = document.getElementById("topicInput");
const brokerSelect = document.getElementById("brokerSelect");
const customBrokerGroup = document.getElementById("customBrokerGroup");
const customBrokerInput = document.getElementById("customBrokerInput");
const generateTopicBtn = document.getElementById("generateTopicBtn");
const copyTopicBtn = document.getElementById("copyTopicBtn");
const reconnectBtn = document.getElementById("reconnectBtn");
const testAudioBtn = document.getElementById("testAudioBtn");
const clearLogBtn = document.getElementById("clearLogBtn");
const consoleScreen = document.getElementById("consoleScreen");
const alertTypeBtns = document.querySelectorAll(".alert-type-btn");

// Modal elements
const guideModal = document.getElementById("guideModal");
const openGuideLink = document.getElementById("openGuideLink");
const closeGuideBtn = document.getElementById("closeGuideBtn");

// Init
function init() {
  topicInput.value = currentTopic;
  signalCounter.textContent = signalCount;

  // Broker selection sync
  if (currentBroker === "wss://broker.hivemq.com:8884/mqtt" || currentBroker === "wss://broker.emqx.io:8084/mqtt") {
    brokerSelect.value = currentBroker;
    customBrokerGroup.style.display = "none";
  } else {
    brokerSelect.value = "custom";
    customBrokerGroup.style.display = "flex";
    customBrokerInput.value = currentBroker;
  }

  setupEventListeners();
  connectToBroker();
}

// Log utility
function appendLog(message, type = "normal") {
  const now = new Date();
  const timeStr = `[${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}:${String(now.getSeconds()).padStart(2, "0")}]`;
  
  const entry = document.createElement("div");
  entry.className = `log-entry log-${type}`;
  
  const timeSpan = document.createElement("span");
  timeSpan.className = "log-time";
  timeSpan.textContent = timeStr;
  
  const msgSpan = document.createElement("span");
  msgSpan.className = "log-msg";
  msgSpan.textContent = message;
  
  entry.appendChild(timeSpan);
  entry.appendChild(msgSpan);
  consoleScreen.appendChild(entry);
  consoleScreen.scrollTop = consoleScreen.scrollHeight;
}

// Web Audio synthesizer for alert beep feedback
function playLocalAlertTone(type = "beep") {
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
      // High pitch two-tone siren
      osc.type = "sawtooth";
      osc.frequency.setValueAtTime(880, audioCtx.currentTime);
      osc.frequency.exponentialRampToValueAtTime(1400, audioCtx.currentTime + 0.15);
      osc.frequency.exponentialRampToValueAtTime(880, audioCtx.currentTime + 0.3);
      gain.gain.setValueAtTime(0.25, audioCtx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.01, audioCtx.currentTime + 0.35);
      osc.start();
      osc.stop(audioCtx.currentTime + 0.35);
    } else {
      // Crisp ping
      osc.type = "sine";
      osc.frequency.setValueAtTime(1046.5, audioCtx.currentTime); // C6
      gain.gain.setValueAtTime(0.3, audioCtx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.001, audioCtx.currentTime + 0.25);
      osc.start();
      osc.stop(audioCtx.currentTime + 0.25);
    }
  } catch (err) {
    console.warn("Audio playback not allowed yet or unsupported:", err);
  }
}

// Connect to MQTT Broker over WebSockets
function connectToBroker() {
  if (client) {
    try {
      client.end(true);
    } catch (e) {}
  }

  updateConnectionStatus("connecting", "Connecting to broker...");
  appendLog(`Attempting connection to ${currentBroker}...`, "info");

  const clientId = "web_trigger_" + Math.random().toString(16).substring(2, 10);
  
  const options = {
    clientId: clientId,
    clean: true,
    connectTimeout: 7000,
    keepalive: 60,
    reconnectPeriod: 4000
  };

  try {
    client = mqtt.connect(currentBroker, options);

    client.on("connect", () => {
      updateConnectionStatus("ready", "System Ready (Connected)");
      appendLog(`Connected successfully via WebSocket! ClientId: ${clientId}`, "success");
      
      // Also optionally subscribe to own topic to calculate roundtrip latency!
      client.subscribe(currentTopic + "/ack", { qos: 0 }, (err) => {
        if (!err) {
          appendLog(`Telemetry subscribed to ${currentTopic}/ack`, "info");
        }
      });
    });

    client.on("message", (topic, payload) => {
      // Handle ACK / response from device if provided
      if (topic.endsWith("/ack")) {
        try {
          const data = JSON.parse(payload.toString());
          if (data.sentAt) {
            const rtt = Date.now() - data.sentAt;
            latencyStat.textContent = `${rtt} ms`;
            appendLog(`Android device acknowledged in ${rtt}ms!`, "success");
          }
        } catch (e) {
          appendLog(`Device ACK received on ${topic}`, "success");
        }
      }
    });

    client.on("reconnect", () => {
      updateConnectionStatus("connecting", "Reconnecting to broker...");
      appendLog("Connection lost. Retrying...", "info");
    });

    client.on("close", () => {
      updateConnectionStatus("disconnected", "Broker Disconnected");
    });

    client.on("error", (err) => {
      updateConnectionStatus("disconnected", "Connection Error");
      appendLog(`MQTT Error: ${err.message || err}`, "error");
    });
  } catch (err) {
    updateConnectionStatus("disconnected", "Init Error");
    appendLog(`Failed to initialize MQTT client: ${err.message}`, "error");
  }
}

function updateConnectionStatus(state, message) {
  if (state === "ready") {
    badgeEl.classList.add("connected");
    connectionBadgeText.textContent = "Broker Connected";
    brokerIndicator.className = "status-indicator ready";
    brokerStatusText.textContent = message;
    triggerBtn.disabled = false;
    triggerSubtext.textContent = "READY TO SEND";
    rippleRing.classList.add("pulsing");
  } else if (state === "connecting") {
    badgeEl.classList.remove("connected");
    connectionBadgeText.textContent = "Connecting...";
    brokerIndicator.className = "status-indicator";
    brokerStatusText.textContent = message;
    triggerBtn.disabled = true;
    triggerSubtext.textContent = "CONNECTING...";
    rippleRing.classList.remove("pulsing");
  } else {
    badgeEl.classList.remove("connected");
    connectionBadgeText.textContent = "Disconnected";
    brokerIndicator.className = "status-indicator";
    brokerStatusText.textContent = message;
    triggerBtn.disabled = true;
    triggerSubtext.textContent = "OFFLINE";
    rippleRing.classList.remove("pulsing");
  }
}

// Trigger button action
function dispatchTrigger() {
  if (!client || !client.connected) {
    appendLog("Cannot dispatch: Broker is disconnected.", "error");
    return;
  }

  const startTime = Date.now();
  playLocalAlertTone(currentAlertType);

  // Button visual feedback
  triggerBtn.style.transform = "scale(0.92)";
  setTimeout(() => {
    triggerBtn.style.transform = "";
  }, 120);

  // Construct payload with alert parameters and timestamp
  const payloadObj = {
    action: "beep",
    type: currentAlertType,
    timestamp: startTime,
    channel: currentTopic
  };
  const payloadStr = JSON.stringify(payloadObj);

  triggerSubtext.textContent = "DISPATCHING...";
  brokerIndicator.className = "status-indicator sending";
  brokerStatusText.textContent = "Publishing payload...";

  // Publish with QoS 1 to guarantee delivery to the broker
  client.publish(currentTopic, payloadStr, { qos: 1 }, (err) => {
    const elapsed = Date.now() - startTime;
    latencyStat.textContent = `${elapsed} ms (pub)`;

    if (!err) {
      signalCount++;
      signalCounter.textContent = signalCount;
      localStorage.setItem("notify_signals", signalCount.toString());

      triggerSubtext.textContent = "SIGNAL SENT!";
      brokerIndicator.className = "status-indicator ready";
      brokerStatusText.textContent = "Delivered to Broker (QoS 1)";
      appendLog(`Signal [${currentAlertType.toUpperCase()}] sent to "${currentTopic}" (${elapsed}ms)`, "success");

      setTimeout(() => {
        if (client && client.connected) {
          triggerSubtext.textContent = "READY TO SEND";
          brokerStatusText.textContent = "System Ready (Connected)";
        }
      }, 1500);
    } else {
      triggerSubtext.textContent = "FAILED";
      brokerIndicator.className = "status-indicator";
      brokerStatusText.textContent = "Publish failed";
      appendLog(`Dispatch failed: ${err.message || err}`, "error");
    }
  });
}

function setupEventListeners() {
  // Main button trigger
  triggerBtn.addEventListener("click", dispatchTrigger);

  // Alert types switcher
  alertTypeBtns.forEach((btn) => {
    btn.addEventListener("click", () => {
      alertTypeBtns.forEach((b) => b.classList.remove("active"));
      btn.classList.add("active");
      currentAlertType = btn.dataset.type;
      appendLog(`Alert mode set to: ${currentAlertType.toUpperCase()}`, "info");
    });
  });

  // Broker select change
  brokerSelect.addEventListener("change", () => {
    if (brokerSelect.value === "custom") {
      customBrokerGroup.style.display = "flex";
    } else {
      customBrokerGroup.style.display = "none";
    }
  });

  // Reconnect / Save config
  reconnectBtn.addEventListener("click", () => {
    const newTopic = topicInput.value.trim();
    if (!newTopic) {
      alert("Please provide a valid MQTT topic channel.");
      return;
    }

    let newBroker = brokerSelect.value;
    if (newBroker === "custom") {
      newBroker = customBrokerInput.value.trim();
      if (!newBroker) {
        alert("Please enter a valid WebSocket broker URL (wss://...)");
        return;
      }
    }

    currentTopic = newTopic;
    currentBroker = newBroker;
    localStorage.setItem("notify_topic", currentTopic);
    localStorage.setItem("notify_broker", currentBroker);

    appendLog(`Configuration saved. Topic: ${currentTopic}`, "info");
    connectToBroker();
  });

  // Random topic generator
  generateTopicBtn.addEventListener("click", () => {
    const randomSuffix = Math.random().toString(36).substring(2, 8) + "_" + Math.floor(1000 + Math.random() * 9000);
    const generated = "shubbyy/alert/ch_" + randomSuffix;
    topicInput.value = generated;
  });

  // Copy topic
  copyTopicBtn.addEventListener("click", () => {
    navigator.clipboard.writeText(topicInput.value).then(() => {
      const origText = copyTopicBtn.textContent;
      copyTopicBtn.textContent = "Copied!";
      setTimeout(() => { copyTopicBtn.textContent = origText; }, 1200);
    }).catch(() => {
      alert("Failed to copy automatically. Please copy manually: " + topicInput.value);
    });
  });

  // Test local audio
  testAudioBtn.addEventListener("click", () => {
    playLocalAlertTone(currentAlertType);
    appendLog("Testing local browser audio synthesizer...", "info");
  });

  // Clear log
  clearLogBtn.addEventListener("click", () => {
    consoleScreen.innerHTML = "";
    appendLog("Console logs cleared.", "normal");
  });

  // Modal interactions
  openGuideLink.addEventListener("click", (e) => {
    e.preventDefault();
    guideModal.classList.add("open");
  });

  closeGuideBtn.addEventListener("click", () => {
    guideModal.classList.remove("open");
  });

  guideModal.addEventListener("click", (e) => {
    if (e.target === guideModal) {
      guideModal.classList.remove("open");
    }
  });
}

// Start
document.addEventListener("DOMContentLoaded", init);
