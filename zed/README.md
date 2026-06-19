# Dep Lens for Zed

This Zed extension implements the Go dependency inlay hint logic from the VS Code plugin with the same matching rules:

* Go import paths matching `github.com/{owner}/{repo}`
* `go.mod` module lines matching `github.com/{owner}/{repo}`
* `go.mod` dependencies marked `// indirect` are skipped
* GitHub star count and last pushed date are displayed inline

The extension runs a lightweight Rust LSP server because Zed extensions expose inlay hints through language servers.

## Development

```sh
rustup target add wasm32-wasip2
cargo build --target wasm32-wasip2 --release
cargo build --manifest-path lsp/Cargo.toml --release
cargo check
```

## Local Install

```sh
./install-local.sh
```

The script builds both the extension WebAssembly entrypoint and the Rust LSP server, writes `extension.wasm`, and symlinks this extension into Zed's local extensions directory. Reload or restart Zed after running it.

If the install fails with a missing Rust target error, run:

```sh
rustup target add wasm32-wasip2
```

Set `GITHUB_TOKEN` or `DEP_LENS_GITHUB_TOKEN` to use authenticated GitHub API requests.
