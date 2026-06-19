import * as vscode from "vscode";
import { NpmInlayUtils } from "../../utils/inlay/NpmInlayUtils";
import { TsImportResolver } from "../../utils/resolver/TsImportResolver";
import { GithubRepoInfoService } from "../../utils/service/GithubRepoInfoService";
import { NpmPkgInfoService } from "../../utils/service/NpmPkgInfoService";
import { BaseDepLensInlayProvider } from "../BaseDepLensInlayProvider";

export class TsDepLensInlayProvider extends BaseDepLensInlayProvider {
  constructor() {
    super();
    NpmPkgInfoService.getInstance().onDidUpdatePackageInfo(() => {
      this.emitter.fire();
    });
    GithubRepoInfoService.getInstance().onDidUpdateRepoInfo(() => {
      this.emitter.fire();
    });
  }

  protected isFileSupported(document: vscode.TextDocument): boolean {
    return document.languageId === "javascript" || document.languageId === "typescript";
  }

  protected async provideInlayHintsForDocument(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken,
  ): Promise<vscode.InlayHint[]> {
    const hints: vscode.InlayHint[] = [];
    const start = range.start.line;
    const end = range.end.line;

    for (let line = start; line <= end; line++) {
      const text = document.lineAt(line).text;

      if (!TsImportResolver.isImport(text)) {
        continue;
      }

      const dep = TsImportResolver.getDepName(text);
      if (!dep || TsImportResolver.isLocalImport(dep)) {
        continue;
      }

      const pkg = TsImportResolver.getPkgName(dep);
      const position = new vscode.Position(line, text.length);
      NpmInlayUtils.addNpmDepInlay(hints, pkg, position);
    }

    return hints;
  }
}
