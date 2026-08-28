# KiteSSOT 3.x DSL Reshape: Topics, Two Verbs, Flow Modifiers

Status: approved design, 2026-08-28.
Versioning: stays in 3.x. Hard break, no deprecation bridge. The plugin has a single user.

## TL;DR

The 3.0 DSL still confuses its own author. Three problems:

1. You cannot tell when a value gets SSOT-ed and when it does not.
2. Opening a block sometimes has side effects and sometimes does not, and the block name never tells you which.
3. One topic is scattered across many places. Version facts live in five blocks today.

The reshape fixes all three with one law and one layout rule:

* **Facts always flow.** Every declared fact reaches every platform found, on every build, in memory only. `skip()` and `only()` beside the fact are the only flow control.
* **Only two words act.** Blocks named `generate` (writes into `build/` only) and `rewrite` (arms by-name tasks that edit source) are the only blocks that ever touch disk, wherever they sit. Every other block is inert.
* **One topic, one block.** Everything about a concern lives inside that concern's block. Platform corners nest inside topics. Platform blocks hold only platform-exclusive things.

## Why 3.0 still hurts

| # | Pain | Example |
|---|------|---------|
| 1 | "Presence means intent" is inconsistent | `android { }` is inert, `desktop { }` changes ordinary builds, `logo { }` arms tasks, `buildConfig { }` generates code. Same syntax, four behaviors |
| 2 | Topics scatter | Version: root `version`, root `scheme`, `android` (3 props), `ios` (5 props), `desktop` (4 props) |
| 3 | Fuzzy words | `propagate`, `scheme`, `rebuild`, `sanitizePlist`, `takeOverLegacyIcons`, `applySdkLevels` |
| 4 | Hidden coupling | Desktop icons silently ride on `logo { }` plus `desktop { }` both being open |
| 5 | Double bookkeeping | `enabled` flags plus internal `configured` trackers next to the presence rule |

## The law

1. **Facts always flow.** A declared fact reaches every platform found, on every build. Flow is in-memory build configuration. Facts never touch files.
2. **Only two verbs act.**
   * `generate { }`: files appear under `build/` during ordinary builds. Never in the source tree.
   * `rewrite { }`: arms a task that edits your source. Nothing runs until you call the task by name. `dryRun`, `backups`, and `onConflict` always apply.
   * Verbs appear only nested inside the topic they serve. There are no top-level verb blocks. A topic without a verb inside it is inert facts.
3. **One topic, one block.** No topic may spread across blocks. Platform details nest inside the topic as corners.

No exceptions. An empty noun block is always a no-op.

## Unified vocabulary

The same word always means the same thing, in every topic.

| Word | Always means |
|------|--------------|
| `pin` | hard manual value, automatic machinery skipped |
| `reupload` | counter to re-upload the same version to a store |
| `shipped` | highest value ever shipped, guard floor |
| `suffix` | appended to the base id |
| `skip(p)` | this fact does not flow to platform p |
| `only(p)` | this fact flows only to platform p |
| `ios("x")` | platform-specific override of the base value |
| `ios { }` | platform detail corner |
| `generate` | files under `build/`, ordinary builds |
| `rewrite` | armed task, source edits, runs only by name |

Platform tokens are lowercase (`android`, `ios`, `desktop`), matching KMP and Gradle DSL style. One token family, three uses: flow argument, value override call, detail corner.

## The full surface

```kotlin
kiteSsot {

    // ============================ FACTS ============================

    // Simple form: appName = "Jetzy"
    appName("Jetzy") {
        ios("Jetzy Lite")          // ios shows its own name
        skip(desktop)              // desktop untouched
    }

    jvmTarget = 21                 // Java + Kotlin JVM level, whole build

    id("com.example.jetzy") {
        android { suffix = ".android" }   // applicationId = base + suffix
        ios     { suffix = ".ios" }       // bundle id     = base + suffix
        desktop { suffix = ".desktop" }
    }

    version("1.4.0") {
        // one formula: version to every store's build number
        formula { v -> 1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.reupload }

        android {
            reupload = 1               // re-upload same version to Play
            shipped  = 1001003090      // highest versionCode ever shipped
            // pin = 123               // hard versionCode, formula skipped
        }
        ios {
            shipped = "1001003090"
            // pin = "42"
            // marketingVersion = "1.4.0"
        }
        desktop { shipped = "1001003090" }
    }

    locales {
        // omit block = auto-detect from Compose resources
        pin("en", "ar", "fr")
        filterAndroidRes = true        // drop Android res folders outside the list
    }

    logo {
        foreground = file("art/logo-fg.png")
        background = file("art/logo-bg.png")
        backgroundColor = "#0B0B0F"
        androidSafeZone = 0.611

        generate { roundMac = true }   // desktop app icons into build/, wired into packaging
        rewrite  { replaceOld = true } // arms kiteRewriteLogo: android res + iOS asset catalog
    }

    splash {
        // every fact defaults to logo, an empty splash { } already works
        image           = file("art/splash.png")   // default: logo.foreground
        backgroundColor = "#101014"                // default: logo.backgroundColor
        dark { backgroundColor = "#000000" }       // optional dark variant

        generate { }    // Android themed splash res into build/ + desktop JVM -splash image
        rewrite  { }    // arms iOS UILaunchScreen plist dict + asset catalog entries
    }

    optIns {
        add("kotlinx.cinterop.ExperimentalForeignApi")
        projects(":shared")
        builtIns = true
    }

    // ---- Platform blocks: only platform-exclusive things ----

    android {
        sdk(min = 26, target = 36, compile = 36)
        ndk = "27.1.12297006"
    }

    ios {
        deploymentTarget = "15.0"
        // pbxproj / podfile / infoPlist / appDirectory / appIconDirectory
        //   set only when detection guesses wrong

        rewrite {                      // arms kiteRewriteXcode: pbxproj, Info.plist, Podfile
            targets("iosApp")
            cleanPlist = true
            onConflict = FAIL
            nonExemptEncryption = false
            proMotion = true
            renameSharedModule(from = "shared", to = "Shared")
        }
    }

    desktop {
        linuxPackageName  = "jetzy"
        deriveUpgradeUuid = true       // stable Windows MSI upgrade id from appId
    }

    web {
        ioWorker {                     // browser IO worker source
            targets("wasmJs")
            generate { }               // the consent, like everywhere else
        }
    }

    // ---- Master flow control, same words as everywhere ----
    // skip(desktop)                   // platform receives nothing at all

    buildConfig {                      // Kotlin object for commonMain
        packageName = "com.example.jetzy"
        className   = "AppInfo"
        stringField("API_HOST", "api.jetzy.app")
        generate { }                   // no generate inside = inert facts, nothing made
    }

    modules {
        shared = ":shared"
        androidApps(":androidApp")
        desktopApps(":desktopApp")
    }

    dryRun  = false                    // rewrites print, write nothing. CLI: -Pkitessot.dryRun=true
    backups = true                     // recovery copy before any rewrite
}
```

## Topic map: old to new

| Topic | Old (3.0) | New |
|-------|-----------|-----|
| App name | root `appName` | `appName` value or `appName("x") { }` with overrides and flow modifiers |
| Identity | root `appId` + `android.idSuffix` + `ios.bundleIdSuffix` + `desktop.idSuffix` | `id("base") { }` with `suffix` corners |
| Version | root `version` + root `scheme` + 12 props across 3 platform blocks | `version("x.y.z") { }` with `formula` and corners |
| Locales | root `locales` + `android.filterResourcesToLocales` | `locales { }` |
| Logo | `logo { }` + `desktop.icons` + `desktop.roundMacOsIcon` + hidden coupling | `logo { }` with nested `generate` and `rewrite` |
| Splash | none | `splash { }`, new topic |
| Xcode sync | `ios { sync { } }` | `ios { rewrite { } }` |
| Opt-ins | `nativeOptIns { }` | `optIns { }` |
| Build config | `buildConfig { }`, presence turns it on | `buildConfig { }` with nested `generate { }` as the consent |
| IO worker | `web { ioWorker { } }`, presence turns it on | `web { ioWorker { } }` with nested `generate { }` as the consent |
| Flow switches | `propagate { }` + `android.applySdkLevels` + `enabled` flags | `skip()` / `only()` at the fact, root `skip(p)` as master |
| Modules | `modules { }` | unchanged |

## Renames

| Old | New | Reason |
|-----|-----|--------|
| `scheme` | `formula` | says what it is |
| `rebuild` | `reupload` | says what it is for |
| `versionCode` / `buildNumber` overrides | `pin` | one word for manual override |
| `publishedVersionCode` / `publishedBuildNumber` | `shipped` | one word for the guard floor |
| `bundleIdSuffix` | `suffix` inside `id { ios { } }` | one word on all platforms |
| `filterResourcesToLocales` | `filterAndroidRes` inside `locales { }` | locale topic owns it |
| `takeOverLegacyIcons` | `replaceOld` | plain words |
| `sanitizePlist` | `cleanPlist` | plain words |
| `roundMacOsIcon` | `roundMac` inside `logo { generate { } }` | context carries the rest |
| `nativeOptIns` | `optIns` | context carries the rest |
| `ios.sync` | `ios.rewrite` | names the effect honestly |
| version fields in `formula` lambda: `v.rebuild` | `v.reupload` | matches the property rename |

Unchanged on purpose: `appName`, `jvmTarget`, `marketingVersion`, `deploymentTarget`, `nonExemptEncryption`, `proMotion`, `dryRun`, `backups`, `modules`, SDK level names inside `sdk(...)`, `ndk`.

## Splash (new topic)

Round one covers Android, iOS, and Desktop. Web is out.

* Facts: `image`, `backgroundColor`, optional `dark { }` variant. Every fact defaults to the matching `logo` fact, so an empty `splash { }` plus an enabled effect is a complete setup.
* **Android** (`generate`): themed splash screen resources (theme XML plus drawable) generated under `build/` and wired into the Android app module as a generated res directory. The user adds one line to their Manifest once: `android:theme="${kiteSplashTheme}"`. `kiteDoctor` verifies the placeholder is present. Pre-12 devices go through the core-splashscreen compat route.
* **iOS** (`rewrite`): `UILaunchScreen` dictionary in Info.plist plus a color and image in the asset catalog. No storyboard. Runs with `kiteRewriteXcode`.
* **Desktop** (`generate`): a composed splash PNG under `build/`, wired into Compose Desktop packaging as a JVM `-splash:` argument.
* Dark variant: Android `values-night` resources, iOS dark appearance on the named color and image.
* If a splash effect is enabled and neither `splash` nor `logo` declares the needed art, the build fails with a message naming both places to fix it.
* `skip()` / `only()` work in `splash { }` like in any topic.

## Behavior changes

1. **Desktop flows automatically**, exactly like Android and iOS, when a Compose Desktop app module exists. The old rule "desktop only flows when the block is open" dies. Existing guards stay: desktop census guard, refusal when Compose is applied at the root, published build number baseline.
2. **Desktop icons become explicit**: `logo { generate { } }`. The old hidden coupling (logo open plus desktop open) dies.
3. **SDK levels flow because they are declared.** Declaring `sdk(...)` is the intent. `applySdkLevels` dies without replacement.
4. **Every `enabled` flag dies.** Writing a feature inside a verb block is the consent. Removing it is the off switch.

## What dies

* The entire deprecated 2.x property layer, 30+ root properties.
* `propagate { }`, `ios.sync`, `desktop.enabled`, `desktop.icons`, all `enabled` flags, all internal `configured` trackers.
* The rule "opening a block turns something on". Nothing works that way anymore.
* `web { }` survives as the web platform block, home of `ioWorker`.

## Task names

| Old | New |
|-----|-----|
| `kiteSsotSyncAndroidLogo`, `kiteSsotSyncIosLogo`, `kiteSsotCleanupLegacyAppLogoArtifacts` | `kiteRewriteLogo` umbrella, granular tasks stay internal |
| `kiteSsotSyncIosConfig`, `kiteSsotSanitizeIosProject` | `kiteRewriteXcode` umbrella |
| `kiteSsotPlan` | `kitePlan`, previews every armed rewrite |
| `kiteSsotCheck` / `kiteSsotDoctor` / `kiteSsotVerify` | `kiteCheck` / `kiteDoctor` / `kiteVerify` |
| `generateKiteSsotBuildConfig`, `generateKiteSsotDesktopIcons` | internal, wired automatically, plus a splash generator |

## Mechanics

* **Dual forms.** Each value-topic is a `Property` plus an overload taking the value and an `Action`: `appName = "x"` and `appName("x") { }` both work. Same for `id(...)` and `version(...)`.
* **Platform tokens.** `android`, `ios`, `desktop` are singleton tokens available in DSL scope. `skip(token)` and `only(token)` record flow rules. Inside a value topic, `ios("value")` records an override. Corners like `ios { }` are ordinary nested scopes, so `ios.reupload = 1` one-liners also work.
* **Everything stays lazy.** Every fact remains a Gradle `Property` or `Provider` underneath. Derived read-only providers (`androidApplicationId`, `iosBundleId`, `desktopBundleId`, `versionCode`, `canonicalLocales`, `resolvedSharedProjectPath`) survive unchanged.
* **Internal model.** The engine keeps reading one resolved internal model, as today. The reshape replaces the public surface and deletes the 2.x fallback chains from that model.
* **CLI overrides** `-Pkitessot.dryRun` and `-Pkitessot.backups` survive unchanged.

## Testing

* Every existing functional test migrates to the new surface. The old surface is gone, so compilation of the test fixtures is itself a migration check.
* New tests: flow modifiers (skip, only, override, master skip), dual forms, vocabulary renames resolving to the same internal model values, desktop auto-flow with its guards, splash generation on all three platforms, doctor's manifest placeholder check.
* Multi-pass check after implementation: full build, full test run, `kiteDoctor` on the fixture project.
