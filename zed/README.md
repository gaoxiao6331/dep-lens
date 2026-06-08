# Dep Lens for Zed

This Zed extension implements the Go dependency inlay hint logic from the VS Code plugin with the same matching rules:

* Go import paths matching `github.com/{owner}/{repo}`
* `go.mod` module lines matching `github.com/{owner}/{repo}`
* `go.mod` dependencies marked `// indirect` are skipped
* GitHub star count and last pushed date are displayed inline

The extension runs a lightweight Rust LSP server because Zed extensions expose inlay hints through language servers.

## Development

```sh
cargo build --manifest-path lsp/Cargo.toml --release
cargo check
```

Set `GITHUB_TOKEN` or `DEP_LENS_GITHUB_TOKEN` to use authenticated GitHub API requests.
