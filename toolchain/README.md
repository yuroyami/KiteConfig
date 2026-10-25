# KiteConfig for the Kotlin Toolchain

The Kotlin Toolchain is the `./kotlin` command line tool that builds a project from `project.yaml` and `module.yaml` files (it was called Amper before).
This folder holds a KiteConfig plugin for it.
The plugin reads one `plugins: kiteconfig:` block in a `module.yaml` and does three things:

- It generates the `KiteBuildConfig` object for the module.
- It writes the version, the build number, the bundle id and the locales into an Xcode project.
- It checks that the Android settings of the module agree with the block.

The Gradle plugin and this plugin share the same code for these jobs.
So a version gives the same build number in both.

## What the plugin cannot do yet

- **It cannot set the Android settings.**
  A Toolchain plugin cannot change module settings.
  You write `applicationId`, `versionCode` and `versionName` yourself, and the `kiteAndroid` check compares them with the block.
- **It cannot be published.**
  The Toolchain loads plugins only from local modules.
  You copy the plugin module into your project.
- **It has no icons, launch screens, `Info.plist` changes, desktop packaging or `kiteDoctor`.**
  Use the Gradle plugin for these jobs.

## Add the plugin to a project

This needs a KiteConfig release newer than 2.1.0.

1. Copy the `kiteconfig/` folder of this directory into the root of your project.
2. Open `kiteconfig/module.yaml`.
3. Replace the `repositories` and `dependencies` entries with these lines:

   ```yaml
   repositories:
     - id: gradlePluginPortal
       url: https://plugins.gradle.org/m2

   dependencies:
     - io.github.yuroyami:kiteconfig:<version>
   ```

4. Add the module to `project.yaml`, under both `modules` and `plugins`:

   ```yaml
   modules:
     - kiteconfig
     - shared

   plugins:
     - ./kiteconfig
   ```

5. Enable the plugin in each module that needs it, as shown in the next section.

## The settings block

```yaml
plugins:
  kiteconfig:
    enabled: true
    appName: Jetzy
    version: 4.1.1
    rebuild: 1                 # default 0
    appId: com.example.jetzy   # androidApplicationId and iosBundleId fall back to it
    locales: [ en, pt-BR ]
    buildConfig:
      fields:
        API_URL: https://example.com/api
```

| Setting | Default | Meaning |
|---|---|---|
| `appName` | none | The app name in `KiteBuildConfig.appName`. |
| `version` | none | The marketing version, three plain numbers such as `4.1.1`. |
| `rebuild` | `0` | A counter for rebuilds of the same version, from 0 to 9. |
| `buildNumber` | unset | An exact build number. When set, it replaces the default layout. |
| `appId` | unset | The app id for every platform that has no id of its own. |
| `androidApplicationId` | `appId` | The Android application id. |
| `iosBundleId` | `appId` | The iOS bundle id. |
| `locales` | `[]` | BCP 47 locale tags, such as `en` or `pt-BR`. |
| `buildConfig.enabled` | `true` | Whether the module gets the generated object. |
| `buildConfig.packageName` | `kiteconfig.generated` | The package of the generated object. |
| `buildConfig.className` | `KiteBuildConfig` | The name of the generated object. |
| `buildConfig.fields` | `{}` | Extra `String` constants, by name. |
| `ios.xcodeProject` | unset | The `.xcodeproj` folder, relative to the module folder. |
| `ios.targets` | `[]` | The application targets to update. Empty means every application target. |
| `android.checkSettings` | `false` | Whether `./kotlin check` compares the Android settings with this block. |

The default build number is `1MMMmmmppr`: major, minor and patch, then the rebuild digit.
For example, `4.1.1` with `rebuild: 1` gives `1004001011`.
Set `buildNumber` when your version does not fit this layout.

To share one block between modules, put it in a module template (a `*.module-template.yaml` file) and list that file under `apply:` in each `module.yaml`.

## The generated object

The plugin writes `KiteBuildConfig` into the build folder and adds it to the module sources.
It has these values: `appName`, `versionName`, `versionCode`, `androidApplicationId`, `iosBundleId`, `locales`, and one `String` for each entry of `buildConfig.fields`.

```kotlin
import kiteconfig.generated.KiteBuildConfig

fun banner(): String = "${KiteBuildConfig.appName} ${KiteBuildConfig.versionName}"
```

Do not put secrets in the generated object. The values go into the compiled app.

## The Xcode project

Set `ios.xcodeProject`, then run this command:

```bash
./kotlin do kiteSyncIos
```

The command writes `MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`, `PRODUCT_BUNDLE_IDENTIFIER` and the known regions into `project.pbxproj`.
It keeps a backup next to the file, as `project.pbxproj.kiteconfig.bak`.
Add `*.kiteconfig.bak` to your `.gitignore`.
Commit the changed `project.pbxproj`.

The `kiteIos` check runs in `./kotlin check`.
It fails when the file does not match the block and tells you to run `./kotlin do kiteSyncIos`.
The check never writes.

## The Android settings

Set `android.checkSettings: true` in an `android/app` module.
Then `./kotlin check` runs the `kiteAndroid` check.
When a value does not match, the check fails and prints the lines to write:

```text
settings: android: in module.yaml does not match plugins: kiteconfig. Write these lines:
settings:
  android:
    versionCode: 1004001011
Found: versionCode: 1.
```

The check is off by default because the Toolchain does not tell a plugin whether a module is an app or a library.

## Commands and checks

| Name | Run it with | What it does |
|---|---|---|
| `kiteSyncIos` | `./kotlin do kiteSyncIos` | Writes the block into the Xcode project. |
| `kiteIos` | `./kotlin check` | Fails when the Xcode project does not match the block. |
| `kiteAndroid` | `./kotlin check` | Fails when the Android settings do not match the block. |

## Work on the plugin in this repository

This directory is a Toolchain project with the plugin and two sample modules.
`sample` is a library with an Xcode project. `sample-android` is an Android app.

1. Run `./stage-local-jar.sh`. It builds the Gradle plugin jar as `0.0.0-dev` and installs it into `~/.m2`.
2. Run `./kotlin test -m kiteconfig`.
3. Run `./kotlin check`.

Run `./stage-local-jar.sh` again after each change to `kiteconfig-core`.
