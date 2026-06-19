use crate::common::i18n_key;
use serde::Deserialize;
use std::sync::OnceLock;

#[allow(non_snake_case)]
#[derive(Deserialize)]
struct I18nText {
    loadingGithub: String,
    failedGithub: String,
    lastUpdated: String,
    reloadToRetry: String,
}

static EN: &str = include_str!("../../../../config/i18n/en_US.json");
static CN: &str = include_str!("../../../../config/i18n/zh_CN.json");

static TEXT: OnceLock<I18nText> = OnceLock::new();

fn detect_language() -> &'static str {
    // Try LANG environment variable first
    if let Ok(lang) = std::env::var("LANG") {
        if lang.starts_with("zh") {
            return "zh";
        }
    }
    // Then try LC_ALL
    if let Ok(lang) = std::env::var("LC_ALL") {
        if lang.starts_with("zh") {
            return "zh";
        }
    }
    "en"
}

fn load_text() -> I18nText {
    let lang = detect_language();
    let json = if lang == "zh" { CN } else { EN };
    serde_json::from_str(json).unwrap_or_else(|_| serde_json::from_str(EN).unwrap())
}

pub struct I18n;

impl I18n {
    pub fn message(key: &str) -> String {
        let text = TEXT.get_or_init(load_text);
        match key {
            i18n_key::LOADING_GITHUB => text.loadingGithub.clone(),
            i18n_key::FAILED_GITHUB => text.failedGithub.clone(),
            i18n_key::LAST_UPDATED => text.lastUpdated.clone(),
            i18n_key::RELOAD_TO_RETRY => text.reloadToRetry.clone(),
            _ => key.to_string(),
        }
    }
}
