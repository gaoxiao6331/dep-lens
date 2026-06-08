mod base_dep_lens_inlay_provider;
mod common;
mod lang;
mod lsp;
mod utils;

use std::collections::HashMap;
use std::io::{self, Read, Write};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use std::thread;

use base_dep_lens_inlay_provider::BaseDepLensInlayProvider;
use lang::go::go_dep_lens_inlay_provider::GoDepLensInlayProvider;
use lsp::{
    create_text_document, DidChangeTextDocumentParams, DidCloseTextDocumentParams,
    DidOpenTextDocumentParams, DocumentState, InlayHintParams,
};
use serde_json::{json, Value};
use utils::logger::logger;
use utils::service::github_repo_info_service::GithubRepoInfoService;

fn main() {
    if let Err(error) = run() {
        logger().error(&format!("Dep Lens LSP exited with error: {error}"));
    }
}

fn run() -> io::Result<()> {
    GithubRepoInfoService::get_instance().init();

    let writer = Arc::new(LspWriter::new());
    let initialized = Arc::new(AtomicBool::new(false));
    let (refresh_tx, refresh_rx) = mpsc::channel();
    GithubRepoInfoService::get_instance().on_did_update_repo_info(refresh_tx);
    spawn_refresh_thread(writer.clone(), initialized.clone(), refresh_rx);

    let mut server = LspServer {
        documents: HashMap::new(),
        go_provider: GoDepLensInlayProvider::new(),
        writer,
        initialized,
    };

    let mut stdin = io::stdin().lock();
    let mut buffer = Vec::new();
    let mut chunk = [0; 8192];

    loop {
        let read = stdin.read(&mut chunk)?;
        if read == 0 {
            break;
        }

        buffer.extend_from_slice(&chunk[..read]);
        while let Some(raw_message) = read_message(&mut buffer) {
            match serde_json::from_slice::<Value>(&raw_message) {
                Ok(message) => server.handle_message(message),
                Err(error) => logger().warn(&format!("Failed to parse LSP message: {error}")),
            }
        }
    }

    GithubRepoInfoService::get_instance().shutdown();
    Ok(())
}

struct LspServer {
    documents: HashMap<String, DocumentState>,
    go_provider: GoDepLensInlayProvider,
    writer: Arc<LspWriter>,
    initialized: Arc<AtomicBool>,
}

impl LspServer {
    fn handle_message(&mut self, message: Value) {
        let Some(method) = message
            .get("method")
            .and_then(Value::as_str)
            .map(str::to_string)
        else {
            return;
        };

        if message.get("id").is_some() {
            self.handle_request(&method, message);
        } else {
            self.handle_notification(&method, message);
        }
    }

    fn handle_request(&mut self, method: &str, message: Value) {
        let id = message.get("id").cloned().unwrap_or(Value::Null);
        let result = match method {
            "initialize" => self.handle_initialize(),
            "shutdown" => Value::Null,
            "textDocument/inlayHint" => {
                let params = serde_json::from_value::<InlayHintParams>(
                    message.get("params").cloned().unwrap_or(Value::Null),
                );
                match params {
                    Ok(params) => json!(self.handle_inlay_hint(params)),
                    Err(error) => {
                        self.writer.send_error(
                            id,
                            -32602,
                            &format!("Invalid inlay hint params: {error}"),
                        );
                        return;
                    }
                }
            }
            _ => Value::Null,
        };

        self.writer.send_response(id, result);
    }

    fn handle_notification(&mut self, method: &str, message: Value) {
        match method {
            "initialized" => {
                self.initialized.store(true, Ordering::SeqCst);
            }
            "textDocument/didOpen" => {
                if let Ok(params) = serde_json::from_value::<DidOpenTextDocumentParams>(
                    message.get("params").cloned().unwrap_or(Value::Null),
                ) {
                    self.handle_did_open(params);
                }
            }
            "textDocument/didChange" => {
                if let Ok(params) = serde_json::from_value::<DidChangeTextDocumentParams>(
                    message.get("params").cloned().unwrap_or(Value::Null),
                ) {
                    self.handle_did_change(params);
                }
            }
            "textDocument/didClose" => {
                if let Ok(params) = serde_json::from_value::<DidCloseTextDocumentParams>(
                    message.get("params").cloned().unwrap_or(Value::Null),
                ) {
                    self.handle_did_close(params);
                }
            }
            "exit" => {
                GithubRepoInfoService::get_instance().shutdown();
                std::process::exit(0);
            }
            _ => {}
        }
    }

    fn handle_initialize(&self) -> Value {
        json!({
            "capabilities": {
                "textDocumentSync": 1,
                "inlayHintProvider": {
                    "resolveProvider": false
                }
            },
            "serverInfo": {
                "name": "Dep Lens",
                "version": "0.1.2"
            }
        })
    }

    fn handle_did_open(&mut self, params: DidOpenTextDocumentParams) {
        self.documents.insert(
            params.text_document.uri.clone(),
            DocumentState {
                uri: params.text_document.uri,
                language_id: params.text_document.language_id,
                version: Some(params.text_document.version),
                text: params.text_document.text,
            },
        );
    }

    fn handle_did_change(&mut self, params: DidChangeTextDocumentParams) {
        let Some(existing) = self.documents.get_mut(&params.text_document.uri) else {
            return;
        };

        let Some(full_text_change) = params.content_changes.last() else {
            return;
        };

        existing.version = params.text_document.version;
        existing.text = full_text_change.text.clone();
    }

    fn handle_did_close(&mut self, params: DidCloseTextDocumentParams) {
        self.documents.remove(&params.text_document.uri);
    }

    fn handle_inlay_hint(&self, params: InlayHintParams) -> Vec<Value> {
        let Some(state) = self.documents.get(&params.text_document.uri) else {
            return Vec::new();
        };

        let document = create_text_document(state);
        self.go_provider
            .provide_inlay_hints(&document, &params.range)
            .into_iter()
            .map(|hint| serde_json::to_value(hint).unwrap_or(Value::Null))
            .collect()
    }
}

struct LspWriter {
    stdout: Mutex<io::Stdout>,
    next_request_id: AtomicU64,
}

impl LspWriter {
    fn new() -> Self {
        Self {
            stdout: Mutex::new(io::stdout()),
            next_request_id: AtomicU64::new(1),
        }
    }

    fn send_response(&self, id: Value, result: Value) {
        self.write_message(json!({
            "jsonrpc": "2.0",
            "id": id,
            "result": result
        }));
    }

    fn send_error(&self, id: Value, code: i64, message: &str) {
        self.write_message(json!({
            "jsonrpc": "2.0",
            "id": id,
            "error": {
                "code": code,
                "message": message
            }
        }));
    }

    fn send_request(&self, method: &str, params: Option<Value>) {
        let id = self.next_request_id.fetch_add(1, Ordering::SeqCst);
        let mut message = json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": method
        });

        if let Some(params) = params {
            message["params"] = params;
        }

        self.write_message(message);
    }

    fn write_message(&self, message: Value) {
        let content = message.to_string();
        let mut stdout = self.stdout.lock().expect("stdout mutex poisoned");
        if let Err(error) = write!(
            stdout,
            "Content-Length: {}\r\n\r\n{}",
            content.len(),
            content
        ) {
            logger().warn(&format!("Failed to write LSP message: {error}"));
            return;
        }
        if let Err(error) = stdout.flush() {
            logger().warn(&format!("Failed to flush LSP message: {error}"));
        }
    }
}

fn spawn_refresh_thread(
    writer: Arc<LspWriter>,
    initialized: Arc<AtomicBool>,
    refresh_rx: mpsc::Receiver<()>,
) {
    thread::spawn(move || {
        while refresh_rx.recv().is_ok() {
            if initialized.load(Ordering::SeqCst) {
                writer.send_request("workspace/inlayHint/refresh", None);
            }
        }
    });
}

fn read_message(buffer: &mut Vec<u8>) -> Option<Vec<u8>> {
    let header_end = find_subsequence(buffer, b"\r\n\r\n")?;
    let header = String::from_utf8_lossy(&buffer[..header_end]);
    let content_length = header.lines().find_map(|line| {
        let (name, value) = line.split_once(':')?;
        if name.eq_ignore_ascii_case("Content-Length") {
            value.trim().parse::<usize>().ok()
        } else {
            None
        }
    })?;

    let message_start = header_end + 4;
    let message_end = message_start + content_length;
    if buffer.len() < message_end {
        return None;
    }

    let raw_message = buffer[message_start..message_end].to_vec();
    buffer.drain(..message_end);
    Some(raw_message)
}

fn find_subsequence(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}
