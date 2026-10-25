# KiteConfig

<p align="center">
  <img src="art/kiteconfig-platform-gear-concept.png" width="180" alt="KiteConfig logo">
</p>

A Gradle plugin for Kotlin Multiplatform projects. You write your app's values
once, in one block, in the root build file: app name, app id, version, build
number, locales, app icon, launch screen, generated build config, SDK levels,
JVM level. KiteConfig applies them to Android, iOS, and Compose Desktop for you.
Without it, each platform keeps its own copy of these values, and the copies
slowly stop matching.

<sub>AI tools wrote much of the code, tests and documentation in this project, under the
direction and review of its author.</sub>

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.yuroyami.kiteconfig?label=plugin%20portal)](https://plugins.gradle.org/plugin/io.github.yuroyami.kiteconfig)
[![CI](https://img.shields.io/github/actions/workflow/status/yuroyami/KiteConfig/ci.yml?branch=main&label=CI)](https://github.com/yuroyami/KiteConfig/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

**[Documentation](https://yuroyami.github.io/KiteConfig/)** has the setup guide
and the generated API reference.

## Setup

Apply the plugin to the **root** project only:

```kotlin
plugins {
    id("io.github.yuroyami.kiteconfig") version "3.0.0"
}
```

Four lines are a complete setup. Locales come from your Compose resources, and
the shared module and app modules are found for you:

```kotlin
kiteConfig {
    appName = "Jetzy"
    appId   = "com.example.jetzy"
    version = "1.4.0"
    autoApply = true
}
```

`autoApply = true` is the one switch. With it on, an ordinary build applies
everything KiteConfig needs to change, including the files you commit. Leave it
off and nothing touches your own files until you run `./gradlew kiteApply`.

With iOS automatic application enabled, Android Studio and IntelliJ Gradle sync
also applies the iOS plan through Kotlin's IDE import task. Sync after changing
KiteConfig so Xcode reads the new version, build number and assets before its
next build. Opening Xcode alone does not run Gradle. For changes made outside
the Gradle IDE, run `./gradlew kiteApplyIos` before archiving.

## Four rules

1. **Write each value once.** It reaches every platform in your project.
2. **A platform block holds only what differs there.** `ios { appName = "Jetzy Lite" }`
   changes iOS and nobody else. Nothing is copied when a block opens, so the
   order you write things in never matters.
3. **A feature is on when you gave it what it needs.** Set `logo { foreground }`
   and icons happen. A feature that needs nothing from you, like the launch
   screen, needs `enabled = true`.
4. **`autoApply` decides who applies the changes.** Nobody else. There are no
   per-topic permission blocks and no task names to memorise.

## The whole surface

Every property, function, and block that exists, in one place. Lines marked
`default` show what you get by leaving them out.

```kotlin
import io.github.yuroyami.kiteconfig.VersionSchemes

kiteConfig {

    // ───────────────────────── Shared facts ─────────────────────────
    appName = "Jetzy"
    appId   = "com.example.jetzy"
    version = "1.4.0"                 // any String or Provider<String>

    // ──────────────────── Apply source edits automatically ───────────
    autoApply = true                  // default false

    // ───────────────────── Shared details, all optional ──────────────
    version {
        scheme(VersionSchemes.DEFAULT)  // default. Or: scheme { v -> "..." }
        rebuild = 0                     // default
    }

    locales {
        tags = listOf("en", "ar", "fr") // default: detected from Compose resources
    }

    logo {                              // on as soon as foreground is set
        foreground = file("art/logo.png")
        background = color("#102A43")   // or image(file("art/logo-bg.png"))
        foregroundScale = 0.75          // default: each renderer's native ratio
        overlay = file("art/badge.png") // drawn over the mark, same size and position; optional
    }

    splash {                            // off until enabled = true
        enabled = true
        image = file("art/splash.png")          // default: logo.foreground
        backgroundColor = "#101014"             // default: the logo's color background
        dark {                                  // on as soon as one dark value is set
            image = file("art/splash-dark.png") // default: the light image
            backgroundColor = "#000000"
        }
    }

    debug {                             // each value is off until you set it
        appId { suffix = ".debug" }     // release id + suffix, on Android and iOS
        appName = "Jetzy Dev"
        logo { overlay = file("art/debug-ribbon.png") }   // unset leaves follow the release icon
    }

    jvm {
        toolchain = 21                  // default: whatever the module declares
        target = 17                     // default: whatever the module declares
    }

    buildConfig {                       // on as soon as a field is declared
        packageName = "com.example.jetzy"   // default: kiteconfig.generated
        className = "AppInfo"               // default: KiteBuildConfig
        includeIdentity = true              // default
        allowBuildCache = false             // default
        stringField("API_HOST", "api.jetzy.app")
        intField("MAX_RETRIES", 3)
        // longField, booleanField, doubleField, each also taking a Provider
    }

    optIns {                            // on as soon as a marker is added
        add("kotlinx.cinterop.ExperimentalForeignApi")
        builtIns = true                 // default
        // projects(":shared")          // default: every Kotlin/Native project
    }

    // ───────── Platform blocks: differences and native settings ──────
    android {
        appName = "Jetzy Droid"
        appId { suffix = ".android" }   // applicationId = appId + suffix
        version {
            rebuild = 1
            publishedBuildNumber = "1001003999"   // new codes must beat it
            // buildNumber = "123"      // exact versionCode, scheme skipped
        }
        locales { filterResources = true }    // drop res folders outside the list
        logo { foregroundScale = 0.61 }
        splash { theme = "AppTheme" }         // your app theme
        debug {
            appId { suffix = ".dev" }   // this platform's debug id differs
            buildTypes("debug")         // default: the build types that count as debug
        }
        sdk(min = 26, target = 36, compile = 36)   // any subset
        ndk = "27.1.12297006"
        jvm { target = 17 }
        // autoApply = false            // this platform only: manual kiteApply
    }

    ios {
        appId = "com.example.jetzy.ios" // exact. Or appId { suffix = ".ios" }
        version = "1.4.0"               // the marketing version shown in the store
        version {
            rebuild = 2
            // buildNumber = "42"       // exact CFBundleVersion, scheme skipped
            publishedBuildNumber = "1001003992"
        }
        locales { tags = listOf("en", "fr") }  // replaces the shared list here
        splash { enabled = false }              // light only: splash { dark { enabled = false } }
        debug {
            appName = "Jetzy (Xcode)"
            configurations("Debug")     // default: the configurations that count as debug
        }
        infoPlist {
            path = file("iosApp/iosApp/Info.plist")   // default
            proMotion = true                          // default: key left alone
            nonExemptEncryption = false               // default: key left alone
        }
        // xcodeTargets("iosApp")       // only when several app targets exist
        pbxproj = file("iosApp/iosApp.xcodeproj/project.pbxproj")           // default
        podfile = file("iosApp/Podfile")                                      // default
        appDirectory = file("iosApp")                                         // default
        appIconDirectory = file("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset") // default
        // renameSharedModule(from = "shared", to = "Shared")  // one-time migration
    }

    desktop {
        appName = "Jetzy Desktop"
        logo {
            roundMac = true             // default
            foregroundScale = 0.85
        }
        jvm { target = 17 }
        linuxPackageName = "jetzy"      // default: derived from appName
        deriveUpgradeUuid = true        // default false: stable Windows MSI upgrade id
    }

    web {
        ioWorker {                      // off until enabled = true
            enabled = true
            targets("js")               // only when several browser targets exist
            packageName = "com.example.jetzy.generated"
        }
    }

    // ───────────── Root selection, plumbing, safety ──────────────────
    only(android, ios)                  // default: every platform found
    skip(desktop)                       // exclusion wins over everything

    // modules {                        // only when detection guesses wrong
    //     shared = ":shared"
    //     androidApps(":androidApp")
    //     desktopApps(":desktopApp")
    //     composeResources = file("shared/src/commonMain/composeResources")
    // }

    dryRun  = false                     // default. Also -Pkiteconfig.dryRun=true
    backups = true                      // default. Also -Pkiteconfig.backups=false
    // ignoreVersionGuards = true       // needs @file:OptIn(DiscouragedKiteApi::class)
}
```

The values above are examples, not recommendations.

## How values resolve

The closest value wins: the platform block, then the root, then the built-in
default. Nothing is copied when a block opens, so this does what it looks like
no matter which line comes first:

```kotlin
kiteConfig {
    ios { version { rebuild = 2 } }
    version { rebuild = 1 }    // Android and desktop get 1. iOS keeps 2.
}
```

Every block is scoped to itself. `android { skip(ios) }` does not compile, and
neither does `ios { jvm { } }`, because iOS has no JVM.

To stop one value reaching one platform, turn its delivery off there:

```kotlin
ios { appName { enabled = false } }   // iOS keeps whatever name it has
```

The value stays readable. Only the write stops. To remove a platform
altogether, use `skip()` at the root, which nothing can argue with.

## Debug builds

The debug build is the one Android Studio and Xcode install while you work.
With the same id as the release build, it collides with the store app. On iOS
it replaces the store app. On Android it refuses to install until you
uninstall the store app, because the signing keys differ. Give the debug build
its own id and both stay installed. Give it its own name and icon and you can
tell them apart:

```kotlin
kiteConfig {
    appName = "Jetzy"
    appId   = "com.example.jetzy"
    logo {
        foreground = file("art/logo.png")
        background = color("#102A43")
    }
    debug {
        appId { suffix = ".debug" }               // com.example.jetzy.debug
        appName = "Jetzy Dev"
        logo { background = color("#B00020") }   // same mark, red plate
    }
}
```

`debug { }` follows the same rules as everything else. Each value is looked up
on its own: the platform's `debug { }` first, then the root `debug { }`, then
that platform's release value. Each value is on once you set it, and only on
platforms where the matching release value is applied. An empty block does
nothing. Desktop has no
debug build, so `desktop { }` has no `debug { }`.

| You write | Android gets | iOS gets |
| --- | --- | --- |
| `appId { suffix }` or `appId` | `applicationIdSuffix` on the `debug` build type, or an exact id on those variants | `PRODUCT_BUNDLE_IDENTIFIER` in the `Debug` configuration |
| `appName` | the label of the `debug` variants | `PRODUCT_NAME` in the `Debug` configuration |
| `logo { }` | launcher icons generated into `build/`, used by the `debug` variants only | `AppIcon-Debug.appiconset` next to the release catalog, used by the `Debug` configuration |

Three ways to change the debug icon:

- `debug { logo { background = color("#B00020") } }` keeps the mark and swaps the plate.
- `debug { logo { overlay = file("art/ribbon.png") } }` draws a PNG over the mark. Use the same width and height as your foreground, transparent everywhere except the ribbon. A PNG of a different shape is centered, so a corner ribbon would move.
- `debug { logo { foreground = file("art/logo-dev.png") } }` swaps the mark.

iOS needs no new scheme and no new target. Xcode's Run action builds `Debug`
and Archive builds `Release`, and the values differ per configuration inside
the target you already have. If your project names things differently, say so:
`ios { debug { configurations("Staging") } }` or
`android { debug { buildTypes("debug", "staging") } }`.

What a new id does not do for you: register the debug bundle id for push
notifications or app groups, add the debug package to `google-services.json`,
or list it in your deep-link files. Automatic signing in Xcode registers the
new App ID by itself.

On iOS the debug name is written as `PRODUCT_NAME`, which also renames the
Swift module in `Debug`. If a test target uses `@testable import`, set
`PRODUCT_MODULE_NAME` in the app target so the module keeps one name.

## Build numbers

`version` is what users see. The build number is derived from it:

```kotlin
version = "1.4.0"                     // 1.4.0 becomes 1001004000
android { version { rebuild = 1 } }   // and 1001004001 on the next upload
```

The scheme returns text, because the stores disagree about what a build number
looks like. Android reads it as the one whole number Google Play wants. Apple
compares it by numeric component, so a dotted build number works there:

```kotlin
ios {
    version { scheme { v -> "${v.major}.${v.minor}.${v.patch * 10 + v.rebuild}" } }
}
```

`publishedBuildNumber` is the highest number you have already shipped on that
platform. The next one has to beat it, or the build stops before release.
Nothing contacts a store; this is a value you keep yourself.

## Reading values back

Everything KiteConfig resolved is readable from any build file, not just the
root. One import, then use it:

```kotlin
import io.github.yuroyami.kiteconfig.kiteConfig

android {
    defaultConfig {
        versionCode = kiteConfig.versionCode.get()
    }
}
```

| Group | Values |
| --- | --- |
| Version | `version`, `versionCode`, `iosBuildNumber`, `iosMarketingVersion`, `desktopBuildNumber` |
| Identity | `appName`, `appNameFor(platform)`, `appId`, `appIdFor(platform)`, `androidApplicationId`, `iosBundleId`, `desktopBundleId` |
| Build | `canonicalLocales`, `jvmTarget`, `jvmToolchain`, `resolvedSharedProjectPath`, `minSdk`, `targetSdk`, `compileSdk`, `ndk` |

Every one is a lazy `Provider`, so wiring one into another task's property costs
nothing at configuration time:

```kotlin
someOtherTask.someProperty.set(kiteConfig.androidApplicationId)
```

### Values you never declared

These accessors supply no defaults and never return null. A value the root build
file never set has no value at all, and reading it stops the build:

```kotlin
kiteConfig.version.get()   // declared     -> the value
                           // not declared -> throws
```

That is on purpose. Reading a value you never declared is a mistake in the build
file, and quietly falling back to something like `?: 24` would put a second copy
of that number in the consumer, which is the duplication this plugin exists to
remove.

### When values resolve

Most values are settled before any subproject build file runs, so reading them
eagerly during configuration is safe. Two resolve later, because they depend on
inspecting every project first:

| Value | Reading it eagerly |
| --- | --- |
| `canonicalLocales` | returns an empty list, unless you set `locales { tags }` |
| `resolvedSharedProjectPath` | has no value, unless `modules { shared }` is declared |

Wire them into a task instead and let them resolve at execution time:

```kotlin
someTask.localeList.set(kiteConfig.canonicalLocales)
```

### Using it inside a task

Capture the provider **inside** the task configuration block, not at script level:

```kotlin
tasks.register("printId") {
    val appId = kiteConfig.androidApplicationId   // inside the block
    doLast { println(appId.get()) }
}
```

A script-level `val` makes the `doLast` lambda capture the build script object,
which the configuration cache cannot serialize. This is a general Gradle rule,
not specific to KiteConfig, but it is the first thing people hit.

### Limits

Configure the plugin in the root build file and read it everywhere else. The
view is read-only, and the model is frozen before subprojects are evaluated, so
what you read is what the build uses.

Reading across projects means this is not compatible with Gradle Isolated
Projects. Neither is the rest of the plugin.

## Tasks

All in the `kiteconfig` group. Nothing attaches to `build` or `check`.

| Task | Writes | What it does |
| --- | --- | --- |
| `kiteVerify` | nothing | Prints the resolved model |
| `kiteDoctor` | nothing | Diagnoses the setup; never fails the build |
| `kitePlan` | nothing | Lists exactly what would change, with paths |
| `kiteCheck` | `build/` | The same findings as JSON or SARIF; fails on errors |
| `kiteApply` | source | Applies the changes for every selected platform |
| `kiteApplyAndroid`, `kiteApplyIos` | source | Applies one platform's changes |
| `kiteInternal*` | `build/` or source | Generators and installers, wired for you |

With `autoApply = true`, an ordinary build runs the plan for the platform it is
building. An Android build never applies iOS changes.

Run `./gradlew kitePlan` before your first apply. It shows everything that would
change and writes nothing.

## Safety

Every source change goes through the same rails:

- **Ownership receipts.** Each installer records the files it created, with a
  checksum, under `.kiteconfig/`. A file whose checksum still matches may be
  replaced. Anything else is left alone and reported.
- **Recovery copies.** The first time KiteConfig claims a path you already had,
  your file is copied to `.kiteconfig/recovery` before anything is written.
- **Staged writes.** Text is written beside the target and moved into place. A
  file that changed since planning aborts the write and is reported.
- **Prepared, then committed.** Everything is rendered and checked before the
  first byte is written, so bad art cannot leave metadata pointing at a file
  that was never installed.

`dryRun = true` prints the plan and writes nothing. `backups = false` turns off
the durable recovery copies. Both have CLI twins that win for one invocation:
`-Pkiteconfig.dryRun=true`, `-Pkiteconfig.backups=false`.

**Commit the `.kiteconfig/` receipts.** They are small, plain text, and they
travel with the files they describe. Without them a fresh clone has the
generated icons but no proof of where they came from. Ignore the recovery
copies:

```gitignore
.kiteconfig/recovery/
```

## iOS, honestly

Android and desktop use new values in the same build, because Gradle sets them
before compilation. iOS is different. Xcode reads `project.pbxproj`,
`Info.plist`, and the asset catalog when it starts a build, and the KMP
framework is built by Gradle inside that Xcode build. Anything written at that
point is picked up by the next Xcode build, not the current one.

So:

- Any Gradle-driven build applies the iOS changes first, and they are in place.
- A build started from Xcode applies them during the framework phase, so the
  **following** Xcode build is correct. `kiteDoctor` says so.

Run `./gradlew kiteApply` once after changing an iOS value if you drive builds
from Xcode.

## Kotlin Toolchain projects

A project that builds with the Kotlin Toolchain (`./kotlin`, `project.yaml` and `module.yaml`) can use a KiteConfig plugin module instead of the Gradle plugin.
It generates `KiteBuildConfig`, writes the Xcode project and checks the Android settings.
It does not install icons or launch screens yet.
See [toolchain/README.md](toolchain/README.md).

## Common questions

- **When is a value applied?** Every build, to every platform in your project,
  unless you excluded it or turned that topic's delivery off there.
- **Does opening a block do something?** No. A feature is on when you gave it
  what it needs, or when you wrote `enabled = true`.
- **What does dryRun cover?** The source changes. Files generated into `build/`
  ignore it, because your build needs them and they are safe to delete.
- **I set an iOS value and nothing changed.** Either `autoApply` is off, so run
  `./gradlew kiteApply`, or Xcode had already read the files. See above.
- **Why did my desktop app get icons I never asked for?** You set
  `logo { foreground }` and a desktop app exists. Write
  `desktop { logo { enabled = false } }` to stop that.
- **My debug build replaced the app I installed from the store.** Give it its
  own id: `debug { appId { suffix = ".debug" } }`. See "Debug builds" above.
- **My AGP or Kotlin version is unsupported.** The features that need deep
  access turn off and say why. `ignoreVersionGuards = true`, with its `@OptIn`
  line, keeps them on at your own risk.

## Upgrading from 1.0.0

2.0.0 renames a lot. Old names do not compile, so nothing changes behaviour
silently.

| 1.0.0 | 2.0.0 |
| --- | --- |
| `id = "x"` | `appId = "x"` |
| `id("x") { android { suffix = ".a" } }` | `android { appId { suffix = ".a" } }` |
| `appName("x") { ios("y") }` | `ios { appName = "y" }` |
| `version("x") { formula { } }` | `version { scheme { } }` |
| `version("x") { android { reupload = 1 } }` | `android { version { rebuild = 1 } }` |
| `android { pin = 123 }` | `android { version { buildNumber = "123" } }` |
| `android { shipped = 1001003090 }` | `android { version { publishedBuildNumber = "1001003090" } }` |
| `ios { marketingVersion = "1.4" }` | `ios { version = "1.4" }` |
| `locales { pin("en") }` | `locales { tags = listOf("en") }` |
| `locales { filterAndroidRes = true }` | `android { locales { filterResources = true } }` |
| `logo { background = file(...) }` | `logo { background = image(file(...)) }` |
| `logo { backgroundColor = "#..." }` | `logo { background = color("#...") }` |
| `logo { android { safeZone = 0.6 } }` | `android { logo { foregroundScale = 0.6 } }` |
| `logo { desktop { roundMac = true } }` | `desktop { logo { roundMac = true } }` |
| `splash { }` turned it on | `splash { enabled = true }` |
| `splash { android { theme = "T" } }` | `android { splash { theme = "T" } }` |
| `jvmTarget = 21` | `jvm { target = 21 }` |
| `<topic> { skip(ios) }` | `ios { <topic> { enabled = false } }` |
| every `rewrite { }` block | `autoApply = true`, or run `kiteApply` |
| `rewrite { auto = true }` | `autoApply = true` |
| `logo { rewrite { replaceOld = true } }` | nothing: adoption is automatic and backed up |
| `ios { rewrite { targets("iosApp") } }` | `ios { xcodeTargets("iosApp") }` |
| `ios { rewrite { cleanPlist = true } }` | nothing: plist keys follow the facts |
| `ios { rewrite { onConflict = ... } }` | nothing: managed keys carry your value |
| `ios { rewrite { proMotion = true } }` | `ios { infoPlist { proMotion = true } }` |
| `ios { infoPlist = file(...) }` | `ios { infoPlist { path = file(...) } }` |
| `ios { rewrite { renameSharedModule(a, b) } }` | `ios { renameSharedModule(a, b) }` |
| `./gradlew kiteRewriteLogo`, `kiteRewriteXcode` | `./gradlew kiteApply` |
| `kiteConfig.id` | `kiteConfig.appId` |
| `ios { deploymentTarget = "15.0" }` | nothing: read from your Xcode project |
| `android:label="${appName}"` in your manifest | nothing: delete the line |
| `android:theme="${kiteSplashTheme}"` in your manifest | nothing: delete the line |

Four things change meaning, not just spelling:

- A project with `logo { }` and no `rewrite { }` used to get desktop icons only.
  With `autoApply = true` it now also installs the Android and iOS icons.
  Without it, nothing changes until you run `kiteApply`.
- An empty `splash { }` used to turn the launch screen on. It no longer does.
- Android gets the app label and the splash theme from a manifest KiteConfig
  generates and AGP merges over yours. Both hand-written lines can go. If you
  keep them, they still work, and `kiteDoctor` tells you which of your own
  manifest values is being replaced.
- `VersionCodeScheme.compute` returns `String` instead of `Int`. A scheme that
  returns an integer needs `.toString()`. The default scheme produces exactly
  the same digits as before, so a published app keeps its numbering.

`only()` with no arguments used to mean "everywhere". It is now an error.

## License

Apache 2.0. See [LICENSE](LICENSE).

## Name and version history

This plugin has had three names. It shipped as `kmp-ssot` at 0.1.0, became
KiteSSOT, and is now KiteConfig. The `KTCNFG` diagnostic prefix replaced an
older `KMPS` prefix left over from the first name.

1.0.0 was the first release under the KiteConfig name. 2.0.0 is the DSL reshape
described above.
