export interface RepoKey {
  owner: string;
  repo: string;
}

export class GithubRepoInfoService {
  static getRepoKey(url: string): RepoKey | null {
    if (!url || typeof url !== 'string') {
      return null;
    }

    // Handle different GitHub URL formats
    const patterns = [
      /^https?:\/\/github\.com\/([^\/]+)\/([^\/]+?)(?:\.git)?$/,  // https://github.com/owner/repo
      /^git@github\.com:([^\/]+)\/([^\/]+?)(?:\.git)?$/,            // git@github.com:owner/repo
      /^https?:\/\/([^\/]+)\.github\.io\/([^\/]+?)$/,             // https://owner.github.io/repo
    ];

    for (const pattern of patterns) {
      const match = url.match(pattern);
      if (match) {
        return {
          owner: match[1],
          repo: match[2]
        };
      }
    }

    // Try to parse as direct owner/repo format
    if (url.includes('/')) {
      const parts = url.split('/');
      if (parts.length >= 2) {
        return {
          owner: parts[parts.length - 2],
          repo: parts[parts.length - 1].replace(/\.git$/, '')
        };
      }
    }

    return null;
  }
}