import * as vscode from "vscode";
import { DartDepLensInlayProvider } from "./lang/dart/DartDepLensInlayProvider";
import { PubspecDepLensInlayProvider } from "./lang/dart/PubspecDepLensInlayProvider";
import { GoDepLensInlayProvider } from "./lang/go/GoDepLensInlayProvider";
import { GradleDepLensInlayProvider } from "./lang/java/GradleDepLensInlayProvider";
import { JavaDepLensInlayProvider } from "./lang/java/JavaDepLensInlayProvider";
import { KotlinDepLensInlayProvider } from "./lang/java/KotlinDepLensInlayProvider";
import { MavenDepLensInlayProvider } from "./lang/java/MavenDepLensInlayProvider";
import { PackageJsonDepLensInlayProvider } from "./lang/ts/PackageJsonDepLensInlayProvider";
import { TsDepLensInlayProvider } from "./lang/ts/TsDepLensInlayProvider";
import { I18n } from "./utils/I18n";
import { Logger } from "./utils/Logger";
import { GithubRepoInfoService } from "./utils/service/GithubRepoInfoService";
import { NpmPkgInfoService } from "./utils/service/NpmPkgInfoService";
import { PubPkgInfoService } from "./utils/service/PubPkgInfoService";

export async function activate(context: vscode.ExtensionContext) {
  const logger = Logger.getInstance();
  logger.show();
  logger.info("DepLens activating...");

  await I18n.loadLocale(context);
  await GithubRepoInfoService.getInstance().init(context);
  await NpmPkgInfoService.getInstance().init(context);
  await PubPkgInfoService.getInstance().init(context);

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
    vscode.languages.registerInlayHintsProvider(
      { scheme: "file", language: "kotlin" },
      kotlinProvider,
    ),
  );

  // Pattern-based selector alone is enough: matches build.gradle and
  // build.gradle.kts regardless of which languageId the Gradle/Kotlin
  // extension assigns. Registering additional language-based selectors
  // would cause VS Code to call the provider twice and duplicate hints.
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
    vscode.languages.registerInlayHintsProvider(
      { scheme: "file", language: "typescript" },
      tsProvider,
    ),
  );

  context.subscriptions.push(
    vscode.languages.registerInlayHintsProvider(
      { scheme: "file", language: "javascript" },
      tsProvider,
    ),
  );

  context.subscriptions.push(
    vscode.languages.registerInlayHintsProvider(
      { scheme: "file", pattern: "**/package.json" },
      packageJsonProvider,
    ),
  );

  // Dart/Flutter providers
  const dartProvider = new DartDepLensInlayProvider();
  const pubspecProvider = new PubspecDepLensInlayProvider();

  context.subscriptions.push(
    vscode.languages.registerInlayHintsProvider(
      { scheme: "file", language: "dart" },
      dartProvider,
    ),
  );

  context.subscriptions.push(
    vscode.languages.registerInlayHintsProvider(
      { scheme: "file", pattern: "**/pubspec.yaml" },
      pubspecProvider,
    ),
  );

  context.subscriptions.push(
    vscode.commands.registerCommand("depLens.clearCache", () => {
      void Promise.all([
        GithubRepoInfoService.getInstance().clearCache(),
        NpmPkgInfoService.getInstance().clearCache(),
        Promise.resolve(PubPkgInfoService.getInstance().clearCache()),
      ]).then(() => {
        vscode.window.showInformationMessage("DepLens cache cleared");
      });
    }),
  );

  context.subscriptions.push(
    vscode.commands.registerCommand("depLens.retry", async (retryToken?: string) => {
      if (!retryToken) {
        return;
      }

      const [type, value] = retryToken.split(":");
      if (type === "npm" && value) {
        await NpmPkgInfoService.getInstance().retryPackageInfo(value);
        return;
      }

      if (type === "pub" && value) {
        await PubPkgInfoService.getInstance().retryPackageInfo(value);
        return;
      }

      if (type === "github" && value) {
        const [owner, repo] = value.split("/");
        if (owner && repo) {
          await GithubRepoInfoService.getInstance().retryRepoInfo(owner, repo);
        }
      }
    }),
  );
}

export function deactivate() {
  GithubRepoInfoService.getInstance().shutdown();
  NpmPkgInfoService.getInstance().shutdown();
  PubPkgInfoService.getInstance().shutdown();
}
