import * as vscode from "vscode";

export abstract class BaseDepLensInlayProvider implements vscode.InlayHintsProvider {
  protected emitter = new vscode.EventEmitter<void>();
  public readonly onDidChangeInlayHints = this.emitter.event;

  constructor() {
    // 可以由具体实现类设置更新回调
  }

  protected abstract isFileSupported(document: vscode.TextDocument): boolean;
  protected abstract provideInlayHintsForDocument(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken
  ): Promise<vscode.InlayHint[]>;

  async provideInlayHints(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken
  ): Promise<vscode.InlayHint[]> {
    if (!this.isFileSupported(document)) {
      return [];
    }
    return this.provideInlayHintsForDocument(document, range, token);
  }
}