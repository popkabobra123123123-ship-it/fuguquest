# AGENTS.md

Rules for AI coding agents (OpenCode / Claude Code / etc.) working in this repo.

## Commits

- Commit as the repo owner, and always add the co-author trailer:

  ```
  Co-authored-by: OpenCode <opencode@users.noreply.github.com>
  ```

- Keep commits small and in English, imperative mood (matches upstream history).

## Build

- The orchestrator is plain Rust with zero dependencies:

  ```sh
  cd rust && cargo build --release
  ```

  Binary lands in `rust/target/release/fuguquest`. No cross-toolchain needed
  (no clang, no NDK) — it builds with stock `rustc`.

## Porting to a new firmware

- Offline, device not required. See `PORTING.md` for the full flow:

  ```sh
  pip install -r requirements-port.txt
  python3 port.py --zip q3_<build>.zip
  ```

- Result is a new `targets/<name>.json` (+ gathered binaries). Never edit the
  JSON offsets by hand — regenerate the target instead.

## Safety rules

- DirtyFrag (CVE-2026-43284) is **patched at security patch level 2026-06-04**.
  Do not attempt to run the chain on any build at or above that level — the
  primitive is dead there and only produces noise (`/proc/net/xfrm_stat` will
  still climb, nothing else happens).
- A freshly ported target is **not** proof of vulnerability. Always confirm
  on-device that the page-cache write actually lands before marking a target
  `"VULNERABLE"` in its JSON (see the patched example:
  `targets/q3_52433670048800520.json`, `"status":"PATCHED"`).
- This repo is research material. Don't re-add the removed app-chain or
  re-generalize the hardcoded paths — upstream removed them deliberately.
