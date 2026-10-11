![HushTumblr. Tumblr, on your terms.](assets/readme-hero.png)

<p align="center">
  <img src="https://img.shields.io/badge/version-0.0.1-446B93" alt="Version 0.0.1">
  <img src="https://img.shields.io/badge/status-in%20development-D4A72C" alt="In development">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License GPL-3.0"></a>
  <img src="https://img.shields.io/badge/platform-Android%208%2B-3DDC84" alt="Platform Android 8+">
  <img src="https://img.shields.io/badge/target-Tumblr%2046.5.0.109-001935" alt="Development target Tumblr 46.5.0.109">
</p>

<p>
  <a href="https://ko-fi.com/X8K126YVER">
    <img height="42" src="https://storage.ko-fi.com/cdn/kofi2.png?v=3" alt="Buy me a coffee on Ko-fi" />
  </a>
</p>

# <img src="assets/icon.png" width="36" alt=""> HushTumblr

Morphe patches for Tumblr on Android. The goal is a quieter dashboard, with controls over ads, suggested content and the parts of Tumblr you don't use.

**HushTumblr is in development.** Version 0.0.1 is the initial source scaffold. There's no published patch bundle yet, and builds and device testing are still pending. Nothing here has been tested on a phone, and the plans below aren't features yet.

## Current target

| | Development target |
|---|---|
| App | Tumblr for Android |
| Package | `com.tumblr` |
| Version | **46.5.0.109** |
| Version code | `1460500109` |
| Android | 8.0 or newer |
| Architecture | Any (the bundle carries ARM64, 32-bit ARM and x86 builds) |
| Input | Tumblr's split APK bundle (`.apkm`) |

This is the version the source is being adapted for. It isn't a compatibility guarantee yet. Other Tumblr versions haven't been verified.

## What's planned

| Area | Planned controls |
|---|---|
| Feed filters | Hide reblogs. Hide video, photo or text posts. Hide posts with words you choose. |
| Posts | Hide the like, reblog and comment counts. Hide each action button on its own. |
| Appearance | Pick your own background color, AMOLED black included. Hide the diamond icon and TumblrMart. Hide the bottom bar's unread dots and rearrange its tabs. |

Each of these has to be checked against Tumblr itself before it's described as available. The first device run also needs to check sign-in and notifications.

## Patches

There are 19 patches so far, all for Tumblr 46.5.0.109. None of them has been built or tried on a phone yet.

| Patch | What it does |
|---|---|
| `Disable analytics` | Turns off Firebase crash reports and Google's analytics collection, and Tumblr's own tracking and ad measurement once it restarts. The Firebase and Google part stays until you patch again without it. On by default. Turn it off in Tumblr settings > HushTumblr > Privacy. |
| `Disable update prompts` | Stops Tumblr asking Google Play for a newer version and showing Play's update prompt. Starts off. Turn it on in Tumblr settings > HushTumblr > More settings > Updates. |
| `Fix push notifications` | Lets the patched app sign up for push notifications. Firebase can refuse a patched Tumblr whose request doesn't name Tumblr's original signature, so this sends that signature's fingerprint and changes nothing else. On by default. Turn it off in Tumblr settings > HushTumblr > More settings > Notifications. |
| `Hide Blaze prompts` | Hides the prompts to pay for Blaze, Tumblr's way to promote a post. That covers the Blaze buttons on your blog and dashboard, the nudge after you post, and the Blaze banners. Starts off. Turn it on in Tumblr settings > HushTumblr > Interface. |
| `Hide Tumblr TV` | Takes the Tumblr TV tab out of the bottom bar, and stops sign-up from sending you there. Starts off. Turn it on in Tumblr settings > HushTumblr > Interface. |
| `Hide Watch it rows` | Takes Tumblr TV's Watch it rows and video hub cards out of your dashboard and other feeds, along with their headings. Starts off. Turn it on in Tumblr settings > HushTumblr > Feed. |
| `Hide ads` | Takes ads and paid Blaze posts out of your dashboard and other feeds, and stops Tumblr asking its ad networks for more. On by default. Turn it off in Tumblr settings > HushTumblr > Feed. |
| `Hide advertising ID` | Tumblr and the ad companies inside it see an empty advertising ID with ad tracking limited, as if you'd deleted your ad ID in Android. On by default. Turn it off in Tumblr settings > HushTumblr > Privacy. |
| `Hide hashtags` | Takes the tags off the bottom of posts in your dashboard and other feeds, and keeps the source link. Starts off. Turn it on in Tumblr settings > HushTumblr > Posts. |
| `Hide suggested blogs` | Takes the blogs Tumblr suggests you follow out of your dashboard and other feeds, whether as cards, rows or carousels, along with their headings. Starts off. Turn it on in Tumblr settings > HushTumblr > Feed. |
| `Hide suggested communities` | Takes the communities Tumblr suggests you join out of your dashboard and other feeds, along with their headings. Starts off. Turn it on in Tumblr settings > HushTumblr > Feed. |
| `Hide the Communities tab` | Takes the Communities tab out of the bottom bar and stops the Communities tips in the post editor. Starts off. Turn it on in Tumblr settings > HushTumblr > Interface. |
| `Hide the Follow button` | Takes the Follow button out of the header of posts from blogs you don't follow yet. Starts off. Turn it on in Tumblr settings > HushTumblr > Posts. |
| `Hide the ad-free banner` | Hides the banner on your dashboard that asks you to pay for Ad-Free Browsing. Starts off. Turn it on in Tumblr settings > HushTumblr > Feed. |
| `HushTumblr settings` | Adds a HushTumblr page where you turn features on or off, pause HushTumblr, back up your settings and read the licenses. Open it from Tumblr's Settings or by long-pressing the Tumblr icon. Works as soon as you patch it in, with no switch. |
| `Remove ad tracking permissions` | Removes Google's advertising ID permission and Android's Privacy Sandbox ad services from Tumblr, so it can't use them. Google Play services then answers with zeros. Works as soon as you patch it in, with no switch. |
| `Skip review prompts` | Stops the pop-up that asks what you think of the Tumblr app and then asks you for a store rating or feedback. Starts off. Turn it on in Tumblr settings > HushTumblr > Interface. |
| `Spoof signature for Google sign-in` | Helps Google sign-in work in the patched app by naming Tumblr's original signature. It only helps with microG-RE or the XSpoofSignatures module. Email sign-in doesn't need it. It isn't selected by default. Works as soon as you patch it in, with no switch. |
| `Turn off screenshot sharing` | Stops Tumblr watching for screenshots, so taking one doesn't bring up its offer to save the post as an image. Starts off. Turn it on in Tumblr settings > HushTumblr > Interface. |

Morphe Manager selects every patch but Spoof signature for Google sign-in by default. Hide ads, Disable analytics, Hide advertising ID and Fix push notifications start with their switches on, and every other switch starts off. Remove ad tracking permissions and the signature patch have no switch. Their manifest changes, and the Firebase and Google part of Disable analytics, stay until you patch again without them. The settings patch is required by the others.

Switches change the runtime hooks without patching again. Pause makes those hooks follow Tumblr's original path. HushTumblr settings opens from the HushTumblr row at the end of Tumblr's own Settings screen. The long-press shortcut on the Tumblr icon and Additional settings in the app, on Tumblr's App info page, open it too.

## Privacy

HushTumblr doesn't collect anything and has no server. The release check stays off until you turn it on. Once it's on, HushTumblr asks `api.github.com` for its latest release at most once a day, when Tumblr starts, and again whenever you tap Check now.

Disable analytics changes Tumblr's manifest when you patch: Firebase Analytics, Crashlytics and Performance collection go off, Google Analytics stops collecting the ad ID, and Google's four default consent signals are set to denied. It leaves Firebase messaging and sign-in in place. Its switch also turns off Tumblr's own tracking once Tumblr restarts: LiveRamp's ID on its requests, Open Measurement and Moat ad measurement, and its logs of which features you saw, network use, app performance and ad supply. Hide advertising ID changes the answer Google's ad ID getters give inside Tumblr, so Tumblr's own requests and the bundled ad and analytics SDKs see zeros. It doesn't touch the ad ID other apps see. Remove ad tracking permissions takes Google's advertising ID permission, the Privacy Sandbox ad services permissions and the ad services configuration out of Tumblr's manifest. Hide ads answers no to Tumblr's own switches for asking its ad networks for ads, so those requests stop once Tumblr restarts.

Fix push notifications looks at the address and package of Firebase's own request before Firebase sends it, and swaps only the certificate fingerprint for Tumblr's original one. It doesn't open a connection or send anything itself.

The About and Licenses screens link to `github.com`, `gitlab.com` and `www.gnu.org`, and Support HushTumblr on the settings home links to `ko-fi.com`. The optional setup guide links to password and data help at `help.tumblr.com`. All of these open in your browser, and only when you tap one.

## Installation

There isn't an installable HushTumblr release or a live patch-source link yet. Once a build has passed its checks, this section will contain the supported Tumblr download and the steps for adding HushTumblr to [Morphe Manager](https://github.com/MorpheApp/morphe-manager).

## Building from source

The project uses Kotlin patches and a Java Android extension. It inherits the Hush build setup and needs JDK 21, the Android SDK, and access to the Morphe packages on GitHub Packages. Set `GITHUB_ACTOR` to your GitHub username and `GITHUB_TOKEN` to a token with `read:packages` access.

The intended build sequence is below. It hasn't been verified for this initial Tumblr scaffold yet.

```powershell
.\gradlew.bat :patches:generatePatchesList
.\gradlew.bat :patches:buildAndroid
```

The bundle is written to `patches/build/release/`. Generate the patch list first so the Android payload is included.

Focused checks use these tasks:

```powershell
.\gradlew.bat :patches:test :extensions:tumblr:testDebugUnitTest :extensions:tumblr:lintDebug
```

Fixture checks need the Tumblr APK named in `AppCompatibilities.kt` in the folder `HUSHTUMBLR_FIXTURE_DIR` points at:

```powershell
.\gradlew.bat :patches:fixtureTest
```

A successful compile won't establish that a patch works in Tumblr. Applying the patches to the target build and exercising them on Android are separate checks.

## Where the patches come from

| Source | What came from it |
|---|---|
| [SysAdminDoc/HushPinterest](https://github.com/SysAdminDoc/HushPinterest) at `debdac4` | The Gradle build, the shared extension library with its settings screen, diagnostics, pause and backup, the privacy and push notification patches, the bytecode helpers, and the checks that apply every patch to a real APK before a release. Most of that came to HushPinterest from [HushTelegram](https://github.com/SysAdminDoc/HushTelegram), [HushThreads](https://github.com/SysAdminDoc/HushThreads) and [Hushfacebook](https://github.com/SysAdminDoc/Hushfacebook). |
| [Morphe](https://github.com/MorpheApp) and [ReVanced](https://gitlab.com/ReVanced/revanced-patches) | The patcher and the patch template. Everything above grew from their code. |

Tumblr patches written for this project come from reading Tumblr itself. Every source file says where it came from in its header, and [provenance.json](provenance.json) maps each file to the project and commit it came from, with its license. The [source ledger](sources/tumblr-sources.json) is where other Tumblr patch projects get recorded once they've been reviewed.

## Release signing key

Releases will sign their `SHA256SUMS.txt` with the HushTumblr release key. Its fingerprint is `2143 368A A5CE 6E5D FFEA  79CF BBC1 3768 83CF 3AB4`, and the public key is [keys/hushtumblr-release.asc](keys/hushtumblr-release.asc). `scripts/verify-release-checksums.ps1` checks a download against it offline.

## Artwork

The navy H icon and poster follow the Hush family style. The poster is brand artwork, not a screenshot of the app. A [social preview](assets/github-social-preview.png) is included, and the original masters and design brief are kept in `concepts/marketing/2026-10-11/`.

## Hush family

| Project | App |
|---|---|
| [HushPinterest](https://github.com/SysAdminDoc/HushPinterest) | Pinterest |
| [HushTelegram](https://github.com/SysAdminDoc/HushTelegram) | Telegram |
| [Hushfeed](https://github.com/SysAdminDoc/hushfeed) | TikTok |
| [HushGram](https://github.com/SysAdminDoc/HushGram) | Instagram |
| [Hushfacebook](https://github.com/SysAdminDoc/Hushfacebook) | Facebook |
| [HushMessenger](https://github.com/SysAdminDoc/HushMessenger) | Messenger |
| [HushThreads](https://github.com/SysAdminDoc/HushThreads) | Threads |

## Credits and license

HushTumblr starts from [HushPinterest](https://github.com/SysAdminDoc/HushPinterest), which carries shared work from HushTelegram and the other Hush projects. The patching tools come from [Morphe](https://github.com/MorpheApp) and [ReVanced](https://gitlab.com/ReVanced/revanced-patches). Source headers keep their upstream attribution.

[GPL-3.0](LICENSE), with the upstream notices in [NOTICE](NOTICE). Tumblr is a trademark of Tumblr, Inc. HushTumblr is an independent project and isn't affiliated with Tumblr.
