const MAX_RETRY_COUNT = 3;
const FAILED_EXPIRE_TIME = 30 * 60 * 1000; // 30 minutes

export class RequestManager {
  private failed = new Map<string, number[]>();

  shouldRequest(key: string): boolean {
    const now = Date.now();
    let items = this.failed.get(key) || [];

    // Filter expired failures
    items = items.filter((expireTime) => expireTime > now);
    this.failed.set(key, items);

    const cnt = items.length;
    return cnt < MAX_RETRY_COUNT;
  }

  updateFailed(key: string): void {
    const now = Date.now();
    const items = this.failed.get(key) || [];
    const expireTimes = [...items, now + FAILED_EXPIRE_TIME];
    this.failed.set(key, expireTimes);
  }
}
