use crate::base_dep_lens_inlay_provider::BaseDepLensInlayProvider;
use crate::lsp::{InlayHint, Position, Range, TextDocument};
use crate::utils::inlay::github_inlay_utils::GithubInlayUtils;
use crate::utils::service::github_repo_info_service::GithubRepoInfoService;

pub struct RustDepLensInlayProvider;

impl RustDepLensInlayProvider {
    pub fn new() -> Self {
        GithubRepoInfoService::get_instance().on_did_update_repo_info_noop();
        Self
    }
}

impl BaseDepLensInlayProvider for RustDepLensInlayProvider {
    fn is_file_supported(&self, document: &TextDocument) -> bool {
        document.file_name.ends_with("Cargo.toml")
    }

    fn provide_inlay_hints_for_document(
        &self,
        document: &TextDocument,
        range: &Range,
    ) -> Vec<InlayHint> {
        let mut hints = Vec::new();
        for dependency in dep_lens_lib::rust::parse_rust_dependencies(
            document.text(),
            &document.file_name,
            &document.language_id,
            range.start.line,
            range.end.line,
        ) {
            GithubInlayUtils::add_repo_inlay(
                &mut hints,
                Position {
                    line: dependency.line,
                    character: dependency.character,
                },
                dependency.owner,
                dependency.repo,
            );
        }

        hints
    }
}
