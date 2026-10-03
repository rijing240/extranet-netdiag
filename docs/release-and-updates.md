# Releases, signing, and the update notice

How a build gets from this repository onto somebody's phone, and what the app is and is not
allowed to do about it.

## The rule this is built around

**An Android app cannot silently replace itself.** An update has to carry the same package name
(`dev.extranet.netdiag`), be signed with the same key as the build already installed, and have a
higher `versionCode`. Even then the system asks the user to confirm the install, because
installing an app is the user's decision and not an app's.

So the app does the one honest thing it can: it **tells** its owner that a newer build is
published, and **opens the download page** for them. It does not download behind their back, it
does not install, and it does not check on its own.

## The release key

The signing key *is* the app's identity. An update signed with a different key is refused by
Android, and there is no recovery: if the key is lost, every phone that installed the app can
never update it again. If it leaks, anyone can publish a build that installs over it.

Create it once, outside the repository, and back it up somewhere that will still exist in five
years (password manager, encrypted offline drive, or both):

```bash
keytool -genkeypair -v \
  -keystore keystore/release.jks \
  -alias extranet \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=extranet, O=extranet, C=KE"
```

Then copy [`keystore.properties.example`](../keystore.properties.example) to `keystore.properties`
and fill in the four values. That file and `*.jks` are git-ignored; the debug key is deliberately
still tracked, because it is a throwaway that only exists so CI can install over its own builds.

Both files are read by [`app/build.gradle.kts`](../app/build.gradle.kts). **With no
`keystore.properties`, the release build is unsigned rather than broken** — a fresh clone and a
pull request can still build and test everything; only publishing needs the key.

## Publishing a release

1. Bump `versionCode` (must go **up** every time) and `versionName` in
   [`app/build.gradle.kts`](../app/build.gradle.kts).
2. Tag it so the two agree, and push the tag:

   ```bash
   git tag v0.3.1 && git push origin v0.3.1
   ```

3. [`release.yml`](../.github/workflows/release.yml) then:

   - refuses to continue unless the tag is exactly `v<versionName>`, and unless `versionCode` is
     greater than the previous tag's — the two mistakes that would otherwise produce an APK
     nobody can install;
   - writes the key from repository secrets and builds `:app:assembleRelease`;
   - renames the APK to `extranet-<version>.apk`, writes a `.sha256` beside it, and proves with
     `apksigner` that it really is signed;
   - publishes a GitHub Release with both files attached, marked as a *pre-release* when the
     version carries a suffix such as `-B3`.

   Secrets the workflow needs: `RELEASE_KEYSTORE_BASE64` (the `.jks`, base64-encoded),
   `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.

## The in-app update notice

The top bar's info action shows the installed version and a **Check for updates** button. Tapping
it makes **one** request to GitHub's release list and reports what it finds:

- **newer build available** — names the version (and says so when it is a test build), shows the
  APK's name and size, and offers to open the release page;
- **up to date** — says so, with the version it compared;
- **could not check** — says why in a sentence, and notes the app is unaffected.

The decision lives in [`UpdateCheck.kt`](../measure/src/main/kotlin/dev/extranet/netdiag/measure/UpdateCheck.kt),
tested without a phone. It ranks releases by **version number, never by date**, ignores drafts,
ignores releases with no APK attached (they are not updates the app can offer), and orders
versions with a proper comparison rather than string order, so `0.10.0` beats `0.9.0` and
`0.3.0-B10` beats `0.3.0-B9`.

What leaves the phone: a `GET` to
`https://api.github.com/repos/rijing240/extranet-netdiag/releases?per_page=20`. No identifier, no
version, no device data, no cookie, no account. GitHub learns that an address asked a question,
exactly as it would from a browser. And nothing is sent at all unless someone taps the button —
there is no scheduler, no alarm, no check on launch.

## The gate we are deliberately not building

The app could refuse to work until it is updated. It is not built, and it should stay that way
except for a security or compatibility emergency: the app works offline and is a measuring
instrument, so a network round trip is not a licence to hold a screen hostage. Note that even
such a gate cannot *force* an install — at most it can decline to run, which is a decision to
make only when running would be worse.
