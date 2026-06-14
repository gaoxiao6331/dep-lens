import * as fs from "node:fs/promises";
import * as path from "node:path";
import { fetch } from "undici";
import * as vscode from "vscode";
import { Result, type ResultWrapper } from "../../common/Result";
import {
  AbstractCachedRequestService,
  type CachedEntry,
} from "./AbstractCachedRequestService";

export interface PubPackageInfo extends CachedEntry {
  name: string;
  githubUrl?: string;
}

interface PubPackageResponse {
  name: string;
  latest?: {
    pubspec?: {
      repository?: string;
      homepage?: string;
      issue_tracker?: string;
      documentation?: string;
    };
  };
}

export class PubPkgInfoService extends AbstractCachedRequestService<PubPackageInfo> {
  private static instance: PubPkgInfoService;
  private readonly onDidUpdateEmitter = new vscode.EventEmitter<void>();
  public readonly onDidUpdatePackageInfo = this.onDidUpdateEmitter.event;
  private storagePath = "";

  private constructor() {
    super("deplens/pub_package_cache.json", null);
  }

  static getInstance(): PubPkgInfoService {
    if (!PubPkgInfoService.instance) {
      PubPkgInfoService.instance = new PubPkgInfoService();
    }
    return PubPkgInfoService.instance;
  }

  async init(context: vscode.ExtensionContext): Promise<void> {
    this.storagePath = context.globalStorageUri.fsPath;
    try {
      await fs.mkdir(this.storagePath, { recursive: true });
    } catch {
      // Ignore directory creation errors.
    }
    await this.loadCacheFromDisk();
  }

  initCache(): void {
    void this.loadCacheFromDisk();
  }

  getPackageInfo(packageName: string): ResultWrapper<PubPackageInfo> {
    return this.getCachedInfo(packageName);
  }

  async fetchPackageInfo(packageName: string, onFinish?: () => void): Promise<void> {
    const request = this.fetchByKey(packageName, onFinish);
    this.onDidUpdateEmitter.fire();
    await request;
    this.onDidUpdateEmitter.fire();
    void this.saveCacheToDisk();
  }

  async retryPackageInfo(packageName: string, onFinish?: () => void): Promise<void> {
    const request = this.retryByKey(packageName, onFinish);
    this.onDidUpdateEmitter.fire();
    await request;
    this.onDidUpdateEmitter.fire();
    void this.saveCacheToDisk();
  }

  async createRequestCall(key: string): Promise<Response> {
    return fetch(`https://pub.dev/api/packages/${encodeURIComponent(key)}`, {
      headers: {
        Accept: "application/json",
      },
    });
  }

  async parseResponseBody(key: string, body: string): Promise<PubPackageInfo | null> {
    try {
      const packageInfo = JSON.parse(body) as PubPackageResponse;
      const pubspec = packageInfo.latest?.pubspec;
      const githubUrl = this.pickGithubUrl([
        pubspec?.repository,
        pubspec?.homepage,
        pubspec?.issue_tracker,
        pubspec?.documentation,
      ]);

      const result: PubPackageInfo = {
        name: packageInfo.name,
        githubUrl,
        fetchedAt: Date.now(),
      };

      this.logger.info(
        `[Request Success] ${key}, github: ${result.githubUrl ?? "none"}`,
      );
      return result;
    } catch (error) {
      this.logger.warn(`[Request Failed] ${key}: ${error}`);
      return null;
    }
  }

  shutdown(): void {
    super.shutdown();
    this.onDidUpdateEmitter.dispose();
  }

  clearCache(): void {
    super.clearCache();
    void this.saveCacheToDisk();
    this.onDidUpdateEmitter.fire();
  }

  private async loadCacheFromDisk(): Promise<void> {
    try {
      if (!this.storagePath) {
        return;
      }

      const cacheFile = path.join(this.storagePath, path.basename(this.cacheFileName));

      try {
        await fs.access(cacheFile);
      } catch {
        return;
      }

      const content = await fs.readFile(cacheFile, "utf-8");
      if (!content) {
        return;
      }

      const map = JSON.parse(content) as Record<string, PubPackageInfo>;
      const now = Date.now();

      for (const [key, value] of Object.entries(map)) {
        if (now - value.fetchedAt <= this.cacheExpiryMillis) {
          this.cache.set(key, { result: Result.SUCCESS, data: value });
        }
      }
    } catch (error) {
      this.logger.warn(`Failed to load pub cache: ${error}`);
    }
  }

  private async saveCacheToDisk(): Promise<void> {
    try {
      if (!this.storagePath) {
        return;
      }

      const cacheFile = path.join(this.storagePath, path.basename(this.cacheFileName));
      const data: Record<string, PubPackageInfo> = {};

      for (const [key, value] of this.cache.entries()) {
        if (value.result === Result.SUCCESS && value.data) {
          data[key] = value.data;
        }
      }

      await fs.writeFile(cacheFile, JSON.stringify(data));
    } catch (error) {
      this.logger.warn(`Failed to save pub cache: ${error}`);
    }
  }

  private pickGithubUrl(candidates: Array<string | undefined>): string | undefined {
    return candidates.find((candidate) => candidate?.includes("github.com"));
  }
}
