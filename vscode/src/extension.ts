import * as vscode from 'vscode'
import { fetch } from 'undici'
import { loadLocale, t } from './i18n'
import { logger } from "./logger";
import { STAR_PLACEHOLDER, LAST_UPDATED_PLACEHOLDER, DATE_PLACEHOLDER } from './const'

type RepoInfo = { stars: number; updatedDate: string }
type Cached = { info: RepoInfo; ts: number }

const cache: Map<string, Cached> = new Map()
let ttlMillis = 10 * 60 * 1000
let showLoading = true
let enabled = true
let githubToken = ''
let labelFormat = '';
const inFlight: Map<string, Promise<RepoInfo>> = new Map()

const reGithubImport = /"github\.com\/([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)(?:\/[^"]*)?"/

async function getRepoInfo(owner: string, repo: string): Promise<RepoInfo> {
  const key = `${owner}/${repo}`
  const now = Date.now()
  const c = cache.get(key)
  if (c && now - c.ts < ttlMillis) return c.info
  const p = inFlight.get(key) ?? fetchRepoInfo(key)
  inFlight.set(key, p)
  const info = await p.finally(() => inFlight.delete(key))
  cache.set(key, { info, ts: now })
  return info
}

function getRepoInfoCached(owner: string, repo: string): RepoInfo | undefined {
  const key = `${owner}/${repo}`
  const now = Date.now()
  const c = cache.get(key)
  if (c && now - c.ts < ttlMillis) return c.info
  return undefined
}

async function fetchRepoInfo(fullName: string): Promise<RepoInfo> {
  try {
    const resp = await fetch(`https://api.github.com/repos/${fullName}`, {
      headers: {
        'Accept': 'application/vnd.github+json',
        'User-Agent': 'dep-lens-vscode',
        ...(githubToken ? { 'Authorization': `Bearer ${githubToken}` } : {})
      }
    })
    if (!resp.ok) return { stars: 0, updatedDate: '—' }
    const json = (await resp.json()) as any
    const stars = Number(json.stargazers_count || 0)
    const updated = String(json.updated_at || '').slice(0, 10) || '—'
    return { stars, updatedDate: updated }
  } catch {
    return { stars: 0, updatedDate: '—' }
  }
}

class GithubInlayProvider implements vscode.InlayHintsProvider {
  private emitter = new vscode.EventEmitter<void>()
  readonly onDidChangeInlayHints = this.emitter.event

  async provideInlayHints(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken
  ): Promise<vscode.InlayHint[]> {
    if (!enabled) return []
    const hints: vscode.InlayHint[] = []
    const start = range.start.line
    const end = range.end.line
    for (let line = start; line <= end; line++) {
      const text = document.lineAt(line).text
      const m = text.match(reGithubImport)
      if (!m) continue
      const owner = m[1]
      const repo = m[2]
      const endIdx = text.lastIndexOf('"')
      if (endIdx < 0) continue
      const pos = new vscode.Position(line, endIdx + 1)
      const cached = getRepoInfoCached(owner, repo)
      if (cached) {
        const label = labelFormat
          .replace(STAR_PLACEHOLDER, String(cached.stars))
          .replace(DATE_PLACEHOLDER, cached.updatedDate)
          .replace(LAST_UPDATED_PLACEHOLDER, t('lastUpdated'))
        hints.push(new vscode.InlayHint(pos, `  ${label}`))
      } else {
        if (showLoading) {
          hints.push(new vscode.InlayHint(pos, `  ${t('loading')}`))
        }
        getRepoInfo(owner, repo).then(() => this.emitter.fire())
      }
    }
    return hints
  }
}

export async function activate(context: vscode.ExtensionContext) {

  const selector = { language: 'go', scheme: 'file' }
  const provider = new GithubInlayProvider()
  context.subscriptions.push(vscode.languages.registerInlayHintsProvider(selector, provider))
  await loadLocale(context)
  applyConfig()
  context.subscriptions.push(vscode.workspace.onDidChangeConfiguration((e: vscode.ConfigurationChangeEvent) => {
    if (
      e.affectsConfiguration('depLens.enabled') ||
      e.affectsConfiguration('depLens.cacheTtlMinutes') ||
      e.affectsConfiguration('depLens.githubToken') ||
      e.affectsConfiguration('depLens.showLoading') ||
      e.affectsConfiguration('depLens.labelFormat')
    ) {
      applyConfig()
      provider['emitter'].fire()
    }
  }))
  context.subscriptions.push(vscode.commands.registerCommand('depLens.clearCache', () => {
    cache.clear()
    provider['emitter'].fire()
  }))

    context.subscriptions.push({
    dispose: () => logger.dispose(),
  });
  logger.info("Extension activated");
}

export function deactivate() {
  logger.info("Extension deactivated");
}

function applyConfig() {
  const cfg = vscode.workspace.getConfiguration('depLens')
  enabled = cfg.get<boolean>('enabled', true)
  const ttlMin = cfg.get<number>('cacheTtlMinutes', 10)
  ttlMillis = Math.max(1, ttlMin) * 60 * 1000
  showLoading = cfg.get<boolean>('showLoading', true)
  const defaultFormat = `⭐ ${STAR_PLACEHOLDER} • ${LAST_UPDATED_PLACEHOLDER} ${DATE_PLACEHOLDER}`
  labelFormat = cfg.get<string>('labelFormat', defaultFormat)
  githubToken = cfg.get<string>('githubToken', '') || process.env.GITHUB_TOKEN || ''
}
