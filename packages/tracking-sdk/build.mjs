// Produces dist/vendocas.js: an IIFE exposing window.Vendocas (no bundler dependency).
import { readFileSync, writeFileSync } from "node:fs";
const esm = readFileSync(new URL("./dist/esm/index.js", import.meta.url), "utf8");
const body = esm.replace(/^export function /gm, "function ").replace(/^export \{[^}]*\};?$/gm, "");
const names = [...esm.matchAll(/^export function (\w+)/gm)].map((m) => m[1]);
writeFileSync(new URL("./dist/vendocas.js", import.meta.url),
  `/* Vendocas tracking SDK v0.1.0 */\n(function(){\n${body}\nwindow.Vendocas={${names.join(",")}};\n})();\n`);
console.log("dist/vendocas.js:", names.join(", "));
