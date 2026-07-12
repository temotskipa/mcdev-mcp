# Architecture

## Rewrite Status

This worktree is an in-progress Java 25 rewrite, not a completed replacement
for the retired server. The early cutover removed the retired runtime and its
worker from this branch. The clean `master` checkout at
`C:\Users\ttski\Projects\mcdev-mcp` is the read-only parity oracle for later
differential work.

## Current Java Surface

The Gradle application contains the currently migrated code under
`src/main/java/dev/mcdevmcp/`. Its top-level packages separate application
startup, MCP transport and catalogs, storage, and shared support. Java tests
and frozen contract resources document the parts of the rewrite that are
implemented today.

Future migration tasks add parity coverage and release packaging without
restoring retired source to this worktree.
