# mcdev-mcp

## Rewrite Status

This branch is an in-progress Java 25 rewrite. The early worktree cutover has
removed the retired server, worker, and root toolchain from this worktree; it
does not assert behavioral parity is complete.

`C:\Users\ttski\Projects\mcdev-mcp` on `master` remains the immutable,
read-only parity oracle. Future differential checks must invoke or materialize
that checkout without restoring retired source here.

## Build

Use the Gradle wrapper from this worktree:

```powershell
.\gradlew.bat clean test shadowJar --console=plain
```

The checked-in Java sources and contract fixtures define the currently
implemented rewrite surface. Packaging and release integration are still
scheduled work.
