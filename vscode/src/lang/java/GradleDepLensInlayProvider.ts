import * as vscode from "vscode";
import { Result } from "../../common/Result";
import { GithubRepoInfoService } from "../../utils/service/GithubRepoInfoService";
import { I18n } from "../../utils/I18n";
import { I18nKey } from "../../common/I18nKey";
import { BaseDepLensInlayProvider } from "../BaseDepLensInlayProvider";

const DEP_NOTATION_REGEX = /(implementation|api|compileOnly|runtimeOnly|testImplementation|testCompileOnly|testRuntimeOnly|kapt|annotationProcessor|classpath|compile|testCompile)\s*\(?\s*['"]([^:'"]+):([^:'"]+):([^'"\s\)]+)['"]\s*\)?/g;
const GROOVY_BLOCK_REGEX = /dependencies\s*\{[^}]*\}/gs;

export class GradleDepLensInlayProvider extends BaseDepLensInlayProvider {

  constructor() {
    super();
    GithubRepoInfoService.getInstance().onDidUpdateRepoInfo(() => {
      this.emitter.fire();
    });
  }

  protected isFileSupported(document: vscode.TextDocument): boolean {
    const fileName = document.fileName;
    return fileName.endsWith("build.gradle") || fileName.endsWith("build.gradle.kts") ||
           document.languageId === "groovy";
  }

  protected async provideInlayHintsForDocument(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken
  ): Promise<vscode.InlayHint[]> {
    const hints: vscode.InlayHint[] = [];
    const text = document.getText();
    const textRange = text.substring(
      document.offsetAt(range.start),
      document.offsetAt(range.end)
    );

    // 查找所有依赖声明
    const matches = textRange.matchAll(DEP_NOTATION_REGEX);

    for (const match of matches) {
      if (!match.index) continue;

      const groupId = match[2];
      const artifactId = match[3];
      const version = match[4];

      // 找到依赖声明的结束位置
      const actualIndex = document.offsetAt(range.start) + match.index + match[0].length;
      const pos = document.positionAt(actualIndex);

      const hint = await this.createMavenHint(groupId, artifactId, pos);
      if (hint) {
        hints.push(hint);
      }
    }

    return hints;
  }

  private async createMavenHint(
    groupId: string,
    artifactId: string,
    position: vscode.Position
  ): Promise<vscode.InlayHint | null> {
    const repoUrl = `https://search.maven.org/artifact/${groupId}/${artifactId}`;
    
    // For now, just show a basic Maven dependency hint
    const label = `📦 ${groupId}:${artifactId}`;
    
    const hint = new vscode.InlayHint(position, `  ${label}`);
    hint.tooltip = `Gradle dependency: ${groupId}:${artifactId}\n\n[View on Maven Central](${repoUrl})`;

    return hint;
  }
}