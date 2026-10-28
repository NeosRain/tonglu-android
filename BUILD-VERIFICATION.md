# Build verification

Taimi Forest / 苔米厸 0.22.0-beta.4 (220004). Independent compile/JVM/all-ABI debug build exited 0 on 2026-10-28. 1082 passed, 1 Windows symlink test skipped; zero failures/errors. Clean unchanged Android fingerprint 1fd87c3ad0f7488640989f0bd70b97469ffd8171df2176b51829352ccc1298b9; receipt 20261028T144922-46984-1793198962214407200. Existing caches were used; this is not hermetic reproducibility. Anonymous manifest verification is recorded in release evidence.

This update fixes an actual light-theme card contrast regression in beta.3: decorative colors now blend at 12% into the opaque app surface and all card ink uses the application primary text slot. A regression test checks five subject colors in both light and dark themes against the 4.5:1 minimum. Staged API35 light-mode reading and vocabulary cards were visually inspected; final-source build evidence is recorded separately.

It also includes subject entries on the learning home, reading/vocabulary cards inside English, direct vocabulary sessions and return, and checkpoint restore before automatic start. Feedback directly submits without app or GitHub login and only shows success after a server receipt. The private server implementation remains outside this Android GPL snapshot.

Exam text bodies, PDFs, model weights, private operations, credentials and official signing keys are excluded. Third-party resources retain separate terms. Physical phone and real paid AI are untested.
