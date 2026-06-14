import type * as vscode from "vscode";
import { GithubInlayUtils } from "../../utils/inlay/GithubInlayUtils";
import { MavenRepoResolver } from "../../utils/resolver/MavenRepoResolver";
import { GithubRepoInfoService } from "../../utils/service/GithubRepoInfoService";
import { BaseDepLensInlayProvider } from "../BaseDepLensInlayProvider";

const DEP_NOTATION_REGEX =
  /(implementation|api|compileOnly|runtimeOnly|testImplementation|testCompileOnly|testRuntimeOnly|kapt|annotationProcessor|classpath|compile|testCompile)\s*\(?\s*['"]([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([A-Za-z0-9_.\-+$]+)[^'"\s\)]*['"]/g;
const MAP_NOTATION_REGEX =
  /(implementation|api|compileOnly|runtimeOnly|testImplementation|testCompileOnly|testRuntimeOnly|kapt|annotationProcessor|classpath|compile|testCompile)\s*\(?\s*group:\s*['"]([A-Za-z0-9_.-]+)['"]\s*,\s*name:\s*['"]([A-Za-z0-9_.-]+)['"](?:\s*,\s*version:\s*['"]([^'"]+)['"])?/g;

export class GradleDepLensInlayProvider extends BaseDepLensInlayProvider {
  constructor() {
    super();
    GithubRepoInfoService.getInstance().onDidUpdateRepoInfo(() => {
      this.emitter.fire();
    });
  }

  protected isFileSupported(document: vscode.TextDocument): boolean {
    return (
      document.fileName.endsWith("build.gradle") || document.fileName.endsWith("build.gradle.kts")
    );
  }

  protected async provideInlayHintsForDocument(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken,
  ): Promise<vscode.InlayHint[]> {
    const hints: vscode.InlayHint[] = [];
    const text = document.getText();

    await this.collectMatches(text, document, range, hints, DEP_NOTATION_REGEX, 2, 3, 4);
    await this.collectMatches(text, document, range, hints, MAP_NOTATION_REGEX, 2, 3, 4);

    return hints;
  }

  private async collectMatches(
    text: string,
    document: vscode.TextDocument,
    range: vscode.Range,
    hints: vscode.InlayHint[],
    regex: RegExp,
    groupIndex: number,
    artifactIndex: number,
    versionIndex: number,
  ): Promise<void> {
    for (const match of text.matchAll(regex)) {
      if (match.index === undefined) {
        continue;
      }

      const position = document.positionAt(match.index + match[0].length);
      if (!range.contains(position)) {
        continue;
      }

      const repoKey = await MavenRepoResolver.repoKeyFromGroupArtifact(
        match[groupIndex],
        match[artifactIndex],
        match[versionIndex],
      );
      if (!repoKey) {
        continue;
      }

      GithubInlayUtils.addRepoInlay(hints, position, repoKey);
    }
  }
}
