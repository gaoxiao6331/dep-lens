use crate::common::i18n_key;
use crate::common::result::Result;
use crate::lsp::{InlayHint, Position};
use crate::utils::i18n::I18n;
use crate::utils::service::github_repo_info_service::GithubRepoInfoService;
use crate::utils::ui_utils::UiUtils;

pub struct GithubInlayUtils;

impl GithubInlayUtils {
    pub fn add_repo_inlay(
        hints: &mut Vec<InlayHint>,
        position: Position,
        owner: String,
        repo: String,
    ) {
        let repo_id = format!("{owner}/{repo}");
        let res = GithubRepoInfoService::get_instance().get_repo_info(&owner, &repo);

        let retry_token = format!("github:{owner}/{repo}");
        let github_url = format!("https://github.com/{owner}/{repo}");

        let label = match res.result {
            Result::None => {
                GithubRepoInfoService::get_instance().start_fetch_repo_info(owner.clone(), repo.clone(), repo_id);
                I18n::message(i18n_key::LOADING_GITHUB)
            }
            Result::Pending => I18n::message(i18n_key::LOADING_GITHUB),
            Result::Success => {
                let data = res.data.expect("success result has data");
                format!(
                    "⭐ {} • {} {}",
                    data.stars,
                    I18n::message(i18n_key::LAST_UPDATED),
                    data.updated_date
                )
            }
            Result::Failure => I18n::message(i18n_key::FAILED_GITHUB),
        };

        UiUtils::add_inlay(
            hints,
            position,
            label.clone(),
            label,
            Some(github_url),
            Some(retry_token),
        );
    }
}
