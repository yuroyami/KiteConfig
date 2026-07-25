<div class="kite-hero" markdown>

# KiteSSOT

One place to declare a Kotlin Multiplatform app's identity — name, version,
bundle ID, locales, SDK levels — propagated to the Android, Xcode and Kotlin
files that each normally keep their own copy of it.

<div class="kite-hero-actions" markdown>
[Get started](#the-shortest-setup-that-does-something){ .kite-primary }
[API reference](api/)
[Plugin Portal](https://plugins.gradle.org/plugin/io.github.yuroyami.kitessot)
[GitHub](https://github.com/yuroyami/KiteSSOT)
</div>

</div>

A typical KMP repo writes its app name and version down four times: the Android
module's `defaultConfig`, the Xcode project's build settings, the source
`Info.plist`, and whatever constant the shared Kotlin code reads. Nothing keeps
them in agreement, so eventually they disagree — usually the week you ship.

## The shortest setup that does something

In the **root** `build.gradle.kts`:

```kotlin
plugins {
    kotlin("multiplatform") version "2.4.10" apply false
    id("com.android.application") version "9.2.1" apply false
    id("io.github.yuroyami.kitessot") version "2.0.2"
}

kiteSsot {
    appName = "Jetzy"
    versionName = "1.4.0"
    bundleIdBase = "com.example.jetzy"

    android {
        compileSdk = 36
        minSdk = 26
        targetSdk = 36
    }
}
```

Then:

```bash
./gradlew kiteSsotVerify
```

That prints the resolved model and writes nothing. It is the right first
command, and the right one to come back to whenever a value is not where you
expected.

!!! warning "Two preconditions that are easy to miss"
    **The plugin goes on the root project.** Applying it in a submodule throws
    immediately — it aggregates across `allprojects` from the root.

    **`apply false` on the Kotlin and Android lines is load-bearing.** KiteSSOT's
    KGP- and AGP-typed integrations are guarded on those plugin classes being
    loadable from KiteSSOT's own classloader. Declare
    `kotlin("multiplatform")` only inside a subproject and KGP lands in a
    sibling classloader that KiteSSOT cannot see.

## The one concept worth understanding first

KiteSSOT splits its work into two tiers, and the split is the whole design.

**Gradle configuration is automatic and continuous.** Android identity and SDK
levels, Java and Kotlin JVM alignment, and generated Kotlin under `build/` are
applied on every build. This happens inside AGP's `finalizeDsl` hook, which runs
after a module's own `android { }` block — so a value set in `kiteSsot { }` wins
over the same value set locally. Set the value, set the switch, done.

**Edits to files you own are opt-in and manual.** `project.pbxproj`,
`Info.plist`, `Podfile`, Swift imports and launcher icons are yours. Touching
them needs an authorization gate (`syncIos`, `propagateLogo`, …), *and* an
explicitly named task that you run yourself, *and* it passes containment,
ownership, checksum, backup and rollback checks first.

The consequence people trip on: setting `propagateLogo = true` installs nothing.
It unlocks `kiteSsotSyncIosLogo`; you still run that task. A plain
`./gradlew build` never writes outside `build/`, and CI asserts it on every
commit.

Run `./gradlew kiteSsotPlan` to see exactly which mutations your current
configuration authorizes, and the exact paths they would touch, before running
any of them. Set `dryRun = true` to make the mutating tasks report without
writing.

## Where things live

<div class="kite-cards" markdown>

<a class="kite-card" href="https://github.com/yuroyami/KiteSSOT#readme">
<strong>README</strong>
<span>The full guide: DSL reference, the task table, compatibility, and the current limits.</span>
</a>

<a class="kite-card" href="api/">
<strong>API reference</strong>
<span>Every extension property and task type, generated from source.</span>
</a>

<a class="kite-card" href="https://github.com/yuroyami/KiteSSOT/blob/main/FEATURES.md">
<strong>FEATURES.md</strong>
<span>Behaviour reference: what each switch does, and every default value.</span>
</a>

<a class="kite-card" href="https://github.com/yuroyami/KiteSSOT/blob/main/CHANGELOG.md">
<strong>Changelog</strong>
<span>Release history, including the 1.x to 2.x migration.</span>
</a>

</div>

## Compatibility

Gradle 8.5 and newer, AGP 8.5.2 through 9.2.x, KGP 2.4.x, on a JDK 17 or 21
daemon. A dedicated `agpCompatibilityTest` builds real consumer projects on
Gradle 8.5, 8.9 and 9.5.1 against AGP 8.5.2 and 9.2.1. CI runs on Linux with
JDK 17 and 21, macOS with JDK 21 and Windows with JDK 21, each twice, asserting
the configuration cache entry is reused and the tracked working tree is
unchanged afterwards.
