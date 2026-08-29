# KiteSSOT 3.x DSL Reshape: Topics, One Law, Flow Modifiers

Status: approved design, 2026-08-28.
Versioning: stays in 3.x. Hard break, no deprecation bridge. The plugin has a single user.

## TL;DR

The 3.0 DSL still confuses its own author. Three problems:

1. You cannot tell when a value gets SSOT-ed and when it does not.
2. Opening a block sometimes has side effects and sometimes does not, and the block name never tells you which.
3. One topic is scattered across many places. Version facts live in five blocks today.

The reshape fixes all three with one law:

1. **Facts always flow.** A declared fact reaches every platform found, on every build. Flow is delivered in memory, or as files under `build/`. Declaring the fact is the consent. `skip()` / `only()` beside the fact are the only flow control.
2. **`rewrite { }` is the only word that acts on YOUR files.** It arms a by-name task that edits source. `dryRun`, `backups`, and `onConflict` always apply. It is the only ceremony in the DSL.
3. **One topic, one block.** Everything about a concern lives inside that concern's block. Platform corners nest inside topics. Platform blocks hold only platform-exclusive things.

No exceptions. There is no block whose mere opening does something a reader cannot predict from these three rules.

## Why 3.0 still hurts

| # | Pain | Example |
|---|------|---------|
| 1 | "Presence means intent" is inconsistent | `android { }` is inert, `desktop { }` changes ordinary builds, `logo { }` arms tasks, `buildConfig { }` generates code. Same syntax, four behaviors |
| 2 | Topics scatter | Version: root `version`, root `scheme`, `android` (3 props), `ios` (5 props), `desktop` (4 props) |
| 3 | Fuzzy words | `propagate`, `scheme`, `rebuild`, `sanitizePlist`, `takeOverLegacyIcons`, `applySdkLevels` |
| 4 | Hidden coupling | Desktop icons silently ride on `logo { }` plus `desktop { }` both being open, following no stated rule |
| 5 | Double bookkeeping | `enabled` flags plus internal `configured` trackers next to the presence rule |

## Design history of the law

Earlier drafts had two acting verbs, `generate` and `rewrite`. The final law drops `generate` entirely: files under `build/` are disposable and gitignored, so producing them is not an act that needs consent. It is flow with a different delivery vehicle. Only source edits are scary, so only `rewrite` keeps ceremony. This is why `buildConfig { }` and `web { ioWorker { } }` produce output by presence alone: their blocks have no other reason to exist, and their output lands only under `build/`.

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
| `rewrite { }` | armed task, source edits, runs only by name |

Platform tokens are lowercase (`android`, `ios`, `desktop`), matching KMP and Gradle DSL style. One token family, three uses: flow argument, value override call, detail corner. Rule of thumb: **corner = values, token = flow.**

## Dual forms

Every value topic has two spellings that write the same underlying `Property`:

* Simple: `appName = "Jetzy"` (plain assignment, calls `set()`)
* Detailed: `appName("Jetzy") { ... }` (function sets the same property, then runs the lambda for corners, overrides, and modifiers)

One storage slot, so standard Gradle semantics apply: the last write wins. There is no precedence system between the forms. Corner details and flow modifiers live in their own properties and survive regardless of which form set the base value. `kiteDoctor` warns when a base value is written more than once. Applies to `appName`, `id`, `version`.

## The full surface

This block is the canonical reference. It shows every property, function, corner, and modifier that exists. The class-level KDoc of the extension must carry this same block (see Documentation).

```kotlin
kiteSsot {

    // ══════════════════════════ THE LAW ══════════════════════════
    // 1. Facts always flow: in memory, or as files under build/.
    //    Declaring the fact is the consent. skip()/only() stop it.
    // 2. rewrite { } is the only word that acts on YOUR files.
    //    It arms a by-name task. dryRun and backups always apply.
    // 3. One topic, one block. Platform corners inside topics.

    // ═════════════════════════ APP NAME ══════════════════════════
    appName = "Jetzy"                        // simple form
    appName("Jetzy") {                       // detailed form
        android("Jetzy Droid")               // platform value override
        ios("Jetzy Lite")                    // platform value override
        desktop("Jetzy Desk")                // platform value override
        skip(ios)                            // do not flow there
        only(android, desktop)               // flow ONLY there (alternative to skip)
    }

    // ═════════════════════════ JVM LEVEL ═════════════════════════
    jvmTarget = 21                           // build-wide, no modifiers

    // ═════════════════════════ IDENTITY ══════════════════════════
    id = "com.example.jetzy"                 // simple form
    id("com.example.jetzy") {                // detailed form
        android { suffix = ".android" }      // applicationId = base + suffix
        ios     { suffix = ".ios" }          // bundle id     = base + suffix
        desktop { suffix = ".desktop" }      // desktop bundle id = base + suffix
        android.suffix = ".android"          // one-liner corner style, same thing
        skip(desktop)
        only(android)
    }

    // ═════════════════════════ VERSION ═══════════════════════════
    version = "1.4.0"                        // simple form
    version("1.4.0") {                       // detailed form
        formula { v ->                       // version -> build number, all stores
            1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.reupload
        }
        android {
            reupload = 1                     // re-upload counter, feeds formula
            shipped  = 1001003090            // guard floor: new codes must beat it
            pin      = 123                   // hard versionCode, formula skipped
            formula { v -> 1 }               // platform-only formula override
        }
        ios {
            reupload = 1
            shipped  = "1001003090"
            pin      = "42"                  // hard buildNumber
            marketingVersion = "1.4.0"       // shown version, defaults to base
            formula { v -> 1 }
        }
        desktop {
            reupload = 1
            shipped  = "1001003090"
            pin      = "42"
            formula { v -> 1 }
        }
        skip(desktop)
        only(android, ios)
    }

    // ═════════════════════════ LOCALES ═══════════════════════════
    // omit the whole block = auto-detect from Compose resources
    locales {
        pin("en", "ar", "fr")                // hand list, detection skipped
        filterAndroidRes = true              // drop Android res outside the list
        skip(ios)                            // knownRegions untouched
        only(android)
    }

    // ═══════════════════════════ LOGO ════════════════════════════
    logo {
        foreground = file("art/logo-fg.png")
        background = file("art/logo-bg.png")
        backgroundColor = "#0B0B0F"          // used when background file absent
        android { safeZone = 0.611 }         // launcher icon safe-zone ratio
        desktop { roundMac = true }          // round the generated macOS icon
        // presence + desktop found = app icons flow into build/, packaged
        skip(desktop)                        // stops exactly that
        only(android, ios)
        rewrite {                            // arms kiteRewriteLogo:
            replaceOld = true                //   android res + iOS asset catalog,
        }                                    //   replaceOld removes legacy icons
    }

    // ══════════════════════════ SPLASH ═══════════════════════════
    splash {
        // empty block already works: art defaults to logo
        image           = file("art/splash.png")  // default: logo.foreground
        backgroundColor = "#101014"               // default: logo.backgroundColor
        dark {                               // optional dark-mode variant
            image           = file("art/splash-dark.png")
            backgroundColor = "#000000"
        }
        // presence = android splash res into build/ (one-time Manifest line
        //   android:theme="${kiteSplashTheme}", kiteDoctor checks it)
        //   + desktop JVM -splash image, packaged
        skip(desktop)
        only(android)
        rewrite { }                          // iOS: UILaunchScreen in Info.plist
    }                                        //   + asset catalog, via kiteRewriteXcode

    // ═════════════════════════ OPT-INS ═══════════════════════════
    // targets Kotlin/Native compilations, selected by project, not platform,
    // so no skip()/only() here: projects() IS the selector
    optIns {
        add("kotlinx.cinterop.ExperimentalForeignApi")
        projects(":shared", ":composeApp")   // default: all detected
        builtIns = true                      // include the built-in marker list
    }

    // ══════════════════ PLATFORM-EXCLUSIVE BLOCKS ════════════════
    android {
        sdk(min = 26, target = 36, compile = 36)   // any subset of the three
        ndk = "27.1.12297006"
    }

    ios {
        deploymentTarget = "15.0"
        pbxproj          = file("iosApp/iosApp.xcodeproj/project.pbxproj")
        podfile          = file("iosApp/Podfile")
        infoPlist        = file("iosApp/iosApp/Info.plist")
        appDirectory     = file("iosApp")
        appIconDirectory = file("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset")
        //   paths only when detection guesses wrong
        rewrite {                            // arms kiteRewriteXcode:
            targets("iosApp")                //   pbxproj target names
            cleanPlist = true                //   normalize plist junk
            onConflict = FAIL                //   FAIL | KEEP | REPLACE
            nonExemptEncryption = false      //   ITSAppUsesNonExemptEncryption
            proMotion = true                 //   CADisableMinimumFrameDurationOnPhone
            renameSharedModule(from = "shared", to = "Shared")
        }
    }

    desktop {
        linuxPackageName  = "jetzy"
        deriveUpgradeUuid = true             // stable Windows MSI upgrade id from id
    }

    web {
        ioWorker {                           // presence = worker source generated
            targets("js")
            projects(":composeApp")          // default: all web-capable
            packageName = "kitessot.generated"
        }
    }

    // ═══════════════════════ BUILD CONFIG ════════════════════════
    buildConfig {                            // presence = object generated to commonMain
        packageName = "com.example.jetzy"
        className   = "AppInfo"              // default: KiteBuildConfig
        includeIdentity = true               // bake appName/id/version/locales in
        allowBuildCache = false              // opt out when fields are volatile
        stringField("API_HOST", "api.jetzy.app")
        stringField("GIT_SHA", providers.exec { /* ... */ }.standardOutput.asText)
        intField("MAX_RETRIES", 3)
        longField("BUILT_AT", 0L)
        booleanField("STAGING", false)
        doubleField("PI_ISH", 3.14)
    }

    // ═════════════════ MASTER FLOW CONTROL (root) ════════════════
    skip(desktop)                            // platform receives NOTHING at all
    only(android, ios)                       // alternative: allowlist form

    // ═══════════════════════ PLUMBING ════════════════════════════
    // only when auto-detection picks wrong
    modules {
        shared = ":shared"                   // the umbrella KMP module
        androidApps(":androidApp")
        desktopApps(":desktopApp")
        androidAppDirectory = file("androidApp")
        composeResources    = file("shared/src/commonMain/composeResources")
    }

    // ════════════════════════ SAFETY ═════════════════════════════
    dryRun  = false                          // armed rewrites print, write nothing
    backups = true                           // recovery copy before any rewrite
}
```

Read-back providers (wire into your own tasks; every fact is lazy underneath):
`androidApplicationId`, `iosBundleId`, `desktopBundleId`, `versionCode`,
`canonicalLocales`, `resolvedSharedProjectPath`.

CLI overrides, per invocation, beat the build file: `-Pkitessot.dryRun=true`,
`-Pkitessot.backups=false`.

## Injectability matrix

| You can inject | Works in | Meaning |
|---|---|---|
| `skip(p...)` / `only(p...)` | root, `appName`, `id`, `version`, `locales`, `logo`, `splash` | flow control, at root = platform master |
| `android("v")` / `ios("v")` / `desktop("v")` | `appName` | platform value override |
| `android { }` / `ios { }` / `desktop { }` corner | `id`, `version`, `logo` | platform detail scope, dot one-liner also works |
| `pin` | `version` corners, `locales` | manual value, machinery skipped |
| `reupload`, `shipped`, `formula` | `version` root and corners | store counter, guard floor, number formula |
| `suffix` | `id` corners | appended to base |
| `dark { }` | `splash` | dark-mode variant |
| `rewrite { }` | `logo`, `splash`, `ios` | the only acting word, arms a task |
| typed `*Field(...)` | `buildConfig` | constants, plain or `Provider` |
| `add(...)`, `projects(...)`, `targets(...)` | `optIns`, `web.ioWorker`, `ios.rewrite` | vararg selectors |

Not injectable anywhere: `enabled` flags, `propagate`, standalone verb blocks. Dead.

## Topic map: old to new

| Topic | Old (3.0) | New |
|-------|-----------|-----|
| App name | root `appName` | `appName` value or `appName("x") { }` with overrides and flow modifiers |
| Identity | root `appId` + `android.idSuffix` + `ios.bundleIdSuffix` + `desktop.idSuffix` | `id("base") { }` with `suffix` corners |
| Version | root `version` + root `scheme` + 12 props across 3 platform blocks | `version("x.y.z") { }` with `formula` and corners |
| Locales | root `locales` + `android.filterResourcesToLocales` | `locales { }` |
| Logo | `logo { }` + `desktop.icons` + `desktop.roundMacOsIcon` + hidden coupling | `logo { }` with corners and nested `rewrite` |
| Splash | none | `splash { }`, new topic |
| Xcode sync | `ios { sync { } }` | `ios { rewrite { } }` |
| Opt-ins | `nativeOptIns { }` | `optIns { }` |
| Build config | `buildConfig { }`, presence turns it on | `buildConfig { }`, presence generates (now lawful: build/ output is flow) |
| IO worker | `web { ioWorker { } }`, presence turns it on | `web { ioWorker { } }`, presence generates (same law) |
| Flow switches | `propagate { }` + `android.applySdkLevels` + `enabled` flags | `skip()` / `only()` at the fact, root `skip(p)` as master |
| Modules | `modules { }` | unchanged |

## Renames

| Old | New | Reason |
|-----|-----|--------|
| `appId` | `id` | shorter, context carries it |
| `scheme` | `formula` | says what it is |
| `rebuild` | `reupload` | says what it is for |
| `versionCode` / `buildNumber` overrides | `pin` | one word for manual override |
| `publishedVersionCode` / `publishedBuildNumber` | `shipped` | one word for the guard floor |
| `bundleIdSuffix` | `suffix` inside `id { ios { } }` | one word on all platforms |
| `filterResourcesToLocales` | `filterAndroidRes` inside `locales { }` | locale topic owns it |
| `takeOverLegacyIcons` | `replaceOld` | plain words |
| `sanitizePlist` | `cleanPlist` | plain words |
| `androidSafeZone` | `safeZone` inside `logo { android { } }` | corner carries the platform |
| `roundMacOsIcon` | `roundMac` inside `logo { desktop { } }` | corner carries the platform |
| `nativeOptIns` | `optIns` | context carries the rest |
| `ios.sync` | `ios.rewrite` | names the effect honestly |
| version fields in `formula` lambda: `v.rebuild` | `v.reupload` | matches the property rename |

Unchanged on purpose: `appName`, `jvmTarget`, `marketingVersion`, `deploymentTarget`, `nonExemptEncryption`, `proMotion`, `dryRun`, `backups`, `modules`, SDK level names inside `sdk(...)`, `ndk`.

## Splash (new topic)

Round one covers Android, iOS, and Desktop. Web is out.

* Facts: `image`, `backgroundColor`, optional `dark { }` variant. Every fact defaults to the matching `logo` fact, so an empty `splash { }` is a complete setup.
* **Android** (flow): themed splash screen resources (style XML plus icon drawables) generated under `build/` and wired into the Android app module as a res directory. The generated `KiteSplash` style inherits the app theme named in `splash { android { theme } }` and only the `values-v31` variant adds splash attributes, so pre-12 devices see a plain alias and no behavior change (no compat library, no app code). The user adds one line to their Manifest once: `android:theme="${kiteSplashTheme}"`. `kiteDoctor` verifies the placeholder and the theme corner.
* **iOS** (`rewrite`): `UILaunchScreen` dictionary in Info.plist plus a color and image in the asset catalog. No storyboard. Runs with `kiteRewriteXcode`.
* **Desktop** (flow): a composed splash PNG under `build/`, wired into Compose Desktop packaging as a JVM `-splash:` argument.
* Dark variant: Android `values-night` resources, iOS dark appearance on the named color and image.
* If a splash surface is active and neither `splash` nor `logo` declares the needed art, the build fails with a message naming both places to fix it.
* `skip()` / `only()` work in `splash { }` like in any topic.

## Behavior changes

1. **Desktop flows automatically**, exactly like Android and iOS, when a Compose Desktop app module exists. The old rule "desktop only flows when the block is open" dies. Existing guards stay: desktop census guard, refusal when Compose is applied at the root, published build number baseline.
2. **Desktop app icons flow from `logo { }` presence.** This is rule 1, not hidden coupling: art declared, desktop found, icons delivered via `build/`. `skip(desktop)` inside `logo { }` stops it. The 3.0 sin was coupling that followed no stated rule.
3. **SDK levels flow because they are declared.** Declaring `sdk(...)` is the intent. `applySdkLevels` dies without replacement.
4. **Every `enabled` flag dies.** Declaring a topic is the consent. Removing it is the off switch.

## What dies

* The entire deprecated 2.x property layer, 30+ root properties.
* `propagate { }`, `ios.sync`, `desktop.enabled`, `desktop.icons`, all `enabled` flags, all internal `configured` trackers.
* The `generate` DSL word, from every scope.
* The rule "opening a block turns something on" as an unpredictable special case. Presence-driven output remains only where the law explains it: flow delivered to `build/`.
* `web { }` survives as the web platform block, home of `ioWorker`.

## Task names

| Old | New |
|-----|-----|
| `kiteSsotSyncAndroidLogo`, `kiteSsotSyncIosLogo`, `kiteSsotCleanupLegacyAppLogoArtifacts` | `kiteRewriteLogo` umbrella, granular tasks stay internal |
| `kiteSsotSyncIosConfig`, `kiteSsotSanitizeIosProject` | `kiteRewriteXcode` umbrella |
| `kiteSsotPlan` | `kitePlan`, previews every armed rewrite |
| `kiteSsotCheck` / `kiteSsotDoctor` / `kiteSsotVerify` | `kiteCheck` / `kiteDoctor` / `kiteVerify` |
| `generateKiteSsotBuildConfig`, `generateKiteSsotDesktopIcons` | internal, wired automatically, plus splash generators |

## Documentation requirements

The current KDocs are correct but flat. The reshape treats documentation as part of the surface:

* The extension's class-level KDoc carries the full canonical surface block from this spec, verbatim, plus the three-rule law and the injectability matrix. IDE quick documentation renders KDoc markdown (tables and code fences), so a dev hovering `kiteSsot` sees everything that exists in one place.
* Every scope class carries its topic's slice of the surface block plus a table of its members: name, meaning, default, flow class (memory, `build/`, or rewrite).
* Every property KDoc stays 1 to 3 lines: what it is, its default, where it lands per platform. Junior-readable, ESL-friendly, no jargon without a plain-words gloss.
* The law is stated once per class, one line, linking to the extension KDoc, not restated in full everywhere.
* Dokka and mkdocs pick the same KDocs up, so the site inherits the structure for free. No em-dashes anywhere.

## Mechanics

* **Dual forms.** Each value topic is a `Property` plus an overload taking the value and an `Action`. Both write the same property, last write wins, no precedence system. `kiteDoctor` warns on double-set of a base value.
* **Platform tokens.** `android`, `ios`, `desktop` are singleton tokens available in DSL scope. `skip(token)` and `only(token)` record flow rules. Inside a value topic, `ios("value")` records an override. Corners like `ios { }` are ordinary nested scopes, so `ios.reupload = 1` one-liners also work.
* **Everything stays lazy.** Every fact remains a Gradle `Property` or `Provider` underneath. Derived read-only providers (`androidApplicationId`, `iosBundleId`, `desktopBundleId`, `versionCode`, `canonicalLocales`, `resolvedSharedProjectPath`) survive unchanged.
* **Internal model.** The engine keeps reading one resolved internal model, as today. The reshape replaces the public surface and deletes the 2.x fallback chains from that model.
* **CLI overrides** `-Pkitessot.dryRun` and `-Pkitessot.backups` survive unchanged.

## Testing

* Every existing functional test migrates to the new surface. The old surface is gone, so compilation of the test fixtures is itself a migration check.
* New tests: flow modifiers (skip, only, override, master skip), dual forms and last-write-wins, vocabulary renames resolving to the same internal model values, desktop auto-flow with its guards, logo-to-desktop icon flow and its skip, splash on all three platforms, doctor's manifest placeholder check, doctor's double-set warning.
* Multi-pass check after implementation: full build, full test run, `kiteDoctor` on the fixture project.
