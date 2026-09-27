# Stremio Lite for Android

A native Android client for **your own Stremio account**: it signs in to `api.strem.io`, pulls the
addon collection and library you already have, browses every addon's catalogs, plays streams — and
adds the thing the official app does not have: a **download button** with a multi-connection,
resumable, background downloader.

## What it does

### Your account, not a separate one
- Sign in with your Stremio email + password (`POST /api/login`), or paste the `authKey` from
  `web.stremio.com` (`localStorage.profile`) if you prefer not to type your password.
- Your addons come from `addonCollectionGet`. Installing or removing an addon in the app calls
  `addonCollectionSet`, so the change shows up on web, desktop and TV too.
- Your library (`datastoreGet` on the `libraryItem` collection) appears as the "My Library" row,
  including watch progress.
- The `authKey` is stored in `EncryptedSharedPreferences`, never in plain preferences.

### Addons
- Full Stremio addon protocol over HTTP: `catalog`, `meta`, `stream`, `subtitles`, `addon_catalog`,
  with `search` / `skip` / `genre` extras encoded the way the SDK expects.
- Install from a manifest URL, a bare domain, or a `stremio://` link (the app registers the scheme,
  so addon links from the browser open here).
- Manifests with `resources` as strings *or* as objects both parse, and `idPrefixes` / `types` are
  respected so an addon is only queried for content it actually serves.
- Catalogs and streams are fetched from all addons **in parallel**, so one slow addon never blocks
  the rest.

### The download button
Every stream with a direct HTTP(S) URL gets a Download button.

- **Parallel connections.** The file is probed with a one-byte range request. If the server reports
  `206` + `Content-Range`, the byte range is split into up to 16 chunks (1–16, default 8, adjustable
  in the Downloads tab) and each chunk is fetched by its own coroutine writing directly into its own
  offset of a pre-allocated file. One shared OkHttp client pools the connections.
- **Several files at once.** 1–6 concurrent downloads (default 2), governed by a semaphore.
- **Runs in the background.** A foreground service with a progress notification keeps downloads
  going when the app is not on screen, with a Pause-all action in the notification.
- **Resumable.** Per-chunk progress is persisted, so a pause, a crash or a killed process resumes
  from the exact byte instead of starting over. Failed chunks retry with backoff (5 attempts).
- **Graceful fallbacks.** Servers without range support get a single stream; a server that ignores
  `Range` mid-download is detected and that chunk restarts cleanly.
- **Offline playback.** Finished files play from disk through the built-in ExoPlayer screen.
- Files land in the app's external `Movies` directory — no storage permission needed.

### Torrent streams download in-app too
Addons like Torrentio return `infoHash` streams rather than HTTP URLs. Those are downloaded by a
**built-in BitTorrent engine** (libtorrent via libtorrent4j — the same engine LibreTorrent and
FrostWire use). Nothing is handed off to another app:

- The magnet is built from the addon's infoHash and trackers (with public-tracker fallbacks) and the
  metadata is fetched over DHT.
- The video file inside the torrent is picked automatically: the addon's `fileIdx` when it sends one,
  otherwise the largest video file — so a season pack does not drag in the whole season, and
  `sample.mkv` / `RARBG.txt` are never mistaken for the film. Every other file is set to `IGNORE`,
  so only the video is written to storage.
- Sequential download order, so the beginning of the file lands first.
- **Play while it downloads.** Pressing Play on a torrent starts the transfer and hands the player
  a URL from a local HTTP server bound to `127.0.0.1` — the same approach Stremio's own streaming
  server takes on port 11470. The server answers range requests out of the partially-downloaded
  file and gives libtorrent piece deadlines around the read position, so playback starts after the
  first pieces instead of after the whole file. One transfer feeds both playback and the saved
  file.
- Live seeds/peers counts, pause and resume, and no seeding after the file is complete.

### Where the video ends up
When a download finishes — HTTP or torrent — the file is copied into **Movies/Stremio Lite** in the
phone's own media library, so it shows up in the gallery and the Files app like any other video. On
Android 10+ this uses MediaStore and needs no permission; on older versions it uses
`WRITE_EXTERNAL_STORAGE` (declared with `maxSdkVersion="28"`).

## Build

```bash
# Android SDK 34 + JDK 17 required; then:
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
gradle testDebugUnitTest      # 27 unit tests, incl. a MockWebServer download test
gradle assembleRelease        # per-ABI APKs under app/build/outputs/apk/release/
```

### Signing

Release builds are signed with a shared key when one is configured, so a new build installs **over**
the previous one. Without it Gradle falls back to the debug key — and because every machine (and
every CI runner) generates its own debug key, APKs from different sources refuse to install over
each other with a signature error.

The keystore is deliberately **not** committed, since this repository is public. Point the build at
it through `local.properties`:

```properties
stremio.keystore=/absolute/path/to/stremio-release.keystore
stremio.keystorePassword=...
stremio.keyAlias=stremio
stremio.keyPassword=...
```

For CI, add these repository secrets (Settings → Secrets and variables → Actions):
`STREMIO_KEYSTORE_BASE64` (the keystore, base64-encoded), `STREMIO_KEYSTORE_PASSWORD`,
`STREMIO_KEY_ALIAS`, `STREMIO_KEY_PASSWORD`. The workflow decodes the key when the secrets exist and
falls back to the debug key when they do not.

CI (`.github/workflows/android.yml`) runs the tests and uploads the APKs as artifacts on every push.

To install on a phone: enable "install unknown apps" and open the APK, or
`adb install app-debug.apk`.

## Layout

```
app/src/main/java/com/stremio/lite/
├── AppContainer.kt              shared graph (one OkHttp client for everything)
├── data/api/                    api.strem.io: auth, addon collection, library
├── data/addon/                  addon protocol: manifest, catalog/meta/stream, repository
├── data/download/               chunked downloader, torrent engine, queue, foreground service
└── ui/                          Compose screens + view models
```

## Notes and limits

- Talking to `api.strem.io` with your own credentials is what the official clients do; this app
  uses no private keys and stores nothing off-device.
- The APK carries libtorrent's native library for arm64, arm32 and x86_64, which is most of its
  ~41 MB size.
- Peer discovery needs DHT and UDP. Mobile networks often block or rate-limit both, so a torrent
  that finds nothing on mobile data may work immediately on Wi-Fi. The UI shows the live DHT node
  count while it searches, so "0 sources" can be told apart from "0 DHT nodes".
- Video inside an archive (`.rar`) is not unpacked; the app picks video files, not archives.
- Watch progress is read from your account but not yet written back (`datastorePut` is wired in the
  API client but no player reporting is hooked up to it).
- Addon `config` (per-addon settings pages) is not implemented; configure such addons on the web and
  the configured transport URL syncs down with your collection.
- The app does not ship or endorse any content source. Which addons you install, and what you do
  with them, is yours to decide.
