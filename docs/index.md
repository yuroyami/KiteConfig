<div class="kite-hero" markdown>

# KiteConfig

One place to declare a Kotlin Multiplatform app's identity: name, version, bundle
ID, locales and SDK levels. KiteConfig propagates it to the Android, Xcode and
Kotlin files that each normally keep their own copy.

<div class="kite-hero-actions" markdown>
[Get started](#the-shortest-setup-that-does-something){ .kite-primary }
[API reference](api/)
[Plugin Portal](https://plugins.gradle.org/plugin/io.github.yuroyami.kiteconfig)
[GitHub](https://github.com/yuroyami/KiteConfig)
</div>

</div>

A typical Kotlin Multiplatform repo records its app name and version in four
places:

- the Android module's `defaultConfig`
- the Xcode project's build settings
- the source `Info.plist`
- whatever constant the shared Kotlin code reads

Nothing keeps those four copies in agreement, so eventually they stop matching.

## The shortest setup that does something

In the **root** `build.gradle.kts`:

```kotlin
plugins {
    kotlin("multiplatform") version "2.4.10" apply false
    id("com.android.application") version "9.3.1" apply false
    id("io.github.yuroyami.kiteconfig") version "1.0.0"
}

kiteConfig {
    appName = "Jetzy"
    version = "1.4.0"
    id = "com.example.jetzy"

    android {
        compileSdk = 36
        minSdk = 26
        targetSdk = 36
    }
}
```

Then:

```bash
./gradlew kiteVerify
```

That prints the resolved model and writes nothing. Run it again whenever a value
is not where you expected it.

!!! warning "Two required preconditions"
    **The plugin goes on the root project.** Applying it in a submodule throws
    immediately, because it aggregates across `allprojects` from the root.

    **Add `apply false` to the Kotlin and Android plugin lines.** KiteConfig
    integrates with typed classes from KGP (the Kotlin Gradle plugin) and AGP
    (the Android Gradle plugin). Those integrations run only when KiteConfig can
    load the plugin classes from its own classloader. Declare
    `kotlin("multiplatform")` only inside a subproject and Gradle loads KGP with
    a different classloader. KiteConfig cannot read the plugin classes from there.

## Two tiers of switch

KiteConfig splits its work into two tiers.

**Gradle configuration is automatic and continuous.** On every build, KiteConfig
applies the Android identity and SDK levels, aligns the Java and Kotlin JVM
targets, and generates Kotlin under `build/`. This happens inside AGP's
`finalizeDsl` hook, which runs after a module's own `android { }` block. A value
set in `kiteConfig { }` therefore replaces the same value set in the module. Set
the value. Nothing else is needed.

**Edits to files you own are opt-in and manual.** `project.pbxproj`,
`Info.plist`, `Podfile`, Swift imports and launcher icons are yours. Editing them
takes three things:

1. The block that unlocks the task. Writing `ios { rewrite { } }` arms the Xcode
   tasks, and the app icon task needs a `logo { }` block as well.
2. An explicitly named task that you run yourself.
3. A set of containment, ownership, checksum, backup and rollback checks, which
   must all pass first.

In 3.0 the block **is** the switch. There is no separate `= true` flag: an empty
`logo { }` counts as on, and leaving the block out counts as off.

This surprises people. Adding `logo { }` installs nothing. It unlocks
`kiteRewriteLogo` only when you also add `logo { rewrite { } }`, an `ios { rewrite { } }` block, and set
`ios { deploymentTarget }`, and you then run that task yourself. A plain
`./gradlew build` never writes outside `build/`, and CI asserts that on every
commit.

Run `./gradlew kitePlan` before you run any mutating task. It lists which
mutations your current configuration authorizes, and the exact paths they would
change. Set `dryRun = true` to make the mutating tasks report without writing.

## Where things live

<div class="kite-cards" markdown>

<a class="kite-card" href="https://github.com/yuroyami/KiteConfig#readme">
<strong>README</strong>
<span>The full guide: DSL reference, the task table, compatibility, and the current limits.</span>
</a>

<a class="kite-card" href="api/">
<strong>API reference</strong>
<span>Every extension property and task type, generated from source.</span>
</a>

<a class="kite-card" href="https://github.com/yuroyami/KiteConfig/blob/main/FEATURES.md">
<strong>FEATURES.md</strong>
<span>Behavior reference: what each switch does, and every default value.</span>
</a>

<a class="kite-card" href="https://github.com/yuroyami/KiteConfig/blob/main/CHANGELOG.md">
<strong>Changelog</strong>
<span>Release history, including the 2.x to 3.0 migration.</span>
</a>

</div>

## Compatibility

Gradle 8.5 and newer, AGP 8.5.2 through 9.3.x, KGP 2.4.x, on a JDK 17 or 21
daemon.

A dedicated `agpCompatibilityTest` builds real consumer projects on Gradle 8.5,
8.9 and 9.5.1, against AGP 8.5.2 and 9.3.1. CI runs on Linux with JDK 17 and 21,
on macOS with JDK 21, and on Windows with JDK 21. Each build runs twice. CI then
checks that the second run reuses the configuration cache entry, and that no
tracked file changed.
