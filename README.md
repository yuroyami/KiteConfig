# KiteSSOT

A Gradle plugin for Kotlin Multiplatform projects. You declare your app's facts
once, in one block, at the root: app name, app id, version, version code, build
number, locales, app logo, splash screen, generated build config, SDK levels,
JVM level. KiteSSOT carries them to Android, iOS, and Compose Desktop, so the
copies that normally drift apart stop existing.

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.yuroyami.kitessot?label=plugin%20portal)](https://plugins.gradle.org/plugin/io.github.yuroyami.kitessot)
[![CI](https://img.shields.io/github/actions/workflow/status/yuroyami/KiteSSOT/ci.yml?branch=main&label=CI)](https://github.com/yuroyami/KiteSSOT/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

**[Documentation](https://yuroyami.github.io/KiteSSOT/)** has the setup guide
and the generated API reference.

## The one law

The whole DSL follows three rules. Learn them once and nothing in it can
surprise you:

1. **Facts always flow.** A declared fact reaches every platform found, on
   every build, either in memory or as files under `build/`. Declaring the
   fact is the consent. `skip()` and `only()` beside the fact are the only
   flow control.
2. **`rewrite { }` is the only word that acts on your files.** It arms a
   task that edits source, and that task runs only when you invoke it by
   name. `dryRun`, `backups`, and `onConflict` always apply to it.
3. **One topic, one block.** Everything about a concern lives inside that
   concern's block. Platform corners nest inside topics. Platform blocks
   hold only platform-exclusive things.

## Usage

Apply the plugin to the **root** project only:

```kotlin
plugins {
    id("io.github.yuroyami.kitessot") version "4.0.0"
}
```

Three lines are a complete setup. Locales auto-detect from Compose resources,
the shared module and the app modules auto-detect too:

```kotlin
kiteSsot {
    appName = "Jetzy"
    version = "1.4.0"
    id      = "com.example.jetzy"
}
```

Everything else below is optional, and every value stays a lazy Gradle
`Property` you can wire into your own build logic.

## The whole surface

Every property, function, corner, and modifier that exists, in one block.

```kotlin
kiteSsot {

    /**
     * THE LAW
     * 1. Facts always flow: in memory, or as files under build/.
     *    Declaring the fact is the consent. skip()/only() stop it.
     * 2. rewrite { } is the only word that acts on YOUR files.
     *    It arms a by-name task. dryRun and backups always apply.
     * 3. One topic, one block. Platform corners inside topics.
     */

    /** APP NAME. Simple form: appName = "Jetzy". Detailed form below. */
    appName("Jetzy") {
        /** Platform value overrides: that platform shows its own name. */
        android("Jetzy Droid")
        ios("Jetzy Lite")
        desktop("Jetzy Desk")
        /** Flow control, available in every topic the same way. */
        skip(ios)
        only(android, desktop)
    }

    /** Java and Kotlin JVM level for the whole build. No modifiers. */
    jvmTarget = 21

    /** IDENTITY. Simple form: id = "com.example.jetzy". */
    id("com.example.jetzy") {
        /** Corners: suffix is appended to the base per platform. */
        android { suffix = ".android" }   /** applicationId = base + suffix */
        ios     { suffix = ".ios" }       /** bundle id     = base + suffix */
        desktop { suffix = ".desktop" }
        /** One-liner corner style works too. */
        android.suffix = ".android"
        skip(desktop)
        only(android)
    }

    /** VERSION. Simple form: version = "1.4.0". */
    version("1.4.0") {
        /** One formula turns the version into every store's build number. */
        formula { v -> 1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.reupload }
        android {
            reupload = 1                  /** re-upload counter, feeds the formula */
            shipped  = 1001003090         /** guard floor: new codes must beat it */
            pin      = 123                /** hard versionCode, formula skipped */
            formula { v -> 1 }            /** platform-only formula override */
        }
        ios {
            reupload = 1
            shipped  = "1001003090"
            pin      = "42"               /** hard buildNumber */
            marketingVersion = "1.4.0"    /** shown version, defaults to base */
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

    /** LOCALES. Omit the whole block to auto-detect from Compose resources. */
    locales {
        pin("en", "ar", "fr")             /** hand list, detection skipped */
        filterAndroidRes = true           /** drop Android res outside the list */
        skip(ios)                         /** knownRegions untouched */
        only(android)
    }

    /** LOGO. Art is a fact; declaring it alone never touches your source. */
    logo {
        foreground = file("art/logo-fg.png")
        background = file("art/logo-bg.png")
        backgroundColor = "#0B0B0F"       /** set this or background, never both */
        android { safeZone = 0.611 }      /** adaptive-icon safe-zone ratio */
        desktop { roundMac = true }       /** round the generated macOS icon */
        /**
         * Presence + a desktop app found = installer icons flow into build/
         * and get packaged. skip(desktop) stops exactly that.
         */
        skip(desktop)
        only(android, ios)
        /**
         * Arms kiteRewriteLogo: writes Android res + the iOS asset catalog.
         * replaceOld also claims and removes legacy launcher icons (backed up).
         */
        rewrite { replaceOld = true }
    }

    /** SPLASH. Empty block already works: art defaults to logo. */
    splash {
        image           = file("art/splash.png")   /** default: logo.foreground */
        backgroundColor = "#101014"                /** default: logo.backgroundColor */
        /** Optional dark-mode variant. Unset members fall back to light. */
        dark {
            image           = file("art/splash-dark.png")
            backgroundColor = "#000000"
        }
        /**
         * Android needs two one-time things, both checked by kiteDoctor:
         * the theme corner below, and one Manifest line:
         * android:theme="${kiteSplashTheme}". The generated KiteSplash style
         * inherits your theme and only adds attributes on Android 12+.
         */
        android { theme = "AppTheme" }
        /**
         * Presence = Android splash res into build/ + desktop JVM -splash
         * image, packaged. iOS is a source edit, so it needs this rewrite
         * AND ios { rewrite { } }, and runs with kiteRewriteXcode.
         */
        rewrite { }
        skip(desktop)
    }

    /** Kotlin/Native interop opt-in markers, selected by project. */
    optIns {
        add("kotlinx.cinterop.ExperimentalForeignApi")
        projects(":shared", ":composeApp")  /** default: all detected */
        builtIns = true                     /** include the built-in marker list */
    }

    /** PLATFORM BLOCKS: only platform-exclusive facts live here. */

    android {
        sdk(min = 26, target = 36, compile = 36)  /** any subset of the three */
        ndk = "27.1.12297006"
    }

    ios {
        deploymentTarget = "15.0"
        /** Paths: set only when detection guesses wrong. */
        pbxproj          = file("iosApp/iosApp.xcodeproj/project.pbxproj")
        podfile          = file("iosApp/Podfile")
        infoPlist        = file("iosApp/iosApp/Info.plist")
        appDirectory     = file("iosApp")
        appIconDirectory = file("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset")
        /** Arms kiteRewriteXcode: pbxproj, Info.plist, Podfile, Swift imports. */
        rewrite {
            targets("iosApp")             /** pbxproj application target names */
            cleanPlist = true             /** maintain SSOT keys in the source plist */
            onConflict = io.github.yuroyami.kitessot.PlistConflictPolicy.FAIL  /** FAIL | KEEP | REPLACE */
            nonExemptEncryption = false   /** ITSAppUsesNonExemptEncryption */
            proMotion = true              /** CADisableMinimumFrameDurationOnPhone */
            renameSharedModule(from = "shared", to = "Shared")
        }
    }

    desktop {
        linuxPackageName  = "jetzy"
        deriveUpgradeUuid = true          /** stable Windows MSI upgrade id from id */
    }

    web {
        /** Presence generates the browser IO worker source into build/. */
        ioWorker {
            targets("js")                 /** browser Kotlin/JS targets */
            projects(":composeApp")       /** default: all web-capable */
            packageName = "kitessot.generated"
        }
    }

    /** BUILD CONFIG. Presence generates a Kotlin object into commonMain. */
    buildConfig {
        packageName = "com.example.jetzy"
        className   = "AppInfo"
        includeIdentity = true            /** bake appName/id/version/locales in */
        allowBuildCache = false           /** opt out when fields are volatile */
        stringField("API_HOST", "api.jetzy.app")
        intField("MAX_RETRIES", 3)
        longField("BUILT_AT", 0L)
        booleanField("STAGING", false)
        doubleField("PI_ISH", 3.14)
    }

    /** MASTER FLOW CONTROL: same words as everywhere, at the root. */
    skip(desktop)                         /** platform receives NOTHING at all */
    only(android, ios)                    /** allowlist form of the same thing */

    /** PLUMBING: only when auto-detection picks wrong. */
    modules {
        shared = ":shared"                /** the umbrella KMP module */
        androidApps(":androidApp")
        desktopApps(":desktopApp")
        androidAppDirectory = file("androidApp")
        composeResources    = file("shared/src/commonMain/composeResources")
    }

    /** SAFETY. Both have CLI twins that win for one invocation. */
    dryRun  = false                       /** armed rewrites print, write nothing */
    backups = true                        /** recovery copy before any rewrite */

    /**
     * Run on AGP/KGP/Compose outside the tested range: hard guards become
     * one loud warning. Needs a forced opt-in in the build script:
     * @file:OptIn(io.github.yuroyami.kitessot.DiscouragedKiteApi::class)
     */
    /** ignoreVersionGuards = true */
}
```

Read-back providers worth wiring into your own tasks, all lazy, no `.get()`
needed: `androidApplicationId`, `iosBundleId`, `desktopBundleId`,
`versionCode`, `canonicalLocales`, `resolvedSharedProjectPath`.

```kotlin
val ssot = extensions.getByType<io.github.yuroyami.kitessot.KiteSsotExtension>()
someOtherTask.someProperty.set(ssot.androidApplicationId)
```

CLI overrides, per invocation, beat the build file: `-Pkitessot.dryRun=true`,
`-Pkitessot.backups=false`.

## Tasks

All in the `kitessot` group. Nothing attaches to `build` or `check`, so the
source-editing tasks run only when you name them.

| Task | Writes | What it does |
| --- | --- | --- |
| `kiteVerify` | nothing | Prints the resolved model |
| `kiteDoctor` | nothing | Diagnoses the setup; never fails the build |
| `kitePlan` | nothing | Lists what the armed rewrites would change, with exact paths |
| `kiteCheck` | `build/` | Same checks as doctor, writes JSON or SARIF, fails on errors |
| `kiteRewriteLogo` | source | Installs the logo into Android res and the iOS asset catalog |
| `kiteRewriteXcode` | source | Applies Xcode build settings, plist, Podfile, Swift, and the iOS splash |
| `kiteInternal*` | `build/` | Generators (build config, worker, icons, splash), wired automatically |

Run `./gradlew kitePlan` before any rewrite. It shows the full mutation plan
and writes nothing.

## Confusion deleters

Plain answers to the questions that used to need archaeology:

- **When does a value get SSOT-ed?** Always, to every platform found, on
  every build, unless a `skip()` says otherwise. That is the whole answer.
- **Does opening a block do something?** Declaring facts makes them flow
  (rule 1). Only `rewrite { }` arms anything that touches your source, and
  arming still is not running.
- **What does dryRun cover?** Rewrites only. Generators into `build/` ignore
  it, because their output is disposable and your build depends on it.
- **Why did nothing happen on iOS?** iOS deliveries are source edits. They
  need `ios { rewrite { } }` (plus `splash { rewrite { } }` for the splash)
  and an explicit `./gradlew kiteRewriteXcode`.
- **Why is my desktop app getting icons I never asked for?** You declared
  `logo { }` art and a desktop app exists, so the fact flowed (rule 1).
  `logo { skip(desktop) }` stops it.
- **Simple form or detailed form?** `version = "1.4.0"` and
  `version("1.4.0") { }` write the same property; last write wins, and
  `kiteDoctor` warns when both were used.
- **Unsupported AGP/KGP version?** Typed integrations switch off and the
  features that need them fail with guidance. `ignoreVersionGuards = true`
  (with its forced `@OptIn`) keeps them active at your own risk.

## Caveats

- Store uploads driven by these outputs (App Store archive, signed DMG, MSI
  upgrade across versions) have not been verified end to end by a release
  pipeline yet. Read `kitePlan` output before rewrites.
- `buildConfig` is not a secret store. Every value reaches generated source,
  task inputs, build scans, and shipped binaries.
- The desktop build number reaches macOS only; Windows and Linux packaging
  have no separate build-number field.
- Desktop application detection reads internal Compose members reflectively.
  If a future Compose release renames them, detection degrades to requiring
  `modules { desktopApps(...) }` instead of failing.
- The iOS splash writes `UILaunchScreen`. A project still carrying a
  `UILaunchStoryboardName` key keeps showing the storyboard until that key
  is removed; the sync warns about it.

## License

Apache 2.0. See [LICENSE](LICENSE).
