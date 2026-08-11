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
4. creates the GitHub release and attaches the APK.

`workflow_dispatch` accepts a version directly if you would rather not tag first.

Each component must stay below 100 (`1.99.99` is the ceiling before versionCode collisions).

## Verifying the update path

1. Install release *N* on the device.
2. Tag and push release *N+1*.
3. Open Screennote. The launch check should offer the update within a second or two; the menu's
   **Check for updates** forces the same check and reports the result either way.
4. The first install requires granting "install unknown apps" to Screennote — the app detects this
   and opens the Settings screen.

If the install dialog reports a signature mismatch, releases *N* and *N+1* were signed with
different keys.
