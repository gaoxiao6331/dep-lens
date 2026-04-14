import * as vscode from "vscode";
import { Result } from "../../common/Result";
import { GithubRepoInfoService } from "../../utils/service/GithubRepoInfoService";
import { I18n } from "../../utils/I18n";
import { I18nKey } from "../../common/I18nKey";
import { BaseDepLensInlayProvider } from "../BaseDepLensInlayProvider";

const DEPENDENCY_REGEX = /<dependency>\s*<groupId>([^<]+)<\/groupId>\s*<artifactId>([^<]+)<\/artifactId>\s*(?:<version>([^<]+)<\/version>)?\s*<\/dependency>/gs;
const GROUP_ARTIFACT_REGEX = /<(groupId|artifactId|version)>([^<]+)<\/\1>/g;

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
    token: vscode.CancellationToken
  ): Promise<vscode.InlayHint[]> {
    const hints: vscode.InlayHint[] = [];
    const text = document.getText();
    const textRange = text.substring(
      document.offsetAt(range.start),
      document.offsetAt(range.end)
    );

    // 查找所有 dependency 块
    const matches = textRange.matchAll(DEPENDENCY_REGEX);

    for (const match of matches) {
      if (!match.index) continue;

      const groupId = match[1];
      const artifactId = match[2];
      const version = match[3]; // 可选

      // 找到 artifactId 的结束位置作为提示位置
      const artifactIdMatch = match[0].match(/<artifactId>[^<]+<\/artifactId>/);
      if (!artifactIdMatch) continue;

      const actualIndex = document.offsetAt(range.start) + 
                         match.index + 
                         match[0].indexOf(artifactIdMatch[0]) + 
                         artifactIdMatch[0].length;
      
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
    hint.tooltip = `Maven dependency: ${groupId}:${artifactId}\n\n[View on Maven Central](${repoUrl})`;

    return hint;
  }
}