import type * as vscode from "vscode";
import { GithubInlayUtils } from "../../utils/inlay/GithubInlayUtils";
import { JvmDefinitionResolver } from "../../utils/resolver/JvmDefinitionResolver";
import { MavenRepoResolver } from "../../utils/resolver/MavenRepoResolver";
import { GithubRepoInfoService } from "../../utils/service/GithubRepoInfoService";
import { BaseDepLensInlayProvider } from "../BaseDepLensInlayProvider";

const IMPORT_REGEX = /^\s*import\s+([a-zA-Z0-9_.*]+)\s*$/gm;

export class KotlinDepLensInlayProvider extends BaseDepLensInlayProvider {
  constructor() {
    super();
    GithubRepoInfoService.getInstance().onDidUpdateRepoInfo(() => {
      this.emitter.fire();
    });
  }

  protected isFileSupported(document: vscode.TextDocument): boolean {
    return document.languageId === "kotlin" && !document.fileName.endsWith(".gradle.kts");
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
        importName.startsWith("kotlin.") ||
        importName.startsWith("java.") ||
        importName.startsWith("javax.") ||
        importName.startsWith("android.") ||
        importName.startsWith("androidx.")
      ) {
        continue;
      }

      const importEnd = document.positionAt(match.index + match[0].length);
      if (!range.contains(importEnd)) {
        continue;
      }

      const lastDot = importName.lastIndexOf(".");
      const lastSegment = lastDot < 0 ? importName : importName.slice(lastDot + 1);
      if (!lastSegment || lastSegment === "*") {
        continue;
      }

      const symbolPosition = document.positionAt(
        match.index + match[0].indexOf(importName) + (lastDot < 0 ? 0 : lastDot + 1),
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
}
