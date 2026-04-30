import * as fs from "node:fs/promises";
import * as path from "node:path";
import { fetch } from "undici";
import * as vscode from "vscode";
import { Logger } from "../Logger";
import { AbstractCachedRequestService, CachedEntry } from "./AbstractCachedRequestService";
import { Result, type ResultWrapper } from "../../common/Result";

export interface NpmPackageInfo extends CachedEntry {
  name: string;
  weeklyDownloads: number;
  githubUrl?: string;
}

interface NpmRegistryResponse {
  name: string;
  repository?: {
    type?: string;
    url?: string;
  };
}

interface NpmDownloadsResponse {
  downloads: number;
  start?: string;
  end?: string;
  package?: string;
}

export class NpmPkgInfoService extends AbstractCachedRequestService<NpmPackageInfo> {
  private static instance: NpmPkgInfoService;
  private _onDidUpdatePackageInfo = new vscode.EventEmitter<void>();
  public readonly onDidUpdatePackageInfo = this._onDidUpdatePackageInfo.event;
  private storagePath = "";

  private constructor() {
    super(
      "deplens/npm_package_cache.json",
      null // No serializer for now
    );
  }

  static getInstance(): NpmPkgInfoService {
    if (!NpmPkgInfoService.instance) {
      NpmPkgInfoService.instance = new NpmPkgInfoService();
    }
    return NpmPkgInfoService.instance;
  }

  async init(context: vscode.ExtensionContext): Promise<void> {
    this.storagePath = context.globalStorageUri.fsPath;
    try {
      await fs.mkdir(this.storagePath, { recursive: true });
    } catch (e) {
      // Ignore directory creation errors
    }
    await this.loadCacheFromDisk();
  }

  initCache(): void {
    // Initialize cache from disk if needed
    this.loadCacheFromDisk();
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
      if (content) {
        const map = JSON.parse(content);
        const now = Date.now();
        const expiry = this.cacheExpiryMillis;

        for (const key in map) {
          const val = map[key] as NpmPackageInfo;
          if (now - val.fetchedAt <= expiry) {
            this.cache.set(key, { result: Result.SUCCESS, data: val });
          }
        }
      }
    } catch (e) {
      this.logger.warn(`Failed to load npm cache: ${e}`);
    }
  }

  private async saveCacheToDisk(): Promise<void> {
    try {
      if (!this.storagePath) {
        return;
      }

      const cacheFile = path.join(this.storagePath, path.basename(this.cacheFileName));

      const obj: any = {};
      for (const [key, result] of this.cache.entries()) {
        if (result.result === Result.SUCCESS && result.data) {
          obj[key] = result.data;
        }
      }
      
      await fs.writeFile(cacheFile, JSON.stringify(obj));
    } catch (e) {
      this.logger.warn(`Failed to save npm cache: ${e}`);
    }
  }

  getCacheKey(packageName: string): string {
    return packageName;
  }

  getPackageInfo(packageName: string): ResultWrapper<NpmPackageInfo> {
    return this.getCachedInfo(packageName);
  }

  async fetchPackageInfo(packageName: string, onFinish?: () => void): Promise<void> {
    const request = this.fetchByKey(packageName, onFinish);
    this._onDidUpdatePackageInfo.fire();
    await request;
    this._onDidUpdatePackageInfo.fire();
    this.saveCacheToDisk();
  }

  async retryPackageInfo(packageName: string, onFinish?: () => void): Promise<void> {
    const request = this.retryByKey(packageName, onFinish);
    this._onDidUpdatePackageInfo.fire();
    await request;
    this._onDidUpdatePackageInfo.fire();
    this.saveCacheToDisk();
  }

  async createRequestCall(key: string): Promise<Response> {
    return fetch(`https://registry.npmjs.org/${key}`, {
      headers: {
        "Accept": "application/json"
      }
    });
  }

  parseResponseBody(key: string, body: string): Promise<NpmPackageInfo | null> {
    return this.parseResponseBodyAsync(key, body);
  }

  private async parseResponseBodyAsync(key: string, body: string): Promise<NpmPackageInfo | null> {
    try {
        const registryInfo = JSON.parse(body) as NpmRegistryResponse;
      
      // Fetch download stats in parallel
      const downloadsResponse = await fetch(`https://api.npmjs.org/downloads/point/last-week/${key}`);
      if (!downloadsResponse.ok) {
        return null;
      }
      
      const downloadsBody = await downloadsResponse.text();
      const downloadsInfo = JSON.parse(downloadsBody) as NpmDownloadsResponse;

      let githubUrl: string | undefined;
      if (registryInfo.repository?.url) {
        githubUrl = registryInfo.repository.url
          .replace(/^git\+/, '')
          .replace(/\.git$/, '')
          .replace(/^git:/, 'https:')
          .replace(/^ssh:\/\/git@github\.com:/, 'https://github.com/');
      }

      const packageInfo: NpmPackageInfo = {
        name: registryInfo.name,
        weeklyDownloads: downloadsInfo.downloads,
        githubUrl,
        fetchedAt: Date.now()
      };

      this.logger.info(`[Request Success] ${key}, downloads: ${packageInfo.weeklyDownloads}, github: ${packageInfo.githubUrl}`);
      return packageInfo;
    } catch (error) {
      this.logger.warn(`[Request Failed] ${key}: ${error}`);
      return null;
    }
  }

  shutdown(): void {
    super.shutdown();
    this._onDidUpdatePackageInfo.dispose();
  }

  clearCache(): void {
    super.clearCache();
    void this.saveCacheToDisk();
    this._onDidUpdatePackageInfo.fire();
  }
}
