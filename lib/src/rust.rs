use regex::Regex;
use serde::Serialize;
use std::cell::RefCell;
use std::sync::OnceLock;

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct RustDependency {
    pub owner: String,
    pub repo: String,
    pub line: usize,
    pub character: usize,
}

fn re_section_header() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r#"^\s*\[([^\]]+)\]\s*$"#).expect("valid Cargo.toml section regex"))
}

fn re_github_repo() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r#"github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:[/?#][^"\s]*)?"#)
            .expect("valid GitHub repo regex")
    })
}

pub fn parse_rust_dependencies(
    text: &str,
    file_name: &str,
    _language_id: &str,
    start_line: usize,
    end_line: usize,
) -> Vec<RustDependency> {
    if start_line > end_line || !file_name.ends_with("Cargo.toml") {
        return Vec::new();
    }

    let mut dependencies = Vec::new();
    let mut in_dependency_section = false;

    for (line, line_text) in text.lines().enumerate() {
        if line < start_line {
            continue;
        }
        if line > end_line {
            break;
        }

        if let Some(captures) = re_section_header().captures(line_text) {
            let section = captures.get(1).map(|m| m.as_str()).unwrap_or("");
            in_dependency_section = is_dependency_section(section);
            continue;
        }

        if !in_dependency_section {
            continue;
        }

        let Some((owner, repo)) = parse_github_dependency(line_text) else {
            continue;
        };

        let character = utf16_len(line_text);

        dependencies.push(RustDependency {
            owner,
            repo,
            line,
            character,
        });
    }

    dependencies
}

fn is_dependency_section(section: &str) -> bool {
    matches!(
        section,
        "dependencies"
            | "dev-dependencies"
            | "build-dependencies"
            | "workspace.dependencies"
    ) || section.ends_with(".dependencies")
        || section.ends_with(".dev-dependencies")
        || section.ends_with(".build-dependencies")
        || section.starts_with("dependencies.")
        || section.starts_with("dev-dependencies.")
        || section.starts_with("build-dependencies.")
        || section.starts_with("workspace.dependencies.")
}

fn parse_github_dependency(line_text: &str) -> Option<(String, String)> {
    let captures = re_github_repo().captures(line_text)?;
    let owner = captures.get(1)?.as_str().to_string();
    let repo = captures
        .get(2)?
        .as_str()
        .strip_suffix(".git")
        .unwrap_or(captures.get(2)?.as_str())
        .to_string();
    Some((owner, repo))
}

fn utf16_len(value: &str) -> usize {
    value.encode_utf16().count()
}

thread_local! {
    static LAST_WASM_RESULT_RUST: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
}

#[no_mangle]
pub unsafe extern "C" fn dep_lens_parse_rust_dependencies_json(
    text_ptr: *const u8,
    text_len: usize,
    file_name_ptr: *const u8,
    file_name_len: usize,
    start_line: usize,
    end_line: usize,
) -> *const u8 {
    let text = read_wasm_str(text_ptr, text_len);
    let file_name = read_wasm_str(file_name_ptr, file_name_len);

    let bytes = match (text, file_name) {
        (Some(text), Some(file_name)) => {
            let dependencies = parse_rust_dependencies(text, file_name, "rust", start_line, end_line);
            serde_json::to_vec(&dependencies).unwrap_or_else(|_| b"[]".to_vec())
        }
        _ => b"[]".to_vec(),
    };

    LAST_WASM_RESULT_RUST.with(|slot| {
        let mut result = slot.borrow_mut();
        *result = bytes;
        result.as_ptr()
    })
}

#[no_mangle]
pub extern "C" fn dep_lens_last_result_len_rust() -> usize {
    LAST_WASM_RESULT_RUST.with(|slot| slot.borrow().len())
}

unsafe fn read_wasm_str<'a>(ptr: *const u8, len: usize) -> Option<&'a str> {
    if len == 0 {
        return Some("");
    }
    if ptr.is_null() {
        return None;
    }

    let bytes = std::slice::from_raw_parts(ptr, len);
    std::str::from_utf8(bytes).ok()
}

#[cfg(test)]
mod tests {
    use super::parse_rust_dependencies;

    #[test]
    fn parses_only_github_dependencies_inside_dependency_sections() {
        let text = r#"[package]
name = "example"
version = "0.1.0"

[dependencies]
tokio = "1.0"
serde = { version = "1.0", features = ["derive"] }
repo_dep = { git = "https://github.com/foo/bar.git", branch = "main" }

[target.'cfg(unix)'.dependencies]
platform_dep = { git = "https://github.com/baz/qux" }

[dependencies.foo]
git = "https://github.com/bar/baz.git"

[package.metadata.dep-lens]
git = "https://github.com/should/not-count.git"
"#;

        let deps = parse_rust_dependencies(text, "Cargo.toml", "toml", 0, 20);

        assert_eq!(deps.len(), 3);
        assert_eq!(deps[0].owner, "foo");
        assert_eq!(deps[0].repo, "bar");
        assert_eq!(deps[1].owner, "baz");
        assert_eq!(deps[1].repo, "qux");
        assert_eq!(deps[2].owner, "bar");
        assert_eq!(deps[2].repo, "baz");
    }

    #[test]
    fn ignores_non_cargo_files() {
        let text = r#"use serde::Serialize;"#;

        let deps = parse_rust_dependencies(text, "main.rs", "rust", 0, 10);

        assert!(deps.is_empty());
    }

    #[test]
    fn preserves_utf16_character_positions() {
        let text = "[dependencies]\nrepo_dep = { git = \"https://github.com/foo/bar\" }\n";

        let deps = parse_rust_dependencies(text, "Cargo.toml", "toml", 0, 10);

        assert_eq!(deps.len(), 1);
        assert_eq!(deps[0].line, 1);
        assert_eq!(deps[0].character, text.lines().nth(1).unwrap().encode_utf16().count());
    }
}
