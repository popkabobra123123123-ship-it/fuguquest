# fugu-root.apk — on-device front-end

**One APK, no PC**: sideload on the headset, tap **ROOT**, the chain runs on the headset itself.

This is *not* the deleted upstream `app-chain/` (option #2 — running the chain from
`untrusted_app`; refuted on-device, zygote preload walled it off, see commit `d89bfeb`).
This app drives **option #1** — the validated shell-domain chain — via an **adb-wireless
self-connect**: a ~300-line Java adb client that talks to the local adbd (`127.0.0.1:5555`),
exactly the `--local` mode the README documents ("drive with a single invocation over an
adb-wireless self-connect").

## Flow

1. **Prereq (once):** developer options → **ADB over network** on the headset.
2. Install the APK, open it, tap **ROOT**.
3. First connect only: the headset shows the usual **"Allow USB debugging?"** RSA
   confirmation (the app keeps its own persistent key in `filesDir`). Tap Allow.
4. The app streams a bundled `fugu.tar.gz` (arm64 `fuguquest` + `targets/`) from **its own
   APK** through the adb shell stdin (`tar xzf -` — Android's toybox has no unzip) into
   `/data/local/tmp/fugu-root` and runs

   ```
   fuguquest --local -t auto --adb-root
   ```

   with all output streamed to the screen. `-t auto` matches the headset's
   `ro.build.version.incremental` against `targets/*.json` by itself.
5. When it finishes, adbd is cred-patched to uid 0 — the **id** button on a fresh
   connect prints `uid=0(root)` (and `getenforce` → `Permissive`).

The **id** button alone is a safe smoke test: it only prints
`id; getenforce; getprop ...`.

## Build

No Gradle, same pipeline as the rest of the repo:

```
bash app/build.sh            # auto-downloads build-tools 34 + platform-33 into ~/.android-cache
NDK=/path/to/ndk bash app/build.sh   # for the aarch64-linux-android fuguquest build
```

CI builds it on every push to `main` — grab the `fugu-root-apk` artifact from
[Actions](../actions).

## Caveats

- Exploit only works on patch level **≤ 2026-06-03** (see the repo README status table).
  On newer builds the chain is dead — the app will still run, but `fuguquest` will fail
  at the primitive stage.
- `ADB over network` must stay enabled while running (adbd listens on 5555).
- Quest's adbd is production-secured: the app passes the legacy RSA AUTH
  (SHA1withRSA + `android_pubkey`), which is why the Allow-dialog appears once.
