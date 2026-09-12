# Design

Decisions taken so far, and the reasoning behind them. Written down because most of it was settled
in discussion before any code existed, and the code only shows the conclusions.

## What the app is for

Capturing, on Android, a **screenshot plus a typed note plus the page's URL**, in one flow. The
screens worth capturing are web pages and PDFs. The browser and PDF viewer exist to make the URL
(and, for PDFs, the page number) reliably available at capture time — that is the whole reason the
app embeds them rather than leaning on Chrome.

## Why an in-app browser at all

Android gives no way to hook the system screenshot. `Activity#registerScreenCaptureCallback`
(API 34) only fires for the app's own foreground activity, so it cannot see Chrome. That leaves two
shapes: react after the fact to an image the system wrote, or be the thing that captures.

Options weighed:

| Approach | Verdict |
| --- | --- |
| **Share target** (`ACTION_SEND` from the screenshot notification) | Kept as a fallback for other apps' screens. Cheap, works everywhere, but **no URL** — sharing an image does not carry one — and the flow is long. |
| **MediaStore observer** (watch `Screenshots/`, pop up a note field) | Rejected for now. Best UX, but needs `READ_MEDIA_IMAGES`, an overlay permission and a foreground service, and still no URL. |
| **AccessibilityService** to read Chrome's address bar | Rejected. The only way to get a URL out of Chrome, but it yields the *displayed* string (truncated, scheme stripped), breaks when Chrome's resource IDs change, and is a heavy permission. |
| **MediaProjection** (app captures the screen itself) | Rejected. Android 14 requires consent per capture session, which makes the flow heavy, and it still needs the accessibility hack for URLs. |
| **Own browser + PDF viewer** ← chosen | Exact URL, page title, selected text, scroll position, PDF page number, full-page capture, and the smallest permission set (just internet). Costs: no Chrome session/logins, and a PDF viewer to build. |

Phasing: browser + PDF viewer first (done), then capture and notes, then the share-target fallback
if it still seems worth it.

## Storage and backup

The user runs **git from Termux**. That settles a question the app would otherwise have freedom on:
Termux is a separate app and cannot read `Android/data/<pkg>/`, so the data has to live in shared
storage.

```
/storage/emulated/0/Download/screennote/
├── .nomedia                       # keep captures out of the gallery
├── images/2026-08-07T12-30-00.webp
└── notes/2026-08-07T12-30-00.json
```

**The app writes plain files and knows nothing about git.** Termux owns `.git` and runs
`git add -A && git commit && git push`. This removes JGit from the app entirely.

Metadata, one JSON sidecar per capture:

```json
{
  "capturedAt": "2026-08-07T12:30:00+09:00",
  "url": "https://example.com/article",
  "title": "Article title",
  "note": "what the user typed",
  "sourceApp": "screennote",
  "pdfPage": 12,
  "selectedText": "optional excerpt"
}
```

Notes on this shape:

- **WebP, not PNG.** Observed screenshots are ~250 KB as PNG; WebP should land at 60–120 KB. Git
  never forgets, so the saving compounds.
- `git config core.fileMode false` on the Termux side — shared storage is a FUSE mount with
  synthesised permission bits, so otherwise every file reads as modified. `safe.directory` may also
  be needed.
- `core.compression 0` is worth setting: the images are already compressed.
- Keep a Room index with FTS for search rather than scanning JSON.
- Markdown export (`![](…)` + URL + note) keeps the data portable and avoids lock-in.
- If the repository ever gets too heavy, the split to reach for is content-addressed images outside
  git with only the metadata tracked.

**Open decision:** writing a subdirectory tree under `Download/` with `java.io.File` needs
`MANAGE_EXTERNAL_STORAGE` (fine for a sideloaded app, never for Play). The alternative is SAF — the
user picks the directory once and the app writes through `DocumentFile`, needing no special
permission. The app only ever writes, so SAF is sufficient; this has not been decided.

Rejected: a git repository **inside** app-private storage with a local HTTP git server or
`git bundle` export. Elegant, and it needs no storage permission, but Termux cannot reach
app-private storage, so it does not fit the user's actual workflow.

## Passwords

**Delegated to the system autofill service.** `importantForAutofill = YES` on the WebView, plus
`AutofillManager.commit()` on navigation — without that call the "save password?" prompt never
fires for WebView content. Screennote has no vault, no encryption, no key management, and never
reads a password field.

This was chosen over building a password manager in the app. A vault would have meant: Keystore-
backed AES-GCM with a biometric gate, a passphrase-derived export path (Keystore keys die with the
device), and above all a JS bridge — the single largest attack surface available, since anything the
page can call could read every credential. Delegating removes all of it.

Two things to know about the delegated path:

- Some managers (Bitwarden, 1Password) warn on or refuse third-party WebViews, because the web
  domain reported to the autofill service is the *app's own claim* and can be spoofed. With Google
  Password Manager this is not felt in practice.
- Passwords entered here do **not** reach Chrome directly — Chrome's store is internal to Chrome and
  WebView's data directory is per-app. But if the device's autofill service is Google, saving puts
  the credential in the account-level store that Chrome also reads, so it is effectively shared.

If a self-contained vault is ever wanted anyway, the non-negotiables are: fill only on explicit user
action (never on load), match on the *frame's* exact origin and refuse cross-origin iframes, keep
the vault out of `allowBackup` and out of `Download/`, and never expose it to page JavaScript.
