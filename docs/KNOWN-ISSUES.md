# Known issues

## WebView rendering artefacts while zoomed (open — worked around)

### Test device

| | |
| --- | --- |
| Device | `BBF100-9` (BlackBerry KEY2 LE) |
| Android | 8.1.0 (API 27) |
| WebView | Chromium **138**.0.7204.179 |

A 2026-era Chromium running on a 2018-era GPU driver. That combination is the heart of the problem
and is rare — most Android 8.1 devices in the field do not carry a WebView this new.

### Symptoms

While a page is **zoomed in and scrolling**:

- white rectangles covering parts of the page content, and page background turning white;
- the toolbar flashing black/white, also during the overflow menu's open animation;
- the system status bar turning white;
- the URL field drawn white-on-black while everything around it was white.

### Resolution so far

**Software rendering clears it**, and is now the default (`RenderMode.DEFAULT`). Offscreen
pre-raster (`setOffscreenPreRaster(true)`) does **not** help. All three modes stay selectable from
the overflow menu.

### Mechanism

WebView does not own its surface. It draws through the **host app's** hardware-accelerated canvas —
the app's RenderThread invokes WebView's native drawing inside the app's GL context. Chrome, Samsung
Internet and other full browsers own their own surface and process, which is why the same Chromium
version is fine there. Lightweight browsers that are WebView wrappers should reproduce this.

This also means the app has no hook into the failing code: there is no `eglMakeCurrent` to sequence,
no `onSurfaceCreated` to recreate resources in, and no texture the app owns. Generic OpenGL advice
does not apply. The only levers are the ones WebView exposes — which is exactly what `RenderMode` is.

### Ruled out, with the evidence

Recorded so it is not re-litigated. Three of these were wrong guesses made before the decisive
evidence arrived.

| Hypothesis | Why it is wrong |
| --- | --- |
| `SwipeRefreshLayout` wrapping the WebView | Removed in 0.1.7; artefacts persisted. (Removal kept anyway — reload is in the menu.) |
| Transparent toolbar / unset `windowBackground` letting content show through | Explicit opaque colours added in 0.1.5; artefacts persisted. And compositing cannot explain white blocks over *page content*. |
| A `DayNight` theme resolving light and dark at once | Themes split into explicit Light/Dark in 0.1.6; artefacts persisted. |
| WebView resetting the process `Configuration` (the known pre-API-29 behaviour) | The debug log shows `resolved=night` constant across an episode, with no `configChanged` and no activity recreation. (The literal-colour painting from 0.1.8 was kept regardless, and did stop the status bar being affected.) |
| Lowering `targetSdk` to 27 | WebView's rendering comes from the installed WebView package, not from `targetSdk`. No effect expected. |

The log staying **empty** across an episode was the finding that killed the theme theories: nothing
in the app's lifecycle was happening at all.

### Remaining options, if software rendering proves too slow

1. **Replace the device's WebView implementation** with an older Chromium (Developer options →
   WebView implementation, with an older Android System WebView installed). Zero app changes, and it
   targets the actual suspect. **Untested.** Costs: affects every app on the device and stops
   security updates for WebView.
2. **Stay on software rendering.** Current state. Costs scrolling smoothness only.
3. **Migrate to GeckoView.** The only realistic non-WebView engine. Different compositor, and its own
   NSS trust store (which would also make the bundled root CA unnecessary). Costs ~50–70 MB per ABI,
   more memory on an already modest device, and a rewrite of `BrowserActivity`. The PDF viewer and the
   updater are unaffected.

Rejected as engines: Crosswalk (dead since 2017), Servo (not production-embeddable), Custom Tabs or
launching external Chrome (no in-app surface, so no capture and no reliable URL — defeats the
project), writing an engine.

### Diagnostics available

- **In-app debug log** — overflow menu → Debug log → Copy. A ring buffer mirrored to logcat
  (`DebugLog`), carrying navigation, console messages, downloads, TLS failures, update checks, the
  resolved vs. painted palette, and PDF zoom anchors. Built because the device is not usually on adb.
- **`--disable-gpu-rasterization`** via `/data/local/tmp/webview-command-line`, if adb is available.
  Confirms GPU rasterisation as the cause without touching the app. Diagnostic only.

### Not yet verified

- Whether **Chrome on this same device** shows the artefacts when zoomed and scrolled. If it does not,
  option 1 above is likely to work. If it does, the fault is deeper in the driver and option 3 gains
  weight.
- Whether software rendering's scrolling is acceptable in daily use. This is the decision that
  governs whether options 1 or 3 are worth pursuing at all.

## Keyboard shortcuts do not reach the app while a page's text field has focus

Ctrl+L and the rest work everywhere except inside a text input on the page, where nothing happens.

Key events reach the input method before the application. A physical-keyboard IME — which is what
this device has — is free to consume them, and does, so `dispatchKeyEvent` is never called. That
method runs ahead of the view hierarchy, not ahead of the IME, and there is no ordering an app can
ask for that changes this.

Left as it is. Tapping outside the field first restores the shortcuts, and the alternative would be
fighting the platform for a case with an easy workaround.

## Resolved

- **TLS failure on `akizukidenshi.com`** (`SSL_UNTRUSTED`). The chain ends at GlobalSign Root R46
  (issued 2019), absent from Android 8.1's trust store; Chrome was fine because it carries its own
  root store. Fixed in 0.1.3 by bundling that one root as an extra trust anchor — see the README.
  Certificate errors are still never bypassed.
- **Status bar / toolbar colours from the wrong palette.** Fixed in 0.1.8 by painting the chrome from
  literals chosen from the app's own stored preference instead of from `-night` resources.
- **PDF pinch jumping to another page.** `ScaleGestureDetector` reports its focus in *window*
  coordinates; the anchor treated them as the page column's own, so every pinch anchored tens of dp
  low — often inside the next page. Fixed in 0.1.12. **Awaiting confirmation on the device.**
