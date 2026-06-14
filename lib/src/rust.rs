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

fn re_cargo_toml_dep() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r#"^\s*([A-Za-z0-9_-]+)\s*=\s*"([^"]+)""#)
            .expect("valid Cargo.toml dependency regex")
    })
}

fn re_github_repo() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r"github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:\.git|/[^/]*)?")
            .expect("valid GitHub repo regex")
    })
}

pub fn parse_rust_dependencies(
    text: &str,
    file_name: &str,
    language_id: &str,
    start_line: usize,
    end_line: usize,
) -> Vec<RustDependency> {
    if start_line > end_line || !(language_id == "rust" || file_name.ends_with("Cargo.toml")) {
        return Vec::new();
    }

    let is_cargo_toml = file_name.ends_with("Cargo.toml");
    let mut dependencies = Vec::new();

    for (line, line_text) in text.lines().enumerate() {
        if line < start_line {
            continue;
        }
        if line > end_line {
            break;
        }

        if !is_cargo_toml {
            continue;
        }

        let Some((owner, repo)) = (|| {
            if let Some(c) = re_github_repo().captures(line_text) {
                let owner = c.get(1).map(|m| m.as_str().to_string());
                let repo = c.get(2).map(|m| m.as_str().to_string());
                if let (Some(o), Some(r)) = (owner, repo) {
                    return Some((o, r));
                }
            }

            if let Some(dep_captures) = re_cargo_toml_dep().captures(line_text) {
                let name = dep_captures.get(1).map(|m| m.as_str()).unwrap_or("");
                let value = dep_captures.get(2).map(|m| m.as_str()).unwrap_or("");
                
                if let Some(c) = re_github_repo().captures(value) {
                    let owner = c.get(1).map(|m| m.as_str().to_string());
                    let repo = c.get(2).map(|m| m.as_str().to_string());
                    if let (Some(o), Some(r)) = (owner, repo) {
                        return Some((o, r));
                    }
                }

                let github_url = format!("github.com/rust-lang/{}", name);
                if let Some(c) = re_github_repo().captures(&github_url) {
                    let owner = c.get(1).map(|m| m.as_str().to_string());
                    let repo = c.get(2).map(|m| m.as_str().to_string());
                    if let (Some(o), Some(r)) = (owner, repo) {
                        return Some((o, r));
                    }
                }
            }

            None
        })() else {
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
    fn parses_cargo_toml_deps() {
        let text = r#"[package]
name = "example"
version = "0.1.0"

[dependencies]
tokio = "1.0"
serde = { version = "1.0", features = ["derive"] }
rand = "0.8"

[dependencies.foo]
git = "https://github.com/bar/baz.git"
"#;

        let deps = parse_rust_dependencies(text, "Cargo.toml", "toml", 0, 20);

        assert_eq!(deps.len(), 4);
    }
}
