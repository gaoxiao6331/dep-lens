use std::path::{Path, PathBuf};
use zed_extension_api::{self as zed, LanguageServerId, Result, Worktree};

struct DepLensExtension;

const LSP_PACKAGE_NAME: &str = "dep-lens-zed-lsp";

impl zed::Extension for DepLensExtension {
    fn new() -> Self {
        Self
    }

    fn language_server_command(
        &mut self,
        _language_server_id: &LanguageServerId,
        _worktree: &Worktree,
    ) -> Result<zed::Command> {
        let extension_dir = std::env::current_dir().map_err(|e| e.to_string())?;

        if let Some(binary_path) = find_lsp_binary(&extension_dir) {
            return Ok(zed::Command {
                command: binary_path.to_string_lossy().into_owned(),
                args: Vec::new(),
                env: Vec::new(),
            });
        }

        let manifest_path = extension_dir.join("lsp").join("Cargo.toml");
        if !manifest_path.exists() {
            return Err(format!(
                "Dep Lens LSP manifest not found at {}",
                manifest_path.display()
            ));
        }

        Ok(zed::Command {
            command: "cargo".to_string(),
            args: vec![
                "run".to_string(),
                "--quiet".to_string(),
                "--manifest-path".to_string(),
                manifest_path.to_string_lossy().into_owned(),
            ],
            env: Vec::new(),
        })
    }
}

fn find_lsp_binary(extension_dir: &Path) -> Option<PathBuf> {
    ["release", "debug"]
        .iter()
        .map(|profile| {
            extension_dir
                .join("lsp")
                .join("target")
                .join(profile)
                .join(binary_file_name())
        })
        .find(|path| path.is_file())
}

fn binary_file_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "dep-lens-zed-lsp.exe"
    } else {
        LSP_PACKAGE_NAME
    }
}

zed::register_extension!(DepLensExtension);
