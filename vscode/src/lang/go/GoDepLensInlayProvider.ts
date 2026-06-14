import * as vscode from "vscode";
import { Logger } from "../../utils/Logger";
import { GithubInlayUtils } from "../../utils/inlay/GithubInlayUtils";
import { GoDependencyParser } from "../../utils/parser/GoDependencyParser";
import { GithubRepoInfoService } from "../../utils/service/GithubRepoInfoService";
import { BaseDepLensInlayProvider } from "../BaseDepLensInlayProvider";

export class GoDepLensInlayProvider extends BaseDepLensInlayProvider {
  constructor(private readonly context: vscode.ExtensionContext) {
    super();

    GithubRepoInfoService.getInstance().onDidUpdateRepoInfo(() => {
      this.emitter.fire();
    });
  }

  protected isFileSupported(document: vscode.TextDocument): boolean {
    return document.languageId === "go" || document.fileName.endsWith("go.mod");
  }

  protected async provideInlayHintsForDocument(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken,
  ): Promise<vscode.InlayHint[]> {
    const hints: vscode.InlayHint[] = [];
    try {
      const dependencies = await GoDependencyParser.parse(this.context, document, range);
      if (token.isCancellationRequested) {
        return [];
      }

      for (const dependency of dependencies) {
        GithubInlayUtils.addRepoInlay(
          hints,
          new vscode.Position(dependency.line, dependency.character),
          { owner: dependency.owner, repo: dependency.repo },
        );
      }
    } catch (error) {
      Logger.getInstance().error(`Failed to provide Go dependency inlay hints: ${String(error)}`);
      return [];
    }

    return hints;
  }
}
