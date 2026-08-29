# Splash Topic Implementation Plan

**Goal:** Implement `splash { }` per specs/2026-08-28-dsl-reshape-design.md, section "Splash (new topic)".

**Architecture:** Phase 1 (controller, inline) lands the DSL scope, the internal model, and inert task shells so every later lane compiles against fixed names. Phase 2 fans out three parallel Opus 5 lanes on disjoint files: Android generator, desktop generator, iOS rewrite. Phase 3 (controller, inline) integrates, wires diagnostics and docs, runs the full suite, commits.

**Rules for lane agents:** no gradle invocations, no git commands, no edits outside the lane's listed files. Code plus tests only; the controller compiles, tests, reviews, and commits centrally.

## Design decisions binding all lanes

- Facts: `image` (default `logo.foreground`), `backgroundColor` (default `logo.backgroundColor`), optional `dark { image, backgroundColor }`.
- Android corner: `splash { android { theme = "AppTheme" } }` names the app's existing theme. The generated style `KiteSplash` inherits it, and only the `values-v31` variant adds the native Android 12 splash attributes, so pre-12 devices get a pure alias and zero behavior change. No androidx dependency, no app code.
- Android delivery: generated res under `build/generated/kitessot/splash-res/` wired into the app module as a res dir, plus the existing manifest-placeholder mechanism sets `kiteSplashTheme` to `@style/KiteSplash`. The user adds `android:theme="${kiteSplashTheme}"` once; `kiteDoctor` checks it.
- Desktop delivery: one composed PNG (background color plus centered image) under `build/`, wired as JVM `-splash:` via Compose Desktop `jvmArgs` and `appResourcesRootDir`. Drift-observe if the user already set either.
- iOS delivery: `UILaunchScreen` dict (UIColorName `KiteSplashBackground`, UIImageName `KiteSplashImage`) written by the existing plist transaction inside `SyncIosConfigTask`, plus a color set and an image set written into the asset catalog (parent directory of `appIconDirectory`). Dark appearances included when `dark { }` is set. Requires BOTH `ios { rewrite { } }` and `splash { rewrite { } }` armed.
- Gates: `effectiveAndroidSplash` = declared AND flows(ANDROID). `effectiveDesktopSplash` = declared AND flows(DESKTOP). iOS = `splashRewriteArmed` AND `effectiveSyncIos`.
- Missing art (no splash image and no logo foreground) fails at task time naming both `splash { }` and `logo { }`.

## Phase 1 (controller): scope and model

Create `KiteSplashScope.kt`: extends `KiteFlowScope`; `image: RegularFileProperty`, `backgroundColor: Property<String>`, `dark(action)` nested `DarkVariant { image, backgroundColor }`, `android(action)` corner `{ theme: Property<String> }` implementing `KitePlatformRef`, `rewrite(action)` arming `splashRewriteArmed`, internal `declared`.
Extension: `fun splash(action)` sets declared; model getters `effectiveSplashImage`, `effectiveSplashColor`, `effectiveSplashDarkImage`, `effectiveSplashDarkColor`, `effectiveSplashAndroidTheme`, `effectiveAndroidSplash`, `effectiveDesktopSplash`, `effectiveIosSplash`, `splashFlowsTo(p)`. Register scope in the plugin, add members to the finalize list. Unit tests in `SplashScopeTest.kt`. Commit.

## Phase 2 lanes (parallel agents)

### Lane A: Android
- Create: `GenerateAndroidSplashTask.kt` (inputs: image, color, dark color/image, theme name, icon safe scaling via `ImageOps`; outputs: `values/kitessot_splash.xml`, `values-v31/kitessot_splash.xml`, `values-night-v31/` when dark set, `drawable-*dpi/kite_splash_icon.png` set, plus `@color/kite_splash_bg`).
- Modify: `ClassicAndroidWiring.kt` only in a clearly marked new function: register the task per selected app module, add the generated dir as a res dir, set manifest placeholder `kiteSplashTheme`.
- Test: `AndroidSplashGenTest.kt` (task-level, ProjectBuilder, no GradleRunner): XML content, v31 attribute set, dark variant presence, icon files produced, missing-art failure message names both blocks.

### Lane B: Desktop
- Create: `GenerateDesktopSplashTask.kt` (compose PNG from color plus centered image; reuse `ImageOps`).
- Modify: `DesktopWiring.kt` only in a clearly marked new function: register the task, wire the output into `appResourcesRootDir` and add `-splash:$APPDIR/resources/...` to `jvmArgs`, drift-observe existing values, gate on `effectiveDesktopSplash`.
- Test: `DesktopSplashGenTest.kt`: PNG produced with expected dimensions and background pixel, missing-art failure, gate off produces nothing.

### Lane C: iOS
- Create: `IosSplashAssets.kt` (writes `KiteSplashBackground.colorset/Contents.json` with srgb components and optional dark appearance, `KiteSplashImage.imageset/Contents.json` plus the PNG; color parsing may reuse `LogoBackgroundColor` helpers).
- Modify: `SyncIosConfigTask.kt`: new optional inputs (splash on, image, colors); inside the existing plist transaction write the `UILaunchScreen` dict honoring `onConflict`; call the asset writer; extend the plan output lines.
- Test: `IosSplashTest.kt`: plist dict written and conflict-policy honored, Contents.json shapes, dark appearance included only when set, disarmed splash leaves everything untouched.

## Phase 3 (controller): integration

Wire task inputs in `KiteSsotPlugin.kt`, diagnostics (`KMPS09x`: per-platform splash status, manifest placeholder check, theme-corner-missing, ios-needs-both-rewrites), doctor manifest scan, 2 functional fixtures (android res generation end to end, ios plist via `kiteRewriteXcode`), splash slice into the extension KDoc surface and scope KDoc table, README surface block line, spec sync if reality diverged. Full suite plus `./gradlew build`. Commits per coherent chunk.
