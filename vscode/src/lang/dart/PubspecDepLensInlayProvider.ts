import * as vscode from "vscode";
import { BaseDepLensInlayProvider } from "../BaseDepLensInlayProvider";
import { PubInlayUtils } from "../../utils/inlay/PubInlayUtils";
import { GithubRepoInfoService } from "../../utils/service/GithubRepoInfoService";
import { PubPkgInfoService } from "../../utils/service/PubPkgInfoService";

const DEP_SECTION_NAMES = new Set([
  "dependencies",
  "dev_dependencies",
  "dependency_overrides",
]);

interface PubspecDependencyEntry {
  line: number;
  name: string;
}

export class PubspecDepLensInlayProvider extends BaseDepLensInlayProvider {
  constructor() {
    super();
    PubPkgInfoService.getInstance().onDidUpdatePackageInfo(() => {
      this.emitter.fire();
    });
    GithubRepoInfoService.getInstance().onDidUpdateRepoInfo(() => {
      this.emitter.fire();
    });
  }

  protected isFileSupported(document: vscode.TextDocument): boolean {
    return document.fileName.endsWith("pubspec.yaml");
  }

  protected async provideInlayHintsForDocument(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken,
  ): Promise<vscode.InlayHint[]> {
    const hints: vscode.InlayHint[] = [];

    for (const entry of this.collectDependencyEntries(document)) {
      if (token.isCancellationRequested) {
        return hints;
      }

      if (entry.line < range.start.line || entry.line > range.end.line) {
        continue;
      }

      const lineText = document.lineAt(entry.line).text;
      const position = new vscode.Position(entry.line, lineText.length);
      PubInlayUtils.addPubDepInlay(hints, entry.name, position);
    }

    return hints;
  }

  private collectDependencyEntries(
    document: vscode.TextDocument,
  ): PubspecDependencyEntry[] {
    const entries: PubspecDependencyEntry[] = [];
    let sectionIndent: number | undefined;

    for (let line = 0; line < document.lineCount; line++) {
      const text = document.lineAt(line).text;
      const trimmed = text.trim();
      const indent = this.getIndent(text);

      const sectionMatch = text.match(
        /^(\s*)(dependencies|dev_dependencies|dependency_overrides):\s*(?:#.*)?$/,
      );
      if (sectionMatch) {
        if (!DEP_SECTION_NAMES.has(sectionMatch[2])) {
          continue;
        }
        sectionIndent = sectionMatch[1].length;
        continue;
      }

      if (sectionIndent === undefined) {
        continue;
      }

      if (trimmed && !trimmed.startsWith("#") && indent <= sectionIndent) {
        sectionIndent = undefined;
        line--;
        continue;
      }

      if (!trimmed || trimmed.startsWith("#")) {
        continue;
      }

      const depMatch = text.match(/^(\s*)([A-Za-z0-9_]+):\s*(.*)$/);
      if (!depMatch) {
        continue;
      }

      const depIndent = depMatch[1].length;
      if (depIndent !== sectionIndent + 2) {
        continue;
      }

      const name = depMatch[2];
      const value = depMatch[3].trim();

      if (name === "flutter") {
        continue;
      }

      if (this.isUnsupportedInlineSource(value)) {
        continue;
      }

      if (!value && this.hasUnsupportedNestedSource(document, line, depIndent)) {
        continue;
      }

      entries.push({ line, name });
    }

    return entries;
  }

  private hasUnsupportedNestedSource(
    document: vscode.TextDocument,
    startLine: number,
    parentIndent: number,
  ): boolean {
    for (let line = startLine + 1; line < document.lineCount; line++) {
      const text = document.lineAt(line).text;
      const trimmed = text.trim();

      if (!trimmed || trimmed.startsWith("#")) {
        continue;
      }

      const indent = this.getIndent(text);
      if (indent <= parentIndent) {
        break;
      }

      if (/^(sdk|path|git):\b/.test(trimmed)) {
        return true;
      }
    }

    return false;
  }

  private isUnsupportedInlineSource(value: string): boolean {
    return /\b(sdk|path|git)\s*:/.test(value);
  }

  private getIndent(line: string): number {
    return line.length - line.trimStart().length;
  }
}
