#!/usr/bin/env node
/*
 * WCAG AA audit of every text/background pair the interface draws.
 *
 * The colours are read out of app.css, not typed in by hand, and translucent tokens
 * are composited over the surface they actually sit on — a secondary label is 60 %
 * grey *over white*, and it is that blend, not the token, a reader has to see. An
 * earlier version of this file checked hand-approximated hex values instead and
 * reported a pass that the real blend did not earn.
 *
 * Run: node web/contrast.mjs
 */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const css = readFileSync(join(dirname(fileURLToPath(import.meta.url)), "app.css"), "utf8");

/** Custom properties declared directly in the first block matching `selector`. */
function tokens(selector) {
  const start = css.indexOf(selector);
  if (start < 0) throw new Error(`no block for ${selector}`);
  const open = css.indexOf("{", start);
  const close = css.indexOf("}", open);
  const out = {};
  for (const [, name, value] of css.slice(open + 1, close).matchAll(/--([\w-]+):\s*([^;]+);/g)) {
    out[name] = value.trim();
  }
  return out;
}

const light = tokens(":root {\n  color-scheme");
const dark = { ...light, ...tokens(':root[data-theme="dark"]') };

function parse(value) {
  const hex = value.match(/^#([0-9a-f]{6})$/i);
  if (hex) {
    const n = parseInt(hex[1], 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255, 1];
  }
  const rgba = value.match(/^rgba?\(([^)]+)\)$/);
  if (rgba) {
    const [r, g, b, a = "1"] = rgba[1].split(",").map((s) => s.trim());
    return [+r, +g, +b, +a];
  }
  throw new Error(`cannot parse colour ${value}`);
}

/** Source-over: `top` painted on an opaque `bottom`. */
const over = ([r, g, b, a], [R, G, B]) =>
  [r * a + R * (1 - a), g * a + G * (1 - a), b * a + B * (1 - a), 1];

const mix = (color, percent) => { const [r, g, b] = color; return [r, g, b, percent / 100]; };

const luminance = ([r, g, b]) => {
  const lin = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
};

const ratio = (a, b) => {
  const [x, y] = [luminance(a), luminance(b)];
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
};

const hex = ([r, g, b]) => "#" + [r, g, b].map((v) => Math.round(v).toString(16).padStart(2, "0")).join("");

function audit(scheme, t) {
  const c = (name) => parse(t[name]);
  const grouped = c("bg-grouped");
  const card = over(c("bg-elevated"), grouped);
  const badge = over(c("fill-track"), card);
  const tinted = over(mix(c("tint"), 16), grouped);   // .cta.tinted
  const ready = over(mix(c("tint"), 14), card);       // .badge.ready
  const segment = over(c("segment-thumb"), over(c("fill-track"), grouped));

  // [where it appears, text colour, surface]
  return [
    ["row title on card", c("label"), card],
    ["secondary label on card", c("label-secondary"), card],
    ["group header / footer", c("label-secondary"), grouped],
    ["size badge", c("label-secondary"), badge],
    ["tint text on card", c("tint"), card],
    ["nav button on grouped bg", c("tint"), grouped],
    ["filled button label", c("on-tint"), c("tint-fill")],
    ["tinted button label", c("tint"), tinted],
    ["ready badge", c("tint"), ready],
    ["selected segment label", c("label"), segment],
    ["unselected segment label", c("label"), over(c("fill-track"), grouped)],
    ["warning footer", c("orange"), grouped],
    ["destructive text on card", c("red"), card],
  ].map(([where, fg, bg]) => {
    const drawn = over(fg, bg);
    return { scheme, where, fg: hex(drawn), bg: hex(bg), ratio: ratio(drawn, bg) };
  });
}

const rows = [...audit("light", light), ...audit("dark", dark)];
let failures = 0;
for (const r of rows) {
  const ok = r.ratio >= 4.5;
  if (!ok) failures++;
  console.log(
    `${r.scheme.padEnd(6)} ${r.where.padEnd(26)} ${r.fg} on ${r.bg}  ${r.ratio.toFixed(2).padStart(5)}:1  ${ok ? "ok" : "FAILS"}`,
  );
}
console.log(failures === 0 ? "\nAll pairs meet WCAG AA (4.5:1)." : `\n${failures} pair(s) below WCAG AA.`);
process.exit(failures === 0 ? 0 : 1);
