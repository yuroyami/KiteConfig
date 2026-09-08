# KiteConfig 2.0 reshape

Status: **all six phases are implemented and green. The `v2.0.0` tag is the
one step left.** Section 14 records where each phase landed, and section 16
lists the two acceptance items that are honestly still open. Every Kotlin
example in this document, in the README and in the KDoc is compiled by the test
suite, so the surface below is the surface that exists.

Baseline: commit `784d9f8` on `main`. KiteConfig 1.0.0 is published on the
Gradle Plugin Portal, so this reshape ships as **2.0.0**, a breaking release
with a migration table (section 12) and no compatibility shims. The tag has not
been cut.

This is the only design document for the reshape. Section 18 lists the older
documents it replaces and what to do with them.

## 1. The promise

You write what your app is. KiteConfig makes every platform match.

- Write each value once. Add a platform block only for what differs.
- Turn a feature on when you want it. KiteConfig works out what that needs.
- Set `autoApply = true` once and normal builds apply everything, including
  the edits to your own source files. Leave it off and one task, `kiteApply`,
  does the same thing when you ask.
- You never learn task names, per-topic permission blocks, or the order of
  installers.

The complete setup for a normal Android, iOS, and Compose Desktop project:

```kotlin
kiteConfig {
    appName = "Jetzy"
    appId   = "com.example.jetzy"
    version = "1.4.0"
    autoApply = true
}
```

That is a promise about ordinary projects. Section 8 says exactly where iOS
falls short of "the first build already uses the new values", and what the
plugin does about it.

## 2. What changes, in one table

| 1.0.0 | 2.0.0 | Why |
|---|---|---|
| `id` | `appId` | Says what it is. Matches `appName`. |
| `appName("x") { ios("y") }` | `ios { appName = "y" }` | Platform blocks hold platform differences. |
| `version("x") { android { reupload; pin; shipped } }` | `android { version { rebuild; buildNumber; publishedBuildNumber } }` | Plain names. No glossary. |
| `formula { }` | `scheme(...)` or `scheme { }` | One word for built-in and custom. |
| `locales { pin("en") }` | `locales { tags = listOf("en") }` | Assignment, not a verb. |
| `locales { filterAndroidRes }` | `android { locales { filterResources = true } }` | Android-only policy lives in the Android block. |
| `logo { background = file; backgroundColor = "#..." }` | `logo { background = image(file) }` or `background = color("#...")` | One choice, not two properties that must not both be set. |
| `logo { android { safeZone } }` | `logo { foregroundScale }`, overridable per platform | One control for all icon renderers. |
| `jvmTarget = 21` | `jvm { toolchain = 21; target = 17 }` | Java and Kotlin stay aligned. Toolchain is explicit. |
| `splash { }` turns splash on | `splash { enabled = true }` | An empty block never changes your app. |
| `skip(p)` / `only(p)` inside topics | `enabled = false` inside the platform block | One way to stop a value on one platform. |
| `rewrite { }` blocks, `auto = true`, `replaceOld`, `cleanPlist`, `onConflict` | `autoApply = true` or `kiteApply` | One switch. No permission ceremony. |
| `ios { rewrite { targets() } }` | `ios { xcodeTargets() }`, only when ambiguous | Selection, not permission. |
| `ios { rewrite { proMotion; nonExemptEncryption } }` | `ios { infoPlist { proMotion; nonExemptEncryption } }` | They are app facts, not rewrite options. |
| `ios { infoPlist = file }` | `ios { infoPlist { path = file } }` | Path moves next to the keys it holds. |
| `ios { deploymentTarget }` | removed, read from the Xcode project | It never set anything. It only checked the icon format. |
| `kiteRewriteLogo`, `kiteRewriteXcode` | `kiteApply`, `kiteApplyAndroid`, `kiteApplyIos` | One verb. |
| `kiteConfig.id` (read-back) | `kiteConfig.appId` | Follows the rename. |

Everything else keeps its name: `appName`, `version`, `modules`, `dryRun`,
`backups`, `ignoreVersionGuards`, `buildConfig`, `optIns`, `web { ioWorker }`,
`android { sdk(); ndk }`, `desktop { linuxPackageName; deriveUpgradeUuid }`,
`kiteDoctor`, `kiteCheck`, `kitePlan`, `kiteVerify`.

These names are final. `appId` was renamed to `id` and back once already, and
`rebuild` went to `reupload` and back. 2.0.0 freezes them.

## 3. The whole surface

One block with everything that exists. Lines marked `default` show the value
you get by leaving the line out.

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
    }

    splash {                            // off until enabled = true
        enabled = true
        image = file("art/splash.png")          // default: logo.foreground
        backgroundColor = "#101014"             // default: logo's color background
        dark {                                  // on as soon as one dark value is set
            image = file("art/splash-dark.png") // default: the light image
            backgroundColor = "#000000"
        }
    }

    jvm {
        toolchain = 21                  // default: whatever the module declares
        target = 17                     // default: whatever the module declares
    }

    buildConfig {                       // on as soon as a field is declared
        packageName = "com.example.jetzy"   // default: kiteconfig.generated
        className = "AppInfo"               // default: KiteBuildConfig
        includeIdentity = true              // default
        allowBuildCache = false             // default. Turn on only when every field is stable
        stringField("API_HOST", "api.jetzy.app")
        intField("MAX_RETRIES", 3)
        // longField, booleanField, doubleField, and stringField(name, provider)
        // enabled = true                   // identity-only generation, no fields
    }

    optIns {                            // on as soon as a marker is added
        add("kotlinx.cinterop.ExperimentalForeignApi")
        builtIns = true                 // default
        // enabled = true               // built-in markers only
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
        splash { theme = "AppTheme" }         // your app theme, if not detectable
        sdk(min = 26, target = 36, compile = 36)   // any subset
        ndk = "27.1.12297006"
        jvm { target = 17 }
        // autoApply = false            // this platform only: manual kiteApply
    }

    ios {
        appId = "com.example.jetzy.ios" // exact. Or appId { suffix = ".ios" }
        version = "1.4.0"               // marketing version shown in the store
        version {
            rebuild = 2
            // buildNumber = "42"       // exact CFBundleVersion, scheme skipped
            publishedBuildNumber = "1001003992"
            // scheme { v -> "${v.major}.${v.minor}.${v.patch * 10 + v.rebuild}" }
        }
        locales { tags = listOf("en", "fr") }  // replaces the shared list here
        splash { enabled = false }              // light-only: splash { dark { enabled = false } }
        infoPlist {
            path = file("iosApp/iosApp/Info.plist")   // default
            proMotion = true                          // default: key left alone
            nonExemptEncryption = false               // default: key left alone
        }
        xcodeTargets("iosApp")          // only when the project has several app targets
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
            projects(":composeApp")     // default: every browser-capable project
            packageName = "com.example.jetzy.generated"
        }
    }

    // ───────────── Root selection, plumbing, safety ──────────────────
    only(android, ios)                  // default: every platform found
    skip(desktop)                       // exclusion wins over everything

    modules {                           // only when detection guesses wrong
        shared = ":shared"
        androidApps(":androidApp")
        desktopApps(":desktopApp")
        composeResources = file("shared/src/commonMain/composeResources")
    }

    dryRun  = false                     // default. Also -Pkiteconfig.dryRun=true
    backups = true                      // default. Also -Pkiteconfig.backups=false
    // ignoreVersionGuards = true       // needs @file:OptIn(DiscouragedKiteApi::class)
}
```

The example values (scales, SDK levels, build numbers) are illustrations, not
recommendations.

## 4. The rules

Seven rules. Everything in the DSL follows from them.

### 4.1 Closest value wins

A value is looked up in this order: the platform block, then the root, then the
built-in default. Nothing is copied when a block opens, so a root value set
after the platform block still loses to the platform block.

```kotlin
kiteConfig {
    ios { version { rebuild = 2 } }
    version { rebuild = 1 }    // Android and desktop get 1. iOS keeps 2.
}
```

A default that depends on another value uses the platform's resolved value.
The splash image defaults to the logo foreground as iOS sees it, not as the
root declared it.

### 4.2 Platform blocks hold differences and native settings

`android { }`, `ios { }`, `desktop { }`, and `web { }` are the only per-platform
places. Each one accepts:

- the shared topics (`appName`, `appId`, `version`, `locales`, `logo`,
  `splash`, `jvm`, `autoApply`) as overrides for that platform, and
- that platform's native settings (`sdk()`, `xcodeTargets()`, `roundMac`, ...).

Each block is its own Kotlin type, so a setting that does not exist on a
platform does not compile there. `ios { jvm { } }` is a compile error, not a
runtime warning. There is no `override(PLATFORM)` call and no enum-based
receiver.

Some leaves exist only in platform blocks, because a shared value would make no
sense: `appId.suffix`, `version.buildNumber`, `version.publishedBuildNumber`.
That is also why there is no "clear an inherited value" operation. To stop an
inherited value you set the platform's own value.

### 4.3 Root selection

`only(...)` and `skip(...)` exist only at the root. They decide which platforms
KiteConfig touches at all. `skip` wins over `only`, and both win over any
platform block. `only()` with no arguments is an error. Neither call creates a
platform; a platform still has to exist in the project.

Platform tokens are the platform blocks themselves (`skip(desktop)`) or the
enum (`skip(KitePlatform.DESKTOP)`). Both compile.

A skipped platform gets nothing: no values, no generated files, no source
edits, no validation of values it would have received.

### 4.4 What turns a feature on

One rule: **a feature is on when you gave it what it needs. A feature that
needs nothing from you needs `enabled = true`.** An explicit `enabled = false`
always wins.

| Feature | On when |
|---|---|
| `appName`, `appId`, `version`, `locales` | declared. `enabled = false` in a platform block stops delivery there, the value stays readable. |
| `logo` | `foreground` is set. Background or scale alone do nothing. |
| `splash` | `enabled = true`. Art, colors, and an empty block do nothing. |
| `splash.dark` | any dark value is set. `dark { enabled = false }` in a platform block drops the dark variant there. |
| `buildConfig` | any field is declared, or `enabled = true` for identity-only output. |
| `optIns` | any marker is added, or `enabled = true` for the built-in set alone. |
| `web.ioWorker` | `enabled = true`. Targets and package alone do nothing. |
| `autoApply` | `autoApply = true`. Inherits into platform blocks. |

The rule looks at what you declared, never at whether you called a block or set
a property. `buildConfig.stringField("A", "b")` on the object and
`buildConfig { stringField("A", "b") }` do the same thing.

Root `enabled = false` is a default, not a ban. `splash { enabled = false }` at
the root with `ios { splash { enabled = true } }` gives iOS a splash. Only root
`skip()` is absolute.

### 4.5 Blocks merge, last write wins

Opening the same block twice configures the same thing. The last assignment to
one leaf wins. Assigning a list replaces that scope's list, including
`emptyList()`. There is no warning for setting a value twice. The old promise
of a doctor warning for mixed forms is dropped because it was never
implemented and is not worth the bookkeeping.

### 4.6 Every value is lazy

Every leaf is a Gradle `Property`. Assignment, `set(...)`, and a
`Provider` all work and mean the same thing. A provider behind a disabled or
skipped feature is never read. Reading a value from the read-back view never
turns anything on and never writes a file.

### 4.7 Nothing happens during configuration

No file is written while build scripts run, during `kiteDoctor`, or during IDE
sync. Source edits happen inside tasks only. The model freezes after the root
project is evaluated; a later write fails with a message that names the value.

### 4.8 No scope leaks

Every receiver carries a `@DslMarker` annotation. Inside `ios { }` you cannot
reach the root's `skip()` or a sibling platform's block by accident.

## 5. Topics

### 5.1 Identity

`appName` and `appId` are the two shared identity facts. Each platform can
override either one.

`appId { suffix = ".android" }` appends to the shared base. `suffix` exists only
in platform blocks and defaults to empty. Setting `appId` inside a platform
block gives that platform an exact identifier, and no suffix is involved.
Read-back and diagnostics show base, suffix, and final identifier separately.

Changing `appId` renames nothing else: not Kotlin packages, not Swift imports,
not directories, not Gradle projects.

Validation runs per platform and only for platforms that will receive the
value. `appId = "com.example.my-app"` with `skip(android)` is accepted, because
only Android rejects the hyphen.

### 5.2 Version

| Member | Meaning |
|---|---|
| `version = "1.4.0"` | The version users see. Android `versionName`, Apple marketing version, desktop package version. A platform block can override it. |
| `version { scheme(...) }` | How a build number is derived from the version. A built-in (`VersionSchemes.DEFAULT`) or `scheme { v -> "..." }`. |
| `version { rebuild = n }` | Extra counter for re-uploading the same version. Default 0. Feeds the scheme. |
| `version { buildNumber = "..." }` | Exact build number, scheme skipped. Platform blocks only. |
| `version { publishedBuildNumber = "..." }` | The highest number already shipped. The resolved number must beat it. Platform blocks only. |

A custom scheme receives `major`, `minor`, `patch`, and `rebuild` and returns a
`String`. Text at the boundary lets Apple builds use dotted numbers. Each
platform then validates natively:

- Android parses the text as a decimal integer with a checked conversion and
  enforces the Google Play range. A published baseline must be beaten, not
  equaled.
- Apple keeps the marketing version and the build number as separate fields
  and compares build numbers by numeric components.
- Desktop validates against the package formats it actually produces.

`VersionSchemes.DEFAULT` keeps today's layout, `1 | major(3) | minor(3) | patch(2) | rebuild(1)`,
as text, on every platform. No published app changes its numbering by
upgrading. New native defaults per store are out of scope for 2.0.

The plugin never increments a published counter, never queries a store, and
never changes a baseline on its own.

### 5.3 Locales

`locales { tags = listOf("en", "ar") }` sets the list. Omit it and KiteConfig
reads the Compose resource folders of the shared module. Tags are normalized
and deduplicated with the existing parser. An explicit empty list means "no
locales", not "detect again".

`android { locales { filterResources = true } }` keeps only the Android res
folders whose locale is in the list. This needs the list before AGP freezes its
resource configuration. Today auto-detection arrives too late for that case, so
the implementation must resolve the shared module before AGP asks, and if it
cannot, fail at configuration time with "set `locales { tags }` or
`modules { shared }`" as the message. It must not force a global evaluation
order.

`locales { enabled = false }` in a platform block stops locale delivery there.

### 5.4 Logo

`foreground`, `background`, `foregroundScale`, and `enabled` are shared and
overridable.

`background` is one choice: `color("#102A43")` or `image(file("bg.png"))`. The
two helpers live on the logo scope, so no import is needed. Replacing a color
with an image in a platform block leaves no hidden color behind.

`foregroundScale` is a ratio in `(0, 1]`. The foreground keeps its aspect
ratio, is centered, and fits inside a box that is that fraction of the icon
canvas on each axis. Transparent padding inside the source image counts as part
of the image. The background always fills the whole canvas. Platform masks
(Android adaptive shapes, macOS rounding) are applied after that, and stay
native settings. When the scale is omitted, each renderer keeps the ratio it
uses today, so existing icons do not move.

Desktop icons are generated into `build/` and packaged whenever the logo is on
and a desktop app exists. Android res files and the iOS asset catalog are
source, so they are written by the apply engine (section 6).

**First contact.** The first apply into a project that already has launcher
icons replaces the files at the plugin's own output paths and removes files that
would collide with them by Android resource name (same name, other extension,
same res folder). Every replaced or removed file is copied to
`.kiteconfig/recovery` first and recorded in the ownership receipt. Anything
else in `res/` or the asset catalog is left alone. There is no `replaceOld`
flag any more; this bounded takeover is what installing a logo means.

Deferred: dark logo variants, alignment and offsets, trimming transparent
padding, per-OS desktop icon overrides. None of them ship in 2.0.

### 5.5 Splash

Splash is off on every platform until `enabled = true`. Declaring art does not
turn it on.

Resolution order for each platform: platform block, then root, then fallback.
The fallback for `image` is that platform's effective logo foreground. The
fallback for `backgroundColor` is that platform's effective logo background,
but only when the logo background is a color. A logo with an image background
gives the splash no color; enabling splash then fails with a message naming
`splash { backgroundColor }`.

`dark { }` follows the same order. A dark variant with no image uses the light
image. A platform can drop an inherited dark variant with
`splash { dark { enabled = false } }`.

Delivery:

- Android: generated res under `build/` (theme XML, drawables, night variant),
  wired as a resource directory of the app module. The generated style
  inherits `android { splash { theme } }`. A dark-only color must be referenced
  by the generated night style; today it is written and never used.
- Desktop: one composed PNG under `build/`, passed as the JVM `-splash:` image
  to packaging and to the `run` tasks.
- iOS: the `UILaunchScreen` dictionary in `Info.plist` plus a color set and an
  image set in the asset catalog. Source edits, so they go through the apply
  engine.

A project whose `Info.plist` still names a launch storyboard
(`UILaunchStoryboardName`) cannot enable the iOS splash: iOS prefers the
storyboard and the splash would silently never show. Today this is a warning.
In 2.0 it is an error that names the key and the two ways out (remove the
storyboard key, or disable splash for iOS).

Turning splash off after it was applied removes the plugin's own launch
integration on the next apply (owned assets, the plist keys it wrote, the
manifest overlay). It never removes assets it did not create. The two known
gaps, an unowned iOS PNG being overwritten and a stale dark PNG surviving a
light-only reconfiguration, are closed by the ownership rules in section 7.

Deferred: `splash.foregroundScale` and screen-relative layout.

### 5.6 JVM

```kotlin
jvm {
    toolchain = 21   // JDK used to compile and run tests; sets the Kotlin and Java toolchain
    target = 17      // bytecode level for Java and Kotlin, kept equal
}
```

`target` replaces `jvmTarget` and fixes the defect where only Kotlin was
aligned and a module with Java sources failed with inconsistent targets. It is
applied to Kotlin compilations, Java compilations, and Android `compileOptions`
together. `toolchain` calls the Kotlin toolchain API, which also configures
Java. Both are optional and leave the module alone when unset.

Platform overrides apply per compilation. `android { jvm { target } }` reaches
Android modules and the Android compilations of KMP modules;
`desktop { jvm { target } }` reaches the Compose Desktop app and the JVM
compilations of KMP modules. A toolchain is project-wide, so two platforms
demanding different toolchains inside one shared module is an error with both
demands named.

Deferred to a later release: `javaSource`, `release` (the `--release` flag),
and a separate test JDK. They are real requests but not part of the reshape.

### 5.7 Generated code

`buildConfig`, `optIns`, and `web { ioWorker }` are project-scoped. Root
`only`/`skip` cannot remove one common source file from one platform. They have
no platform overrides.

- `buildConfig` writes one object into `commonMain`. Identity fields keep their
  current names. `includeIdentity = false` must not read any identity provider.
- `optIns` adds markers to the Kotlin/Native compilations of the selected
  projects.
- `ioWorker` generates the browser worker source for browser Kotlin/JS targets
  only. A Node-only or Wasm target that is selected explicitly is an error.

### 5.8 Native settings

Things that mean nothing outside one platform stay in that platform's block:

| Platform | Native settings |
|---|---|
| Android | `sdk(min, target, compile)`, `ndk`, `locales { filterResources }`, `splash { theme }` |
| iOS | `xcodeTargets()`, `pbxproj`, `podfile`, `appDirectory`, `appIconDirectory`, `infoPlist { path; proMotion; nonExemptEncryption }`, `renameSharedModule(from, to)` |
| Desktop | `logo { roundMac }`, `linuxPackageName`, `deriveUpgradeUuid` |
| Web | `ioWorker { targets(); projects(); packageName }` |

`renameSharedModule` is a one-time transition. It rewrites the Podfile
declaration and Swift `import` lines from one exact name to the other. It
does not rename Gradle projects, directories, or packages. It runs through the
apply engine like everything else, is a no-op once the references are renamed,
and reports a `from` name that matches nothing as an error rather than
silently succeeding.

The old `deploymentTarget` property is gone. The iOS icon installer reads
`IPHONEOS_DEPLOYMENT_TARGET` from the selected Xcode target and refuses to
install a single-size icon below iOS 12. When the setting cannot be read, the
installer says so and skips the check.

## 6. Applying changes

### 6.1 Two ways to apply, one plan

From the resolved model, the selected platforms, and the enabled features,
KiteConfig derives one plan: the list of files to create, update, or remove,
with the reason for each. `kitePlan` prints it. Then:

| Mode | What happens |
|---|---|
| `autoApply = true` | The plan for a platform runs before that platform's ordinary build. |
| `autoApply = false` (default) | Nothing runs until you call `kiteApply`, `kiteApplyAndroid`, or `kiteApplyIos`. |
| `dryRun = true` or `-Pkiteconfig.dryRun=true` | The plan is printed, nothing is written, in both modes. |

The plan does not depend on `autoApply`. The switch only decides who triggers
it. `kiteApply` needs no other permission: calling it is the intent.

Generated files under `build/` are not part of the plan. They are ordinary task
outputs with inputs and outputs, wired into the consuming tasks, on in both
modes.

### 6.2 What each platform gets

| Declared | Android | iOS | Desktop |
|---|---|---|---|
| `appName` | manifest placeholder, in memory | `PRODUCT_NAME` and display name in the Xcode project | `packageName` |
| `appId` | `applicationId`, in memory | `PRODUCT_BUNDLE_IDENTIFIER` | `macOS.bundleID` |
| `version` | `versionName`, `versionCode`, in memory | marketing version, build number | `packageVersion`, `macOS.packageBuildVersion` |
| `locales` | locale filters, in memory | localization list in the project | not applicable |
| `logo` | res files (source) | asset catalog (source) | icons under `build/` |
| `splash` | res under `build/`, theme placeholder | plist keys, catalog entries (source) | PNG under `build/` |
| `jvm` | `compileOptions`, Kotlin target | not applicable | Kotlin and Java tasks |
| `infoPlist` facts | not applicable | plist keys (source) | not applicable |

"In memory" means the value is set through the platform's Gradle DSL and no
file changes. "Source" means a file you commit changes, through the apply
engine.

### 6.3 Which build triggers automatic application

- Android: the app module's `preBuild` depends on the Android apply task. A
  build of the Android app never runs the iOS plan.
- Desktop: nothing to apply. All delivery is in memory or under `build/`.
- iOS: the framework link and embed tasks depend on the iOS apply task. See
  section 8 for what that does and does not guarantee.

The manual `kiteApply` runs every selected platform's plan. The per-platform
tasks run one plan. Diagnostic tasks never pull an apply task.

### 6.4 Scope and shared files

Two Xcode app targets can share one `project.pbxproj`, one `Info.plist`, or one
asset catalog. The plan groups every edit to one file into one write. Two
operations that want different values in the same place are an error that
names both, never a race decided by task order.

### 6.5 Continuous integration

On a tree where everything was applied and committed, `autoApply` writes
nothing and touches no timestamp. On a tree where it was not, a CI build with
`autoApply = true` changes files. To catch that instead, run `kiteCheck`
first: it reports pending source changes as an error.

## 7. Safety

Everything the 1.0.0 installers already do stays, and applies to every source
edit in 2.0:

- **Ownership receipts.** Each installer keeps a list of the files it created
  with a checksum, under `.kiteconfig/` next to the outputs. A file whose
  checksum still matches may be replaced or removed. Anything else is left
  alone and reported. Receipts are committed; they survive `clean`.
- **Backups.** `backups = true` (default) copies a file to
  `.kiteconfig/recovery` before it is replaced or removed on first contact. The
  recovery folder is ignored by git.
- **Contained paths.** Every target is inside the project, never a symlink,
  never followed through one.
- **Staged writes.** Text is staged next to the target and moved into place.
  A change to the file between planning and writing aborts the write and
  reports the file.

New in 2.0:

- **Prepare everything, then write.** All text and all images for one plan are
  rendered and validated before the first byte is written. A corrupt splash PNG
  fails the plan before the plist is touched. Today the plist is committed
  first.
- **One transaction per plan.** The iOS plist, project file, catalog files,
  and receipts for one platform are written as one group. If a later write
  fails, the earlier ones are rolled back from their staged originals, and an
  external edit found during rollback is preserved and reported.
- **Reconciliation.** Turning a feature off, or dropping a dark variant,
  removes the plugin's own now-obsolete outputs on the next apply, using the
  receipt to know which files are its own. Unowned files are never removed.
- **No-op is a no-op.** Applying an unchanged desired state writes nothing and
  changes no timestamp.
- **Conflicts are specific.** An owned file whose checksum changed produces a
  "modified since installed" result naming the file and the recovery step.
  The plugin never overwrites an edit it did not make.
- **Cheap when nothing changed.** Under `autoApply` the apply task runs on
  every build, so it has to be fast on the common path. Each installer
  compares a fingerprint of its inputs (source art bytes, colors, scale,
  target paths) with the fingerprint stored in its receipt before it decodes
  or renders anything. Equal fingerprint and intact outputs means no work.
  Today the logo installers render first and fingerprint after, on every run.
- **Line endings survive.** A rewritten text file keeps the line ending style
  it had. A CRLF plist stays CRLF.
- **Paths come from the build model.** Android res and manifest locations are
  read from the AGP variant sources API, not assumed to be `src/main`.

Deferred: a write-ahead journal that survives a killed process mid-write. The
staged-write mechanism already leaves the tree either untouched or complete
per file; cross-file recovery after a kill is a follow-up.

### 7.1 What the refactor must not break

These mechanics exist today, were hard-won, and are not part of the reshape.
The implementation keeps them and their tests.

- Desktop values are written from a callback registered before Compose's own
  `afterEvaluate`, and never from inside `plugins.withId`, or every write lands
  too late. Compose applied to the root project is refused with the fix in the
  message.
- Desktop app detection reads Compose's initialization flags reflectively,
  because touching `application` creates packaging tasks the user never asked
  for. A failed reflection is "unavailable", not "no app".
- `vendor`, `description`, and `copyright` are never written to Compose.
  `targetFormats` is read for validation and never written.
- Windows package versions are capped per component and only checked when an
  MSI or EXE format is enabled. Debian and RPM package names are slugs, and an
  unslugifiable app name fails with `linuxPackageName` as the escape hatch.
- The Windows upgrade UUID is a pure function of the identifier and a fixed
  namespace constant. That constant is a published contract.
- The macOS icon container skips the two small PNG entry types readers
  disagree on. Windows and Linux icons stay square.
- The desktop splash lives under a `common/` subdirectory of the app resources
  root, and the launcher argument uses the `$APPDIR` form so no developer path
  ships inside an installer.
- One plan writes each file once. The iOS plist edits for identity, facts, and
  splash are merged into one text change.
- The KMP Android adapter cannot set the NDK version or Java compatibility;
  the classic adapter can. Diagnostics say which one ran.
- Locale detection accepts the documented `values-*` folder shapes only, does
  not follow links, and is bounded.
- Every size bound in the current feature list (image dimensions, plist size,
  locale counts, generated field counts, text budgets) stays.

## 8. iOS integration, honestly

Android and desktop can use new values in the same build, because Gradle sets
them before compilation. iOS is different: Xcode reads `project.pbxproj`,
`Info.plist`, and the asset catalog when it starts a build, and the KMP
framework is built by Gradle *inside* that Xcode build. Anything Gradle writes
at that point is seen by the next Xcode build, not the current one.

What 2.0 guarantees on iOS:

- Any Gradle-driven invocation (`./gradlew kiteApply`, `./gradlew build`, an
  IDE Gradle run, the framework link tasks) applies the iOS plan first.
- A build started from Xcode applies the plan during the framework phase, so
  the following Xcode build is correct. `kiteDoctor` says this in plain words
  when it detects an Xcode-driven workflow.

### Spike result: pre-actions are early enough

Measured with Xcode 26.6 on a hand-written project, building for the iOS
Simulator. A scheme pre-action rewrote an `.xcconfig` that the app target
referenced, and the same build used the new values.

| What was read | Clean build | Second build, derived data kept |
|---|---|---|
| The build phase's `$MARKETING_VERSION` | new value | new value |
| The built app's `CFBundleShortVersionString` | new value | new value |
| The built app's `CFBundleVersion` | new value | new value |
| The built app's `CFBundleIdentifier` | new value | new value |
| The built app's `CFBundleName` | new value | new value |

So the "next build" limitation is removable for identity and version. Two
facts decide what it costs.

**A key in the target's own `buildSettings` beats the xcconfig.** With the
xcconfig saying one version and the target saying another, the target won. The
one-time project edit is therefore not just adding a `baseConfigurationReference`.
It also has to delete `MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`,
`PRODUCT_BUNDLE_IDENTIFIER` and `PRODUCT_NAME` from every selected
configuration, which are exactly the four keys KiteConfig writes today.

**The pre-action has to live in a scheme.** A shared scheme is another file
people commit and merge. A per-user scheme is not committed at all, so a fresh
clone silently loses the behaviour. And the pre-action runs a Gradle
invocation on every Xcode build: roughly 0.6 to 0.8 seconds against a warm
daemon on this repo, and a cold daemon start when there is no warm one, with no
message explaining why Xcode appears to hang. `xcodebuild -showBuildSettings`
and Xcode's own editor do not run pre-actions, so both keep showing the
previous values until a build runs.

**2.0 does not automate this.** The measurement says it works; the delivery is
its own feature, with its own owned files, its own failure modes, and a
per-build cost the reader has to agree to. Section 15 carries it as designed
follow-up work rather than a maybe. Until then the guarantee above stands as
written, and the doctor message stays.

### Locales on iOS: the plist owns them

Also measured, in the same build. `CFBundleLocalizations` in `Info.plist`
reaches the built app. `knownRegions` from `project.pbxproj` appears nowhere in
the product, and a Compose app has no `.lproj` directories for the system to
find, so the project file alone offered the user nothing.

KiteConfig now writes both. `CFBundleLocalizations` is what makes iOS offer the
languages; `knownRegions` stays because Xcode uses it for projects that do have
localized variant groups, and dropping it would regress those.

Plist keys (`UILaunchScreen`, `ITSAppUsesNonExemptEncryption`,
`CADisableMinimumFrameDurationOnPhone`) and the asset catalog remain source
edits in every outcome.

The Xcode project parser keeps its one known defect on the list: a quoted
value consisting only of a brace, a parenthesis, or a semicolon is read as
structure. Fixing that is a prerequisite for any further project-file work.

## 9. Reading values back

The 1.0.0 read-back view stays. It is flat, lazy, and documented, and it was
finished a week ago; a second redesign is not worth the churn.

Changes:

| 1.0.0 | 2.0.0 |
|---|---|
| `kiteConfig.id` | `kiteConfig.appId` |
| `kiteConfig.jvmTarget` | `kiteConfig.jvmTarget`, plus `kiteConfig.jvmToolchain` |
| `appNameFor(platform)` | unchanged, plus `appIdFor(platform)`, `versionFor(platform)`, `buildNumberFor(platform)` |
| new | `deliversTo(platform, topic)`: whether the value will actually be applied there, so a consumer can tell "configured" from "delivered" |

`androidApplicationId`, `iosBundleId`, `desktopBundleId`, `versionCode`,
`iosBuildNumber`, `iosMarketingVersion`, `desktopBuildNumber`,
`canonicalLocales`, `resolvedSharedProjectPath`, `minSdk`, `targetSdk`,
`compileSdk`, and `ndk` keep their names and meaning. Reading never configures
anything, never writes, and never invents a default. The two late-resolving
values (`canonicalLocales`, `resolvedSharedProjectPath`) keep their documented
behavior.

## 10. Tasks

All in the `kiteconfig` group. Nothing attaches to `build` or `check`.

| Task | Writes | What it does |
|---|---|---|
| `kiteVerify` | nothing | Prints the resolved model per platform, with where each value came from |
| `kiteDoctor` | nothing | Explains findings. Never fails the build |
| `kitePlan` | nothing | Prints the apply plan: files, operations, reasons, recovery notes |
| `kiteCheck` | `build/` | Same findings as doctor as JSON or SARIF. Fails on errors and on pending source changes |
| `kiteApply` | source | Runs the plan for every selected platform |
| `kiteApplyAndroid`, `kiteApplyIos` | source | Runs one platform's plan |
| `kiteInternal*` | `build/` or source | Generators and installers. Wired automatically, never invoked by hand |

## 11. One validation engine

Normal builds, `kiteDoctor`, `kiteCheck`, `kitePlan`, and `kiteVerify` all read
the same resolved model and the same list of findings. A configuration that
fails a normal build fails `kiteCheck` with the same message. Today `kiteCheck`
skips part of normal validation and can pass a model the build rejects.

Findings explain the configuration, not the internals:
`iOS launch screen: off, because splash.enabled is false (root default)`.
When a feature is on but `autoApply` is off, the finding says that and names
`kiteApply` as the optional next step. No finding ever suggests a `rewrite`
block or an internal task.

Every finding shows: topic, platform, resolved value, where it came from
(platform block, root, default, detected), whether it will be delivered, and
why not if not.

## 12. Migration from 1.0.0

This is a hard break. Old names do not compile. The renames:

| 1.0.0 | 2.0.0 |
|---|---|
| `id = "x"` / `id("x") { }` | `appId = "x"` / `appId { }` |
| `appName("x") { ios("y") }` | `appName = "x"; ios { appName = "y" }` |
| `id("x") { android { suffix = ".a" } }` | `android { appId { suffix = ".a" } }` |
| `version("x") { formula { } }` | `version = "x"; version { scheme { } }` |
| `version("x") { android { reupload = 1 } }` | `android { version { rebuild = 1 } }` |
| `android { pin = 123 }` | `android { version { buildNumber = "123" } }` |
| `android { shipped = 1001003090 }` | `android { version { publishedBuildNumber = "1001003090" } }` |
| `ios { marketingVersion = "1.4" }` | `ios { version = "1.4" }` |
| `locales { pin("en", "fr") }` | `locales { tags = listOf("en", "fr") }` |
| `locales { filterAndroidRes = true }` | `android { locales { filterResources = true } }` |
| `logo { background = file(...) }` | `logo { background = image(file(...)) }` |
| `logo { backgroundColor = "#..." }` | `logo { background = color("#...") }` |
| `logo { android { safeZone = 0.6 } }` | `android { logo { foregroundScale = 0.6 } }` |
| `logo { desktop { roundMac = true } }` | `desktop { logo { roundMac = true } }` |
| `logo { rewrite { replaceOld = true } }` | nothing. First-contact takeover is automatic and backed up |
| `splash { }` (on by presence) | `splash { enabled = true }` |
| `splash { android { theme = "T" } }` | `android { splash { theme = "T" } }` |
| `splash { rewrite { } }` | nothing. Enabling splash is enough |
| `jvmTarget = 21` | `jvm { target = 21 }` |
| `<topic> { skip(ios) }` | `ios { <topic> { enabled = false } }` |
| `<topic> { only(android) }` | `ios { <topic> { enabled = false } }; desktop { <topic> { enabled = false } }` |
| `logo { rewrite { } }`, `ios { rewrite { } }` | `autoApply = true`, or run `kiteApply` |
| `rewrite { auto = true }` | `autoApply = true` |
| `ios { rewrite { targets("iosApp") } }` | `ios { xcodeTargets("iosApp") }`, and only if needed |
| `ios { rewrite { cleanPlist = true } }` | nothing. Plist keys follow the facts |
| `ios { rewrite { onConflict = ... } }` | nothing. Managed keys are set; other keys untouched |
| `ios { rewrite { proMotion = true } }` | `ios { infoPlist { proMotion = true } }` |
| `ios { rewrite { nonExemptEncryption = false } }` | `ios { infoPlist { nonExemptEncryption = false } }` |
| `ios { infoPlist = file(...) }` | `ios { infoPlist { path = file(...) } }` |
| `ios { rewrite { renameSharedModule(from, to) } }` | `ios { renameSharedModule(from, to) }` |
| `ios { deploymentTarget = "15.0" }` | nothing. Read from the project |
| `./gradlew kiteRewriteLogo`, `kiteRewriteXcode` | `./gradlew kiteApply` |
| `kiteConfig.id` | `kiteConfig.appId` |

Things that change meaning, not just name:

- A project with `logo { }` and no `rewrite { }` used to get desktop icons only.
  In 2.0 with `autoApply = true` it also gets Android res and the iOS catalog
  installed. Without `autoApply`, nothing changes until `kiteApply`.
- An empty `splash { }` used to turn splash on for Android and desktop. In 2.0
  it does nothing until `enabled = true`.
- `VersionCodeScheme.compute` returns `String` instead of `Int`. A custom
  scheme that returns an integer needs `.toString()`. The default scheme
  produces the same digits as before.
- `optIns` and `web { ioWorker }` used to fall back to the shared module only
  when `projects()` was empty, although the README said "all detected". In 2.0
  the default is every eligible project. Name the projects to narrow it.
- `only()` with no arguments used to mean "everywhere". In 2.0 it is an error.

The migration is not a mechanical search and replace. Anyone who had
`rewrite { }` blocks and ran the tasks by hand keeps exactly that behavior by
leaving `autoApply` off and running `kiteApply`.

## 13. Known defects this reshape closes

Each of these was reproduced against 1.0.0. **Fourteen are now fixed, each with
a test that fails without the fix.** The fifteenth, the locale list arriving
after Android needs it, is softened rather than solved: the message now names
the two ways to answer, which is what section 5.3 allows when discovery
genuinely cannot answer in time.

Section 19 carries the evidence for each one.

| Defect | Closed by |
|---|---|
| iOS splash overwrites an unowned or hand-edited PNG without backup | ownership for every installer, first-contact rules (phase 3) |
| A failed splash install leaves the plist and project already changed | prepare-then-write, one transaction per plan (phase 3) |
| `skip` / `only` do not stop the logo installers | one selection gate per platform and topic, used by every delivery (phase 1, 3) |
| Auto-detected locales arrive after AGP needs them for filtering | resolve the shared module before AGP asks, or fail early with the fix (phase 2) |
| `kiteCheck` passes a model the build rejects | one validation engine (phase 1) |
| `jvmTarget` aligns Kotlin only, Java modules fail | `jvm { target }` sets both (phase 2) |
| Xcode parser reads a quoted brace or semicolon as structure | token kinds at every structural branch (phase 0) |
| Excluded platforms still validate their values | select first, validate only receivers (phase 1) |
| Nested blocks reach the outer or a sibling scope | `@DslMarker` on every receiver, negative compile tests (phase 1) |
| `ignoreVersionGuards` does not reach the KMP Android adapter | same guard policy in every adapter (phase 0) |
| Dark-only Android splash color is generated but never referenced | night style references it (phase 0) |
| The documented compatibility check fails configuration-cache storage | task input instead of a captured script object (phase 0) |
| Property-style `buildConfig` configuration leaves generation off | activation reads content, not block calls (phase 1) |
| The promised mixed-form warning never fires | promise removed (phase 1) |
| Removing dark iOS art leaves a stale PNG | reconciliation from receipts (phase 3) |

## 14. Work plan

Six phases, in order. Each phase ends green: tests, ABI check, the
configuration-cache run, and the AGP compatibility matrix. A phase is done when
its evidence exists, not when its checkbox is ticked.

### Where it stands

| Phase | State |
|---|---|
| 0. Fix what is broken today | Done. All five items, each with a test that fails without the fix. |
| 1. The model | Done. Typed platform blocks, lazy inheritance, content-based activation, the DSL marker, one validation layer, the renamed read-back. |
| 2. Deliveries | Done, with one item softened. Identity, version, locales, JVM, logo and splash all deliver on the new model, and the splash fixture now runs the real resource compiler against a live Android SDK. Android resource filtering still cannot resolve a detected shared module before AGP asks; the failure now names the two ways to answer instead of pretending the list was empty. Renderer geometry is not yet measured from known input bounds. |
| 3. The apply engine | Done, minus one. One planner now feeds both the preview and the tasks, a feature turned off has its installed output taken back out, fault injection covers every write step, and the manifest-overlay spike ran and landed. The one open item, two app targets sharing one project file, is iOS work and belongs to phase 4. |
| 4. iOS | Done. The deployment target is read from the Xcode project, the xcconfig and pre-action spike ran against Xcode 26.6 with its result in section 8, the plist carries `CFBundleLocalizations`, which is the only thing that made a Compose app's languages reachable, and the doctor now states the Xcode build-order fact instead of leaving it to the README. |
| 5. Generated code and diagnostics | Done. Activation reads content, the vocabulary is current, every message names a DSL path that exists, a Node-only target is refused for the browser worker, and every generated field type takes a `Provider`. |
| 6. Documentation and release | Done, minus the tag. README, the site page, FEATURES, the changelog and the release notes are current, the example test compiles the README's fences as well as the KDoc's, and explicit API mode is on: the ABI dump went from 691 lines to 359, holding only what a build script can name. The `v2.0.0` tag is the one step left, and it is the maintainer's to take: pushing a `v*` tag runs `publishPlugins`, so the tag is the release. |

The detailed task-by-task plan is written when a phase starts, from this
document.

### Phase 0: fix what is broken today

No DSL changes. Everything here is a correctness fix on the 1.0.0 surface and
can ship as 1.0.1 if wanted.

- Xcode parser: require the symbol token kind at every structural branch; test
  every reserved punctuation character as a quoted value, and parse, rewrite,
  parse, rewrite stability.
- KMP Android adapter honors `ignoreVersionGuards` like the classic adapter;
  the override fixture asserts the effective SDK values.
- Android splash: the night style references the dark color; a dark-only color
  with an explicit image is covered.
- `agpCompatibilityTest` takes the plugin jar as a declared input; the
  documented command runs under `--configuration-cache-problems=fail` in CI.
- iOS splash assets go through the ownership machinery with backups.

Evidence: the audit's reproductions, re-run, pass.

### Phase 1: the model

- New scopes: root, `android`, `ios`, `desktop`, `web`, each a typed receiver
  with `@KiteDsl`. Shared topic scopes reused inside each platform block.
- Inheritance: closest value wins, lazy, no copying on block open, repeated
  blocks merge, lists replace.
- Activation per section 4.4. Root `only`/`skip` with both token forms.
- One resolver that answers, for a platform and a leaf: value, origin,
  delivered or not, and why.
- One validation engine used by builds and by every diagnostic task.
  Validation runs only for platforms that receive the value.
- Read-back: rename, the three `*For(platform)` accessors, `deliversTo`.
- Delete: topic `skip`/`only`, `rewrite` scopes, `auto`, `replaceOld`,
  `cleanPlist`, `onConflict`, `deploymentTarget`, `doubleSetWarnings`,
  `KiteFlowScope`.

Evidence: real Gradle DSL fixtures for the full surface block and for the
minimal example; negative compile fixtures for scope leaks and for
`ios { jvm { } }`; ordering tests (root after platform); a throwing provider
behind a disabled feature never runs; root `skip` beats a platform
`enabled = true`; `kiteCheck` and a normal build agree on every finding.

### Phase 2: deliveries on the new model

- Identity and version: `appId` plus suffix, exact platform ids, text scheme
  contract, `rebuild`, `buildNumber`, `publishedBuildNumber`, per-platform
  validation, default scheme unchanged.
- Locales: `tags`, early resolution for Android filtering, `filterResources`.
- JVM: `target` on Java and Kotlin together, `toolchain`, per-compilation
  platform overrides, conflicting toolchain error.
- Logo: background choice, `foregroundScale` in every renderer, native ratios
  when omitted.
- Splash: explicit enable, fallback after platform resolution, dark rules,
  disabled outputs not wired.

Evidence: a mixed Java and Kotlin module compiles under `jvm { target }`; an
Android build with resource filtering and auto-detected locales succeeds;
generated icon geometry measured from known inputs; boundary and overflow cases
for every scheme path; splash off with art configured produces nothing; an
Android splash fixture that runs the real resource compiler when an SDK is
available, so a missing parent theme fails as a KiteConfig message and not
as an `aapt2` error; the answer to "does the JVM launcher take the first or
the last `-splash:` argument", recorded in the desktop wiring.

### Phase 3: the apply engine

- Planner: from model to a list of file operations with reasons, grouped per
  platform and per file.
- Executor: prepare all outputs, validate, then commit as one group with
  rollback; receipts, first-contact takeover, reconciliation of obsolete owned
  outputs, no-op detection.
- Tasks: `kiteApply`, `kiteApplyAndroid`, `kiteApplyIos`; `kitePlan` prints the
  same plan the executor runs; `kiteCheck` reports pending changes.
- Android `preBuild` hook for `autoApply`; the Android plan never includes iOS
  operations.
- Spike: deliver the Android app label and the splash theme through a
  generated manifest overlay instead of two hand-written placeholder lines. If
  the merge priority allows it, the placeholder lines and their doctor checks
  go away. If not, they stay and the doctor keeps checking them.

**Spike result: the merge priority allows it, so the lines are gone.** Measured
against a real Android SDK on AGP 8.5.2 with Gradle 8.9 and AGP 9.3.1 with
Gradle 9.5.1. `variant.sources.manifests.addGeneratedManifestFile` has the same
descriptor on both, so this needs no floor adapter.

| Module manifest declares | Overlay carries `tools:replace` | Result |
|---|---|---|
| label and theme | no | merge fails, and the error points at a file in `build/` that nobody wrote |
| neither | no | the overlay supplies both, cleanly |
| label only | no | merge fails on the label; the theme would have merged |
| anything, including the 1.0.0 `${appName}` line | yes | the overlay wins |

So the overlay carries `tools:replace` and names only the attributes it writes.
The `appName` and `kiteSplashTheme` placeholders are still defined, because a
manifest written against 1.0.0 still contains them and an undefined placeholder
fails the merge. Both doctor checks flipped direction: they used to say "add
this line", and now they say which of your own manifest values the merge
replaces.

The same spike turned up a real defect. The Android splash added its generated
resources through `sourceSets.main.res.srcDir(provider)`, which AGP 9 refuses
outright for generated output, so every AGP 9 project with the splash on failed
to configure. It now uses `variant.sources.res.addGeneratedSourceDirectory`,
which has the same descriptor on both AGP ends, and the fixture makes aapt2
resolve the generated style so a silent drop cannot pass.

Evidence: fault injection at every write and delete; an external edit survives
rollback; repeat apply is byte-for-byte and timestamp-for-timestamp idle; two
app targets sharing one project file; disable, `clean`, re-enable; an Android
build leaves every iOS byte untouched.

All of these are covered except two app targets sharing one project file, which
moves to phase 4 with the rest of the iOS work.

### Phase 4: iOS

- `infoPlist { }` facts, `xcodeTargets()`, `renameSharedModule` through the
  plan, splash through the plan, deployment target read from the project.
- The framework link and embed hook for `autoApply`.
- The xcconfig and pre-action spike from section 8, with `xcodebuild` as the
  judge. The result is written into section 8 of this document.
- The `CFBundleLocalizations` versus `knownRegions` decision.
- Doctor wording for Xcode-driven workflows.

Evidence: `xcodebuild` output inspected after a change to `appId`, `appName`,
and `version`, first build and second build, with the observed behavior
recorded; a project with several app targets and no `xcodeTargets()` fails
with the candidates listed.

### Phase 5: generated code and diagnostics

- `enabled` and content activation for `buildConfig`, `optIns`, `ioWorker`.
- `optIns` and `ioWorker` default to every eligible project, not only the
  shared module. Browser-capability check for worker targets.
- Every `buildConfig` field type accepts a `Provider`, not only strings. The
  resolved iOS marketing version, iOS build number, and desktop build number
  join the generated constants next to the Android `versionCode`.
- `kiteVerify`, `kiteDoctor`, `kiteCheck`, `kitePlan` speak the new vocabulary
  only. Diagnostic id uniqueness test still passes. The JSON report uses
  project-relative paths like the SARIF report already does.
- Every error message names a DSL path that exists. Today several name
  retired paths (`web { projectPaths }`, `web { browserTargetNames }`,
  `android { ndkVersion }`, `add("appId")` while the DSL said `id`). The
  message hygiene test learns the 2.0 vocabulary and fails on any other.

Evidence: fields-only `buildConfig` never reads an identity provider; a
Node-only target selected for the worker fails; every user-visible message
grep-clean of `rewrite`, `pin`, `shipped`, `reupload`, `formula`, `id(`.

### Phase 6: documentation and release

- README, FEATURES, `docs/index.md`, the extension KDoc surface block, scope
  member tables. The KDoc example test compiles every example, and grows to
  cover the README fences too, so a wrong README fails CI.
- Explicit API mode on the plugin module. Adapter input holders and generator
  tasks that are not user-facing become internal. The ABI dump shrinks to what
  users can actually call.
- ABI dump regenerated and reviewed.
- Changelog entry and `.github/release-notes/v2.0.0.md` with the migration
  table from section 12.
- Tag `v2.0.0`. Pushing it runs the publish workflow, which uploads to the
  Gradle Plugin Portal and reads `.github/release-notes/v2.0.0.md` for the
  release body. That file is written and current. Cutting the tag is a
  publication, so it waits for a person.

Evidence: the existing release workflow passes end to end, including the
configuration-cache reuse check.

## 15. Out of scope for 2.0

| Item | Why not now |
|---|---|
| Per-store native default numbering | The current default works everywhere it is used. Changing it changes shipped apps. |
| `jvm { javaSource; release; tests { jdk } }` | Real requests, separate design. |
| Dark logo variants, offsets, trimming, per-OS desktop icons | Need consumers and a fallback contract first. |
| `splash.foregroundScale` | Needs its own reference canvas per renderer. |
| Per-platform generated constants in `buildConfig` | Separate generation model. |
| Write-ahead journal surviving a killed process | Staged writes already leave files whole. Cross-file recovery is a follow-up. |
| The xcconfig and scheme pre-action setup | The spike in section 8 proves it works. Delivering it means owning a scheme file, deleting four keys people committed, and paying a Gradle call per Xcode build. That is a feature with its own design, not a corner of this one. |
| Gradle Isolated Projects | The read-back view reads across projects. Same as 1.0.0. |
| Compatibility shims for 1.0.0 names | One migration table, one hard break. |
| Per-flavor, per-build-type, or per-Xcode-configuration identity | One identity per platform is the 2.0 model. |
| Generated or binary `Info.plist` | Source XML only, as today. |
| SVG or vector icon input, dark or tinted Apple icons | As today. |
| Store APIs, signing, uploads, `pod install`, automatic version bumps | Never in scope. |
| Windows and Linux build numbers, desktop locale metadata | jpackage has no field for the first; the second is one raw XML string the user may own. |

## 16. Acceptance checklist

- [ ] The minimal example builds an Android app, an iOS framework, and a
      desktop package from a fresh KMP wizard project with no other
      configuration. **Not verified.** Every piece has a fixture, but nothing
      runs the whole wizard project end to end, so this is a claim the test
      suite does not make.
- [x] `appId` is the only identifier name in the DSL, the read-back view, the
      diagnostics, and the docs.
- [x] Every shared topic has the same name and shape at the root and inside
      each platform block; a platform value beats a root value regardless of
      declaration order.
- [x] `ios { jvm { } }` does not compile. `android { skip(ios) }` does not
      compile.
- [x] An empty or art-only `splash { }` produces no output on any platform.
- [x] Root `skip(desktop)` wins over `desktop { splash { enabled = true } }`
      and reads no desktop provider.
- [ ] `logo { foreground }` plus `autoApply = true` installs and references the
      icon on Android, iOS, and desktop with no other line. **Installs, does not
      reference.** Android still needs `android:icon="@mipmap/ic_launcher"` in
      the manifest, for the reason section 8 gives: an overlay attribute naming
      a resource that `kiteApply` has to install first would turn a plain build
      into an `aapt2` error. The doctor still checks that line.
- [x] `splash { enabled = true }` plus `autoApply = true` installs the launch
      screen on every platform; setting it back to false removes the plugin's
      own integration on the next apply.
- [x] No example in the docs contains `rewrite`, a per-installer flag, or a
      manual task name, except the section that describes `kiteApply`.
- [x] `VersionSchemes.DEFAULT` produces the same numbers as 1.0.0 on every
      platform.
- [x] A module with Java and Kotlin sources compiles with `jvm { target }`.
- [x] Re-running `kiteApply` on an applied tree changes no file and no
      timestamp.
- [x] An Android build never modifies an iOS file.
- [x] Section 8 records the measured Xcode behavior, first and second build.
- [x] `kiteCheck`, `kiteDoctor`, `kitePlan`, `kiteVerify`, and a normal build
      report the same findings, and none of them writes a source file.
- [x] `./gradlew build`, `checkKotlinAbi`, `agpCompatibilityTest`, and a
      second configuration-cache run all pass.

## 17. Decisions and why

Choices made while turning the design conversation into this document. Each
one is final unless implementation proves it impossible.

- **Platform blocks instead of `override(PLATFORM)`.** One place per platform
  is easier to read than "overrides here, native settings there". Typed blocks
  also reject a setting at compile time, which an enum-based receiver cannot.
- **No `clear()` operation.** The only leaves where clearing mattered
  (`buildNumber`, `publishedBuildNumber`, `suffix`) are platform-only now, so
  a shared pin cannot trap another platform. Three-state leaves are not worth
  their explanation.
- **One activation rule instead of a table to memorize.** Section 4.4's table
  is derived from "on when you gave it what it needs; otherwise `enabled`".
- **Keep the flat read-back view.** It shipped a week ago and works. Rename one
  member and add four.
- **Keep the default version scheme.** Text at the boundary, same digits.
  Native defaults per store are a separate decision with migration cost.
- **`jvm` gets `toolchain` and `target` only.** That fixes the real defect and
  covers what templates already do. The rest is a later design.
- **`renameSharedModule` stays in `ios { }`.** A `migrations { }` umbrella
  for one function is not worth a block.
- **`onConflict` and `cleanPlist` go.** KiteConfig sets the plist keys it
  manages and never touches the rest. Your declared value is the source of
  truth; that is the point of the plugin.
- **`deploymentTarget` goes.** It never set the Xcode value, and the check it
  fed can read the project instead.
- **`autoApply` needs no opt-in annotation.** It is the headline feature, not
  a discouraged escape hatch. The CI story in section 6.5 covers the "dirty
  checkout" concern.
- **Journal deferred.** Staged single-file writes already leave the tree whole
  after a kill. Multi-file recovery is a nice follow-up, not a 2.0 gate.
- **2.0.0, not 1.1.0.** The rename table alone is a breaking change. Pretending
  otherwise helps nobody.
- **Platform blocks do not "nest the truth".** An older design warned that a
  shared fact declared in two places drifts. A platform block holds a
  difference, not a copy: the root value is still the truth, and the block
  says where one platform departs from it. A platform value that merely
  repeats the root value is pointless, and `kiteDoctor` says so.
- **The escape hatch stays a DSL property.** `ignoreVersionGuards` is read
  lazily inside each subproject's plugin callback, after the root script ran,
  so the old worry that it is read too early does not apply.

## 18. Documents this replaces

Every older planning, audit, and ledger document was read and checked against
the source, then deleted. What was worth keeping is in this file: the design in
sections 1 to 12, the defect evidence in section 19, the test techniques in
section 20.

| Document | Why it went | What survived, and where |
|---|---|---|
| `ASTRA_RESHAPE.md` | This document is its successor. | The contract, the activation rules, the version and JVM direction, the audit carry-forward, the honest iOS gate. |
| `OVERHAUL.md` | Describes a DSL two renames ago, and claims the shipped code matches it, which was false. | The rule that an explicit off always wins. The value-origin idea behind section 11. |
| `SOLAUDIT.md` | Every file and line reference in it was dead after the rename. | Its still-open items: one validation engine, text build numbers for Apple, the DSL marker, JVM target versus toolchain, lazy values for every generated field type, iOS numbers in the generated constants, relative paths in reports, error messages naming DSL paths that exist, installers that re-render on every run. Sections 5, 7, 11, 14. |
| `docs/audits/2026-09-05-deep-audit.md` | Its findings are the phase 0 work list, and `docs_dir` was publishing it to the public site. | Section 13 lists the defects. Section 19 carries the evidence for each one. |
| `docs/superpowers/plans/2026-09-05-deep-audit-fixes.md` | Never executed. Its API decisions (keep `id`, splash on by content, keep the old counter words) are overruled here. | Its test and safety techniques, in section 20. |
| `specs/2026-08-24-desktop-block-*.md` | Shipped in 3.1.0. | The desktop invariants in section 7.1. |
| `specs/2026-08-28-dsl-reshape-*.md` | Shipped in 4.0.0. Its three rules are replaced by section 4. | The reasoning that output under `build/` needs no consent, kept in section 6.1. |
| `specs/2026-08-29-splash-plan.md` | Shipped in 4.0.0. | The launch storyboard rule in section 5.5 and the missing end-to-end fixtures in phase 2. |
| `docs/superpowers/specs/2026-08-31-kiteconfig-1.0.0-design.md` and its plan | Shipped as 1.0.0. | The read-back contract, kept as is in section 9. |
| `.superpowers/sdd/` | Git-ignored execution residue. | Its four open notes: duplicated plist helpers in the iOS splash code, no scan for orphaned assets, the storyboard warning, desktop run tasks matched by name. Sections 5.5, 7, 14. |

Kept, untouched by this reshape:

- `FEATURES.md`, the behavior reference for 1.0.0 and the checklist for what
  phase 6 rewrites. Three DSL paths in it are wrong and two entries are
  missing; phase 6 fixes them.
- `CHANGELOG.md`, history. One dangling reference to a removed section near
  the bottom to clean up.
- `.github/release-notes/v1.0.0.md`, published. Its Compose range is wrong: the
  plugin accepts 1.11 to 1.12, not 1.9 to 1.12.

## 19. Defect evidence for phase 0

The fifteen defects in section 13, with the evidence behind each one. All were
reproduced against this baseline. None has a fix commit. Line numbers are from
the baseline and will drift as phase 0 lands.

**iOS splash overwrites artwork it does not own.**
`IosSplashAssets.kt:113` walks the destination files directly, and the PNG
branch at line 126 writes without a backup or an ownership check. An existing
hand-made splash PNG is replaced even with backups on. The probe reported the
unowned file overwritten and no backup written. Fix: install through the same
ownership machinery every other installer uses.

**A failed splash leaves the project half-changed.**
`SyncIosConfigTask.kt:256` commits the plist and project edits, then line 265
starts installing assets, and the image is not decoded until line 499. With a
corrupt PNG the run ends with the plist pointing at a splash asset that does
not exist. A second probe blocked the second asset with a directory: the first
asset stayed on disk. Fix: render and validate everything, then commit as one
group.

**Platform exclusions do not reach the logo installers.**
`KiteConfigPlugin.kt:1119` enables the iOS icon task from the two rewrite
consents alone, and line 1138 enables the Android one from logo consent alone.
`KiteConfigExtension.kt:666` shows why: the effective flag is just "rewrite is
armed". Line 1100 passes the same ungated value to the Xcode task. So
`logo { only(android) }` still installs the iOS icon. Fix: one effective
predicate per topic and platform, used by validation, task conditions, metadata,
dependencies, and diagnostics alike.

**Detected locales arrive after Android needs them.**
`ClassicAndroidWiring.kt:87` reads the locale list inside AGP's DSL
finalization. The auto-detected shared module is not assigned until
`KiteConfigPlugin.kt:476`, which runs later. With one Android app, one shared
module holding the resources, and resource filtering on, even `./gradlew help`
fails with "requires at least one canonical locale". Naming the shared module
by hand fixes it. Fix: resolve the module before AGP asks, or fail early with
the two lines that fix it.

**The strict check passes a model the build rejects.**
`KiteConfigPlugin.kt:182` returns before validation on diagnostic invocations,
and the diagnostic path has no input for the JVM level that line 245 validates.
With an invalid JVM level, `kiteCheck` reports zero errors and succeeds while an
ordinary build fails. Fix: one validation layer for both.

**The JVM level aligns Kotlin only.**
`KotlinJvmTargetWiring.kt:23` sets the Kotlin compiler option and nothing else.
Outside Android there is no Java side. On JDK 21 with Java and Kotlin sources
and the level set to 17, Java compiled to 21, Kotlin to 17, and the build failed
on inconsistent targets. The property KDoc at `KiteConfigExtension.kt:245` calls
it the Java and Kotlin level for the whole build. Fix: set both.

**The Xcode parser reads quoted punctuation as structure.**
`PbxprojTargetScope.kt:115`, 202, and 251 branch on a token's text without
checking that the token is a symbol. An app name that is exactly `}` or `;`
passes the name validator and writes successfully, and the next run rejects the
file it just produced as unbalanced. Fix: check the token kind at every
structural branch, and test parse, rewrite, parse, rewrite.

**Excluded platforms still enforce their rules.**
`KiteConfigPlugin.kt:208` validates the Android counter whenever a version
reaches any platform, and lines 209 to 212 validate both identifiers whenever
an identifier reaches any platform. So an iOS-only identifier with a hyphen
fails Android's rule. Worse, the error suggests a call that does not compile,
because the platform enum cannot be passed to the exclusion functions. Fix in
2.0: validate per delivery, and make the enum a valid argument.

**Nested blocks reach outside themselves.**
There is no DSL marker anywhere in the source. Both of these compile today and
neither does what the indentation suggests: a platform block calling the root
exclusion function, and a version corner nested inside another corner writing
the sibling's counter. Fix: mark every receiver, add tests that assert the bad
forms fail to compile and the documented forms still succeed.

**The version guard override misses one adapter.**
`KiteConfigPlugin.kt:412` checks only whether the adapters are supported, while
the classic Android paths at lines 382 and 396 also honor the override. On an
untested AGP with the override on, the build succeeded, said the typed
integrations stayed active, and the module kept its own SDK levels. Fix: one
gate function for all six branches.

**A dark-only Android splash color is written and never used.**
`GenerateAndroidSplashTask.kt:105` builds the Android 12 style from the light
color, lines 106 and 107 write the night color, and line 142 only references a
color when a light one exists. With art, a parent theme, and a dark color but no
light color, the night color is unreferenced. Fix: reference it from a night
style, or require a light color with a clear error.

**The documented compatibility command fails to store its configuration cache.**
`build.gradle.kts:255` captures a build-script variable inside a task action. All
nine compatibility tests pass and the build then fails while storing the cache.
Fix: pass the jar through a declared input provider instead.

**Property-style configuration leaves generation off.**
`KiteConfigExtension.kt:353` exposes the mutable generated-config object, but
only the block form at line 357 marks it declared. So adding a field through the
object generates nothing, while adding an empty block generates everything. The
same split exists for the logo, the opt-ins, and the browser worker. This is
what convention plugins hit. Fix: activation reads what was declared, not which
syntax was used.

**The promised duplicate-value warning does not exist.**
`KiteConfigExtension.kt:176` records duplicate declarations into a set that is
initialized, frozen, and never read. The README promises the warning. Fix in
2.0: drop the promise.

**Removing dark iOS artwork leaves the old file behind.**
`IosSplashAssets.kt:98` drops the dark image from the next output list, and
nothing reconciles the previous list. Installing with a dark variant and then
without leaves the dark PNG on disk after its catalog entry is gone. Fix:
remove obsolete owned files inside the same transaction.

Baseline verification, for comparison after phase 0: 410 tests in 51 classes,
no failures, with the configuration cache on and problems set to fail. The
compatibility suite passes its nine tests and then fails the enclosing build,
which is the twelfth defect above.

## 20. Techniques worth reusing

From the unexecuted fix plan. These are how to build and prove the phases, not
what to build.

**Safety mechanics**

- Give each install location its own ownership namespace, derived from a hash
  of its project-relative path. Two catalogs with the same directory name then
  cannot claim each other's files.
- A rollback entry asserts the bytes the transaction itself wrote. If someone
  edited the file in between, the rollback stops and names the file instead of
  destroying their edit.
- Write each source-changing task as four phases in order: prepare, commit
  text, install assets, roll back on failure. No validation after the first
  write.
- The preview task must list the whole footprint, not only the outputs: the
  ownership receipt, its lock file, and the recovery directory are touched too.

**Validation**

- Validators return findings instead of throwing. An ordinary build re-throws
  the first one with its original message; the diagnostics render all of them.
  One implementation, three presentations.
- Findings must be serializable so they survive the configuration cache.
- When moving validation into its own layer, the existing test suite is the
  proof. Messages must come out byte-identical. A failure there means a message
  drifted during the move; fix the message, not the test.
- A warning must never fail the report that renders it. Wrap every diagnostic
  input so a failure becomes a finding.
- When nothing failed, print an explicit pass line. Silence and "never ran"
  look the same.

**Tests**

- Test the DSL marker with real Gradle scripts that must fail to compile, and
  pair every one with a positive test that the documented form still works. A
  marker that silently invalidates the documented surface is worse than no
  marker.
- Test platform exclusion by putting a hand-written file in the excluded
  platform's tree, running the real task, and asserting the bytes did not
  change. A model-level test cannot cover this.
- Guard fixtures that need the Android SDK on an environment check so they run
  where an SDK exists and skip cleanly elsewhere. That is the only way to prove
  a generated resource actually compiles.
- Prove the configuration cache is reused, not merely stored. Run twice and
  look for the reuse line.
- Assert that an ordinary build changes no tracked file: build, then check the
  working tree is clean.
- Compile the README examples in CI, not only the KDoc ones.
- The most valuable regression shape: run one small configuration through
  resolution, an ordinary build, every diagnostic task, and the platform tasks,
  then compare the outcomes. That tests the promises instead of testing each
  layer's private opinion.
