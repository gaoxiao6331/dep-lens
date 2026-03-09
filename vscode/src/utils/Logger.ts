import * as vscode from "vscode";
import { NAME } from "../common/Const";

export type LogLevel = "debug" | "info" | "warn" | "error";

const levelPriority: Record<LogLevel, number> = {
  debug: 0,
  info: 1,
  warn: 2,
  error: 3,
};

export class Logger {
  private static instance: Logger;
  private channel: vscode.OutputChannel;
  private level: LogLevel;

  private constructor() {
    this.channel = vscode.window.createOutputChannel(NAME);
    this.level = this.getConfiguredLevel();
  }

  static getInstance() {
    if (!Logger.instance) {
      Logger.instance = new Logger();
    }
    return Logger.instance;
  }

  private getConfiguredLevel(): LogLevel {
    const config = vscode.workspace.getConfiguration("myExtension");
    return config.get<LogLevel>("logLevel", "info");
  }

  private shouldLog(level: LogLevel): boolean {
    return levelPriority[level] >= levelPriority[this.level];
  }

  private format(level: LogLevel, message: string): string {
    const time = new Date().toISOString();
    return `[${time}] [${level.toUpperCase()}] ${message}`;
  }

  private write(level: LogLevel, message: string) {
    if (!this.shouldLog(level)) return;

    this.channel.appendLine(this.format(level, message));
  }

  debug(message: string) {
    this.write("debug", message);
  }

  info(message: string) {
    this.write("info", message);
  }

  warn(message: string) {
    this.write("warn", message);
  }

  error(message: string, showPopup = false) {
    this.write("error", message);
    if (showPopup) {
      vscode.window.showErrorMessage(message);
    }
  }

  show() {
    this.channel.show();
  }

  dispose() {
    this.channel.dispose();
  }
}

export const logger = Logger.getInstance();
