#!/usr/bin/env bash
#
# Build the MCPB (Model Context Protocol Bundle). The bundle is universal:
# the runtime is pure JavaScript plus sql.js (SQLite-as-WebAssembly), so the
# same .mcpb works on macOS (arm64 and x86_64), Linux (x64/arm64), and
# Windows. Earlier releases shipped one bundle per platform; consolidating
# was always listed as a follow-up and is now realised.
#
# Usage:
#   scripts/build-mcpb.sh                       # default output path
#   scripts/build-mcpb.sh path/to/output.mcpb   # custom output path
#
# Output: dist-mcpb/mcdev-mcp-<version>.mcpb
#
# Requires: node >= 18, npm. `unzip` is used by the post-pack smoke test;
# the build degrades gracefully (skips that check) if it is unavailable. The
# @anthropic-ai/mcpb CLI is a devDependency, so a fresh `npm install` in the
# repo root is enough to make this script work.
#
# Why a staging dir? mcpb pack zips the directory you point it at. If we
# pointed it at the repo root, we'd ship dev dependencies (jest, ts-jest,
# typescript, etc.), src/, tests/, docs/, and .github/. Staging gives us a
# clean, prod-only tree.
#
# Note: we use sql.js (SQLite-as-WebAssembly), so there are no native
# dependencies to ship. Earlier iterations pinned better-sqlite3 prebuilds to
# a specific Electron ABI, then briefly relied on node:sqlite (Node >=22.5
# only); both classes of failure are gone now, which is what makes the
# universal bundle safe.

set -euo pipefail

# Move to repo root regardless of where the script is invoked from.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

VERSION="$(node -p 'require("./package.json").version')"

OUTPUT="${1:-dist-mcpb/mcdev-mcp-${VERSION}.mcpb}"
mkdir -p "$(dirname "$OUTPUT")"

STAGE="$(mktemp -d)"
# SMOKE_EXTRACT is created later (post-pack); the `:+` guard keeps the trap
# safe under `set -u` while it is still unset.
cleanup() { rm -rf "$STAGE" ${SMOKE_EXTRACT:+"$SMOKE_EXTRACT"}; }
trap cleanup EXIT

MCPB="$REPO_ROOT/node_modules/.bin/mcpb"
if [[ ! -x "$MCPB" ]]; then
  echo ">> mcpb CLI not found at $MCPB — running 'npm install' to install devDependencies..."
  npm install --no-fund --no-audit --loglevel=error
fi

echo ">> Building TypeScript..."
npm run build

echo ">> Validating manifest.json..."
"$MCPB" validate manifest.json

echo ">> Staging files in $STAGE..."
cp manifest.json package.json "$STAGE/"
[[ -f README.md ]]   && cp README.md   "$STAGE/"
[[ -f LICENSE ]]     && cp LICENSE     "$STAGE/"
[[ -f .mcpbignore ]] && cp .mcpbignore "$STAGE/"
cp -R dist "$STAGE/dist"
# resources/ holds markdown surfaced through the MCP resources API
# (e.g. mcdev://guides/python-scripting). The runtime reads these files
# from disk via src/resources/index.ts, so they have to ship with the
# bundle alongside dist/.
[[ -d resources ]] && cp -R resources "$STAGE/resources"

echo ">> Installing production dependencies into staging dir..."
# No native deps remain — sql.js is pure JS+WASM. This is now a
# pure-JavaScript install.
(
  cd "$STAGE"
  npm install --omit=dev --no-fund --no-audit --loglevel=error
)

# Prune the unused sql.js variants. The package ships ~37 MB of dist files
# (asm.js, debug builds, browser-specific WASM, web-worker bundles) but we
# only need the Node-targeted sql-wasm.{js,wasm} pair (≈700 KB total). The
# saving matters for bundle download size and Claude Desktop install speed.
SQLJS_DIST="$STAGE/node_modules/sql.js/dist"
if [[ -d "$SQLJS_DIST" ]]; then
  echo ">> Pruning unused sql.js variants in $SQLJS_DIST..."
  find "$SQLJS_DIST" -type f \
    ! -name 'sql-wasm.js' \
    ! -name 'sql-wasm.wasm' \
    -delete
fi

# Native-binary re-download blocks used to live here. They're gone now that
# we use sql.js (WASM) — there is no .node file to ship, and therefore no
# ABI mismatch or Team-ID dlopen failure to worry about. The whole class of
# "Claude Desktop bumped Electron, our MCPB silently died on load" bugs
# disappears.

echo ">> Packing $OUTPUT..."
"$MCPB" pack "$STAGE" "$OUTPUT"

# ---------------------------------------------------------------------------
# Smoke test the PACKED bundle.
#
# This deliberately runs against the *extracted .mcpb* — not the staging dir.
# `mcpb pack` applies .mcpbignore (and mcpb's own default excludes) while it
# zips, so the staging tree and the shipped bundle are NOT the same set of
# files. A pre-pack smoke test booted code the bundle does not contain, and
# so was blind to packaging bugs — e.g. an unanchored .mcpbignore pattern
# (`src/`) stripping a dependency's `src/` entry point, which shipped a
# bundle that crashed at startup with ERR_MODULE_NOT_FOUND.
#
# What it catches:
#   - dist/mcpb-bootstrap.js missing or broken (tsc didn't emit it)
#   - a top-level import crash somewhere in the ./index.js module graph
#   - a dependency file the runtime needs but pack / .mcpbignore removed
#   - the server.connect(transport) path never resolving
#   - an async EPIPE-style crash with no stderr output
#
# It does NOT catch Node-version-specific bugs when the build host runs a
# different Node than Claude Desktop's embedded runtime.
#
# How it works:
#   1. Extract the packed .mcpb to a temp dir.
#   2. Run `node dist/mcpb-bootstrap.js serve` there in the background with
#      MCDEV_MCP_DEBUG_LOG pointing at a temp file.
#   3. Poll that file for up to 5s for the "connected, ready" breadcrumb.
#   4. Kill the process. If the breadcrumb never showed up, delete the broken
#      bundle (so it cannot be installed) and fail the build.
#
# The server never gets a real `initialize` JSON-RPC message — stdin is
# /dev/null — so it sits idle on `await server.connect()`, which is exactly
# the "connected and waiting" state we want to observe.
#
# Skippable via MCDEV_MCP_SKIP_SMOKE=1 if the build host can't run the
# bootstrap for any reason. Also skipped (with a warning) if `unzip` is
# unavailable, since the bundle cannot be extracted without it.
# ---------------------------------------------------------------------------
if [[ "${MCDEV_MCP_SKIP_SMOKE:-0}" == "1" ]]; then
  echo ">> Smoke test skipped (MCDEV_MCP_SKIP_SMOKE=1)"
elif ! command -v unzip >/dev/null 2>&1; then
  echo ">> WARNING: 'unzip' not found — skipping the packed-bundle smoke test."
  echo ">>          Install unzip to re-enable this check."
else
  echo ">> Smoke test: extracting and booting the packed bundle..."
  SMOKE_EXTRACT="$(mktemp -d)"
  unzip -q "$OUTPUT" -d "$SMOKE_EXTRACT"

  SMOKE_LOG="$(mktemp -t mcdev-mcp-smoke.XXXXXX)"
  SMOKE_STDERR="$(mktemp -t mcdev-mcp-smoke-stderr.XXXXXX)"
  SMOKE_PID=""
  SMOKE_OK=0

  # Start the bootstrap in the background from the EXTRACTED bundle.
  # stdin=/dev/null gives it immediate EOF on the JSON-RPC stream, but
  # `server.connect()` resolves before any message is read, so the success
  # breadcrumb still lands. stderr is teed to a file so a top-level import
  # crash that fires before the debug-log infrastructure spins up still has
  # a diagnostic to surface in the failure branch below.
  (
    cd "$SMOKE_EXTRACT"
    MCDEV_MCP_DEBUG_LOG="$SMOKE_LOG" \
      node dist/mcpb-bootstrap.js serve < /dev/null > /dev/null 2> "$SMOKE_STDERR" &
    echo $! > "$SMOKE_LOG.pid"
    wait $! 2>/dev/null || true
  ) &
  SMOKE_RUNNER=$!

  # Poll the log for the success marker. 10 * 0.5s = 5s cap.
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if grep -q "startServer: connected, ready for requests" "$SMOKE_LOG" 2>/dev/null; then
      SMOKE_OK=1
      break
    fi
    sleep 0.5
  done

  # Kill the bootstrap if it's still running.
  if [[ -f "$SMOKE_LOG.pid" ]]; then
    SMOKE_PID="$(cat "$SMOKE_LOG.pid")"
    if [[ -n "$SMOKE_PID" ]]; then
      kill "$SMOKE_PID" 2>/dev/null || true
      sleep 0.2
      kill -9 "$SMOKE_PID" 2>/dev/null || true
    fi
    rm -f "$SMOKE_LOG.pid"
  fi
  wait "$SMOKE_RUNNER" 2>/dev/null || true

  if [[ "$SMOKE_OK" != "1" ]]; then
    echo "!! FATAL: smoke test failed — the packed bundle did not reach"
    echo "!!        'startServer: connected, ready for requests' within 5s."
    echo "!! Debug log ($SMOKE_LOG):"
    if [[ -s "$SMOKE_LOG" ]]; then
      sed 's/^/!!   /' "$SMOKE_LOG"
    else
      echo "!!   (empty — the bootstrap died before any breadcrumb landed.)"
    fi
    echo "!! Process stderr ($SMOKE_STDERR):"
    if [[ -s "$SMOKE_STDERR" ]]; then
      sed 's/^/!!   /' "$SMOKE_STDERR"
    else
      echo "!!   (empty — process exited cleanly without writing stderr.)"
    fi
    echo "!! To reproduce by hand (extract dir left on disk for inspection):"
    echo "!!   cd $SMOKE_EXTRACT"
    echo "!!   MCDEV_MCP_DEBUG_LOG=/tmp/mcdev-debug.log node dist/mcpb-bootstrap.js serve"
    echo "!!   (delete it afterwards: rm -rf $SMOKE_EXTRACT)"
    echo "!! Deleting the broken bundle $OUTPUT so it cannot be installed."
    rm -f "$OUTPUT"
    rm -f "$SMOKE_LOG" "$SMOKE_STDERR"
    # Leave SMOKE_EXTRACT on disk for the reproduce-by-hand hint above.
    SMOKE_EXTRACT=""
    exit 1
  fi
  rm -f "$SMOKE_LOG" "$SMOKE_STDERR"
  echo ">> Smoke test passed: packed bundle reached 'connected, ready for requests'"
fi

echo
echo ">> Bundle info:"
"$MCPB" info "$OUTPUT" || true

echo
echo "✓ Built $OUTPUT ($(du -h "$OUTPUT" | cut -f1))"
