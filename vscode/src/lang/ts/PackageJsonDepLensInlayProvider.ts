import * as vscode from "vscode";
import { Result } from "../../common/Result";
import { I18n } from "../../utils/I18n";
import { I18nKey } from "../../common/I18nKey";
import { BaseDepLensInlayProvider } from "../BaseDepLensInlayProvider";
import { NpmInlayUtils } from "../../utils/inlay/NpmInlayUtils";
import { NpmPkgInfoService } from "../../utils/service/NpmPkgInfoService";
import { GithubRepoInfoService } from "../../utils/service/GithubRepoInfoService";

const DEP_SECTIONS = new Set([
  "dependencies",
  "devDependencies",
  "peerDependencies",
  "optionalDependencies",
  "bundledDependencies",
  "bundleDependencies"
]);

export class PackageJsonDepLensInlayProvider extends BaseDepLensInlayProvider {

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
    return document.fileName.endsWith("package.json");
  }

  protected async provideInlayHintsForDocument(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken
  ): Promise<vscode.InlayHint[]> {
    const hints: vscode.InlayHint[] = [];
    const text = document.getText();
    
    try {
      const json = JSON.parse(text);
      
      // 检查每个依赖区块
      for (const sectionName of DEP_SECTIONS) {
        const deps = json[sectionName];
        if (!deps || typeof deps !== "object") continue;

        // 查找对应的区块在文件中的位置
        const sectionRegex = new RegExp(`"${sectionName}"\\s*:\\s*{`);
        const match = text.match(sectionRegex);
        if (!match) continue;

        // 为每个依赖项创建提示
        for (const [pkgName, version] of Object.entries(deps)) {
          const versionStr = version as string;
          const pkgRegex = new RegExp(`"${this.escapeRegExp(pkgName)}"\\s*:\\s*"${this.escapeRegExp(versionStr)}"`);
          const pkgMatch = text.match(pkgRegex);
          
          if (pkgMatch && pkgMatch.index !== undefined) {
            const endQuoteIndex = text.indexOf('"', pkgMatch.index + pkgMatch[0].length - 1);
            if (endQuoteIndex !== -1) {
              const pos = document.positionAt(endQuoteIndex + 1);
              NpmInlayUtils.addNpmDepInlay(
                hints,
                pkgName,
                pos
              );
            }
          }
        }
      }
    } catch (e) {
      // JSON 解析错误，不生成提示
    }

    return hints;
  }

  private async createNpmHint(
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

  private escapeRegExp(str: string): string {
    return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }
}
