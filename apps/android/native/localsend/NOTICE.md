# LocalSend native integration

Vendored `vendor/core` is the unmodified core source and assets from LocalSend
v1.18.2, commit `af0416be50770a97760f7070684bc667b759a15c`.

Upstream: https://github.com/localsend/localsend/tree/v1.18.2/packages/core

Copyright 2022–2026 Tien Do Nam and LocalSend contributors.
Licensed under Apache License 2.0; see `vendor/core/LICENSE`.

`src/lib.rs` is this project's Android JNI adapter, also Apache-2.0. It calls the
upstream Rust HTTP, multicast and crypto modules directly. Flutter UI, FRB and
upstream application branding are not embedded. The Compose interface and
Android document permissions are implemented by this project.

The core is compiled with its `full` feature as required by upstream guidance.
Only LAN v2.2 is exposed in this first adapter. WebRTC/cloud storage are separate
work and are not claimed to be implemented by enabling a Cargo feature.
