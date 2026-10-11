![HushTumblr. Tumblr, on your terms.](assets/readme-hero.png)

<p align="center">
  <img src="https://img.shields.io/badge/version-0.0.1-446B93" alt="Version 0.0.1">
  <img src="https://img.shields.io/badge/status-in%20development-D4A72C" alt="In development">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License GPL-3.0"></a>
  <img src="https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84" alt="Android 8.0 or newer">
  <img src="https://img.shields.io/badge/target-Tumblr%2046.5.0.109-001935" alt="Development target Tumblr 46.5.0.109">
</p>

# <img src="assets/icon.png" width="36" alt=""> HushTumblr

Morphe patches for Tumblr on Android. The goal is a quieter dashboard, with controls over ads, suggested content and the parts of Tumblr you don't use.

**HushTumblr is in development.** Version 0.0.1 is the initial source scaffold. There's no published patch bundle yet, and builds and device testing are still pending. The features below describe the plan, not a tested release.

<p>
  <a href="https://ko-fi.com/X8K126YVER"><img height="42" src="https://storage.ko-fi.com/cdn/kofi2.png?v=3" alt="Support HushTumblr on Ko-fi"></a>
</p>

A coffee helps cover the time spent testing and maintaining the Hush projects.

## Current target

| | Development target |
|---|---|
| App | Tumblr for Android |
| Package | `com.tumblr` |
| Version | **46.5.0.109** |
| Version code | `1460500109` |
| Android | 8.0 or newer |
| Architecture | ARM64 |
| Input | Tumblr's split APK bundle (`.apkm`) |

This is the version the source is being adapted for. It isn't a compatibility guarantee yet. Other Tumblr versions haven't been verified.

## What's planned

| Area | Intended controls |
|---|---|
| Dashboard | Hide ads, suggested blogs and communities, and Tumblr TV rows. |
| Feed filters | Filter reblogs, post types, and posts containing words you choose. |
| Posts | Choose which tags, counts and action buttons appear. |
| Appearance | Add an AMOLED black background and options for the bottom bar. |
| Privacy | Reduce targeted analytics and advertising identifiers. |
| Settings | Bring the Hush settings screen, pause controls, backups and diagnostics to Tumblr. |

Each control needs to be checked against Tumblr itself before it's described as available. The first device run also needs to check sign-in and notifications.

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

Fixture checks need the matching Tumblr APKs in `HUSHTUMBLR_FIXTURE_DIR`:

```powershell
.\gradlew.bat :patches:fixtureTest
```

A successful compile won't establish that a patch works in Tumblr. Applying the patches to the target build and exercising them on Android are separate checks.

## Artwork

The navy H icon and poster follow the Hush family style. The poster is brand artwork, not a screenshot of the app. A [social preview](assets/github-social-preview.png) is included, and the original masters and design brief are kept in `concepts/marketing/2026-10-11/`.

## Hush family

| Project | App |
|---|---|
| [HushTelegram](https://github.com/SysAdminDoc/HushTelegram) | Telegram |
| [Hushfeed](https://github.com/SysAdminDoc/hushfeed) | TikTok |
| [HushGram](https://github.com/SysAdminDoc/HushGram) | Instagram |
| [Hushfacebook](https://github.com/SysAdminDoc/Hushfacebook) | Facebook |
| [HushMessenger](https://github.com/SysAdminDoc/HushMessenger) | Messenger |
| [HushThreads](https://github.com/SysAdminDoc/HushThreads) | Threads |

## Credits and license

HushTumblr starts from [HushPinterest](https://github.com/SysAdminDoc/HushPinterest), which carries shared work from HushTelegram and the other Hush projects. The patching tools come from [Morphe](https://github.com/MorpheApp) and [ReVanced](https://gitlab.com/ReVanced/revanced-patches). Source headers retain their upstream attribution.

[GPL-3.0](LICENSE), with the upstream notices in [NOTICE](NOTICE). Tumblr belongs to its respective owners. HushTumblr is an independent project and isn't affiliated with Tumblr.
