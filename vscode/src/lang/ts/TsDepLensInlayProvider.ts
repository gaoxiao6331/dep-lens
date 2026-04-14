import * as vscode from "vscode";
import { Result } from "../../common/Result";
import { I18n } from "../../utils/I18n";
import { I18nKey } from "../../common/I18nKey";
import { BaseDepLensInlayProvider } from "../BaseDepLensInlayProvider";
import { TsImportResolver } from "../../utils/resolver/TsImportResolver";
import { NpmInlayUtils } from "../../utils/inlay/NpmInlayUtils";
import { NpmPkgInfoService } from "../../utils/service/NpmPkgInfoService";


export class TsDepLensInlayProvider extends BaseDepLensInlayProvider {

  constructor() {
    super();
    NpmPkgInfoService.getInstance().onDidUpdatePackageInfo(() => {
      this.emitter.fire();
    });
  }

  protected isFileSupported(document: vscode.TextDocument): boolean {
    return document.languageId === "javascript" || 
           document.languageId === "typescript";
  }

  protected async provideInlayHintsForDocument(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken
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
       NpmInlayUtils.addNpmDepInlay(
         document,
         hints,
         pkg,
         position
       );
    }

    return hints;
  }

  private async createNpmHint(
    document: vscode.TextDocument,
    pkgName: string,
    position: vscode.Position
  ): Promise<vscode.InlayHint | null> {
    const res = NpmPkgInfoService.getInstance().getPackageInfo(pkgName);

    let label = "";
    if (res.result === Result.NONE) {
      label = I18n.message(I18nKey.loadingNpmMeta);
      NpmPkgInfoService.getInstance().fetchPackageInfo(pkgName);
    } else if (res.result === Result.SUCCESS && res.data) {
      const data = res.data;
      label = `📦 ${data.weeklyDownloads.toLocaleString()} weekly downloads`;
    } else {
      label = I18n.message(I18nKey.failedNpmMeta);
    }

    const hint = new vscode.InlayHint(position, `  ${label}`);
    hint.tooltip = label;

    return hint;
  }
}