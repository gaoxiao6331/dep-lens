import * as vscode from "vscode";
import { Result } from "../../common/Result";
import { GithubRepoInfoService } from "../../utils/service/GithubRepoInfoService";
import { I18n } from "../../utils/I18n";
import { I18nKey } from "../../common/I18nKey";
import { BaseDepLensInlayProvider } from "../BaseDepLensInlayProvider";

const IMPORT_REGEX = /import\s+([a-zA-Z0-9_.*]+)\*?/g;

export class KotlinDepLensInlayProvider extends BaseDepLensInlayProvider {

  constructor() {
    super();
    GithubRepoInfoService.getInstance().onDidUpdateRepoInfo(() => {
      this.emitter.fire();
    });
  }

  protected isFileSupported(document: vscode.TextDocument): boolean {
    return document.languageId === "kotlin";
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

    // 查找所有 import 语句
    const matches = textRange.matchAll(IMPORT_REGEX);

    for (const match of matches) {
      if (!match.index) continue;

      const fullImport = match[1];
      
      // 跳过 kotlin.*、java.* 等系统导入
      if (fullImport.startsWith('kotlin.') || fullImport.startsWith('java.') ||
          fullImport.startsWith('javax.') || fullImport.startsWith('android.') ||
          fullImport.startsWith('androidx.')) {
        continue;
      }

      // 转换为 Maven/Gradle 的 groupId:artifactId 格式
      const mavenInfo = this.extractMavenInfo(fullImport);
      if (!mavenInfo) continue;

      const actualIndex = document.offsetAt(range.start) + match.index + match[0].length;
      const pos = document.positionAt(actualIndex);

      const hint = await this.createMavenHint(mavenInfo.groupId, mavenInfo.artifactId, pos);
      if (hint) {
        hints.push(hint);
      }
    }

    return hints;
  }

  private extractMavenInfo(fullImport: string): { groupId: string; artifactId: string } | null {
    const parts = fullImport.split('.');
    if (parts.length < 3) return null;

    // Kotlin 特定的 groupId 识别
    const kotlinGroupIds = [
      'org.jetbrains.kotlin', 'kotlinx', 'com.jetbrains', 'io.ktor',
      'org.springframework', 'com.google', 'org.apache', 'androidx'
    ];

    let groupId = '';
    let artifactId = '';

    for (const kotlinGroup of kotlinGroupIds) {
      if (fullImport.startsWith(kotlinGroup)) {
        groupId = kotlinGroup;
        // 取下一级作为 artifactId
        const groupParts = kotlinGroup.split('.').length;
        if (parts.length > groupParts) {
          artifactId = parts[groupParts];
        }
        break;
      }
    }

    // 如果没有匹配，使用启发式方法
    if (!groupId) {
      groupId = parts.slice(0, 2).join('.');
      artifactId = parts[2];
    }

    return { groupId, artifactId };
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
    hint.tooltip = `Kotlin dependency: ${groupId}:${artifactId}\n\n[View on Maven Central](${repoUrl})`;

    return hint;
  }
}