#!/usr/bin/env bash
# One-line install for nos, MAGIC's Clojure-on-CLR task runner.
#
# Usage (latest, resolved from main's version.edn):
#   curl -fsSL https://raw.githubusercontent.com/flybot-sg/magic/main/install/nos.sh | sh
#
# Pin a specific release:
#   curl -fsSL https://raw.githubusercontent.com/flybot-sg/magic/main/install/nos.sh | MAGIC_VERSION=v0.1.0 sh
#
# Requires: bash, curl, tar, mono. Mono hosts the Nostrand runtime.
#
# Overrides (env vars):
#   MAGIC_VERSION   git tag to install (default: read from main's version.edn)
#   INSTALL_DIR     where the runtime lives (default: $HOME/.local/nostrand)
#   INSTALL_LINK    symlink path for `nos` (default: $HOME/.local/bin/nos)
set -euo pipefail

if ! command -v mono >/dev/null 2>&1; then
  echo "Error: 'mono' is required but not found in PATH." >&2
  echo "Install it first:" >&2
  echo "  macOS:         brew install mono" >&2
  echo "  Debian/Ubuntu: sudo apt-get install -y mono-runtime" >&2
  echo "  Other:         https://www.mono-project.com/download/stable/" >&2
  exit 1
fi

if [ -z "${MAGIC_VERSION:-}" ]; then
  echo "Resolving latest MAGIC version from main's version.edn..."
  RESOLVED=$(curl -fsSL https://raw.githubusercontent.com/flybot-sg/magic/main/version.edn \
              | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -n 1 || true)
  if [ -z "${RESOLVED}" ]; then
    echo "Error: could not resolve version from main's version.edn." >&2
    echo "Set MAGIC_VERSION explicitly, e.g. MAGIC_VERSION=v0.1.0" >&2
    exit 1
  fi
  MAGIC_VERSION="v${RESOLVED}"
fi

INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/nostrand}"
INSTALL_LINK="${INSTALL_LINK:-$HOME/.local/bin/nos}"

TARBALL="nostrand-${MAGIC_VERSION}-mono.tar.gz"
URL="https://github.com/flybot-sg/magic/releases/download/${MAGIC_VERSION}/${TARBALL}"

TMP_DIR=$(mktemp -d)
trap 'rm -rf "${TMP_DIR}"' EXIT

echo "Downloading ${URL}"
curl -fsSL "${URL}" | tar xz -C "${TMP_DIR}"

mkdir -p "$(dirname "${INSTALL_DIR}")"
rm -rf "${INSTALL_DIR}"
mv "${TMP_DIR}/nostrand-${MAGIC_VERSION}-mono" "${INSTALL_DIR}"

mkdir -p "$(dirname "${INSTALL_LINK}")"
ln -sf "${INSTALL_DIR}/nos" "${INSTALL_LINK}"

echo "Installed nos ${MAGIC_VERSION} at ${INSTALL_LINK}"
echo "Make sure '$(dirname "${INSTALL_LINK}")' is on your PATH."
"${INSTALL_LINK}" version
