import * as vscode from "vscode";
import { GithubRepoInfoService } from "../../utils/service/GithubRepoInfoService";
import { BaseDepLensInlayProvider } from "../BaseDepLensInlayProvider";
import { GithubInlayUtils } from "../../utils/inlay/GithubInlayUtils";
import { MavenRepoResolver } from "../../utils/resolver/MavenRepoResolver";

const DEPENDENCY_REGEX =
  /<dependency>[\s\S]*?<groupId>([^<]+)<\/groupId>[\s\S]*?<artifactId>([^<]+)<\/artifactId>(?:[\s\S]*?<version>([^<]+)<\/version>)?[\s\S]*?<\/dependency>/g;

export class MavenDepLensInlayProvider extends BaseDepLensInlayProvider {
  constructor() {
    super();
    GithubRepoInfoService.getInstance().onDidUpdateRepoInfo(() => {
      this.emitter.fire();
    });
  }

  protected isFileSupported(document: vscode.TextDocument): boolean {
    return document.fileName.endsWith("pom.xml");
  }

  protected async provideInlayHintsForDocument(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken,
  ): Promise<vscode.InlayHint[]> {
    const hints: vscode.InlayHint[] = [];
    const text = document.getText();

    for (const match of text.matchAll(DEPENDENCY_REGEX)) {
      if (match.index === undefined) {
        continue;
      }

      const artifactIdTagMatch = match[0].match(/<artifactId>[^<]+<\/artifactId>/);
      if (!artifactIdTagMatch) {
        continue;
      }

      const position = document.positionAt(
        match.index + match[0].indexOf(artifactIdTagMatch[0]) + artifactIdTagMatch[0].length,
      );
      if (!range.contains(position)) {
        continue;
      }

      const repoKey = await MavenRepoResolver.repoKeyFromGroupArtifact(match[1], match[2], match[3]);
      if (!repoKey) {
        continue;
      }

      GithubInlayUtils.addRepoInlay(hints, position, repoKey);
    }

    return hints;
  }
}
