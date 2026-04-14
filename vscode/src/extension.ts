import * as vscode from "vscode";
import { GoDepLensInlayProvider } from "./lang/go/GoDepLensInlayProvider";
import { JavaDepLensInlayProvider } from "./lang/java/JavaDepLensInlayProvider";
import { KotlinDepLensInlayProvider } from "./lang/java/KotlinDepLensInlayProvider";
import { GradleDepLensInlayProvider } from "./lang/java/GradleDepLensInlayProvider";
import { MavenDepLensInlayProvider } from "./lang/java/MavenDepLensInlayProvider";
import { TsDepLensInlayProvider } from "./lang/ts/TsDepLensInlayProvider";
import { PackageJsonDepLensInlayProvider } from "./lang/ts/PackageJsonDepLensInlayProvider";
import { GithubRepoInfoService } from "./utils/service/GithubRepoInfoService";
import { NpmPkgInfoService } from "./utils/service/NpmPkgInfoService";
import { I18n } from "./utils/I18n";
import { Logger } from "./utils/Logger";

export async function activate(context: vscode.ExtensionContext) {
  const logger = Logger.getInstance();
  logger.show();
  logger.info("DepLens activating...");

  await I18n.loadLocale(context);
  await GithubRepoInfoService.getInstance().init(context);
  await NpmPkgInfoService.getInstance().init(context);

  // Go providers
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

  // Java/Kotlin providers
  const javaProvider = new JavaDepLensInlayProvider();
  const kotlinProvider = new KotlinDepLensInlayProvider();
  const gradleProvider = new GradleDepLensInlayProvider();
  const mavenProvider = new MavenDepLensInlayProvider();

  context.subscriptions.push(
    vscode.languages.registerInlayHintsProvider({ scheme: "file", language: "java" }, javaProvider),
  );

  context.subscriptions.push(
    vscode.languages.registerInlayHintsProvider({ scheme: "file", language: "kotlin" }, kotlinProvider),
  );

  context.subscriptions.push(
    vscode.languages.registerInlayHintsProvider({ scheme: "file", language: "groovy" }, gradleProvider),
  );

  context.subscriptions.push(
    vscode.languages.registerInlayHintsProvider(
      { scheme: "file", pattern: "**/build.gradle*" },
      gradleProvider,
    ),
  );

  context.subscriptions.push(
    vscode.languages.registerInlayHintsProvider(
      { scheme: "file", pattern: "**/pom.xml" },
      mavenProvider,
    ),
  );

  // TypeScript/JavaScript providers
  const tsProvider = new TsDepLensInlayProvider();
  const packageJsonProvider = new PackageJsonDepLensInlayProvider();

  context.subscriptions.push(
    vscode.languages.registerInlayHintsProvider({ scheme: "file", language: "typescript" }, tsProvider),
  );

  context.subscriptions.push(
    vscode.languages.registerInlayHintsProvider({ scheme: "file", language: "javascript" }, tsProvider),
  );

  context.subscriptions.push(
    vscode.languages.registerInlayHintsProvider({ scheme: "file", language: "json" }, packageJsonProvider),
  );

  context.subscriptions.push(
    vscode.languages.registerInlayHintsProvider(
      { scheme: "file", pattern: "**/package.json" },
      packageJsonProvider,
    ),
  );

  context.subscriptions.push(
    vscode.commands.registerCommand("depLens.clearCache", () => {
      GithubRepoInfoService.getInstance().clearCache();
      vscode.window.showInformationMessage("DepLens cache cleared");
    }),
  );
}

export function deactivate() {
  GithubRepoInfoService.getInstance().shutdown();
  NpmPkgInfoService.getInstance().shutdown();
}
