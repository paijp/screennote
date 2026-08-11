# Releasing

## One-time: create the signing key

Android will only install an update over an existing app when both APKs are signed by the **same**
key. Losing this key means every user has to uninstall and reinstall, so keep a backup somewhere
you trust — it must never be committed to this (public) repository.

```sh
keytool -genkeypair -v \
  -keystore screennote-release.jks \
  -alias screennote \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12
```

Then register it as repository secrets:

```sh
base64 -w0 screennote-release.jks | gh secret set KEYSTORE_BASE64 --repo paijp/screennote
gh secret set KEYSTORE_PASSWORD --repo paijp/screennote   # the store password you just chose
gh secret set KEY_ALIAS         --repo paijp/screennote   # screennote
```

`KEY_PASSWORD` is optional and defaults to `KEYSTORE_PASSWORD`. A PKCS12 keystore — what the
command above creates, and what recent `keytool` produces by default — cannot hold a separate
password per key, so the two are necessarily the same. Set `KEY_PASSWORD` only for a JKS keystore
where they genuinely differ.

Without `gh`, add the secrets at
`https://github.com/paijp/screennote/settings/secrets/actions`.

Store `screennote-release.jks` and its passwords offline. The release workflow fails loudly if
`KEYSTORE_BASE64` is missing rather than falling back to a throwaway key, because a release signed
with a different key would silently break every future in-app update.

## Cutting a release

```sh
git tag v0.1.0
git push origin v0.1.0
```

`.github/workflows/release.yml` then:

1. derives `versionName` from the tag and `versionCode` as `major*10000 + minor*100 + patch`
   (so `0.1.0` → `100`, `1.2.3` → `10203`) — this must increase on every release;
2. runs the unit tests;
3. builds and signs `screennote-<version>.apk`;
4. creates the GitHub release and attaches the APK;
5. commits the same APK to `release/` on the default branch, together with `release/latest.json`.

Each version component must stay below 100 (`1.99.99` is the ceiling before versionCode
collisions).

### Without pushing a tag

`workflow_dispatch` takes the version directly, which is the only route available from an
environment whose git proxy refuses tag pushes:

- Actions → **release** → **Run workflow** → version `0.1.1`, or
- `gh workflow run release.yml --repo paijp/screennote -f version=0.1.1`

`gh release create` creates the tag server-side either way, so the result is identical to tagging.

## How the app finds updates

The app does **not** use the Releases API. It reads
`https://raw.githubusercontent.com/paijp/screennote/main/release/latest.json`, which the release
workflow writes:

```json
{
  "versionName": "0.1.1",
  "versionCode": 101,
  "apk": "screennote-release.apk",
  "sha256": "…",
  "publishedAt": "2026-08-11T05:42:06Z"
}
```

`versionName` is compared against `BuildConfig.VERSION_NAME`; if it is newer, the APK is fetched
from `…/main/release/screennote-release.apk`, its SHA-256 checked against the manifest, and handed
to the package installer.

Why `release/` rather than the Releases API: no token, no per-IP API rate limit, and the manifest
can carry a checksum. GitHub Releases remain the per-version archive — `release/` only ever holds
the newest build.

The repository, branch and directory are baked in at build time
(`BuildConfig.GITHUB_REPO`, `RELEASE_BRANCH`, `RELEASE_DIR`) and can be overridden with
`-PgithubRepo=` / `-PreleaseBranch=` / `-PreleaseDir=`.

Two consequences worth remembering:

- **`raw.githubusercontent.com` is CDN-cached for a few minutes.** The app appends a timestamp
  query to defeat a stale edge copy, but a check made seconds after a release can still miss it.
- **The workflow pushes to the default branch.** That push is made with `GITHUB_TOKEN`, so it does
  not itself trigger `build.yml`.

## Verifying the update path

1. Install release *N* on the device.
2. Release *N+1* (tag push, or `workflow_dispatch` as above).
3. Confirm `release/latest.json` on the default branch shows *N+1*.
4. Open Screennote. The launch check should offer the update within a second or two; the menu's
   **Check for updates** forces the same check and reports the result either way.
5. The first install requires granting "install unknown apps" to Screennote — the app detects this
   and opens the Settings screen.

If the install dialog reports a signature mismatch, releases *N* and *N+1* were signed with
different keys. If the download fails with "checksum mismatch", `release/latest.json` and
`release/screennote-release.apk` are out of step — re-run the release.
