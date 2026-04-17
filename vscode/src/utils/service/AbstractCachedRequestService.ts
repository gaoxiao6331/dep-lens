import { Result } from "../../common/Result";
import { Logger } from "../Logger";

export interface CachedEntry {
  fetchedAt: number;
}

export class ResultWrapper<T> {
  constructor(
    public result: Result,
    public data?: T
  ) {}
}

export abstract class AbstractCachedRequestService<T extends CachedEntry> {
  protected cache = new Map<string, ResultWrapper<T>>();
  protected runningRequests = new Set<string>();
  protected failureCounts = new Map<string, number>();
  protected maxFailureCount = 3;
  protected cacheExpiryMillis = 3 * 24 * 60 * 60 * 1000;
  protected logger: Logger;

  constructor(
    protected cacheFileName: string,
    protected dataSerializer: any
  ) {
    this.logger = Logger.getInstance();
    this.initCache();
  }

  abstract initCache(): void | Promise<void>;
  abstract createRequestCall(key: string): Promise<Response>;
  abstract parseResponseBody(key: string, body: string): Promise<T | null>;

  getCachedInfo(key: string): ResultWrapper<T> {
    const cached = this.cache.get(key);
    
    if (!cached) {
      return new ResultWrapper(Result.NONE);
    }

    if (cached.result === Result.SUCCESS) {
      const data = cached.data;
      if (data && (Date.now() - data.fetchedAt) > this.cacheExpiryMillis) {
        // Cache expired, but return it anyway and trigger background refresh
        Promise.resolve().then(() => this.fetchByKey(key));
        return cached;
      }
    }

    return cached;
  }

  isRequestRunning(key: string): boolean {
    return this.runningRequests.has(key);
  }

  hasFailure(key: string): boolean {
    return (this.failureCounts.get(key) || 0) >= this.maxFailureCount;
  }

  async fetchByKey(key: string, onFinish?: () => void): Promise<void> {
    if (this.runningRequests.has(key)) {
      return;
    }

    this.runningRequests.add(key);
    this.cache.set(key, new ResultWrapper(Result.PENDING));

    try {
      const response = await this.createRequestCall(key);
      
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      const body = await response.text();
      const data = await this.parseResponseBody(key, body);

      if (data) {
        this.cache.set(key, new ResultWrapper(Result.SUCCESS, data));
        this.failureCounts.delete(key);
        this.logger.info(`[Request Success] ${key}`);
      } else {
        throw new Error("Failed to parse response");
      }
    } catch (error) {
        this.logger.warn(`[Request Failed] ${key}: ${error}`);
      
      const currentFailures = this.failureCounts.get(key) || 0;
      this.failureCounts.set(key, currentFailures + 1);
      
      if (currentFailures + 1 >= this.maxFailureCount) {
        this.cache.set(key, new ResultWrapper(Result.FAILURE));
      } else {
        this.cache.set(key, new ResultWrapper(Result.NONE));
      }
    } finally {
      this.runningRequests.delete(key);
      
      if (onFinish) {
        setTimeout(onFinish, 100);
      }
    }
  }

  async retryByKey(key: string, onFinish?: () => void): Promise<void> {
    // Explicit retry: bypass failure quota and trigger immediate fetch
    this.failureCounts.delete(key);
    await this.fetchByKey(key, onFinish);
  }

  clearCache(): void {
    this.cache.clear();
    this.failureCounts.clear();
    this.runningRequests.clear();
  }

  shutdown(): void {
    this.runningRequests.clear();
    this.cache.clear();
    this.failureCounts.clear();
  }
}
