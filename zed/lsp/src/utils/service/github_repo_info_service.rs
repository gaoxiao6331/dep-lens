use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::sync::{mpsc, Arc, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use crate::common::consts::RETRY_DELAY_MILLIS;
use crate::common::result::{Result, ResultWrapper};
use crate::utils::formatter::Formatter;
use crate::utils::logger::logger;

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct GithubRepoInfo {
    pub stars: String,
    #[serde(rename = "originalStars")]
    pub original_stars: u64,
    #[serde(rename = "updatedDate")]
    pub updated_date: String,
    #[serde(rename = "fetchedAt")]
    pub fetched_at: u128,
}

#[derive(Debug, Deserialize)]
struct GithubApiResponse {
    stargazers_count: u64,
    pushed_at: String,
}

#[derive(Debug)]
struct GithubRepoInfoServiceState {
    cache: HashMap<String, GithubRepoInfo>,
    // true means a fetch is currently in flight, false means the last fetch failed
    // and we are waiting for the retry window to expire.
    request_manager: HashMap<String, bool>,
    cache_file: PathBuf,
}

pub struct GithubRepoInfoService {
    state: Mutex<GithubRepoInfoServiceState>,
    listeners: Mutex<Vec<mpsc::Sender<()>>>,
}

impl GithubRepoInfoService {
    pub fn get_instance() -> Arc<GithubRepoInfoService> {
        static INSTANCE: OnceLock<Arc<GithubRepoInfoService>> = OnceLock::new();
        INSTANCE
            .get_or_init(|| Arc::new(GithubRepoInfoService::new()))
            .clone()
    }

    fn new() -> Self {
        Self {
            state: Mutex::new(GithubRepoInfoServiceState {
                cache: HashMap::new(),
                request_manager: HashMap::new(),
                cache_file: default_cache_file(),
            }),
            listeners: Mutex::new(Vec::new()),
        }
    }

    pub fn init(&self) {
        let cache_file = {
            self.state
                .lock()
                .expect("state mutex poisoned")
                .cache_file
                .clone()
        };
        if let Some(parent) = cache_file.parent() {
            if let Err(error) = fs::create_dir_all(parent) {
                logger().warn(&format!("Failed to create github repo cache dir: {error}"));
            }
        }
        self.load_cache_from_disk();
    }

    pub fn on_did_update_repo_info(&self, sender: mpsc::Sender<()>) {
        self.listeners
            .lock()
            .expect("listeners mutex poisoned")
            .push(sender);
    }

    pub fn on_did_update_repo_info_noop(&self) {}

    fn fire_did_update_repo_info(&self) {
        let listeners = self
            .listeners
            .lock()
            .expect("listeners mutex poisoned")
            .clone();

        for listener in listeners {
            let _ = listener.send(());
        }
    }

    fn load_cache_from_disk(&self) {
        let cache_file = {
            self.state
                .lock()
                .expect("state mutex poisoned")
                .cache_file
                .clone()
        };
        let Ok(content) = fs::read_to_string(cache_file) else {
            return;
        };
        if content.is_empty() {
            return;
        }

        let Ok(map) = serde_json::from_str::<HashMap<String, GithubRepoInfo>>(&content) else {
            logger().warn("Failed to parse github repo cache");
            return;
        };

        let now = now_millis();
        let three_days_millis = 3 * 24 * 60 * 60 * 1000;
        let mut state = self.state.lock().expect("state mutex poisoned");
        for (key, val) in map {
            // Drop stale entries on load so the cache file can survive across sessions
            // without forcing the UI to show obviously outdated metadata.
            if now.saturating_sub(val.fetched_at) <= three_days_millis {
                state.cache.insert(key, val);
            }
        }
    }

    fn save_cache_to_disk_async(&self) {
        let (cache_file, cache) = {
            let state = self.state.lock().expect("state mutex poisoned");
            (state.cache_file.clone(), state.cache.clone())
        };

        thread::spawn(move || {
            let Ok(content) = serde_json::to_string(&cache) else {
                logger().warn("Failed to serialize github repo cache");
                return;
            };
            if let Err(error) = fs::write(cache_file, content) {
                logger().warn(&format!("Failed to save github repo cache: {error}"));
            }
        });
    }

    #[allow(dead_code)]
    pub fn get_repo_key(repo_path: &str) -> Option<(String, String)> {
        let raw = repo_path.trim();
        if raw.is_empty() {
            return None;
        }

        let mut cleaned = raw.to_string();
        cleaned = cleaned.strip_prefix("git+").unwrap_or(&cleaned).to_string();
        cleaned = cleaned
            .strip_prefix("git://")
            .unwrap_or(&cleaned)
            .to_string();
        cleaned = cleaned
            .strip_prefix("ssh://")
            .unwrap_or(&cleaned)
            .to_string();

        if let Some(rest) = cleaned.strip_prefix("github:") {
            let parts = rest
                .trim_start_matches('/')
                .split('/')
                .filter(|part| !part.is_empty())
                .collect::<Vec<_>>();
            if parts.len() < 2 {
                return None;
            }
            let owner = parts[0].to_string();
            let repo = normalize_repo_name(parts[1]);
            if owner.is_empty() || repo.is_empty() {
                return None;
            }
            return Some((owner, repo));
        }

        let Some(index) = cleaned.find("github.com") else {
            return None;
        };
        let rest = cleaned[index + "github.com".len()..].trim_start_matches([':', '/']);
        let parts = rest.split('/').collect::<Vec<_>>();
        if parts.len() < 2 {
            return None;
        }

        let owner = parts[0].to_string();
        let repo = normalize_repo_name(parts[1]);
        if owner.is_empty() || repo.is_empty() {
            return None;
        }
        Some((owner, repo))
    }

    pub fn get_repo_info(&self, owner: &str, repo: &str) -> ResultWrapper<GithubRepoInfo> {
        let key = format!("{owner}/{repo}");
        {
            let mut state = self.state.lock().expect("state mutex poisoned");
            if let Some(info) = state.cache.get(&key) {
                let three_days_millis = 3 * 24 * 60 * 60 * 1000;
                if now_millis().saturating_sub(info.fetched_at) > three_days_millis {
                    // Expire stale entries eagerly so the next UI refresh can trigger a refetch.
                    state.cache.remove(&key);
                    drop(state);
                    self.save_cache_to_disk_async();
                } else {
                    return ResultWrapper {
                        result: Result::Success,
                        data: Some(info.clone()),
                    };
                }
            }
        }

        let state = self.state.lock().expect("state mutex poisoned");
        match state.request_manager.get(&key).copied() {
            Some(true) => ResultWrapper {
                result: Result::Pending,
                data: None,
            },
            Some(false) => ResultWrapper {
                result: Result::Failure,
                data: None,
            },
            None => ResultWrapper {
                result: Result::None,
                data: None,
            },
        }
    }

    pub fn start_fetch_repo_info(&self, owner: String, repo: String, repo_id: String) {
        let key = format!("{owner}/{repo}");
        {
            let mut state = self.state.lock().expect("state mutex poisoned");
            if state.request_manager.contains_key(&key) {
                return;
            }
            state.request_manager.insert(key.clone(), true);
        }
        self.fire_did_update_repo_info();

        // Fetch in the background so the inlay can render a loading state immediately.
        thread::spawn(move || {
            if let Err(error) =
                GithubRepoInfoService::get_instance().finish_fetch_repo_info(&owner, &repo, key)
            {
                logger().warn(&format!("Failed to load repo info for {repo_id}: {error}"));
            }
        });
    }

    pub fn fetch_repo_info(&self, owner: &str, repo: &str) -> std::result::Result<(), String> {
        let key = format!("{owner}/{repo}");
        {
            let mut state = self.state.lock().expect("state mutex poisoned");
            if matches!(state.request_manager.get(&key), Some(false)) {
                state.request_manager.insert(key.clone(), true);
            } else if state.request_manager.get(&key).copied().unwrap_or(false) {
                return Ok(());
            } else {
                state.request_manager.insert(key.clone(), true);
            }
        }
        self.fire_did_update_repo_info();

        self.finish_fetch_repo_info(owner, repo, key)
    }

    fn finish_fetch_repo_info(
        &self,
        owner: &str,
        repo: &str,
        key: String,
    ) -> std::result::Result<(), String> {
        let result = self.fetch_repo_info_inner(owner, repo);
        match result {
            Ok(repo_info) => {
                logger().info(&format!(
                    "[Success] {key}, stars: {}, updated: {}",
                    repo_info.stars, repo_info.updated_date
                ));
                {
                    let mut state = self.state.lock().expect("state mutex poisoned");
                    state.cache.insert(key.clone(), repo_info);
                }
                self.save_cache_to_disk_async();
                self.fire_did_update_repo_info();
            }
            Err(error) => {
                logger().warn(&format!("GitHub request error: {key} {error}"));
                {
                    let mut state = self.state.lock().expect("state mutex poisoned");
                    state.request_manager.insert(key.clone(), false);
                }
                // Keep the failed state briefly so repeated renders do not hammer the API.
                self.clear_request_state_after(key.clone(), RETRY_DELAY_MILLIS);
                return Err(error);
            }
        }

        // Successful requests are also rate-limited for a short period to avoid duplicate fetches.
        self.clear_request_state_after(key, 60000);
        Ok(())
    }

    fn fetch_repo_info_inner(
        &self,
        owner: &str,
        repo: &str,
    ) -> std::result::Result<GithubRepoInfo, String> {
        let url = format!("https://api.github.com/repos/{owner}/{repo}");
        logger().info(&format!("Fetching GitHub repo info for {url}"));
        
        let agent = ureq::AgentBuilder::new()
            .timeout_read(std::time::Duration::from_secs(10))
            .timeout_connect(std::time::Duration::from_secs(10))
            .build();
            
        let mut request = agent.get(&url)
            .set("Accept", "application/vnd.github+json")
            .set("User-Agent", "dep-lens-zed");

        if let Ok(github_token) =
            std::env::var("DEP_LENS_GITHUB_TOKEN").or_else(|_| std::env::var("GITHUB_TOKEN"))
        {
            if !github_token.is_empty() {
                logger().debug("Using GitHub token from env");
                request = request.set("Authorization", &format!("Bearer {github_token}"));
            }
        }

        let response = request.call().map_err(|error| {
            logger().warn(&format!("GitHub request failed for {url}: {error}"));
            error.to_string()
        })?;
        logger().info(&format!("GitHub API response status: {}", response.status()));
        
        let body = response.into_string().map_err(|error| error.to_string())?;
        logger().debug(&format!("GitHub API response body: {}", &body[0..std::cmp::min(200, body.len())]));
        
        let json =
            serde_json::from_str::<GithubApiResponse>(&body).map_err(|error| error.to_string())?;
        // We only show the date portion in the inlay to keep the label compact.
        let updated_date = json
            .pushed_at
            .split('T')
            .next()
            .unwrap_or("N/A")
            .to_string();

        Ok(GithubRepoInfo {
            stars: Formatter::format_github_star(json.stargazers_count),
            original_stars: json.stargazers_count,
            updated_date,
            fetched_at: now_millis(),
        })
    }

    pub fn retry_repo_info(&self, owner: &str, repo: &str) -> std::result::Result<(), String> {
        let key = format!("{owner}/{repo}");
        self.state
            .lock()
            .expect("state mutex poisoned")
            .request_manager
            .remove(&key);
        self.fetch_repo_info(owner, repo)
    }

    #[allow(dead_code)]
    pub fn clear_cache(&self) {
        self.state
            .lock()
            .expect("state mutex poisoned")
            .cache
            .clear();
        self.save_cache_to_disk_async();
        self.fire_did_update_repo_info();
    }

    pub fn shutdown(&self) {
        self.listeners
            .lock()
            .expect("listeners mutex poisoned")
            .clear();
    }

    fn clear_request_state_after(&self, key: String, delay_millis: u64) {
        let service = Self::get_instance();
        thread::spawn(move || {
            thread::sleep(Duration::from_millis(delay_millis));
            service
                .state
                .lock()
                .expect("state mutex poisoned")
                .request_manager
                .remove(&key);
        });
    }
}

#[allow(dead_code)]
fn normalize_repo_name(value: &str) -> String {
    // Match the looser repository URL handling used by editor extensions:
    // strip fragments, queries and a trailing .git suffix.
    let without_hash = value.split('#').next().unwrap_or(value);
    let without_query = without_hash.split('?').next().unwrap_or(without_hash);
    without_query
        .strip_suffix(".git")
        .unwrap_or(without_query)
        .trim()
        .to_string()
}

fn default_cache_file() -> PathBuf {
    if let Ok(xdg_cache_home) = std::env::var("XDG_CACHE_HOME") {
        if !xdg_cache_home.is_empty() {
            return PathBuf::from(xdg_cache_home)
                .join("dep-lens")
                .join("zed")
                .join("github_repo_cache.json");
        }
    }

    std::env::var("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|_| std::env::temp_dir())
        .join(".cache")
        .join("dep-lens")
        .join("zed")
        .join("github_repo_cache.json")
}

fn now_millis() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
}

#[cfg(test)]
mod tests {
    use super::GithubRepoInfoService;

    #[test]
    fn parses_github_repo_key_like_vscode() {
        assert_eq!(
            GithubRepoInfoService::get_repo_key("https://github.com/owner/repo.git"),
            Some(("owner".to_string(), "repo".to_string()))
        );
        assert_eq!(
            GithubRepoInfoService::get_repo_key("github:owner/repo#main"),
            Some(("owner".to_string(), "repo".to_string()))
        );
        assert_eq!(
            GithubRepoInfoService::get_repo_key("https://example.com/x/y"),
            None
        );
    }
}
