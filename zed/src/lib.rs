use std::path::{Path, PathBuf};
use zed_extension_api::{self as zed, LanguageServerId, Result, Worktree};

struct DepLensExtension;

const EXTENSION_ID: &str = "dep-lens";
const LSP_PACKAGE_NAME: &str = "dep-lens-zed-lsp";

// 注意：Zed 扩展使用 wasm32-wasip2 目标，因为 wasm32-wasip1 产出的是普通 module，Zed 需要的是 wasm component
// 详见 https://zed.dev/docs/extensions

impl zed::Extension for DepLensExtension {
    fn new() -> Self {
        extension_log("extension loaded");
        Self
    }

    fn language_server_command(
        &mut self,
        _language_server_id: &LanguageServerId,
        _worktree: &Worktree,
    ) -> Result<zed::Command> {
        let extension_dir = std::env::current_dir().map_err(|e| e.to_string())?;
        extension_log(&format!("resolving language server command from {}", extension_dir.display()));

        let candidate_dirs = candidate_extension_dirs(&extension_dir);
        extension_log(&format!(
            "candidate extension dirs: {}",
            candidate_dirs
                .iter()
                .map(|dir| dir.display().to_string())
                .collect::<Vec<_>>()
                .join(", ")
        ));

        if let Some(binary_path) = find_lsp_binary(&candidate_dirs) {
            extension_log(&format!(
                "starting bundled LSP binary: {}",
                binary_path.display()
            ));
            return Ok(zed::Command {
                command: binary_path.to_string_lossy().into_owned(),
                args: Vec::new(),
                env: Vec::new(),
            });
        }

        if let Some(manifest_path) = find_lsp_manifest(&candidate_dirs) {
            extension_log(&format!(
                "bundled LSP binary not found, falling back to cargo run with manifest {}",
                manifest_path.display()
            ));
            return Ok(zed::Command {
                command: "cargo".to_string(),
                args: vec![
                    "run".to_string(),
                    "--quiet".to_string(),
                    "--manifest-path".to_string(),
                    manifest_path.to_string_lossy().into_owned(),
                ],
                env: Vec::new(),
            });
        }

        extension_log("missing LSP binary and manifest in all candidate dirs");
        Err(format!(
            "Dep Lens LSP not found in candidate dirs: {}",
            candidate_dirs
                .iter()
                .map(|dir| dir.display().to_string())
                .collect::<Vec<_>>()
                .join(", ")
        ))
    }
}

fn candidate_extension_dirs(extension_dir: &Path) -> Vec<PathBuf> {
    let mut candidates = vec![extension_dir.to_path_buf()];

    if let Some(installed_dir) = installed_dir_from_work_dir(extension_dir) {
        candidates.push(installed_dir);
    }

    if let Some(home_dir) = std::env::var_os("HOME") {
        candidates.push(
            PathBuf::from(home_dir)
                .join("Library")
                .join("Application Support")
                .join("Zed")
                .join("extensions")
                .join("installed")
                .join(EXTENSION_ID),
        );
    }

    candidates.dedup();
    candidates
}

fn installed_dir_from_work_dir(extension_dir: &Path) -> Option<PathBuf> {
    let extension_name = extension_dir.file_name()?;
    let work_dir = extension_dir.parent()?;
    if work_dir.file_name()?.to_str()? != "work" {
        return None;
    }

    Some(work_dir.parent()?.join("installed").join(extension_name))
}

fn find_lsp_binary(extension_dirs: &[PathBuf]) -> Option<PathBuf> {
    extension_dirs.iter().find_map(|extension_dir| {
        // 优先级1：先检查候选目录根目录是否有二进制（install-local.sh 会复制一份到这里）
        // 这是最可靠的查找方式，因为 Zed 的 wasm 扩展沙箱只对目录根有明确可见性
        let direct_path = extension_dir.join(binary_file_name());
        if direct_path.is_file() {
            return Some(direct_path);
        }

        // 优先级2：检查常见的 cargo target 路径（本地开发时常用）
        ["release", "debug"]
            .iter()
            .flat_map(|profile| {
                [
                    extension_dir.join("lsp").join("target").join(profile).join(binary_file_name()),
                    extension_dir.join("target").join(profile).join(binary_file_name()),
                ]
            })
            .find(|path| path.is_file())
    })
}

fn find_lsp_manifest(extension_dirs: &[PathBuf]) -> Option<PathBuf> {
    extension_dirs
        .iter()
        .map(|extension_dir| extension_dir.join("lsp").join("Cargo.toml"))
        .find(|path| path.is_file())
}

fn binary_file_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "dep-lens-zed-lsp.exe"
    } else {
        LSP_PACKAGE_NAME
    }
}

fn extension_log(message: &str) {
    eprintln!("[DepLens Zed Extension] {message}");
}

zed::register_extension!(DepLensExtension);
