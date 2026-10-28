# Build verification

Taimi Forest / 苔米厸 0.22.1-beta.1 (220101). Independent compile/JVM/all-ABI debug build exited 0 on 2026-10-29. 1088 passed, 1 Windows symlink test skipped; zero failures/errors. Receipt 20261028T231550-17716-1793229350357017500; clean unchanged Android fingerprint 13b3d60496b52a53cffea1c58d31f3b3de03f4ec139ac8a6dc5e4913d291f2a4. Existing caches were reused; this is not hermetic reproducibility. Physical-phone validation of the official signed APK is recorded separately in release evidence.

Update checks use HTTP/1.1 for small manifests, bounded connection/read/call timeouts, parallel independent release channels and clear network errors. A failing channel does not hide a valid newer release; an older release alone cannot be reported as up to date after partial failure. Six policy regressions cover channel failure, concurrency, cancellation and error handling.

The private backend, operations, credentials, official signing keys, unlicensed exam text bodies and model weights are excluded. Third-party resources retain their separate licenses.
