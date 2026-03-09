import * as fs from "node:fs/promises";
import * as path from "node:path";
import { fetch } from "undici";
import * as vscode from "vscode";
import { RETRY_DELAY_MILLIS } from "../common/Const";
import { Result, type ResultWrapper } from "../common/Result";
import { Formatter } from "./Formatter";
import { logger } from "./Logger";
import { RequestManager } from "./RequestManager";

export interface GithubRepoInfo {
  stars: string;
  originalStars: number;
  updatedDate: string;
  fetchedAt: number;
}

interface GithubApiResponse {
  stargazers_count: number;
  pushed_at: string;
}

export class GithubRepoInfoService {
  private static instance: GithubRepoInfoService;
  private cache = new Map<string, GithubRepoInfo>();
  private requestManager = new RequestManager();
  private runningRequests = new Map<string, Promise<void>>();
  private cacheFile = "";
  private _onDidUpdateRepoInfo = new vscode.EventEmitter<void>();
  public readonly onDidUpdateRepoInfo = this._onDidUpdateRepoInfo.event;

  private constructor() {}

  static getInstance(): GithubRepoInfoService {
    if (!GithubRepoInfoService.instance) {
      GithubRepoInfoService.instance = new GithubRepoInfoService();
    }
    return GithubRepoInfoService.instance;
  }

  async init(context: vscode.ExtensionContext) {
    const storagePath = context.globalStorageUri.fsPath;
    try {
      await fs.mkdir(storagePath, { recursive: true });
    } catch {}
    this.cacheFile = path.join(storagePath, "github_repo_cache.json");
    await this.loadCacheFromDisk();
  }

  private async loadCacheFromDisk() {
    try {
      // Check if file exists
      try {
        await fs.access(this.cacheFile);
      } catch {
        return;
      }

      const content = await fs.readFile(this.cacheFile, "utf-8");
      if (content) {
        const map = JSON.parse(content);
        const now = Date.now();
        const threeDaysMillis = 3 * 24 * 60 * 60 * 1000;
        let hasExpired = false;

        for (const key in map) {
          const val = map[key] as GithubRepoInfo;
          if (now - val.fetchedAt > threeDaysMillis) {
            hasExpired = true;
          } else {
            this.cache.set(key, val);
          }
        }

        if (hasExpired) {
          this.saveCacheToDiskAsync();
        }
      }
    } catch (e) {
      logger.warn(`Failed to load github repo cache: ${e}`);
    }
  }

  private async saveCacheToDiskAsync() {
    try {
      const obj = Object.fromEntries(this.cache);
      await fs.writeFile(this.cacheFile, JSON.stringify(obj));
    } catch (e) {
      logger.warn(`Failed to save github repo cache: ${e}`);
    }
  }

  getRepoInfo(owner: string, repo: string): ResultWrapper<GithubRepoInfo> {
    const key = `${owner}/${repo}`;
    const info = this.cache.get(key);
    if (info) {
      const now = Date.now();
      const threeDaysMillis = 3 * 24 * 60 * 60 * 1000;
      if (now - info.fetchedAt > threeDaysMillis) {
        this.cache.delete(key);
        this.saveCacheToDiskAsync();
      } else {
        return { result: Result.SUCCESS, data: info };
      }
    }

    if (this.isFailure(key)) {
      return { result: Result.FAILURE };
    }
    return { result: Result.NONE };
  }

  isFailure(key: string): boolean {
    return !this.cache.has(key) && !this.requestManager.shouldRequest(key);
  }

  async fetchRepoInfo(owner: string, repo: string): Promise<void> {
    const key = `${owner}/${repo}`;
    if (this.runningRequests.has(key)) return;
    if (!this.requestManager.shouldRequest(key)) return;

    const githubToken =
      vscode.workspace.getConfiguration("depLens").get<string>("githubToken") ||
      process.env.GITHUB_TOKEN ||
      "";

    const task = async () => {
      try {
        const resp = await fetch(`https://api.github.com/repos/${owner}/${repo}`, {
          headers: {
            Accept: "application/vnd.github+json",
            "User-Agent": "dep-lens-vscode",
            ...(githubToken ? { Authorization: `Bearer ${githubToken}` } : {}),
          },
        });

        if (!resp.ok) {
          logger.warn(`GitHub API failed: ${resp.status}`);
          return;
        }

        const json = (await resp.json()) as GithubApiResponse;
        const dateStr = json.pushed_at;
        const date = new Date(dateStr);
        const formattedDate = date.toISOString().split("T")[0]; // YYYY-MM-DD

        const repoInfo: GithubRepoInfo = {
          stars: Formatter.formatGithubStar(json.stargazers_count),
          originalStars: json.stargazers_count,
          updatedDate: formattedDate,
          fetchedAt: Date.now(),
        };

        logger.info(`[Success] ${key}, stars: ${repoInfo.stars}, updated: ${repoInfo.updatedDate}`);

        this.cache.set(key, repoInfo);
        this.saveCacheToDiskAsync();
        this._onDidUpdateRepoInfo.fire();
      } catch (e) {
        logger.warn(`GitHub request error: ${key} ${e}`);
      } finally {
        if (!this.cache.has(key)) {
          this.requestManager.updateFailed(key);
          // Retry after delay
          setTimeout(() => {
            this.fetchRepoInfo(owner, repo);
          }, RETRY_DELAY_MILLIS);
        }
        this.runningRequests.delete(key);
      }
    };

    const p = task();
    this.runningRequests.set(key, p);
    return p;
  }

  async clearCache() {
    this.cache.clear();
    await this.saveCacheToDiskAsync();
  }
}
