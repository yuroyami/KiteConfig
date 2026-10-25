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
- **It cannot be loaded from a repository.**
  The Toolchain loads plugins only from modules inside the project.
  So the plugin module lives in your project, and `install-kiteconfig.sh` copies it there and keeps it up to date.
- **It has no icons, launch screens, `Info.plist` changes, desktop packaging or `kiteDoctor`.**
  Use the Gradle plugin for these jobs.

## Add the plugin to a project

This needs KiteConfig 3.0.0 or newer.

1. Open a terminal in the folder that holds `project.yaml`.
2. Run the install script:

   ```bash
   curl -fsSL https://raw.githubusercontent.com/yuroyami/KiteConfig/main/toolchain/install-kiteconfig.sh | bash
   ```

   The script installs the plugin module of the latest release in `./kiteconfig`.
   To install one release, end the command with `bash -s 3.0.0` instead of `bash`.

3. Add the module to `project.yaml`, under both `modules` and `plugins`.
   The script prints these lines when they are missing:

   ```yaml
   modules:
     - kiteconfig
     - shared

   plugins:
     - ./kiteconfig
   ```

4. Enable the plugin in each module that needs it, as shown in the next section.
5. Commit the `kiteconfig/` folder with your project.

### Update the plugin

Run the install script again.
It replaces `./kiteconfig` with the plugin module of the new release.
Do not edit the files in `./kiteconfig`, because the next update replaces them.

### What the script does

- It downloads the source archive of the release from GitHub.
- It copies the plugin module (`toolchain/kiteconfig/`, without its tests) into `./kiteconfig`.
- It points `kiteconfig/module.yaml` at the released KiteConfig jar on the Gradle Plugin Portal.
- It replaces `./kiteconfig` only when that folder holds the KiteConfig plugin module.

The script needs `bash`, `curl` and `tar`. On Windows, run it in Git Bash.
To read the script before you run it, download it first:

```bash
curl -fsSLO https://raw.githubusercontent.com/yuroyami/KiteConfig/main/toolchain/install-kiteconfig.sh
```

Then run `bash install-kiteconfig.sh`.

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

### The install script

Keep two lines of `kiteconfig/module.yaml` exactly as they are, with their two-space indent: the `- mavenLocal` repository and the `- io.github.yuroyami:kiteconfig:0.0.0-dev` dependency.
The install script replaces exactly these two lines, and it refuses a `module.yaml` without them.

`install-test/` is a new project for the install script.
CI copies it out of the checkout, runs `install-kiteconfig.sh` in it, and runs its tests against the latest release.
CI also runs the script on the plugin module of the checkout.
So a `module.yaml` change that the script does not know fails CI before the next release.
