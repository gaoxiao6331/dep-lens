import * as vscode from "vscode";

export class ProgressUtils {
  static runBackground<T>(
    title: string,
    task: () => Thenable<T>,
    minVisibleMillis = 0,
  ): Thenable<T> {
    return new Promise<T>((resolve, reject) => {
      const startTime = Date.now();

      vscode.window.withProgress(
        {
          location: vscode.ProgressLocation.Window,
          title: title,
          cancellable: false,
        },
        async () => {
          try {
            const result = await task();
            const elapsed = Date.now() - startTime;

            // Ensure minimum visible time if specified
            if (minVisibleMillis > 0 && elapsed < minVisibleMillis) {
              await new Promise((resolve) => setTimeout(resolve, minVisibleMillis - elapsed));
            }

            resolve(result);
          } catch (error) {
            reject(error);
          }
        },
      );
    });
  }
}
