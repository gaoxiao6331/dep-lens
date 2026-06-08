use regex::Regex;
use std::sync::OnceLock;

use crate::base_dep_lens_inlay_provider::BaseDepLensInlayProvider;
use crate::lsp::{InlayHint, Position, Range, TextDocument};
use crate::utils::inlay::github_inlay_utils::GithubInlayUtils;
use crate::utils::service::github_repo_info_service::GithubRepoInfoService;

fn re_github_import() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r#""github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:/[^"]*)?""#)
            .expect("valid GitHub Go import regex")
    })
}

fn re_github_mod() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r"github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)")
            .expect("valid GitHub go.mod regex")
    })
}

pub struct GoDepLensInlayProvider;

impl GoDepLensInlayProvider {
    pub fn new() -> Self {
        GithubRepoInfoService::get_instance().on_did_update_repo_info_noop();
        Self
    }
}

impl BaseDepLensInlayProvider for GoDepLensInlayProvider {
    fn is_file_supported(&self, document: &TextDocument) -> bool {
        document.language_id == "go" || document.file_name.ends_with("go.mod")
    }

    fn provide_inlay_hints_for_document(
        &self,
        document: &TextDocument,
        range: &Range,
    ) -> Vec<InlayHint> {
        let mut hints = Vec::new();
        let start = range.start.line;
        let end = range.end.line;
        let is_go_mod = document.file_name.ends_with("go.mod");

        for line in start..=end {
            let text = document.line_at(line);

            if is_go_mod && text.contains("// indirect") {
                continue;
            }

            let captures = if is_go_mod {
                re_github_mod().captures(text)
            } else {
                re_github_import().captures(text)
            };
            let Some(captures) = captures else {
                continue;
            };

            let Some(owner) = captures.get(1).map(|m| m.as_str().to_string()) else {
                continue;
            };
            let Some(repo) = captures.get(2).map(|m| m.as_str().to_string()) else {
                continue;
            };

            let end_idx = if is_go_mod {
                utf16_len(text)
            } else {
                let Some(byte_idx) = text.rfind('"') else {
                    continue;
                };
                utf16_len(&text[..byte_idx]) + 1
            };

            let pos = Position {
                line,
                character: end_idx,
            };

            GithubInlayUtils::add_repo_inlay(&mut hints, pos, owner, repo);
        }

        hints
    }
}

fn utf16_len(value: &str) -> usize {
    value.encode_utf16().count()
}
