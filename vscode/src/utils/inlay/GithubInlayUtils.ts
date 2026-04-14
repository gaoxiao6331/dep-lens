import * as vscode from "vscode";
import { I18nKey } from "../../common/I18nKey";
import { Result } from "../../common/Result";
import { GithubRepoInfoService } from "../service/GithubRepoInfoService";
import { I18n } from "../I18n";
import { ProgressUtils } from "../ProgressUtils";
import { UiUtils } from "../UiUtils";

export class GithubInlayUtils {
  static addGithubInlay(
    document: vscode.TextDocument,
    hints: vscode.InlayHint[],
    position: vscode.Position,
    owner: string,
    repo: string
  ): void {
    const repoKey = `${owner}/${repo}`;
    const res = GithubRepoInfoService.getInstance().getRepoInfo(owner, repo);

    let label = "";
    let retryToken = `github:${owner}/${repo}`;
    let githubUrl = `https://github.com/${owner}/${repo}`;

    switch (res.result) {
      case Result.NONE:
        label = I18n.message(I18nKey.loadingGithub);
        ProgressUtils.runBackground(
          `DepLens: Fetch GitHub ${owner}/${repo}`,
          () => GithubRepoInfoService.getInstance().fetchRepoInfo(owner, repo)
        ).then(() => {
          try {
            GithubRepoInfoService.getInstance().fetchRepoInfo(owner, repo);
          } catch (e) {
            console.warn(`Failed to load repo info for ${repoKey}`, e);
          }
        });
        break;

      case Result.PENDING:
        label = I18n.message(I18nKey.loadingGithub);
        break;

      case Result.SUCCESS:
        const data = res.data;
        if (data) {
          const stars = data.stars;
          const updated = data.updatedDate;
          label = `⭐ ${stars} • ${I18n.message(I18nKey.lastUpdated)} ${updated}`;
        } else {
          label = I18n.message(I18nKey.failedGithub);
        }
        break;

      case Result.FAILURE:
        label = I18n.message(I18nKey.failedGithub);
        break;
    }

    UiUtils.addInlay(
      hints,
      position,
      label,
      label,
      githubUrl,
      retryToken
    );
  }
}