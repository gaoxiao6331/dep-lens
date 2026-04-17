import * as vscode from "vscode";
import { GithubRepoInfoService } from "../../utils/service/GithubRepoInfoService";
import { BaseDepLensInlayProvider } from "../BaseDepLensInlayProvider";
import { GithubInlayUtils } from "../../utils/inlay/GithubInlayUtils";
import { MavenRepoResolver } from "../../utils/resolver/MavenRepoResolver";

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

      const symbolPosition = document.positionAt(match.index + match[0].indexOf(importName));
      const resolvedPath = await this.resolveDefinitionPath(document.uri, symbolPosition);
      const repoKey = await MavenRepoResolver.repoKeyFromResolvedPath(resolvedPath);
      if (!repoKey) {
        continue;
      }

      GithubInlayUtils.addRepoInlay(hints, importEnd, repoKey);
    }

    return hints;
  }

  private async resolveDefinitionPath(
    documentUri: vscode.Uri,
    position: vscode.Position,
  ): Promise<string | null> {
    const definitions = await vscode.commands.executeCommand<(vscode.Location | vscode.LocationLink)[]>(
      "vscode.executeDefinitionProvider",
      documentUri,
      position,
    );

    const first = definitions?.[0];
    if (!first) {
      return null;
    }

    return "targetUri" in first ? first.targetUri.fsPath : first.uri.fsPath;
  }
}
