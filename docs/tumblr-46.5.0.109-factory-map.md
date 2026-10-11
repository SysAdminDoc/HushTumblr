# Tumblr 46.5.0.109 app map

Static notes on the Tumblr build HushTumblr targets, taken from the APK with a dexlib2 probe on 2026-10-11. Use it to find hooks again when Tumblr ships a new version. Obfuscated names (three or four lowercase letters, like `mq7`) change with every release, so each one below says how to find it again by shape.

## The APK

| Fact | Value |
|---|---|
| Package | `com.tumblr` |
| Version | 46.5.0.109, versionCode 1460500109 (stable, released 2026-09-28) |
| SDK | minSdk 26 (Android 8.0), targetSdk 36 |
| Shipped as | Split bundle only on APKMirror (base plus 4 ABI, 6 density and 14 language splits) |
| Bundle | `tumblr-46.5.0.109-1460500109.apkm`, 39,786,920 bytes, SHA-256 `4e46ec7c464b42525de5b66ca1317fc739a2807374de5eb48f86eca719447e8f` |
| Merged APK | `tumblr-46.5.0.109-1460500109.apk`, 82,860,491 bytes, made with `scripts/MergeSplits.java` |
| Dex | 8 files in base.apk, about 47 MB, 67,272 classes |
| Application | `com.tumblr.App` |
| Launcher | `com.tumblr.ui.activity.JumpoffActivity` |
| Signer | `CN=Tumblr, OU=Tumblr, O=Tumblr, L=New York, ST=NY, C=US`, v2 and v3 schemes plus a Google source stamp |
| Cert SHA-256 | `39ac9c821ad2d605e4849f066e8aad3cca27d5e13eb03ad8755be234a2985dfd` |
| Cert SHA-1 | `cd177dd4057daf3af7eca430825ee7dc08ad56fa` |
| APKMirror path | `tumblr-inc/tumblr` (titles read "Tumblr: Social Media & Art") |

Tumblr keeps the real names of its API models (`com.tumblr.rumblr.model.*`, `com.tumblr.rumblr.response.*`) and of `com.tumblr.UserInfo`. Most other code is renamed by R8.

## Feature flags

Tumblr reads 213 server-controlled flags from `/v2/config`. They live in one enum, `mq7` in this build.

- **Find it:** the enum whose static initializer holds the string "Whether or not we should show the Tumblr Blaze landing page banner". It also has fields named `FEATURE_BUCKETS`, `immutableFeatureStateMap`, `analyticsFeatureReadStateMap` and `analyticsFeatureExposedStateMap`, and its companion holds "You're checking for Feature.isEnabled() with a non-boolean bucket!".
- **Constant names are kept** (`IN_APP_UPDATE`, `AD_FREE_CTA_BANNER` and so on). `toString()` turns them into the API's camelCase (`inAppUpdate`, `adFreeCtaBanner`).
- **The check path:** the static checks `k(mq7)Z` and `o(mq7)Z`, the instance checks `l()`, `m()`, `n()` and the varargs `h(mq7[])Z` all end in the companion (`mq7$b`). `d(mq7)Z` caches the answer in `immutableFeatureStateMap` after asking `e(mq7)Z`. `e` calls `f(mq7, bucket)Z`, which calls `g(mq7, String)Z`.
- **`g(feature, expected)`** reads the server value through `mt4.d(mt4, mq7, Z, I, Object)String` (a default-argument bridge to `mt4.c(mq7, Z)String`), compares it with `expected` ignoring case, logs the exposure through `l(mq7, Z)` and returns the result. Hooking the result right after its `move-result` (before the `l` call) overrides every boolean check, including the cached one, since the cache stores what `e` returned. A flag override applies after Tumblr restarts.
- `mt4` is the configuration holder. It also carries `AdPlacementConfiguration`, `Gdpr`, `Privacy`, `ClientControl` and `Dismissals`.

Flags worth a switch (all boolean):

| Area | Flags |
|---|---|
| Updates and nags | `IN_APP_UPDATE`, `IN_APP_PLAY_STORE_REVIEW`, `ENABLE_REPROMPTING_APP_REVIEW`, `AD_FREE_CTA_BANNER`, `SCREENSHOT_INTERCEPTION` |
| Tumblr TV | `TUMBLR_TV_MOBILE_NAV`, `TUMBLR_TV_ONBOARDING_REDIRECT`, `VIDEO_HUBS_RELATED_NUDGE` |
| Blaze | `BLAZE_DASHBOARD_ENTRYPOINT_IN_USER_BLOG_HEADER`, `BLAZE_DASHBOARD_ENTRYPOINT_NEAR_PREMIUM`, `BLAZE_NUDGE_AFTER_POST`, `SHOW_BLAZE_SETTINGS_BANNER`, `TUMBLR_BLAZE_LANDING_PAGE_BANNER` |
| Communities | `COMMUNITIES_TAB_BAR_ITEM`, `COMMUNITIES_TOOLTIPS`, `COMMUNITIES_SEARCH_TAB` |
| Ad requests | `MAKE_ADX_REQUESTS`, `MAKE_APS_AD_REQUESTS`, `MAKE_DISPLAY_IO_REQUEST`, `MAKE_DISPLAY_IO_INTERSCROLLER_REQUEST`, `MAKE_FAN_REQUESTS`, `MAKE_NIMBUS_CLIENT_AD_REQUESTS`, `GOOGLE_BANNER_ADS`, `GOOGLE_NATIVE_ADS`, `GOOGLE_REWARDED_ADS`, `SMART_BANNER_ADS`, `RENDER_NIMBUS_ADS`, `RENDER_VUNGLE_ADS`, `RENDER_DISPLAY_IO_INTERSCROLLER_S2S_ADS` |
| Tracking | `LIVERAMP_ATS`, `ENABLE_OM_SDK`, `MOAT_VIDEO_AD_BEACONING`, `LITTLE_SISTER_MOAT_LOGGING`, `SEND_FEATURE_EXPOSED_ANALYTICS`, `SEND_NETWORK_USAGE_DATA`, `SUPPLY_LOGGING`, `MOBILE_PERFORMANCE_LOGGING` |
| Shop and premium | `TUMBLRMART_EARNED_BADGES_NOTIFICATIONS`, `PREMIUM_PERK_GIFTS`, `TUMBLR_FREE_PREMIUM` |

Turning an ad-request flag off is a second line behind the feed filter, not a replacement for it: server-side ads (`GEMINI_AD`) arrive in the timeline whatever the flags say.

## Timeline

- `com.tumblr.rumblr.model.TimelineObject` wraps one feed item: `getData()` returns a `Timelineable`, and the metadata adds `isShowSponsoredLabel()Z`, `getSponsoredBadgeUrl()`, `getRecommendationReason()`, `getPlacementId()` and `getServeId()`.
- `Timelineable.getTimelineObjectType()` returns `com.tumblr.rumblr.model.TimelineObjectType`. Its ad and promotion constants in this build: `GEMINI_AD`, `FACEBOOK_BIDDAABLE` (Tumblr's spelling), `NIMBUS_AD`, `DISPLAY_IO_INTERSCROLLER_S2S_ADS`, `VUNGLE_AD`, `CLIENT_SIDE_AD`, `CLIENT_SIDE_MEDIATION`, `DISPLAY_IO_INTERSCROLLER_AD`, `GOOGLE_NATIVE` and `BLAZED_POST`. Other notable ones: `COMMUNITY_CARD`, `COMMUNITY_ROW`, `VIDEO_HUB_CARD`, `VIDEO_HUBS_ROW`, `TRENDING_TOPIC`, `TAG_RIBBON`, `BLOG_CARD`, `CAROUSEL`, `TIMELINE_MESSAGE`, `BANNER`, `RICH_BANNER`, `ANNOUNCEMENT`.
- Ad models: `ClientAd`, `NimbusClientAd`, `advertising.ServerSideAd`, `advertising.SimpleAd`, `BlazedPost` (which wraps a `post.Post` and a `blazerBlog`).
- **Lists to filter**, both reached from Moshi with the parsed list as the first argument:
  - `Timeline.<init>(List, PaginationLink)` (wrapped by `WrappedTimelineResponse`, the dashboard path).
  - `TimelineResponse.<init>(List, PaginationLink, Map)`, the base of `PostsResponse` (blog posts), `LikesResponse` and `BlogSearchResponse`.
- `post.Post` is partly renamed. Its Blaze data is `BlazeCampaign` (field `Q1`, getter `G()`) and `BlazeControl` (field `c2`, getter `h0()`). A post on your own blog can carry both, so they don't mark a paid placement on their own.

## Settings hub

The Settings screen is Jetpack Compose: `com.tumblr.settingshub.impl.hub.SettingsHubActivity`.

- **Rows:** the view model `settingshub.impl.hub.h` builds them in `J()List`. It starts a list builder (`ur3.c()`), adds one `qg0` per row and freezes the list with `ur3.a(List)List` just before returning. Appending a row means adding to the builder before that last call.
- **Row model `qg0`:** `<init>(rg0 kind, int titleRes, as9 icon, Integer subtitleRes)`, plus a default-argument bridge. The getters are `b()` kind, `c()` title, `a()` icon, `d()` subtitle.
- **Kinds `rg0`:** `PREFERENCES`, `NOTIFICATIONS`, `SUBSCRIPTIONS`, `LABS`, `PANEL` (only when `oy2.a()`), `ACCOUNT`.
- **Icons:** `com.tumblr.compose.ds.icons.outline.<Name>Kt.get<Name>(Lkx6;)Las9;` with `kx6.a` as the argument. The set includes `Hide`, `Shield`, `Settings` and `Sparkle`.
- **Clicks:** the row's click lambda `hub.c$b$a` (fields `a` the event sink, `b` the `qg0`) sends `hub.g$b(kind)`. The view model's `F(rg0)` maps the kind to an Intent through `hqh` and the activity's `v3(a$b)` starts it, toasting "This section isn't available yet." when the target is missing. A row of our own is recognized in the click lambda by its title resource before the kind is read.

## Other anchors

- `com.tumblr.UserInfo` keeps its name and has about 40 static `()Z` preference getters. `G()Z` reads `pref_is_premium_user`.
- The blog notification reminder reads `blog_notify_enabled` in `rk2.l()Z`.
- Play Core's in-app update library is present (`com.google.android.play.core.appupdate`) and gated by `IN_APP_UPDATE`.
- Ad and tracking SDKs in the dex: Google Mobile Ads, Amazon APS, Facebook Audience Network, Vungle (Liftoff), Mintegral, Smart AdServer, Nimbus, Display.io, LiveRamp ATS and the IAB Open Measurement SDK.
