import type * as vscode from "vscode";
import { GithubInlayUtils } from "../../utils/inlay/GithubInlayUtils";
import { JvmDefinitionResolver } from "../../utils/resolver/JvmDefinitionResolver";
import { MavenRepoResolver } from "../../utils/resolver/MavenRepoResolver";
import { GithubRepoInfoService } from "../../utils/service/GithubRepoInfoService";
import { BaseDepLensInlayProvider } from "../BaseDepLensInlayProvider";

const IMPORT_REGEX = /^\s*import\s+(?:static\s+)?([a-zA-Z0-9_.*]+)\s*;/gm;

export class JavaDepLensInlayProvider extends BaseDepLensInlayProvider {
  constructor() {
    super();
    GithubRepoInfoService.getInstance().onDidUpdateRepoInfo(() => {
      this.emitter.fire();
    });
  }

  protected isFileSupported(document: vscode.TextDocument): boolean {
    return document.languageId === "java";
  }

  protected async provideInlayHintsForDocument(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken,
  ): Promise<vscode.InlayHint[]> {
    const hints: vscode.InlayHint[] = [];
    const text = document.getText();

    for (const match of text.matchAll(IMPORT_REGEX)) {
      if (match.index === undefined) {
        continue;
      }

      const importName = match[1];
      if (
        importName.startsWith("java.") ||
        importName.startsWith("javax.") ||
        importName.startsWith("sun.") ||
        importName.startsWith("com.sun.")
      ) {
        continue;
      }

      const importEnd = document.positionAt(match.index + match[0].length);
      if (!range.contains(importEnd)) {
        continue;
      }

      const symbolOffset = this.computeSymbolOffset(importName);
      if (symbolOffset === null) {
        continue;
      }

      const symbolPosition = document.positionAt(
        match.index + match[0].indexOf(importName) + symbolOffset,
      );
      const resolvedPath = await JvmDefinitionResolver.resolveJarPath(document.uri, symbolPosition);
      if (!resolvedPath) {
        continue;
      }
      const repoKey = await MavenRepoResolver.repoKeyFromResolvedPath(resolvedPath);
      if (!repoKey) {
        continue;
      }

      GithubInlayUtils.addRepoInlay(hints, importEnd, repoKey);
    }

    return hints;
  }

  /**
   * 返回 importName 内“最后一个可解析段”的起始偏移。
   * 例如 `foo.bar.Baz` → 8（指向 `Baz`），`foo.bar.*` → null（通配符不解析）。
   * 这样 `executeDefinitionProvider` 才能命中具体类/成员，从而拿到所在 JAR。
   */
  private computeSymbolOffset(importName: string): number | null {
    const lastDot = importName.lastIndexOf(".");
    const lastSegment = lastDot < 0 ? importName : importName.slice(lastDot + 1);
    if (!lastSegment || lastSegment === "*") {
      return null;
    }
    return lastDot < 0 ? 0 : lastDot + 1;
  }
}
