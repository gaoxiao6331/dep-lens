import * as fs from "node:fs/promises";
import * as path from "node:path";
import { fetch } from "undici";
import * as vscode from "vscode";
import { RETRY_DELAY_MILLIS } from "../../common/Const";
import { Result, ResultWrapper } from "../../common/Result";
import { Formatter } from "../Formatter";
import { Logger } from "../Logger";

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
  private requestManager = new Map<string, boolean>();
  private cacheFile = "";
  private _onDidUpdateRepoInfo = new vscode.EventEmitter<void>();
  public readonly onDidUpdateRepoInfo = this._onDidUpdateRepoInfo.event;
  private logger = Logger.getInstance();

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
    } catch {
    }
    this.cacheFile = path.join(storagePath, "github_repo_cache.json");
    await this.loadCacheFromDisk();
  }

  private async loadCacheFromDisk() {
    try {
      await fs.access(this.cacheFile);
      const content = await fs.readFile(this.cacheFile, "utf-8");
      if (content) {
        const map = JSON.parse(content);
        const now = Date.now();
        const threeDaysMillis = 3 * 24 * 60 * 60 * 1000;

        for (const key in map) {
          const val = map[key] as GithubRepoInfo;
          if (now - val.fetchedAt <= threeDaysMillis) {
            this.cache.set(key, val);
          }
        }
      }
    } catch (e) {
      this.logger.warn(`Failed to load github repo cache: ${e}`);
    }
  }

  private async saveCacheToDiskAsync() {
    try {
      const obj = Object.fromEntries(this.cache);
      await fs.writeFile(this.cacheFile, JSON.stringify(obj));
    } catch (e) {
      this.logger.warn(`Failed to save github repo cache: ${e}`);
    }
  }

  static getRepoKey(path: string): { owner: string; repo: string } | null {
    const raw = path?.trim();
    if (!raw) return null;

    let cleaned = raw;
    cleaned = cleaned.replace(/^git\+/, "");
    cleaned = cleaned.replace(/^git:\/\//, "");
    cleaned = cleaned.replace(/^ssh:\/\//, "");

    if (cleaned.startsWith("github:")) {
      const rest = cleaned.replace(/^github:/, "").replace(/^\/+/, "");
      const parts = rest.split("/").filter(Boolean);
      if (parts.length < 2) return null;
      const owner = parts[0];
      const repo = this.normalizeRepoName(parts[1]);
      if (!owner || !repo) return null;
      return { owner, repo };
    }

    const match = cleaned.match(/github\.com[:/]+([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)/);
    if (!match) return null;

    const owner = match[1];
    const repo = this.normalizeRepoName(match[2]);
    if (!owner || !repo) return null;
    return { owner, repo };
  }

  private static normalizeRepoName(value: string): string {
    return value
      .split("#")[0]
      .split("?")[0]
      .replace(/\.git$/, "")
      .trim();
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

    const runningState = this.requestManager.get(key);
    if (runningState === true) {
      return { result: Result.PENDING };
    }
    if (runningState === false) {
      return { result: Result.FAILURE };
    }
    return { result: Result.NONE };
  }

  async fetchRepoInfo(owner: string, repo: string): Promise<void> {
    const key = `${owner}/${repo}`;
    if (this.requestManager.get(key)) return;
    this.requestManager.set(key, true);
    this._onDidUpdateRepoInfo.fire();

    try {
      const githubToken =
        vscode.workspace.getConfiguration("depLens").get<string>("githubToken") ||
        process.env.GITHUB_TOKEN ||
        "";

      const resp = await fetch(`https://api.github.com/repos/${owner}/${repo}`, {
        headers: {
          Accept: "application/vnd.github+json",
          "User-Agent": "dep-lens-vscode",
          ...(githubToken ? { Authorization: `Bearer ${githubToken}` } : {}),
        },
      });

      if (!resp.ok) {
        this.logger.warn(`GitHub API failed: ${resp.status}`);
        this.requestManager.set(key, false);
        setTimeout(() => {
          this.requestManager.delete(key);
        }, RETRY_DELAY_MILLIS);
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

      this.logger.info(`[Success] ${key}, stars: ${repoInfo.stars}, updated: ${repoInfo.updatedDate}`);

      this.cache.set(key, repoInfo);
      this.saveCacheToDiskAsync();
      this._onDidUpdateRepoInfo.fire();
    } catch (e) {
      this.logger.warn(`GitHub request error: ${key} ${e}`);
      this.requestManager.set(key, false);
      setTimeout(() => {
        this.requestManager.delete(key);
      }, RETRY_DELAY_MILLIS);
    } finally {
      setTimeout(() => {
        this.requestManager.delete(key);
      }, 60000); // Clear running flag after 1 minute
    }
  }

  async retryRepoInfo(owner: string, repo: string): Promise<void> {
    const key = `${owner}/${repo}`;
    this.requestManager.delete(key);
    await this.fetchRepoInfo(owner, repo);
  }

  async clearCache() {
    this.cache.clear();
    await this.saveCacheToDiskAsync();
    this._onDidUpdateRepoInfo.fire();
  }

  shutdown() {
    this._onDidUpdateRepoInfo.dispose();
  }
}
