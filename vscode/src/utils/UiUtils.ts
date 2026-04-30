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

    const tooltip = new vscode.MarkdownString(hoverText);
    tooltip.isTrusted = true;

    if (githubUrl) {
      tooltip.appendMarkdown(`\n\n[Open on GitHub](${githubUrl})`);
    }
    if (retryToken) {
      const encodedArgs = encodeURIComponent(JSON.stringify([retryToken]));
      tooltip.appendMarkdown(`\n\n[Retry](command:depLens.retry?${encodedArgs})`);
    }

    hint.tooltip = tooltip;
    hints.push(hint);
  }
}
