#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXTENSION_ID="dep-lens"

# Allow callers to override Zed's extension locations, while defaulting to the
# standard macOS install paths used by Zed.
ZED_EXTENSIONS_DIR="${ZED_EXTENSIONS_DIR:-$HOME/Library/Application Support/Zed/extensions}"
ZED_LOCAL_EXTENSIONS_DIR="${ZED_LOCAL_EXTENSIONS_DIR:-$ZED_EXTENSIONS_DIR/installed}"
INSTALL_DIR="$ZED_LOCAL_EXTENSIONS_DIR/$EXTENSION_ID"

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

# Build the bundled LSP before linking the extension into Zed.
require_command cargo

echo "==> Building Dep Lens Zed LSP"
cargo build --manifest-path "$SCRIPT_DIR/lsp/Cargo.toml" --release

if [[ "$SKIP_CHECK" -eq 0 ]]; then
  echo "==> Checking Zed extension"
  cargo check --manifest-path "$SCRIPT_DIR/Cargo.toml"
fi

echo "==> Installing local extension"
mkdir -p "$ZED_LOCAL_EXTENSIONS_DIR"

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

cat <<EOF
Done.

Installed:
  $INSTALL_DIR -> $SCRIPT_DIR

Reload or restart Zed, then open a Go file or go.mod.
EOF
