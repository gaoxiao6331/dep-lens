use crate::base_dep_lens_inlay_provider::BaseDepLensInlayProvider;
use crate::lsp::{InlayHint, Position, Range, TextDocument};
use crate::utils::inlay::github_inlay_utils::GithubInlayUtils;
use crate::utils::service::github_repo_info_service::GithubRepoInfoService;

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
        for dependency in dep_lens_lib::go::parse_go_dependencies(
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
