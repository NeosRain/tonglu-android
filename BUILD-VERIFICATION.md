# Build verification

Taimi Forest / 苔米厸 0.22.1-beta.1 (220101). Independent build verification for this snapshot is pending; previous receipts do not verify this version.

Update checks use HTTP/1.1 for small manifests, bounded connection/read/call timeouts, parallel independent release channels and clear network errors. A failing channel does not hide a valid newer release; an older release alone cannot be reported as up to date after partial failure. Six policy regressions cover channel failure, concurrency, cancellation and error handling.

The private backend, operations, credentials, official signing keys, unlicensed exam text bodies and model weights are excluded. Third-party resources retain their separate licenses.
