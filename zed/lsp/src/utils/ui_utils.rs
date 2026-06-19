use serde_json::json;

use crate::common::i18n_key;
use crate::lsp::{InlayHint, MarkupContent, Position};
use crate::utils::i18n::I18n;

pub struct UiUtils;

impl UiUtils {
    pub fn add_inlay(
        hints: &mut Vec<InlayHint>,
        position: Position,
        display_text: String,
        hover_text: String,
        github_url: Option<String>,
        retry_token: Option<String>,
    ) {
        let mut tooltip = hover_text.clone();
        if let Some(github_url) = &github_url {
            tooltip.push_str(&format!("\n\n[Open on GitHub]({github_url})"));
        }
        if retry_token.is_some() {
            tooltip.push_str(&format!("\n\n{}", I18n::message(i18n_key::RELOAD_TO_RETRY)));
        }

        hints.push(InlayHint {
            position,
            label: format!("  {display_text}"),
            tooltip: Some(MarkupContent {
                kind: "markdown".to_string(),
                value: tooltip,
            }),
            padding_left: false,
            padding_right: false,
            data: Some(json!({
                "githubUrl": github_url,
                "retryToken": retry_token
            })),
        });
    }
}
