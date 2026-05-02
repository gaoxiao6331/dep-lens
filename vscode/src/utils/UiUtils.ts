import * as vscode from "vscode";

export class UiUtils {
  /**
   * 创建一个 InlayHint 并加入 hints 数组。
   *
   * 使用 InlayHintLabelPart 实现交互：
   *   - hover 显示 tooltip（非 trusted，鼠标离开后自动消失，不会残留）
   *   - 点击 hint 文字直接跳转 GitHub
   *   - 有 retryToken 时额外显示可点击的 ↻ 重试按钮
   */
  static addInlay(
    hints: vscode.InlayHint[],
    position: vscode.Position,
    displayText: string,
    hoverText: string = displayText,
    githubUrl?: string,
    retryToken?: string,
  ): void {
    const parts: vscode.InlayHintLabelPart[] = [];

    // ── 主文本，点击跳转 GitHub ──
    const mainPart = new vscode.InlayHintLabelPart(`  ${displayText}`);
    if (githubUrl) {
      mainPart.command = {
        title: "Open on GitHub",
        command: "vscode.open",
        arguments: [vscode.Uri.parse(githubUrl)],
      };
    }
    parts.push(mainPart);

    const hint = new vscode.InlayHint(position, parts);

    // tooltip 设在 hint 级别，hover 检测范围覆盖整个 hint 区域。
    // 需要 isTrusted 才能正常触发 hover 以及支持 command: 链接。
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
