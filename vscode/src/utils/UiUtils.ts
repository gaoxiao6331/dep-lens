import * as vscode from "vscode";

export class UiUtils {
  static addInlay(
    hints: vscode.InlayHint[],
    position: vscode.Position,
    displayText: string,
    hoverText: string = displayText,
    githubUrl?: string,
    retryToken?: string
  ): void {
    const hint = new vscode.InlayHint(position, `  ${displayText}`);
    
    // Create tooltip with GitHub link and retry link if provided
    let tooltipText = hoverText;
    if (githubUrl) {
      tooltipText += `\n\n[Open on GitHub](${githubUrl})`;
    }
    if (retryToken) {
      tooltipText += `\n[Retry](command:depLens.retry?"${encodeURIComponent(retryToken)}")`;
    }
    
    hint.tooltip = tooltipText;
    hints.push(hint);
  }

  static refreshInlayHints(): void {
    // Trigger inlay hints refresh by firing the event on all providers
    // This would be handled by the extension's inlay hint providers
    // TODO: Implement event-based refresh mechanism
  }
}