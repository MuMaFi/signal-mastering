/*
 * Design preview for signal.isolate.
 *
 * This is the app's interface, not its engine: the separation is simulated. What is
 * *not* simulated is the arithmetic behind the numbers on screen — the model sizes,
 * real-time factors, chunk lengths and stem sets are the measured values from
 * `android/app/.../ModelCatalog.kt`, so the progress copy, the ETA and the output
 * sizes are what the real run would put there. Only the clock is sped up, so a
 * six-minute render can be judged in a few seconds.
 */

const SPEED = 30;                 // virtual seconds per real second
const DOWNLOAD_BYTES_PER_S = 12e6;
const SAMPLE_RATE = 44100;

// Names, copy and sizes are ModelCatalog.kt's, word for word.
const MODELS = [
  {
    id: "roformer",
    name: "Mel-Band RoFormer",
    subtitle: "Maximum quality · SYHFT / Kim Vocal lineage",
    bytes: 741_190_540,
    rtf: 2.1,
    window: 485_100,
    stride: 8 * SAMPLE_RATE,
    stems: ["vocals", "instrumental"],
    minRamGb: 8,
    note: "Best separation available offline. Clean sibilance, very little instrumental bleed. " +
      "~2x real time — a 4-minute song takes about 8 minutes.",
  },
  {
    id: "demucs-ft",
    name: "HT-Demucs FT (vocals)",
    subtitle: "Faster · fine-tuned vocals specialist",
    bytes: 165_612_636,
    rtf: 0.47,
    window: 343_980,
    stride: 343_980 - 343_980 / 4,
    stems: ["vocals", "instrumental"],
    minRamGb: 8,
    note: "Very good vocal isolation; slightly more instrumental bleed than RoFormer. " +
      "~0.5x real time — a 4-minute song takes about 2 minutes.",
  },
  {
    id: "demucs-4",
    name: "HT-Demucs (4 stems)",
    subtitle: "Drums · bass · other · vocals",
    bytes: 165_612_636,
    rtf: 0.47,
    window: 343_980,
    stride: 343_980 - 343_980 / 4,
    stems: ["vocals", "instrumental", "drums", "bass", "other"],
    minRamGb: 8,
    note: "Full band split when you want more than vocals and backing track. " +
      "~0.5x real time — a 4-minute song takes about 2 minutes.",
  },
];

const STEM_LABELS = {
  vocals: "Vocals",
  instrumental: "Instrumental",
  drums: "Drums",
  bass: "Bass",
  other: "Other",
};

const FORMATS = [
  { id: "f32", label: "32-bit float", bytesPerSample: 4 },
  { id: "i24", label: "24-bit", bytesPerSample: 3 },
  { id: "i16", label: "16-bit", bytesPerSample: 2 },
];

const state = {
  track: null,            // { name, seconds, peaks }
  modelId: "roformer",
  stems: new Set(["vocals", "instrumental"]),
  formatId: "f32",
  installed: new Set(),   // model ids already "downloaded"
  phase: "idle",          // idle | downloading | decoding | separating | finalizing | done
  progress: 0,
  chunk: 0,
  chunks: 0,
  etaSeconds: null,
  elapsed: 0,
  results: [],
};

let timer = null;

const model = () => MODELS.find((m) => m.id === state.modelId);
const main = document.getElementById("main");
const fileInput = document.getElementById("file-input");

/* ---------------- formatting ---------------- */

// Decimal units, so a download reads the same here, in the README and on Hugging Face.
const bytes = (n) =>
  n >= 1e9 ? `${(n / 1e9).toFixed(2)} GB`
  : n >= 1e6 ? `${Math.round(n / 1e6)} MB`
  : `${Math.round(n / 1e3)} kB`;

const clock = (s) => {
  s = Math.max(0, Math.round(s));
  const m = Math.floor(s / 60);
  return m >= 60 ? `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, "0")}m`
    : m > 0 ? `${m}:${String(s % 60).padStart(2, "0")}`
    : `0:${String(s).padStart(2, "0")}`;
};

const humanEta = (s) => {
  if (s < 45) return "less than a minute";
  const m = Math.round(s / 60);
  return m < 60 ? `about ${m} minute${m === 1 ? "" : "s"}`
    : `about ${Math.floor(m / 60)}h ${m % 60}m`;
};

const icon = (id, size = 16, cls = "") =>
  `<svg class="${cls}" width="${size}" height="${size}" aria-hidden="true"><use href="#i-${id}"/></svg>`;

const esc = (s) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

/* ---------------- run plan ---------------- */

/** The same chunk arithmetic the pipeline uses, so the preview counts real chunks. */
function plan() {
  const m = model();
  const frames = (state.track?.seconds ?? 0) * SAMPLE_RATE;
  const chunks = Math.max(1, Math.ceil((frames - m.window) / m.stride) + 1);
  const download = state.installed.has(m.id) ? 0 : m.bytes / DOWNLOAD_BYTES_PER_S;
  const decode = Math.max(1.5, (state.track?.seconds ?? 0) * 0.02);
  const separate = (state.track?.seconds ?? 0) * m.rtf;
  return { chunks, download, decode, separate, finalize: 1.2 };
}

/* ---------------- rendering ---------------- */

let shownScreen = null;

function screen() {
  return state.phase === "idle" ? "setup" : state.phase === "done" ? "done" : "run";
}

function render() {
  thumbIndex = null;
  const next = screen();
  main.innerHTML = next === "setup" ? setupView() : runView();
  // Only a change of screen animates in. Re-rendering the same screen must not replay
  // the entrance, or a view refreshed every tick would sit permanently mid-fade.
  if (next !== shownScreen && next !== "setup") {
    main.querySelector(".group")?.classList.add("fade-in");
  }
  shownScreen = next;
  wire();
}

/** Per-tick progress update: text and bar in place, no DOM rebuild. */
function updateRun() {
  const copy = runCopy();
  const pct = Math.round(state.progress * 100);
  document.getElementById("run-title").textContent = copy[0];
  document.getElementById("run-sub").textContent = copy[1];
  document.getElementById("run-fill").style.width = `${(state.progress * 100).toFixed(1)}%`;
  document.getElementById("run-bar").setAttribute("aria-valuenow", pct);
  document.getElementById("run-pct").textContent = `${pct}%`;
  document.getElementById("run-eta").textContent =
    state.etaSeconds != null ? `${humanEta(state.etaSeconds)} left` : "";
}

function setupView() {
  const m = model();
  const selected = [...state.stems].filter((s) => m.stems.includes(s));
  const canRun = state.track && selected.length > 0;

  return `
    ${noticeCard()}

    <section class="group">
      <h2 class="group-header">Track</h2>
      <div class="card">
        <button class="row tappable" data-action="pick">
          <span class="row-body">
            <span class="row-title">${state.track ? esc(state.track.name) : "Choose an audio file"}</span>
            ${state.track
              ? `<span class="row-subtitle">${state.track.seconds ? clock(state.track.seconds) : "Reading…"}</span>`
              : `<span class="row-subtitle">Any format your device can play</span>`}
          </span>
          ${icon("chevron", 13, "chevron")}
        </button>
        ${state.track ? `<div class="row" style="padding-top:4px"><canvas class="waveform" id="wave"></canvas></div>` : ""}
      </div>
    </section>

    <section class="group">
      <h2 class="group-header">Model</h2>
      <div class="card">
        ${MODELS.map((candidate) => `
          <button class="row tappable" role="radio" aria-checked="${candidate.id === state.modelId}"
                  data-action="model" data-id="${candidate.id}">
            <span class="row-check">${icon("check", 17)}</span>
            <span class="row-body">
              <span class="row-title">${candidate.name}</span>
              <span class="row-subtitle">${candidate.subtitle}</span>
            </span>
            <span class="badge ${state.installed.has(candidate.id) ? "ready" : ""}">${
              state.installed.has(candidate.id) ? "Ready" : bytes(candidate.bytes)
            }</span>
          </button>`).join("")}
      </div>
      <p class="group-footer">${m.note} Needs a phone with about ${m.minRamGb} GB of RAM.</p>
    </section>

    <section class="group">
      <h2 class="group-header">Stems</h2>
      <div class="card">
        ${m.stems.map((stem) => `
          <div class="row">
            <span class="row-body"><span class="row-title">${STEM_LABELS[stem]}</span></span>
            <button class="switch" role="switch" aria-checked="${state.stems.has(stem)}"
                    aria-label="${STEM_LABELS[stem]}" data-action="stem" data-id="${stem}"></button>
          </div>`).join("")}
      </div>
      <p class="group-footer">${stemNote(m)}</p>
    </section>

    <section class="group">
      <h2 class="group-header">Format</h2>
      <div class="segmented" id="format">
        <span class="thumb"></span>
        ${FORMATS.map((f) => `<button data-action="format" data-id="${f.id}">${f.label}</button>`).join("")}
      </div>
      <p class="group-footer" id="format-note">${formatNote()}</p>
    </section>

    <button class="cta" id="start" data-action="start" ${canRun ? "" : "disabled"}>Separate</button>
    <p class="group-footer" id="start-hint" style="text-align:center" ${canRun ? "hidden" : ""}>
      Choose a track and at least one stem.</p>
  `;
}

function runCopy() {
  const m = model();
  return {
    downloading: ["Downloading model", `${m.name} · ${bytes(state.progress * m.bytes)} of ${bytes(m.bytes)}`],
    decoding: ["Reading the track", "Decoding and resampling to 44.1 kHz"],
    separating: ["Separating", `Chunk ${state.chunk} of ${state.chunks}`],
    finalizing: ["Writing files", "Almost there"],
  }[state.phase];
}

function runView() {
  if (state.phase === "done") return doneView();

  const m = model();
  const copy = runCopy();
  const determinate = state.phase !== "finalizing";

  return `
    <section class="group">
      <h2 class="group-header">Now separating</h2>
      <div class="card">
        <div class="row">
          <span class="row-body">
            <span class="row-title">${esc(state.track.name)}</span>
            <span class="row-subtitle">${clock(state.track.seconds)} · ${m.name} · ${
              [...state.stems].filter((s) => m.stems.includes(s)).map((s) => STEM_LABELS[s]).join(", ")
            }</span>
          </span>
        </div>
        <div class="row" style="flex-direction:column;align-items:stretch;padding:16px">
          <div style="display:flex;align-items:center;gap:12px">
            <span class="row-body">
              <span class="row-title" id="run-title" style="font-weight:600">${copy[0]}</span>
              <span class="row-subtitle" id="run-sub">${copy[1]}</span>
            </span>
            ${determinate ? "" : icon("spinner", 20, "spinner")}
          </div>
          <div class="progress-track" role="progressbar" aria-valuemin="0" aria-valuemax="100"
               id="run-bar" aria-valuenow="${Math.round(state.progress * 100)}">
            <div class="progress-fill ${determinate ? "" : "indeterminate"}" id="run-fill"
                 style="${determinate ? `width:${(state.progress * 100).toFixed(1)}%` : ""}"></div>
          </div>
          <div class="progress-meta">
            <span id="run-pct">${determinate ? `${Math.round(state.progress * 100)}%` : "Working"}</span>
            <span id="run-eta">${state.etaSeconds != null ? `${humanEta(state.etaSeconds)} left` : ""}</span>
          </div>
        </div>
      </div>
    </section>

    <button class="cta tinted" data-action="cancel">Cancel</button>
    <p class="group-footer" style="text-align:center">
      You can leave the app — the work keeps running.
    </p>
  `;
}

function doneView() {
  return `
    <section class="group">
      <h2 class="group-header">${state.results.length} file${state.results.length === 1 ? "" : "s"}</h2>
      <div class="card">
        ${state.results.map((r) => `
          <div class="row">
            <span class="row-body">
              <span class="row-title">${STEM_LABELS[r.stem]}</span>
              <span class="row-subtitle">${bytes(r.bytes)} · ${esc(r.name)}</span>
            </span>
            <button class="text-button" data-action="noop" style="padding-right:0">${icon("share", 17)}</button>
          </div>`).join("")}
      </div>
      <p class="group-footer">Finished in ${clock(state.elapsed)} of real time.</p>
    </section>

    <button class="cta" data-action="share-all">Share all</button>
    <div style="height:10px"></div>
    <button class="cta tinted" data-action="reset">Separate another track</button>
  `;
}

/**
 * Only the vocals-only models reconstruct the instrumental as `mix − vocals`, which is
 * what makes the pair sum back exactly. The four-stem model's instrumental is its own
 * drums + bass + other, and that sum is close to the mix but measurably not equal.
 */
function stemNote(m) {
  return m.stems.includes("drums")
    ? "Each stem is the model's own estimate. Together they come close to the original mix, not exactly."
    : "Vocals and instrumental always add back up to the original mix, exactly.";
}

function formatNote() {
  return state.formatId === "f32"
    ? "Nothing clips and nothing is rescaled — the safest choice for further editing."
    : "Peak-safe gain is applied only if a stem would otherwise clip.";
}

/** Enables Separate once there is a track and at least one stem, without a re-render. */
function syncStart() {
  const button = document.getElementById("start");
  if (!button) return;
  const canRun = Boolean(state.track) && [...state.stems].some((s) => model().stems.includes(s));
  button.disabled = !canRun;
  document.getElementById("start-hint").hidden = canRun;
}

function noticeCard() {
  return `
    <div class="notice">
      ${icon("warn", 15)}
      <span><b>Design preview.</b> The separation here is simulated — the interface,
      the copy and every number are the real ones, but no audio is processed. The
      working engine is the Android app in <code>android/</code>.</span>
    </div>`;
}

/* ---------------- interaction ---------------- */

function wire() {
  main.querySelectorAll("[data-action]").forEach((el) => {
    el.addEventListener("click", () => onAction(el.dataset.action, el.dataset.id, el));
  });
  positionThumb();
  drawWave();
}

/*
 * Switches and the segmented control are updated in place rather than re-rendered: a
 * fresh element has no previous state to transition from, so a full render would make
 * every switch snap and every thumb slide in from the left edge.
 */
function onAction(action, id, el) {
  switch (action) {
    case "pick": fileInput.click(); break;
    case "model":
      state.modelId = id;
      // Keep the selection inside what this model can produce.
      state.stems = new Set([...state.stems].filter((s) => model().stems.includes(s)));
      if (state.stems.size === 0) state.stems.add("vocals");
      render();
      break;
    case "stem":
      // Every stem can be switched off; Separate simply disables until one is back on.
      if (state.stems.has(id)) state.stems.delete(id);
      else state.stems.add(id);
      el.setAttribute("aria-checked", state.stems.has(id));
      syncStart();
      break;
    case "format":
      state.formatId = id;
      positionThumb();
      document.getElementById("format-note").textContent = formatNote();
      break;
    case "start": start(); break;
    case "cancel": stop(); render(); break;
    case "reset": state.phase = "idle"; state.results = []; render(); break;
  }
}

let thumbIndex = null;

function positionThumb() {
  const bar = document.getElementById("format");
  if (!bar) return;
  const buttons = [...bar.querySelectorAll("button")];
  const index = FORMATS.findIndex((f) => f.id === state.formatId);
  const thumb = bar.querySelector(".thumb");
  const place = (i) => {
    thumb.style.width = `${buttons[i].offsetWidth}px`;
    thumb.style.transform = `translateX(${buttons[i].offsetLeft - 2}px)`;
  };
  // Pin the thumb where it last was with transitions off, then let it travel.
  thumb.style.transition = "none";
  place(thumbIndex ?? index);
  thumb.getBoundingClientRect();
  thumb.style.transition = "";
  place(index);
  thumbIndex = index;
  buttons.forEach((b, i) => {
    b.style.fontWeight = i === index ? "600" : "500";
    b.setAttribute("aria-pressed", i === index);
  });
}

/* ---------------- waveform ---------------- */

function drawWave() {
  const canvas = document.getElementById("wave");
  if (!canvas || !state.track?.peaks) return;
  const dpr = window.devicePixelRatio || 1;
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;
  canvas.width = width * dpr;
  canvas.height = height * dpr;
  const ctx = canvas.getContext("2d");
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, width, height);

  const peaks = state.track.peaks;
  const barWidth = 2;
  const gap = 1.5;
  const count = Math.floor(width / (barWidth + gap));
  const tint = getComputedStyle(document.documentElement).getPropertyValue("--tint-fill").trim();
  ctx.fillStyle = tint;

  for (let i = 0; i < count; i++) {
    const peak = peaks[Math.floor((i / count) * peaks.length)] ?? 0;
    const h = Math.max(2, peak * (height - 2));
    ctx.beginPath();
    ctx.roundRect(i * (barWidth + gap), (height - h) / 2, barWidth, h, 1);
    ctx.fill();
  }
}

fileInput.addEventListener("change", async () => {
  const file = fileInput.files?.[0];
  if (!file) return;
  state.track = { name: file.name, seconds: 0, peaks: null };
  render();
  try {
    const context = new (window.AudioContext || window.webkitAudioContext)();
    const buffer = await context.decodeAudioData(await file.arrayBuffer());
    state.track.seconds = buffer.duration;
    state.track.peaks = envelope(buffer, 400);
    context.close();
  } catch {
    // Unsupported codec: fall back to a size estimate so the layout can still be judged.
    state.track.seconds = Math.max(30, file.size / 32_000);
    state.track.peaks = Array.from({ length: 400 }, (_, i) =>
      0.25 + 0.6 * Math.abs(Math.sin(i / 11)) * (0.6 + 0.4 * Math.sin(i / 47)));
  }
  render();
});

function envelope(buffer, buckets) {
  const data = buffer.getChannelData(0);
  const size = Math.floor(data.length / buckets);
  const peaks = [];
  for (let b = 0; b < buckets; b++) {
    let peak = 0;
    for (let i = b * size; i < (b + 1) * size; i += 16) {
      const v = Math.abs(data[i]);
      if (v > peak) peak = v;
    }
    peaks.push(peak);
  }
  const max = Math.max(...peaks, 1e-6);
  return peaks.map((p) => p / max);
}

/* ---------------- simulated run ---------------- */

/** Real seconds a preview run lasts at least, so short test files still show progress. */
const MIN_PREVIEW_SECONDS = 6;

function start() {
  const m = model();
  const p = plan();
  const total = p.download + p.decode + p.separate + p.finalize;
  const speed = Math.min(SPEED, total / MIN_PREVIEW_SECONDS);
  const startedAt = performance.now();
  let virtual = 0;

  state.phase = p.download > 0 ? "downloading" : "decoding";
  state.chunks = p.chunks;
  state.chunk = 0;
  state.progress = 0;
  render();

  clearInterval(timer);
  timer = setInterval(() => {
    virtual += (100 / 1000) * speed;
    const before = state.phase;

    if (virtual < p.download) {
      state.phase = "downloading";
      state.progress = virtual / p.download;
    } else if (virtual < p.download + p.decode) {
      state.phase = "decoding";
      state.progress = (virtual - p.download) / p.decode;
    } else if (virtual < p.download + p.decode + p.separate) {
      state.phase = "separating";
      state.progress = (virtual - p.download - p.decode) / p.separate;
      state.chunk = Math.min(p.chunks, Math.floor(state.progress * p.chunks) + 1);
    } else if (virtual < total) {
      state.phase = "finalizing";
    } else {
      finish(m, (performance.now() - startedAt) / 1000);
      return;
    }

    state.etaSeconds = Math.max(0, total - virtual);
    // Finalizing swaps the bar for a spinner, so a phase change needs the full view.
    if (state.phase !== before) render();
    else updateRun();
  }, 100);
}

function finish(m, realSeconds) {
  clearInterval(timer);
  const format = FORMATS.find((f) => f.id === state.formatId);
  const base = (state.track.name.replace(/\.[^.]+$/, "") || "track").slice(0, 40);
  const size = Math.round(state.track.seconds * SAMPLE_RATE * 2 * format.bytesPerSample) + 44;

  state.results = m.stems
    .filter((s) => state.stems.has(s))
    .map((stem) => ({ stem, name: `${base}_${stem}.wav`, bytes: size }));
  state.installed.add(m.id);
  state.elapsed = realSeconds;
  state.phase = "done";
  state.etaSeconds = null;
  render();
}

function stop() {
  clearInterval(timer);
  state.phase = "idle";
  state.etaSeconds = null;
}

/* ---------------- chrome ---------------- */

const nav = document.getElementById("nav");
const largeTitle = document.getElementById("large-title");
const onScroll = () =>
  nav.classList.toggle("scrolled", scrollY > largeTitle.offsetHeight - 44);
addEventListener("scroll", onScroll, { passive: true });
addEventListener("resize", () => { positionThumb(); drawWave(); });

const THEMES = ["auto", "light", "dark"];
document.getElementById("theme-toggle").addEventListener("click", () => {
  const current = document.documentElement.dataset.theme || "auto";
  const next = THEMES[(THEMES.indexOf(current) + 1) % THEMES.length];
  if (next === "auto") delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = next;
  drawWave();
});

/* ----------------------------------------------------------------------------
 * `?demo=<phase>` jumps straight to a state with a stand-in track, so every screen
 * can be reviewed — and screenshotted — without picking a file first.
 * Phases: downloading, decoding, separating, finalizing, done.
 * -------------------------------------------------------------------------- */
const demoPhase = new URLSearchParams(location.search).get("demo");
if (demoPhase) {
  state.track = {
    name: "04 Nightdrive (master).wav",
    seconds: 247,
    peaks: Array.from({ length: 400 }, (_, i) =>
      0.3 + 0.55 * Math.abs(Math.sin(i / 9)) * (0.55 + 0.45 * Math.sin(i / 53))),
  };
  const p = plan();
  state.chunks = p.chunks;
  if (demoPhase === "done") {
    state.installed.add(state.modelId);
    finish(model(), 11.4);
  } else {
    state.phase = demoPhase;
    state.chunk = Math.round(p.chunks * 0.42);
    state.progress = demoPhase === "downloading" ? 0.38 : demoPhase === "decoding" ? 0.71 : 0.42;
    state.etaSeconds = demoPhase === "separating" ? p.separate * 0.58 : p.download * 0.62 + p.separate;
  }
}

render();
