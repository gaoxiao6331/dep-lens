import type * as vscode from "vscode";
import { NpmInlayUtils } from "../../utils/inlay/NpmInlayUtils";
import { GithubRepoInfoService } from "../../utils/service/GithubRepoInfoService";
import { NpmPkgInfoService } from "../../utils/service/NpmPkgInfoService";
import { BaseDepLensInlayProvider } from "../BaseDepLensInlayProvider";

const DEP_SECTION_NAMES = new Set([
  "dependencies",
  "devDependencies",
  "peerDependencies",
  "optionalDependencies",
  "bundledDependencies",
  "bundleDependencies",
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
    token: vscode.CancellationToken,
  ): Promise<vscode.InlayHint[]> {
    const hints: vscode.InlayHint[] = [];
    const text = document.getText();

    try {
      const json = JSON.parse(text);

      // 检查每个依赖区块
      for (const sectionName of DEP_SECTION_NAMES) {
        const deps = json[sectionName];
        if (!deps || typeof deps !== "object") continue;

        // 查找对应的区块在文件中的位置
        const sectionRegex = new RegExp(`"${sectionName}"\\s*:\\s*{`);
        const match = text.match(sectionRegex);
        if (!match) continue;

        // 为每个依赖项创建提示
        for (const [pkgName, version] of Object.entries(deps)) {
          const versionStr = version as string;
          const pkgRegex = new RegExp(
            `"${this.escapeRegExp(pkgName)}"\\s*:\\s*"${this.escapeRegExp(versionStr)}"`,
          );
          const pkgMatch = text.match(pkgRegex);

          if (pkgMatch && pkgMatch.index !== undefined) {
            const endQuoteIndex = text.indexOf('"', pkgMatch.index + pkgMatch[0].length - 1);
            if (endQuoteIndex !== -1) {
              const pos = document.positionAt(endQuoteIndex + 1);
              NpmInlayUtils.addNpmDepInlay(hints, pkgName, pos);
            }
          }
        }
      }
    } catch (e) {
      // JSON 解析错误，不生成提示
    }

    return hints;
  }

  private escapeRegExp(str: string): string {
    return str.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }
}
