import * as vscode from "vscode";

/**
 * 光标行切换后的防抖延迟（毫秒）。
 * 参考 JetBrains 版 INLAY_SWITCH_DWELL_MILLIS，
 * 防止快速划过行时频繁刷新 inlay hints。
 */
const INLAY_SWITCH_DWELL_MS = 120;

/**
 * InlayHintsProvider 基类。
 *
 * 核心机制：
 *   1. 子类通过 emitter.fire() 通知 VS Code 重新请求 inlay hints
 *      （例如 npm / GitHub 数据加载完成后刷新显示）。
 *   2. 监听光标行变化，经防抖后触发 emitter.fire()，
 *      使 VS Code 重建 hints 对象，从而关闭前一行残留的 tooltip。
 *      tooltip 使用 isTrusted: true 确保 hover 仍可正常触发。
 */
export abstract class BaseDepLensInlayProvider implements vscode.InlayHintsProvider {
  protected emitter = new vscode.EventEmitter<void>();
  public readonly onDidChangeInlayHints = this.emitter.event;

  /** 上一次光标所在行 */
  private _activeLine: number | undefined;
  /** 上一次光标所在文档 URI */
  private _activeDocumentUri: string | undefined;
  /** 防抖定时器 */
  private _switchTimer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    // 光标换行时延迟刷新 hints，关闭旧行残留的 tooltip
    vscode.window.onDidChangeTextEditorSelection((e) => {
      const line = e.selections[0]?.active.line;
      const uri = e.textEditor.document.uri.toString();
      if (line === this._activeLine && uri === this._activeDocumentUri) {
        return;
      }
      if (this._switchTimer) {
        clearTimeout(this._switchTimer);
      }
      this._switchTimer = setTimeout(() => {
        this._activeLine = line;
        this._activeDocumentUri = uri;
        this._switchTimer = undefined;
        this.emitter.fire();
      }, INLAY_SWITCH_DWELL_MS);
    });
  }

  protected abstract isFileSupported(document: vscode.TextDocument): boolean;
  protected abstract provideInlayHintsForDocument(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken,
  ): Promise<vscode.InlayHint[]>;

  async provideInlayHints(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken,
  ): Promise<vscode.InlayHint[]> {
    if (!this.isFileSupported(document)) {
      return [];
    }
    return this.provideInlayHintsForDocument(document, range, token);
  }
}
