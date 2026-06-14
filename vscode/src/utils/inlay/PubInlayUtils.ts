import * as vscode from "vscode";
import { I18nKey } from "../../common/I18nKey";
import { Result } from "../../common/Result";
import { GithubRepoInfoService } from "../service/GithubRepoInfoService";
import { PubPkgInfoService } from "../service/PubPkgInfoService";
import { I18n } from "../I18n";
import { ProgressUtils } from "../ProgressUtils";
import { UiUtils } from "../UiUtils";

export class PubInlayUtils {
  static addPubDepInlay(
    hints: vscode.InlayHint[],
    pkg: string,
    position: vscode.Position,
  ): void {
    const pubRes = PubPkgInfoService.getInstance().getPackageInfo(pkg);

    switch (pubRes.result) {
      case Result.NONE:
        if (
          PubPkgInfoService.getInstance().hasFailure(pkg) &&
          !PubPkgInfoService.getInstance().isRequestRunning(pkg)
        ) {
          UiUtils.addInlay(
            hints,
            position,
            I18n.message("failedPubMeta"),
            undefined,
            undefined,
            `pub:${pkg}`,
          );
          return;
        }

        void Promise.resolve(
          ProgressUtils.runBackground(`DepLens: Fetch pub ${pkg}`, () =>
            PubPkgInfoService.getInstance().fetchPackageInfo(pkg),
          ),
        ).catch((error: unknown) => {
          console.warn(`Failed to load pub info for ${pkg}`, error);
        });
        UiUtils.addInlay(
          hints,
          position,
          I18n.message("loadingPubMeta"),
          undefined,
          undefined,
          `pub:${pkg}`,
        );
        return;

      case Result.PENDING:
        UiUtils.addInlay(
          hints,
          position,
          I18n.message("loadingPubMeta"),
          undefined,
          undefined,
          `pub:${pkg}`,
        );
        return;

      case Result.SUCCESS:
        break;

      case Result.FAILURE:
        UiUtils.addInlay(
          hints,
          position,
          I18n.message("failedPubMeta"),
          undefined,
          undefined,
          `pub:${pkg}`,
        );
        return;
    }

    const pubInfo = pubRes.data;
    if (!pubInfo) {
      UiUtils.addInlay(
        hints,
        position,
        I18n.message("failedPubMeta"),
        undefined,
        undefined,
        `pub:${pkg}`,
      );
      return;
    }

    const url = pubInfo.githubUrl;
    if (!url) {
      UiUtils.addInlay(
        hints,
        position,
        I18n.message(I18nKey.noGithubUrl),
        undefined,
        undefined,
        `pub:${pkg}`,
      );
      return;
    }

    const repoKey = GithubRepoInfoService.getRepoKey(url);
    if (!repoKey) {
      UiUtils.addInlay(
        hints,
        position,
        I18n.message(I18nKey.invalidGithubUrl),
        undefined,
        undefined,
        `pub:${pkg}`,
      );
      return;
    }

    const repoRes = GithubRepoInfoService.getInstance().getRepoInfo(
      repoKey.owner,
      repoKey.repo,
    );
    switch (repoRes.result) {
      case Result.NONE:
        void Promise.resolve(
          ProgressUtils.runBackground(
            `DepLens: Fetch GitHub ${repoKey.owner}/${repoKey.repo}`,
            () =>
              GithubRepoInfoService.getInstance().fetchRepoInfo(
                repoKey.owner,
                repoKey.repo,
              ),
          ),
        ).catch((error: unknown) => {
          console.warn(
            `Failed to load repo info for ${repoKey.owner}/${repoKey.repo}`,
            error,
          );
        });
        UiUtils.addInlay(
          hints,
          position,
          I18n.message(I18nKey.loadingGithub),
          undefined,
          `https://github.com/${repoKey.owner}/${repoKey.repo}`,
          `github:${repoKey.owner}/${repoKey.repo}`,
        );
        return;

      case Result.PENDING:
        UiUtils.addInlay(
          hints,
          position,
          I18n.message(I18nKey.loadingGithub),
          undefined,
          `https://github.com/${repoKey.owner}/${repoKey.repo}`,
          `github:${repoKey.owner}/${repoKey.repo}`,
        );
        return;

      case Result.SUCCESS: {
        const stars = repoRes.data?.stars ?? "0";
        const updated = repoRes.data?.updatedDate ?? "N/A";
        UiUtils.addInlay(
          hints,
          position,
          `⭐ ${stars} • ${I18n.message(I18nKey.lastUpdated)} ${updated}`,
          undefined,
          `https://github.com/${repoKey.owner}/${repoKey.repo}`,
          `github:${repoKey.owner}/${repoKey.repo}`,
        );
        return;
      }

      case Result.FAILURE:
        UiUtils.addInlay(
          hints,
          position,
          I18n.message(I18nKey.failedGithub),
          undefined,
          `https://github.com/${repoKey.owner}/${repoKey.repo}`,
          `github:${repoKey.owner}/${repoKey.repo}`,
        );
        return;
    }
  }
}
