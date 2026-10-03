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

   A commit on its own changes nothing for anyone who has the app. Nothing is pushed to users,
   nothing is announced, and the app never asks: the tag is the whole act of publication, and
   until a tag exists there is no version to be newer than.

## Where the download actually is

Published 3 October 2026: **`v0.3.0-B3`** (version code 3) and then **`v0.3.1-B4`** (version code
4), each an `extranet-<version>.apk` with a `.sha256` beside it. The link to hand people is the
release index,

<https://github.com/rijing240/extranet-netdiag/releases>

and it stays correct as new versions are published — there is no URL to update anywhere.

Three things about it are easy to trip over.

- **The repository has to be public.** GitHub answers `404` to an unauthenticated request for a
  private repository — deliberately, so its name and contents are not discoverable — and the app
  reports that as "this app's release list was not found", which reads like a typo in the URL. It
  was exactly this: the first published release was invisible to every phone but the author's.
- **`/releases/latest` is empty on purpose.** GitHub resolves "latest" to the newest release that
  is *not* a pre-release, and every build so far carries a `-B3`-style suffix, so they are all
  marked as test builds. The index page above lists them anyway; the app's own check lists them
  too, labelled as test builds rather than hidden. The first version published without a suffix
  makes `/releases/latest` resolve on its own, and it needs no change here.
- **The pre-release APK will not install over a debug build.** Android refuses an update signed
  by a different key. Anyone who sideloaded a debug APK — including on a development phone —
  must uninstall it first. After that, every published build updates in place, because they all
  carry the one release key.

## The in-app update notice

A published build now **says so when the app opens**. If the newest release is newer than the one
installed, a banner sits above the screen naming the version, the file and its size, with **Get
it** to open the release page and **Not now** to make it go away. The dismissal belongs to *that
release* rather than to the question, so it is not shown again for that build, and the next
published one asks afresh — which is the whole difference between a notice and a nag, and is
[UpdatePrompt](../measure/src/main/kotlin/dev/extranet/netdiag/measure/UpdatePrompt.kt) in five
lines. It is a banner rather than a dialog on purpose: a dialog on every launch is something
people learn to dismiss without reading.

The top bar's info action remains for the explicit version, and still offers the same report on
demand. Tapping **Check for updates** makes **one** request to GitHub's release list and reports
what it finds:

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
exactly as it would from a browser. And the update check itself is only ever made when someone
taps the button — there is no scheduler, no alarm, and it is never asked on its own.

## Retiring a build

The update notice tells somebody a newer build exists. Retiring a build is the other half — telling
them the one they are holding must stop working — and it is the part Android gives no app any way
to do for itself. No app can close another, `adb` needs a cable, and there is no Play Console to
halt a sideloaded build. So the lever the app offers is a file, and it is read exactly once:

```
https://raw.githubusercontent.com/rijing240/extranet-netdiag/main/withdrawal-switch.txt
```

[`WithdrawalSwitch`](../measure/src/main/kotlin/dev/extranet/netdiag/measure/Withdrawal.kt) reads
it, and only the exact word `off` on the first line retires the build; `message` and `url` lines
say why and where the current build is. Everything else runs the app. **Every failure runs the
app** — no network, a timeout, a `404`, a captive portal answering instead of the file, a typo —
because a switch that could brick an app on a bad connection would strand whoever was using it,
with no way to tell why. The honest limit of that: the switch can retire a version, and it cannot
enforce anything against a phone that is offline.

Neither is instant, and both are worth knowing before relying on it. GitHub's raw host serves
the file from a cache for a few minutes after a commit, so an app opened immediately can still
read the old line; and the app asks once when it opens and never again, so a copy already running
keeps working until its owner closes it and starts it again. Retiring a build is therefore a
matter of minutes, not seconds — which is right for a version being handed over to a production
build, and would not be enough for a security emergency.

So this does add the app's one pass at launch that nobody asked for, which the rest of this
document used to say did not exist. It asks two questions in that one pass — *is this build still
wanted*, and *is there a newer one published* — because there is no sense in a phone making two
journeys at the moment it has already agreed to make one. It is bounded on purpose: once per
launch, never on resume, never retried, four seconds of timeout, 8 KB ceiling, and the app is drawn
and usable while the answer is on its way — the notice only ever *replaces* the screens, it never
covers them. What it sends is the URL and nothing else: no identifier, no version, no cookie, no
account. A withdrawn copy is told which build it is refusing to run, that nothing on the phone was
changed, that no measurement was uploaded, and — because a withdrawal that leaves somebody with no
way forward is a dead end rather than a retirement — which version is published and where to get
it.

The other lever, which costs no privacy at all, is to publish a version that declines to run. It
takes effect only for people who update, which is usually the point.

## The gate we are deliberately not building

The app could refuse to work until it is updated. It is not built, and it should stay that way
except for a security or compatibility emergency: the app works offline and is a measuring
instrument, so a network round trip is not a licence to hold a screen hostage. Note that even
such a gate cannot *force* an install — at most it can decline to run, which is a decision to
make only when running would be worse.
