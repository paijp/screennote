# Screennote

An Android browser with a built-in PDF viewer, intended as the capture surface for a
screenshot-plus-note tool: capture a web page or PDF **together with a typed note and the page's
URL**. The browser exists so that the URL — and, for a PDF, the page number — is reliably available
at capture time; see [docs/DESIGN.md](docs/DESIGN.md) for why that rules out leaning on Chrome.

This first milestone is the browser and viewer themselves, plus a self-update path so the app can be
kept current on a device without a store. **The capture and note features are not built yet.**

- **minSdk 27 (Android 8.1)**, targetSdk 34, Kotlin, views + ViewBinding.
- PDFs render with the platform's `android.graphics.pdf.PdfRenderer` (API 21+), so no JavaScript PDF
  engine is bundled and nothing depends on the device's WebView version.
- Passwords are delegated to the **system autofill service**. Screennote has no password store of
  its own and never reads password fields.

## Where things stand

| Area | Behaviour |
| --- | --- |
| Browsing | URL/search bar, back navigation, pinch zoom, page progress, desktop-site toggle, light/dark theme, rendering-mode choice |
| Errors | Network, HTTP and TLS failures are shown in place of the blank page WebView would otherwise leave; certificate errors are never bypassed |
| PDF | Links ending in `.pdf`, `application/pdf` downloads, and `ACTION_VIEW` intents from other apps open in the built-in viewer |
| PDF viewer | Continuous vertical scroll, pinch zoom with diagonal panning, lazy page rendering with an LRU cache, page indicator |
| Autofill | `importantForAutofill=YES` on the WebView, plus `AutofillManager.commit()` on navigation so the "save password?" prompt fires |
| Updates | On launch (silently) and from the menu, reads `release/latest.json` on the default branch and offers to download and install a newer APK, verifying its SHA-256 |
| Diagnostics | `DebugLog` ring buffer, readable and copyable from the overflow menu, mirrored to logcat |

Not implemented: **capture and note-taking**, PDF text selection/search.

Latest release: **v0.1.12**. Releases are cut by version, not by a fixed cadence; see
[docs/RELEASING.md](docs/RELEASING.md).

## Layout

```
app/src/main/java/jp/pai/screennote/
├── ScreennoteApp.kt          application: night mode, cache cleanup
├── DebugLog.kt               ring buffer mirrored to logcat
├── Palette.kt                chrome colours as literals (see "Notes on choices")
├── Prefs.kt                  SharedPreferences wrapper
├── browser/
│   ├── BrowserActivity.kt    the WebView host: navigation, errors, menu
│   ├── RenderMode.kt         GPU / offscreen pre-raster / software
│   ├── UserAgents.kt         desktop UA derived from WebView's own
│   └── UrlUtils.kt           input normalisation, PDF detection (pure, unit-tested)
├── pdf/
│   ├── PdfActivity.kt        viewer: zoom, panning, page indicator
│   ├── PdfDocument.kt        PdfRenderer wrapper, serialised access
│   ├── PdfPageAdapter.kt     lazy page rendering + LRU cache
│   └── PdfSource.kt          resolves http/content/file URIs to a seekable file
└── update/
    ├── UpdateChecker.kt      reads release/latest.json, version comparison
    ├── UpdateInstaller.kt    download + SHA-256 verify + package installer
    └── UpdateFlow.kt         the user-facing check/prompt/install flow
```

## Building

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew test                 # JVM unit tests
```

The version is injected at build time; local builds get `0.0.0-dev`, which is deliberately older
than any release so the update path can be exercised.

CI builds and tests every branch (`.github/workflows/build.yml`). Releases are built and published by
`.github/workflows/release.yml`.

## Updating on the device

`UpdateChecker` reads `release/latest.json` from the default branch over
`raw.githubusercontent.com`, compares its `versionName` against `BuildConfig.VERSION_NAME`, and
offers the APK sitting next to it — verifying the manifest's SHA-256 before handing it to the
package installer. The release workflow writes both files. See
[docs/RELEASING.md](docs/RELEASING.md) for why this is used instead of the Releases API.

Two things have to be true for this to work:

1. **Every release is signed with the same key.** Android refuses an upgrade signed by a different
   key. See [docs/RELEASING.md](docs/RELEASING.md).
2. **The user has allowed Screennote to install apps.** On Android 8+ this is a per-app grant; the
   app checks `canRequestPackageInstalls()` and sends the user to the right Settings screen.

## Notes on choices

- **Rendering mode.** WebView's GPU tile rasteriser leaves white rectangles over page content while
  zoomed on the target device, and the same artefacts reach the app's own chrome. WebView draws
  through the host app's hardware-accelerated canvas rather than owning its surface, which is why a
  full Chromium browser on the same device is unaffected. The menu offers GPU, offscreen pre-raster,
  and software; **only software cleared it, so that is the default.** Full investigation, including
  what was ruled out and what remains untried, in [docs/KNOWN-ISSUES.md](docs/KNOWN-ISSUES.md).
- **Pull-to-refresh was removed** while chasing that bug. Reload is in the overflow menu.
- **Chrome colours are literals, not `-night` resources** (`Palette.kt`), applied programmatically on
  create and resume. Android 8.1 has no system dark setting for a `DayNight` theme to follow, so the
  app offers the choice itself and paints from its own stored preference.
- **Extra trust anchors.** Android 8.1's trust store predates roots in everyday use, so some sites
  fail with `SSL_UNTRUSTED` in WebView while loading fine in Chrome — Chrome ships its own root
  store, WebView uses the platform's. `res/xml/network_security_config.xml` keeps the system anchors
  and adds specific publicly trusted roots that newer Android versions ship themselves. Each one's
  fingerprint is verified and recorded there. Certificate errors are never bypassed:
  `onReceivedSslError` always cancels.
- `usesCleartextTraffic` is **true**. It has to be, for a browser — plenty of sites are still plain
  HTTP. Mixed content inside an HTTPS page is still blocked (`MIXED_CONTENT_NEVER_ALLOW`), and Safe
  Browsing is on.
- `androidx.pdf` was not used: it requires API 31+.
- The repository is public, so **releases are public downloads**. That is fine for the APK; it is the
  reason no keystore and no captured data live here.

## Picking this up

Read [docs/DESIGN.md](docs/DESIGN.md) first — the capture and storage design is settled but
unimplemented, and the reasoning is not visible in the code.

Next piece of work: **capture and notes**. Per that document, the app writes plain files to
`Download/screennote/` (images as WebP, one JSON sidecar each) and knows nothing about git; the user
commits and pushes from Termux.

Decisions waiting on the device owner:

- **SAF or `MANAGE_EXTERNAL_STORAGE`** for writing under `Download/`. SAF is sufficient, since the app
  only writes. Not decided.
- **Whether software rendering's scrolling is acceptable in daily use.** This governs whether the
  remaining WebView options — swapping the device's WebView implementation, or migrating to GeckoView
  — are worth pursuing. See [docs/KNOWN-ISSUES.md](docs/KNOWN-ISSUES.md).
- **Confirmation that v0.1.12 fixed the PDF pinch anchoring.**

One environment note for whoever continues: in the session this was built in, the git proxy refused
**tag pushes** with HTTP 403, so every release was cut through `workflow_dispatch` instead of by
pushing a tag. Both routes produce the same result — `gh release create` makes the tag server-side.
