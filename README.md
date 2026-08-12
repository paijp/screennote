# Screennote

An Android browser with a built-in PDF viewer, intended as the capture surface for a
screenshot-plus-note tool. This first milestone is the browser and viewer themselves, plus a
self-update path so the app can be kept current on a device without a store.

- **minSdk 27 (Android 8.1)**, targetSdk 34, Kotlin.
- PDFs are rendered with the platform's `android.graphics.pdf.PdfRenderer` (API 21+), so no
  JavaScript PDF engine is bundled and nothing depends on the device's WebView version.
- Passwords are delegated to the **system autofill service**. Screennote has no password store of
  its own and never reads password fields.

## What works

| Area | Behaviour |
| --- | --- |
| Browsing | URL/search bar, back navigation, pinch zoom, page progress, desktop-site toggle, light/dark theme |
| PDF | Links ending in `.pdf`, `application/pdf` downloads, and `ACTION_VIEW` intents from other apps open in the built-in viewer |
| PDF viewer | Continuous vertical scroll, pinch zoom with diagonal panning, lazy page rendering with an LRU cache, page indicator |
| Autofill | `importantForAutofill=YES` on the WebView, plus `AutofillManager.commit()` on navigation so the "save password?" prompt fires |
| Updates | On launch (silently) and from the menu, reads `release/latest.json` on the default branch and offers to download and install a newer APK |

Not yet implemented: the note-taking and capture features themselves, and PDF text
selection/search.

Pull-to-refresh was removed: `SwipeRefreshLayout` wrapping the WebView coincided with rendering
artefacts while zoomed (white rectangles over page content, the toolbar flashing). Reload lives in
the overflow menu. Those artefacts come from WebView's own tile rasteriser and outlived the
change — WebView draws through the host app's hardware-accelerated canvas instead of owning its
surface, which is why a full Chromium browser on the same device is unaffected. The menu therefore
offers a **rendering mode**: GPU (default), offscreen pre-raster (keeps the GPU, changes the raster
path, costs memory), or software (bypasses GPU raster entirely, costs smoothness).

## Building

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew test                 # JVM unit tests
```

The version is injected at build time; local builds get `0.0.0-dev`, which is deliberately older
than any release so the update path can be exercised.

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

- `usesCleartextTraffic` is **true**. It has to be, for a browser — plenty of sites are still
  plain HTTP. Mixed content inside an HTTPS page is still blocked
  (`MIXED_CONTENT_NEVER_ALLOW`), and Safe Browsing is on.
- The repository is public, so **releases are public downloads**. That is fine for the APK; it is
  the reason no keystore and no captured data live in this repository.
- `androidx.pdf` was not used: it requires API 31+.
- **Extra trust anchors.** Android 8.1's trust store predates roots that are in everyday use, so
  some sites fail with `SSL_UNTRUSTED` in WebView while loading fine in Chrome — Chrome ships its
  own root store, WebView uses the platform's. `res/xml/network_security_config.xml` keeps the
  system anchors and adds specific publicly trusted roots that newer Android versions ship
  themselves. Certificate errors are never bypassed: `onReceivedSslError` always cancels.
