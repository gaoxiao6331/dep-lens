import * as vscode from "vscode";

/**
 * 通过 `vscode.executeDefinitionProvider` 将 Java / Kotlin 的 import
 * 解析到它所在的 JAR 文件路径，供 MavenRepoResolver 进一步推导坐标。
 *
 * 处理两类 URI：
 *   1. file:// —— 直接使用 fsPath，路径通常形如 /.m2/repository/.../foo.jar!/... 。
 *   2. jdt://  —— Red Hat Java 扩展返回的虚拟 URI，其 query 中内嵌了
 *      真实 jar 路径（以 `\/` 转义）。遍历解码后查找 `.m2/repository/`
 *      或 `.gradle/caches/modules-2/files-2.1/` 作为锚点即可。
 */
export class JvmDefinitionResolver {

  static async resolveJarPath(
    documentUri: vscode.Uri,
    position: vscode.Position,
  ): Promise<string | null> {
    const definitions = await vscode.commands.executeCommand<
      (vscode.Location | vscode.LocationLink)[]
    >("vscode.executeDefinitionProvider", documentUri, position);

    const first = definitions?.[0];
    if (!first) {
      return null;
    }

    const targetUri = "targetUri" in first ? first.targetUri : first.uri;
    if (targetUri.scheme === "file") {
      return targetUri.fsPath;
    }

    const jarPath = this.extractJarPathFromUri(targetUri);
    return jarPath;
  }

  private static extractJarPathFromUri(uri: vscode.Uri): string | null {
    let raw: string;
    try {
      raw = decodeURIComponent(uri.toString());
    } catch {
      raw = uri.toString();
    }
    raw = raw.replace(/\\\//g, "/").replace(/\\/g, "/");

    const markers = ["/.m2/repository/", "//.gradle/caches/modules-2/files-2.1/"];
    const matched = markers.find((marker) => raw.includes(marker));
    if (!matched) {
      return null;
    }
    const markerIndex = raw.indexOf(matched);
    if (markerIndex < 0) {
      return null;
    }
    const jarPath = raw.slice(markerIndex).split("!")[0];
    return jarPath;
  }
}
