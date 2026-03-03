import { promises as fs } from "node:fs";
import * as path from "node:path";
import * as vscode from "vscode";
import type en from "../../config/i18n/en_US.json";
import { logger } from "./logger";

export type Text = typeof en;

function vscodeLangToJetBrainsLocale(lang: string): string {
  if (!lang) return "en_US";

  const parts = lang.split("-");

  if (parts.length === 1) {
    // 例如 ja / fr
    return parts[0];
  }

  const language = parts[0].toLowerCase();
  const region = parts[1].toUpperCase();

  return `${language}_${region}`;
}

let text: Text = {} as Text;

let loaded = false;

export async function loadLocale(context: vscode.ExtensionContext) {
  const lang = vscode.env.language;
  const configPath = path.join(context.extensionPath, "../config/i18n");
  const langPath = path.join(configPath, `${vscodeLangToJetBrainsLocale(lang)}.json`);
  const enPath = path.join(configPath, "en_US.json");

  try {
    const filePath = (await fileExists(langPath)) ? langPath : enPath;
    const data = await fs.readFile(filePath, "utf-8");
    text = JSON.parse(data);
  } catch {
    text = {} as Text;
    logger.error(`Failed to load locale file for language ${lang}`);
  } finally {
    loaded = true;
  }
}

async function fileExists(p: string) {
  try {
    await fs.access(p);
    return true;
  } catch {
    return false;
  }
}

export function t(key: keyof Text): string {
  if (!loaded) throw new Error("i18n not loaded");
  return text[key] || key;
}
