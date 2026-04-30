import * as vscode from "vscode";
import { I18nKey } from "../../common/I18nKey";
import { Result } from "../../common/Result";
import { GithubRepoInfoService } from "../service/GithubRepoInfoService";
import { RepoKey } from "../service/RepoKey";
import { I18n } from "../I18n";
import { ProgressUtils } from "../ProgressUtils";
import { UiUtils } from "../UiUtils";

export class GithubInlayUtils {
  static addRepoInlay(
    hints: vscode.InlayHint[],
    position: vscode.Position,
    repoKey: RepoKey,
  ): void {
    const { owner, repo } = repoKey;
    const repoId = `${owner}/${repo}`;
    const res = GithubRepoInfoService.getInstance().getRepoInfo(owner, repo);

    let label = "";
    const retryToken = `github:${owner}/${repo}`;
    const githubUrl = `https://github.com/${owner}/${repo}`;

    switch (res.result) {
      case Result.NONE:
        label = I18n.message(I18nKey.loadingGithub);
        void Promise.resolve(
          ProgressUtils.runBackground(
            `DepLens: Fetch GitHub ${owner}/${repo}`,
            () => GithubRepoInfoService.getInstance().fetchRepoInfo(owner, repo),
          ),
        ).catch((error: unknown) => {
          console.warn(`Failed to load repo info for ${repoId}`, error);
        });
        break;

      case Result.PENDING:
        label = I18n.message(I18nKey.loadingGithub);
        break;

      case Result.SUCCESS: {
        const stars = res.data?.stars ?? "0";
        const updated = res.data?.updatedDate ?? "N/A";
        label = `⭐ ${stars} • ${I18n.message(I18nKey.lastUpdated)} ${updated}`;
        break;
      }

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
