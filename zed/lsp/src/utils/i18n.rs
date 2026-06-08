use crate::common::i18n_key;

pub struct I18n;

impl I18n {
    pub fn message(key: &str) -> String {
        match key {
            i18n_key::LOADING_GITHUB => "loading github...".to_string(),
            i18n_key::FAILED_GITHUB => "github info failed".to_string(),
            i18n_key::LAST_UPDATED => "last updated at".to_string(),
            _ => key.to_string(),
        }
    }
}
