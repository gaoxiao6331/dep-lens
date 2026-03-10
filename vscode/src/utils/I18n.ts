import * as vscode from "vscode";
import type en from "../../../config/i18n/en_US.json";
import { logger } from "./Logger";

export type Text = typeof en;

function vscodeLangToJetBrainsLocale(lang: string): string {
  if (!lang) return "en_US";

  const parts = lang.split("-");

  if (parts.length === 1) {
    return parts[0];
  }

  const language = parts[0].toLowerCase();
  const region = parts[1].toUpperCase();

  return `${language}_${region}`;
}

let text: Text = {} as Text;
let loaded = false;

async function loadLocale(context: vscode.ExtensionContext) {
  const lang = vscode.env.language;

  const i18nDir = vscode.Uri.joinPath(context.extensionUri, "config", "i18n");

  const langFile = vscode.Uri.joinPath(
    i18nDir,
    `${vscodeLangToJetBrainsLocale(lang)}.json`
  );

  const enFile = vscode.Uri.joinPath(i18nDir, "en_US.json");

  try {
    const fileUri = (await pathExists(langFile)) ? langFile : enFile;

    const data = await vscode.workspace.fs.readFile(fileUri);
    text = JSON.parse(Buffer.from(data).toString("utf8"));

    logger.info(`Loaded locale file for language ${lang} from ${fileUri.fsPath}`);
  } catch (err) {
    text = {} as Text;
    logger.error(`Failed to load locale file for language ${lang} from ${i18nDir.fsPath}`, true); 
  } finally {
    loaded = true;
  }
}

async function pathExists(uri: vscode.Uri) {
  try {
    await vscode.workspace.fs.stat(uri);
    return true;
  } catch {
    return false;
  }
}

function t(key: keyof Text): string {
  if (!loaded) return key;
  return text[key] || key;
}

export const I18n = {
  loadLocale,

  message: (key: string, ...params: any[]) => {
    let msg = t(key as keyof Text);

    params.forEach((param, index) => {
      msg = msg.replace(`{${index}}`, String(param));
    });

    return msg;
  },
};