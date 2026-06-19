import type * as vscode from "vscode";
import { I18nKey } from "../../common/I18nKey";
import { Result } from "../../common/Result";
import { I18n } from "../I18n";
import { ProgressUtils } from "../ProgressUtils";
import { UiUtils } from "../UiUtils";
import { GithubRepoInfoService } from "../service/GithubRepoInfoService";
import { NpmPkgInfoService } from "../service/NpmPkgInfoService";

export class NpmInlayUtils {
  static addNpmDepInlay(hints: vscode.InlayHint[], pkg: string, position: vscode.Position): void {
    const npmRes = NpmPkgInfoService.getInstance().getPackageInfo(pkg);

    switch (npmRes.result) {
      case Result.NONE:
        if (
          NpmPkgInfoService.getInstance().hasFailure(pkg) &&
          !NpmPkgInfoService.getInstance().isRequestRunning(pkg)
        ) {
          UiUtils.addInlay(
            hints,
            position,
            I18n.message(I18nKey.failedNpmMeta),
            undefined,
            undefined,
            `npm:${pkg}`,
          );
          return;
        }

        void Promise.resolve(
          ProgressUtils.runBackground(`DepLens: Fetch npm ${pkg}`, () =>
            NpmPkgInfoService.getInstance().fetchPackageInfo(pkg),
          ),
        ).catch((error: unknown) => {
          console.warn(`Failed to load npm info for ${pkg}`, error);
        });
        UiUtils.addInlay(
          hints,
          position,
          I18n.message(I18nKey.loadingNpmMeta),
          undefined,
          undefined,
          `npm:${pkg}`,
        );
        return;

      case Result.PENDING:
        UiUtils.addInlay(
          hints,
          position,
          I18n.message(I18nKey.loadingNpmMeta),
          undefined,
          undefined,
          `npm:${pkg}`,
        );
        return;

      case Result.SUCCESS:
        break;

      case Result.FAILURE:
        UiUtils.addInlay(
          hints,
          position,
          I18n.message(I18nKey.failedNpmMeta),
          undefined,
          undefined,
          `npm:${pkg}`,
        );
        return;
    }

    const npmInfo = npmRes.data;
    if (!npmInfo) {
      UiUtils.addInlay(
        hints,
        position,
        I18n.message(I18nKey.failedNpmMeta),
        undefined,
        undefined,
        `npm:${pkg}`,
      );
      return;
    }

    const url = npmInfo.githubUrl;
    if (!url) {
      UiUtils.addInlay(
        hints,
        position,
        I18n.message(I18nKey.noGithubUrl),
        undefined,
        undefined,
        `npm:${pkg}`,
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
        `npm:${pkg}`,
      );
      return;
    }

    const repoRes = GithubRepoInfoService.getInstance().getRepoInfo(repoKey.owner, repoKey.repo);
    switch (repoRes.result) {
      case Result.NONE:
        void Promise.resolve(
          ProgressUtils.runBackground(
            `DepLens: Fetch GitHub ${repoKey.owner}/${repoKey.repo}`,
            () => GithubRepoInfoService.getInstance().fetchRepoInfo(repoKey.owner, repoKey.repo),
          ),
        ).catch((error: unknown) => {
          console.warn(`Failed to load repo info for ${repoKey.owner}/${repoKey.repo}`, error);
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
