import * as vscode from "vscode";
import { I18nKey } from "../../common/I18nKey";
import { Result } from "../../common/Result";
import { GithubRepoInfoService } from "../service/GithubRepoInfoService";
import { NpmPkgInfoService } from "../service/NpmPkgInfoService";
import { I18n } from "../I18n";
import { ProgressUtils } from "../ProgressUtils";
import { UiUtils } from "../UiUtils";

export class NpmInlayUtils {
  static addNpmDepInlay(
    document: vscode.TextDocument,
    hints: vscode.InlayHint[],
    pkg: string,
    position: vscode.Position
  ): void {
    const npmRes = NpmPkgInfoService.getInstance().getPackageInfo(pkg);
    let dispatchedGithubFetch = false;

    switch (npmRes.result) {
      case Result.NONE:
        if (NpmPkgInfoService.getInstance().hasFailure(pkg) && 
            !NpmPkgInfoService.getInstance().isRequestRunning(pkg)) {
          UiUtils.addInlay(
            hints,
            position,
            I18n.message(I18nKey.failedNpmMeta),
            undefined,
            undefined,
            `npm:${pkg}`
          );
          return;
        }

        ProgressUtils.runBackground(
          `DepLens: Fetch npm ${pkg}`
        ).then(() => {
          try {
            NpmPkgInfoService.getInstance().fetchPackageInfo(pkg, () => {
              // Request npm metadata completed
              const updatedNpmRes = NpmPkgInfoService.getInstance().getPackageInfo(pkg);

              if (updatedNpmRes.result === Result.SUCCESS) {
                const url = updatedNpmRes.data?.githubUrl || "";
                const repoKey = GithubRepoInfoService.getInstance().getRepoKey(url);
                
                if (repoKey) {
                  const repoRes = GithubRepoInfoService.getInstance().getRepoInfo(repoKey.owner, repoKey.repo);
                  if (repoRes.result === Result.NONE && !dispatchedGithubFetch) {
                    dispatchedGithubFetch = true;
                    GithubRepoInfoService.getInstance().fetchRepoInfo(repoKey.owner, repoKey.repo);
                  }
                }
              }
            });
          } catch (e) {
            console.warn(`Failed to load npm info for ${pkg}`, e);
          }
        });
        break;

      case Result.SUCCESS:
        const npmData = npmRes.data;
        if (npmData) {
          let label = `📦 ${npmData.weeklyDownloads.toLocaleString()} weekly downloads`;
          
          // Add GitHub info if available
          const url = npmData.githubUrl || "";
          const repoKey = GithubRepoInfoService.getInstance().getRepoKey(url);
          if (repoKey) {
            const repoRes = GithubRepoInfoService.getInstance().getRepoInfo(repoKey.owner, repoKey.repo);
            if (repoRes.result === Result.SUCCESS && repoRes.data) {
              const stars = repoRes.data.stars;
              label += ` • ⭐ ${stars}`;
            } else if (repoRes.result === Result.NONE && !dispatchedGithubFetch) {
              dispatchedGithubFetch = true;
              ProgressUtils.runBackground(
                `DepLens: Fetch GitHub ${repoKey.owner}/${repoKey.repo}`
              ).then(() => {
                GithubRepoInfoService.getInstance().fetchRepoInfo(repoKey.owner, repoKey.repo);
              });
            }
          }

          UiUtils.addInlay(
            hints,
            position,
            label,
            label,
            npmData.githubUrl,
            `npm:${pkg}`
          );
        }
        break;

      case Result.FAILURE:
        UiUtils.addInlay(
          hints,
          position,
          I18n.message(I18nKey.failedNpmMeta),
          undefined,
          undefined,
          `npm:${pkg}`
        );
        break;
    }
  }
}