import * as vscode from "vscode";
import { I18nKey } from "../../common/Const";
import { Result } from "../../common/Result";
import { GithubRepoInfoService } from "../../utils/GithubRepoInfoService";
import { I18n } from "../../utils/I18n";

const reGithubImport = /"github\.com\/([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)(?:\/[^"]*)?"/;
const reGithubMod = /github\.com\/([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)/;

export class GoDepLensInlayProvider implements vscode.InlayHintsProvider {
  private emitter = new vscode.EventEmitter<void>();
  readonly onDidChangeInlayHints = this.emitter.event;

  constructor() {
    GithubRepoInfoService.getInstance().onDidUpdateRepoInfo(() => {
      this.emitter.fire();
    });
  }

  async provideInlayHints(
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

      const res = GithubRepoInfoService.getInstance().getRepoInfo(owner, repo);

      let label = "";
      if (res.result === Result.NONE) {
        label = I18n.message(I18nKey.loading);
        GithubRepoInfoService.getInstance().fetchRepoInfo(owner, repo);
      } else if (res.result === Result.SUCCESS && res.data) {
        const data = res.data;
        const stars = data.stars;
        const updated = data.updatedDate;
        label = `⭐ ${stars} • ${I18n.message(I18nKey.lastUpdated)} ${updated}`;
      } else {
        label = I18n.message(I18nKey.failedToLoad);
      }

      const hint = new vscode.InlayHint(pos, `  ${label}`);
      hint.tooltip = label;
      hints.push(hint);
    }
    return hints;
  }
}
