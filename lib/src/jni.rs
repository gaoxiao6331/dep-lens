//! JNI 绑定，供 Kotlin 调用

use super::go::*;
use serde_json;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};

#[no_mangle]
pub unsafe extern "C" fn dep_lens_parse_go_dependencies(
    text: *const c_char,
    file_name: *const c_char,
    language_id: *const c_char,
    start_line: c_int,
    end_line: c_int,
) -> *mut c_char {
    let text_str = if text.is_null() {
        ""
    } else {
        CStr::from_ptr(text).to_str().unwrap_or("")
    };

    let file_name_str = if file_name.is_null() {
        ""
    } else {
        CStr::from_ptr(file_name).to_str().unwrap_or("")
    };

    let language_id_str = if language_id.is_null() {
        ""
    } else {
        CStr::from_ptr(language_id).to_str().unwrap_or("")
    };

    let deps = parse_go_dependencies(
        text_str,
        file_name_str,
        language_id_str,
        start_line as u32,
        end_line as u32,
    );

    let json = serde_json::to_string(&deps).unwrap_or_else(|_| "[]".to_string());
    let c_str = CString::new(json).unwrap();
    c_str.into_raw()
}

#[no_mangle]
pub unsafe extern "C" fn dep_lens_free_string(s: *mut c_char) {
    if !s.is_null() {
        let _ = CString::from_raw(s);
    }
}
