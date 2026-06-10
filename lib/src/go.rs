use regex::Regex;
use serde::Serialize;
use std::cell::RefCell;
use std::sync::OnceLock;

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct GoDependency {
    pub owner: String,
    pub repo: String,
    pub line: usize,
    pub character: usize,
}

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

pub fn parse_go_dependencies(
    text: &str,
    file_name: &str,
    language_id: &str,
    start_line: usize,
    end_line: usize,
) -> Vec<GoDependency> {
    if start_line > end_line || !(language_id == "go" || file_name.ends_with("go.mod")) {
        return Vec::new();
    }

    let is_go_mod = file_name.ends_with("go.mod");
    let mut dependencies = Vec::new();

    for (line, line_text) in text.lines().enumerate() {
        if line < start_line {
            continue;
        }
        if line > end_line {
            break;
        }

        if is_go_mod && line_text.contains("// indirect") {
            continue;
        }

        let captures = if is_go_mod {
            re_github_mod().captures(line_text)
        } else {
            re_github_import().captures(line_text)
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

        let character = if is_go_mod {
            utf16_len(line_text)
        } else {
            let Some(byte_idx) = line_text.rfind('"') else {
                continue;
            };
            utf16_len(&line_text[..byte_idx]) + 1
        };

        dependencies.push(GoDependency {
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
    static LAST_WASM_RESULT: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
}

#[no_mangle]
pub extern "C" fn dep_lens_alloc(len: usize) -> *mut u8 {
    let mut buffer = vec![0; len];
    let ptr = buffer.as_mut_ptr();
    std::mem::forget(buffer);
    ptr
}

#[no_mangle]
pub unsafe extern "C" fn dep_lens_dealloc(ptr: *mut u8, len: usize) {
    if len == 0 || ptr.is_null() {
        return;
    }
    drop(Vec::from_raw_parts(ptr, len, len));
}

#[no_mangle]
pub unsafe extern "C" fn dep_lens_parse_go_dependencies_json(
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
            let dependencies = parse_go_dependencies(text, file_name, "go", start_line, end_line);
            serde_json::to_vec(&dependencies).unwrap_or_else(|_| b"[]".to_vec())
        }
        _ => b"[]".to_vec(),
    };

    LAST_WASM_RESULT.with(|slot| {
        let mut result = slot.borrow_mut();
        *result = bytes;
        result.as_ptr()
    })
}

#[no_mangle]
pub extern "C" fn dep_lens_last_result_len() -> usize {
    LAST_WASM_RESULT.with(|slot| slot.borrow().len())
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
    use super::parse_go_dependencies;

    #[test]
    fn parses_go_imports() {
        let text = r#"
package main

import "github.com/owner/repo/subpkg"
"#;

        let deps = parse_go_dependencies(text, "main.go", "go", 0, 10);

        assert_eq!(deps.len(), 1);
        assert_eq!(deps[0].owner, "owner");
        assert_eq!(deps[0].repo, "repo");
        assert_eq!(deps[0].line, 3);
        assert_eq!(deps[0].character, 37);
    }

    #[test]
    fn parses_go_mod_and_skips_indirect() {
        let text = r#"module example.com/app

require (
	github.com/alpha/bravo v1.2.3
	github.com/skip/indirect v1.0.0 // indirect
)
"#;

        let deps = parse_go_dependencies(text, "go.mod", "go.mod", 0, 10);

        assert_eq!(deps.len(), 1);
        assert_eq!(deps[0].owner, "alpha");
        assert_eq!(deps[0].repo, "bravo");
        assert_eq!(deps[0].line, 3);
    }
}
