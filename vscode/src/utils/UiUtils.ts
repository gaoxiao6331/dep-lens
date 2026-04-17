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

    let tooltipText = hoverText;
    if (githubUrl) {
      tooltipText += `\n\n[Open on GitHub](${githubUrl})`;
    }
    if (retryToken) {
      const encodedArgs = encodeURIComponent(JSON.stringify([retryToken]));
      tooltipText += `\n[Retry](command:depLens.retry?${encodedArgs})`;
    }

    hint.tooltip = tooltipText;
    hints.push(hint);
  }
}
