#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXTENSION_ID="dep-lens"
EXTENSION_CRATE_NAME="dep-lens-zed"
EXTENSION_WASM_BASENAME="${EXTENSION_CRATE_NAME//-/_}.wasm"

# 关键：Zed 扩展必须使用 wasm32-wasip2 目标，因为：
# - wasm32-wasip1 产出普通 wasm module，Zed 的扩展沙箱需要的是 wasm component
# - 如果用 wasm32-wasip1，Zed 加载时会报错："failed to compile wasm component: failed to parse WebAssembly module: attempted to parse a wasm module with a component parser"
WASM_TARGET="wasm32-wasip2"
WASM_OUTPUT="$SCRIPT_DIR/target/$WASM_TARGET/release/$EXTENSION_WASM_BASENAME"
EXTENSION_WASM="$SCRIPT_DIR/extension.wasm"

# Allow callers to override Zed's extension locations, while defaulting to the
# standard macOS install paths used by Zed.
ZED_EXTENSIONS_DIR="${ZED_EXTENSIONS_DIR:-$HOME/Library/Application Support/Zed/extensions}"
ZED_LOCAL_EXTENSIONS_DIR="${ZED_LOCAL_EXTENSIONS_DIR:-$ZED_EXTENSIONS_DIR/installed}"
ZED_WORK_EXTENSIONS_DIR="${ZED_WORK_EXTENSIONS_DIR:-$ZED_EXTENSIONS_DIR/work}"
INSTALL_DIR="$ZED_LOCAL_EXTENSIONS_DIR/$EXTENSION_ID"
WORK_DIR="$ZED_WORK_EXTENSIONS_DIR/$EXTENSION_ID"
WORK_LSP_DIR="$WORK_DIR/lsp/target/release"

usage() {
  cat <<EOF
Usage: $(basename "$0") [--skip-check]

Build and install the Dep Lens Zed extension for local development.

Environment:
  ZED_EXTENSIONS_DIR        Zed extensions root
  ZED_LOCAL_EXTENSIONS_DIR  Local install directory, defaults to \$ZED_EXTENSIONS_DIR/installed
EOF
}

# Keep argument parsing small and explicit so unsupported flags fail loudly.
SKIP_CHECK=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-check)
      SKIP_CHECK=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

require_rust_target() {
  if ! rustup target list --installed | grep -qx "$WASM_TARGET"; then
    cat >&2 <<EOF
Missing required Rust target: $WASM_TARGET

Run:
  rustup target add $WASM_TARGET
EOF
    exit 1
  fi
}

binary_name() {
  if [[ "$OSTYPE" == msys* || "$OSTYPE" == cygwin* || "$OSTYPE" == win32* ]]; then
    echo "dep-lens-zed-lsp.exe"
  else
    echo "dep-lens-zed-lsp"
  fi
}

# Build the wasm extension and bundled LSP before linking into Zed.
require_command cargo
require_command rustup
require_rust_target

echo "==> Building Dep Lens Zed extension wasm"
cargo build --manifest-path "$SCRIPT_DIR/Cargo.toml" --target "$WASM_TARGET" --release

if [[ ! -f "$WASM_OUTPUT" ]]; then
  echo "Missing built wasm artifact: $WASM_OUTPUT" >&2
  exit 1
fi

# Zed expects the extension entrypoint at <extension>/extension.wasm.
cp "$WASM_OUTPUT" "$EXTENSION_WASM"

echo "==> Building Dep Lens Zed LSP"
cargo build --manifest-path "$SCRIPT_DIR/lsp/Cargo.toml" --release

if [[ "$SKIP_CHECK" -eq 0 ]]; then
  echo "==> Checking Zed extension"
  cargo check --manifest-path "$SCRIPT_DIR/Cargo.toml"
fi

echo "==> Installing local extension"
mkdir -p "$ZED_LOCAL_EXTENSIONS_DIR"
mkdir -p "$WORK_LSP_DIR"

# Replace an existing symlink in-place, but preserve a real directory by moving
# it aside so local edits or previous installs are not discarded.
if [[ -L "$INSTALL_DIR" ]]; then
  rm "$INSTALL_DIR"
elif [[ -e "$INSTALL_DIR" ]]; then
  BACKUP_DIR="${INSTALL_DIR}.bak.$(date +%Y%m%d%H%M%S)"
  mv "$INSTALL_DIR" "$BACKUP_DIR"
  echo "Moved existing install to $BACKUP_DIR"
fi

ln -s "$SCRIPT_DIR" "$INSTALL_DIR"

echo "==> Syncing bundled LSP into Zed work dir and install dir"
mkdir -p "$WORK_LSP_DIR"
# 复制到 work 目录：Zed 的扩展运行时在 work 目录下，从当前目录解析相对路径
cp "$SCRIPT_DIR/lsp/target/release/$(binary_name)" "$WORK_LSP_DIR/"
# 也复制到 install dir 根目录：扩展的候选目录列表里包含了 installed 目录，
# 放在根目录下是最可靠的查找方式，避免嵌套层级查找失败
cp "$SCRIPT_DIR/lsp/target/release/$(binary_name)" "$INSTALL_DIR/"

cat <<EOF
Done.

Installed:
  $INSTALL_DIR -> $SCRIPT_DIR
  $WORK_LSP_DIR/$(binary_name)
  $INSTALL_DIR/$(binary_name)

Reload or restart Zed, then open a Go file or go.mod.

Tips:
  - 查看日志：Zed 命令面板 -> "Zed: Open Log" 或用 zed --foreground 启动
  - 开启 debug 日志：DEP_LENS_LOG_LEVEL=debug zed --foreground
EOF
