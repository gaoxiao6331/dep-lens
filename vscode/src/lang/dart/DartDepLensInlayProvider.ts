import * as vscode from "vscode";
import { BaseDepLensInlayProvider } from "../BaseDepLensInlayProvider";
import { PubInlayUtils } from "../../utils/inlay/PubInlayUtils";
import { DartImportResolver } from "../../utils/resolver/DartImportResolver";
import { GithubRepoInfoService } from "../../utils/service/GithubRepoInfoService";
import { PubPkgInfoService } from "../../utils/service/PubPkgInfoService";

export class DartDepLensInlayProvider extends BaseDepLensInlayProvider {
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
    return document.languageId === "dart";
  }

  protected async provideInlayHintsForDocument(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken,
  ): Promise<vscode.InlayHint[]> {
    const hints: vscode.InlayHint[] = [];

    for (let line = range.start.line; line <= range.end.line; line++) {
      if (token.isCancellationRequested) {
        return hints;
      }

      const text = document.lineAt(line).text;
      if (!DartImportResolver.isImport(text)) {
        continue;
      }

      const dep = DartImportResolver.getDepName(text);
      if (
        !dep ||
        DartImportResolver.isLocalImport(dep) ||
        !DartImportResolver.isPackageImport(dep)
      ) {
        continue;
      }

      const pkg = DartImportResolver.getPkgName(dep);
      const position = new vscode.Position(line, text.length);
      PubInlayUtils.addPubDepInlay(hints, pkg, position);
    }

    return hints;
  }
}
