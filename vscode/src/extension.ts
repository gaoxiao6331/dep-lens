import * as vscode from "vscode";
import { GoDepLensInlayProvider } from "./lang/go/GoDepLensInlayProvider";
import { GithubRepoInfoService } from "./utils/GithubRepoInfoService";
import { I18n } from "./utils/I18n";
import { logger } from "./utils/Logger";

export async function activate(context: vscode.ExtensionContext) {
  logger.show();
  logger.info("DepLens activating...");

  await I18n.loadLocale(context);
  await GithubRepoInfoService.getInstance().init(context);

  const goProvider = new GoDepLensInlayProvider();

  context.subscriptions.push(
    vscode.languages.registerInlayHintsProvider({ scheme: "file", language: "go" }, goProvider),
  );

  context.subscriptions.push(
    vscode.languages.registerInlayHintsProvider(
      { scheme: "file", pattern: "**/go.mod" },
      goProvider,
    ),
  );

  context.subscriptions.push(
    vscode.commands.registerCommand("depLens.clearCache", () => {
      GithubRepoInfoService.getInstance().clearCache();
      vscode.window.showInformationMessage("DepLens cache cleared");
    }),
  );
}

export function deactivate() {}
