import * as vscode from "vscode";
import { GithubRepoInfoService } from "../../utils/service/GithubRepoInfoService";
import { BaseDepLensInlayProvider } from "../BaseDepLensInlayProvider";
import { GithubInlayUtils } from "../../utils/inlay/GithubInlayUtils";
import { GoDependencyParser } from "../../utils/parser/GoDependencyParser";

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

    for (const dependency of await GoDependencyParser.parse(this.context, document, range)) {
      GithubInlayUtils.addRepoInlay(
        hints,
        new vscode.Position(dependency.line, dependency.character),
        { owner: dependency.owner, repo: dependency.repo }
      );
    }

    return hints;
  }
}
