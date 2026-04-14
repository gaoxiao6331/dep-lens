import * as vscode from "vscode";
import { Result } from "../../common/Result";
import { GithubRepoInfoService } from "../../utils/service/GithubRepoInfoService";
import { I18n } from "../../utils/I18n";
import { BaseDepLensInlayProvider } from "../BaseDepLensInlayProvider";
import { GithubInlayUtils } from "../../utils/inlay/GithubInlayUtils";

const reGithubImport = /"github\.com\/([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)(?:\/[^"]*)?"/;
const reGithubMod = /github\.com\/([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)/;

export class GoDepLensInlayProvider extends BaseDepLensInlayProvider {

  constructor() {
    super();
    GithubRepoInfoService.getInstance().onDidUpdateRepoInfo(() => {
      this.emitter.fire();
    });
  }

  protected isFileSupported(document: vscode.TextDocument): boolean {
    return document.languageId === "go" || document.fileName.endsWith("go.mod");
  }

  protected async provideInlayHintsForDocument(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken,
  ): Promise<vscode.InlayHint[]> {
    const hints: vscode.InlayHint[] = [];
    const start = range.start.line;
    const end = range.end.line;
    const isGoMod = document.fileName.endsWith("go.mod");

    for (let line = start; line <= end; line++) {
      const text = document.lineAt(line).text;

      if (isGoMod && text.includes("// indirect")) {
        continue;
      }

      const m = text.match(isGoMod ? reGithubMod : reGithubImport);
      if (!m) continue;
      const owner = m[1];
      const repo = m[2];

      const endIdx = isGoMod ? text.length : text.lastIndexOf('"');
      if (endIdx < 0) continue;
      const pos = new vscode.Position(line, endIdx + (isGoMod ? 0 : 1));

      GithubInlayUtils.addGithubInlay(
        document,
        hints,
        pos,
        owner,
        repo
      );
    }
    return hints;
  }
}
