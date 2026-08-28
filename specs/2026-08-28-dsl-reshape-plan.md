# KiteSSOT DSL Reshape Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This repo's owner forbids parallel subagents on Fable 5: execute inline, single-threaded.

**Goal:** Replace the 3.0 DSL surface with the topic-scoped, one-law surface from the spec, keeping the engine behind a stable internal model.

**Architecture:** New topic scope classes (appName, id, version, locales, logo) carry facts, platform corners, and flow modifiers. The root extension resolves everything into per-platform `effective*` providers. Engine files (wirings, tasks, diagnostics) keep consuming the internal model, now parameterized by platform. The 2.x layer and all enabled/propagate machinery are deleted outright.

**Tech Stack:** Kotlin Gradle plugin, lazy `Property`/`Provider` API, JUnit + Gradle `ProjectBuilder` unit tests, GradleRunner functional tests.

**Spec:** `specs/2026-08-28-dsl-reshape-design.md` (read it first, it contains the law, the full surface block, and the injectability matrix).

**Out of scope here:** the `splash { }` topic. It gets its own plan after this one lands.

## Global Constraints

- Version stays 3.x. Hard break: no deprecation bridge, no 2.x compatibility.
- Every fact stays a lazy Gradle `Property`/`Provider`. No eager resolution at configuration time.
- Internal model getter names stay stable where possible; consumers pass a `KitePlatform` where flow requires it.
- Platform tokens are lowercase: `android`, `ios`, `desktop`.
- `rewrite { }` is the only DSL word that arms source-editing tasks. Presence of any other block must never touch the source tree.
- One uniform internal presence flag per acting topic is allowed (it replaces the old enabled+configured pair, it does not reintroduce it).
- No em-dashes in any KDoc, docs, or commit text. Neutral tone.
- Commits: conventional prefix, no Co-Authored-By trailer, stay on `main`.
- After every task: run that task's tests. After tasks 10-13: run the full suite `./gradlew test`.
- KDoc code snippets must keep `KdocExampleCompilationTest` green; user-facing strings must keep `MessageHygieneTest` green.

---

### Task 1: Platform tokens and flow core

**Files:**
- Create: `src/main/kotlin/io/github/yuroyami/kitessot/KitePlatform.kt`
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/KiteFlowTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum class KitePlatform { ANDROID, IOS, DESKTOP }`; `interface KitePlatformRef { val platform: KitePlatform }`; `abstract class KiteFlowScope` with `fun skip(vararg refs: KitePlatformRef)`, `fun only(vararg refs: KitePlatformRef)`, `internal fun flowsTo(p: KitePlatform): Provider<Boolean>`. Every later topic scope extends `KiteFlowScope`; every corner and platform block implements `KitePlatformRef`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.yuroyami.kitessot

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KiteFlowTest {

    private abstract class TestScope : KiteFlowScope()
    private class Ref(override val platform: KitePlatform) : KitePlatformRef

    private fun scope(): KiteFlowScope =
        ProjectBuilder.builder().build().objects.newInstance(TestScope::class.java)

    @Test
    fun `default is flow everywhere`() {
        val s = scope()
        KitePlatform.entries.forEach { assertTrue(s.flowsTo(it).get()) }
    }

    @Test
    fun `skip stops one platform only`() {
        val s = scope()
        s.skip(Ref(KitePlatform.IOS))
        assertFalse(s.flowsTo(KitePlatform.IOS).get())
        assertTrue(s.flowsTo(KitePlatform.ANDROID).get())
        assertTrue(s.flowsTo(KitePlatform.DESKTOP).get())
    }

    @Test
    fun `only allows listed platforms only`() {
        val s = scope()
        s.only(Ref(KitePlatform.ANDROID), Ref(KitePlatform.DESKTOP))
        assertTrue(s.flowsTo(KitePlatform.ANDROID).get())
        assertFalse(s.flowsTo(KitePlatform.IOS).get())
        assertTrue(s.flowsTo(KitePlatform.DESKTOP).get())
    }

    @Test
    fun `skip beats only when both name the same platform`() {
        val s = scope()
        s.only(Ref(KitePlatform.ANDROID))
        s.skip(Ref(KitePlatform.ANDROID))
        assertFalse(s.flowsTo(KitePlatform.ANDROID).get())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.KiteFlowTest'`
Expected: compilation failure, `KiteFlowScope` not defined.

- [ ] **Step 3: Write the implementation**

```kotlin
package io.github.yuroyami.kitessot

import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty

/** The three delivery targets a fact can flow to. */
enum class KitePlatform { ANDROID, IOS, DESKTOP }

/** Anything that can stand for a platform in skip()/only(). */
interface KitePlatformRef {
    val platform: KitePlatform
}

/**
 * Base of every topic scope. skip()/only() beside the fact are the only
 * flow control in the DSL. Default: flow everywhere.
 */
abstract class KiteFlowScope {

    internal abstract val skipped: SetProperty<KitePlatform>
    internal abstract val allowed: SetProperty<KitePlatform>

    /** This fact does not flow to the given platforms. */
    fun skip(vararg refs: KitePlatformRef) = refs.forEach { skipped.add(it.platform) }

    /** This fact flows only to the given platforms. */
    fun only(vararg refs: KitePlatformRef) = refs.forEach { allowed.add(it.platform) }

    internal fun flowsTo(p: KitePlatform): Provider<Boolean> =
        skipped.zip(allowed) { s, o -> p !in s && (o.isEmpty() || p in o) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.KiteFlowTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/yuroyami/kitessot/KitePlatform.kt src/test/kotlin/io/github/yuroyami/kitessot/KiteFlowTest.kt
git commit -m "feat: add platform tokens and the flow scope core"
```

---

### Task 2: Rename the formula input field rebuild to reupload

**Files:**
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/VersionScheme.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/VersionCode.kt` (call sites)
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/VersionResolution.kt` (call sites, if it references the field)
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/VersionCodeTest.kt`

**Interfaces:**
- Consumes: the existing `VersionCodeScheme` fun interface and its parts type (open `VersionScheme.kt` to see the exact type name; the parts carry `major`, `minor`, `patch`, `rebuild`).
- Produces: the same parts type with the last field named `reupload`. Everything else unchanged.

- [ ] **Step 1: Update the test first**

In `VersionCodeTest.kt`, change every scheme lambda and assertion that references `rebuild` to `reupload`. Example of the shape after the change:

```kotlin
val scheme = VersionCodeScheme { v -> 100 * v.patch + v.reupload }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.VersionCodeTest'`
Expected: compilation failure, unresolved reference `reupload`.

- [ ] **Step 3: Rename the field**

In `VersionScheme.kt`, rename the parts field `rebuild` to `reupload` (keep KDoc, reword it to: "Counter to re-upload the same version to a store. Feeds the formula."). Fix all compile errors in `VersionCode.kt` and `VersionResolution.kt` by renaming references. `KiteSsotExtension.kt` still compiles because it passes the value positionally into `computeVersionCode`; if it names the field, rename there too.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.VersionCodeTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src/main/kotlin src/test/kotlin
git commit -m "feat: rename the formula rebuild input to reupload"
```

---

### Task 3: appName topic scope

**Files:**
- Create: `src/main/kotlin/io/github/yuroyami/kitessot/KiteAppNameScope.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotExtension.kt` (add the dual form and per-platform resolution next to the existing `appName` property)
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/AppNameScopeTest.kt`

**Interfaces:**
- Consumes: Task 1 (`KiteFlowScope`, `KitePlatformRef`, `KitePlatform`).
- Produces: `KiteAppNameScope` with `val android/ios/desktop: NameToken` where `NameToken` has `operator fun invoke(value: String)`; on the extension: `fun appName(value: String, action: Action<KiteAppNameScope>)`, `internal fun effectiveAppNameFor(p: KitePlatform): Provider<String>`, `internal fun appNameFlowsTo(p: KitePlatform): Provider<Boolean>`. Task 10 wires consumers to these.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.yuroyami.kitessot

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppNameScopeTest {

    private fun ext(): KiteSsotExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.github.yuroyami.kitessot")
        return project.extensions.getByType(KiteSsotExtension::class.java)
    }

    @Test
    fun `simple form flows the same value everywhere`() {
        val e = ext()
        e.appName.set("Jetzy")
        KitePlatform.entries.forEach {
            assertEquals("Jetzy", e.effectiveAppNameFor(it).get())
            assertTrue(e.appNameFlowsTo(it).get())
        }
    }

    @Test
    fun `detailed form sets base, overrides, and flow`() {
        val e = ext()
        e.appName("Jetzy") {
            ios("Jetzy Lite")
            skip(desktop)
        }
        assertEquals("Jetzy", e.effectiveAppNameFor(KitePlatform.ANDROID).get())
        assertEquals("Jetzy Lite", e.effectiveAppNameFor(KitePlatform.IOS).get())
        assertFalse(e.appNameFlowsTo(KitePlatform.DESKTOP).get())
        assertTrue(e.appNameFlowsTo(KitePlatform.ANDROID).get())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.AppNameScopeTest'`
Expected: compilation failure.

- [ ] **Step 3: Write the scope**

```kotlin
package io.github.yuroyami.kitessot

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import javax.inject.Inject

/**
 * Details for [KiteSsotExtension.appName]: per-platform name overrides
 * and flow modifiers. `android("x")` overrides the shown name there.
 */
abstract class KiteAppNameScope @Inject constructor(objects: ObjectFactory) : KiteFlowScope() {

    /** A platform word usable two ways: `ios("value")` and `skip(ios)`. */
    class NameToken internal constructor(
        override val platform: KitePlatform,
        internal val override: Property<String>,
    ) : KitePlatformRef {
        operator fun invoke(value: String) = override.set(value)
    }

    val android: NameToken = NameToken(KitePlatform.ANDROID, objects.property(String::class.java))
    val ios: NameToken = NameToken(KitePlatform.IOS, objects.property(String::class.java))
    val desktop: NameToken = NameToken(KitePlatform.DESKTOP, objects.property(String::class.java))

    internal fun overrideFor(p: KitePlatform): Property<String> = when (p) {
        KitePlatform.ANDROID -> android.override
        KitePlatform.IOS -> ios.override
        KitePlatform.DESKTOP -> desktop.override
    }
}
```

- [ ] **Step 4: Wire the extension**

In `KiteSsotExtension.kt`, next to the existing `appName` property, add (register `KiteAppNameScope` as a nested extension named `appNameScope` in `KiteSsotPlugin` the same way the existing sub-extensions are registered; find the registration site by grepping for the string that registers `modules`):

```kotlin
/** Detailed form of [appName]: overrides and flow modifiers. */
fun appName(value: String, action: Action<in KiteAppNameScope>) {
    if (appName.isPresent) doubleSetWarnings.add("appName")
    appName.set(value)
    action.execute(appNameScope)
}

internal val appNameScope: KiteAppNameScope
    get() = nested()

internal abstract val doubleSetWarnings: SetProperty<String>

internal fun effectiveAppNameFor(p: KitePlatform): Provider<String> =
    appNameScope.overrideFor(p).orElse(appName)

internal fun appNameFlowsTo(p: KitePlatform): Provider<Boolean> =
    appNameScope.flowsTo(p)
```

Keep the old `effectiveAppName`/`effectivePropagateAppName` members for now; Task 10 deletes them.

- [ ] **Step 5: Run test, then commit**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.AppNameScopeTest'`
Expected: PASS.

```bash
git add -A src/main/kotlin src/test/kotlin
git commit -m "feat: add the appName topic scope with overrides and flow"
```

---

### Task 4: id topic scope

**Files:**
- Create: `src/main/kotlin/io/github/yuroyami/kitessot/KiteIdScope.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotExtension.kt`
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/IdScopeTest.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces: extension property `val id: Property<String>` and `fun id(value: String, action: Action<KiteIdScope>)`; `KiteIdScope` with corners `android/ios/desktop` each exposing `val suffix: Property<String>` plus `fun android(action: Action<IdCorner>)` accessors; per-platform resolution `internal fun effectiveIdFor(p: KitePlatform): Provider<String>` (base + suffix) and `internal fun idFlowsTo(p: KitePlatform): Provider<Boolean>`. Public derived providers `androidApplicationId`, `iosBundleId`, `desktopBundleId` re-route through `effectiveIdFor` (keep `validateAppleBundleId` on the desktop one, as today).

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.yuroyami.kitessot

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class IdScopeTest {

    private fun ext(): KiteSsotExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.github.yuroyami.kitessot")
        return project.extensions.getByType(KiteSsotExtension::class.java)
    }

    @Test
    fun `suffix corners build platform ids`() {
        val e = ext()
        e.id("com.example.jetzy") {
            android { suffix = ".android" }
            ios { suffix = ".ios" }
        }
        assertEquals("com.example.jetzy.android", e.androidApplicationId.get())
        assertEquals("com.example.jetzy.ios", e.iosBundleId.get())
        assertEquals("com.example.jetzy", e.desktopBundleId.get())
    }

    @Test
    fun `simple form works without corners`() {
        val e = ext()
        e.id.set("com.example.jetzy")
        assertEquals("com.example.jetzy", e.androidApplicationId.get())
    }
}
```

Note: if `KiteIdScope.IdCorner.suffix` is a Gradle `Property`, the assignment `suffix = ".android"` needs the Kotlin DSL assign plugin behavior that only applies in build scripts. In plain Kotlin test code call `suffix.set(".android")`. Keep the test using `.set(...)`, and keep `suffix` a `Property<String>` so build scripts get plain `=`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.IdScopeTest'`
Expected: compilation failure.

- [ ] **Step 3: Write scope and wiring**

```kotlin
package io.github.yuroyami.kitessot

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/** Details for [KiteSsotExtension.id]: per-platform suffixes and flow. */
abstract class KiteIdScope @Inject constructor(objects: ObjectFactory) : KiteFlowScope() {

    /** One platform's identity deviation: `suffix` appended to the base. */
    class IdCorner internal constructor(
        override val platform: KitePlatform,
        val suffix: Property<String>,
    ) : KitePlatformRef

    val android: IdCorner = IdCorner(KitePlatform.ANDROID, objects.property(String::class.java))
    val ios: IdCorner = IdCorner(KitePlatform.IOS, objects.property(String::class.java))
    val desktop: IdCorner = IdCorner(KitePlatform.DESKTOP, objects.property(String::class.java))

    fun android(action: Action<in IdCorner>) = action.execute(android)
    fun ios(action: Action<in IdCorner>) = action.execute(ios)
    fun desktop(action: Action<in IdCorner>) = action.execute(desktop)

    internal fun suffixFor(p: KitePlatform): Property<String> = when (p) {
        KitePlatform.ANDROID -> android.suffix
        KitePlatform.IOS -> ios.suffix
        KitePlatform.DESKTOP -> desktop.suffix
    }
}
```

Extension additions (same registration pattern as Task 3; `id` is a new abstract `Property<String>`):

```kotlin
abstract val id: Property<String>

fun id(value: String, action: Action<in KiteIdScope>) {
    if (id.isPresent) doubleSetWarnings.add("id")
    id.set(value)
    action.execute(idScope)
}

internal val idScope: KiteIdScope
    get() = nested()

internal fun effectiveIdFor(p: KitePlatform): Provider<String> =
    id.zip(idScope.suffixFor(p).orElse("")) { base, suffix -> base + suffix }

internal fun idFlowsTo(p: KitePlatform): Provider<Boolean> = idScope.flowsTo(p)
```

Re-point the public derived providers: `androidApplicationId` = `effectiveIdFor(ANDROID)`, `iosBundleId` = `effectiveIdFor(IOS)`, `desktopBundleId` = `effectiveIdFor(DESKTOP).map(::validateAppleBundleId)`. Leave the old `appId`/suffix chains in place for Task 10 to delete.

- [ ] **Step 4: Run test, then commit**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.IdScopeTest'`
Expected: PASS.

```bash
git add -A src/main/kotlin src/test/kotlin
git commit -m "feat: add the id topic scope with suffix corners"
```

---

### Task 5: version topic scope

**Files:**
- Create: `src/main/kotlin/io/github/yuroyami/kitessot/KiteVersionScope.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotExtension.kt`
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/VersionScopeTest.kt`

**Interfaces:**
- Consumes: Tasks 1 and 2.
- Produces: `fun version(value: String, action: Action<KiteVersionScope>)` on the extension; `KiteVersionScope` with `fun formula(s: VersionCodeScheme)` and corners:
  `AndroidCorner { reupload: Property<Int>; shipped: Property<Int>; pin: Property<Int>; formula(s) }`,
  `IosCorner { reupload: Property<Int>; shipped: Property<String>; pin: Property<String>; marketingVersion: Property<String>; formula(s) }`,
  `DesktopCorner { reupload: Property<Int>; shipped: Property<String>; pin: Property<String>; formula(s) }`.
  Internal resolution: `effectiveAndroidVersionCode` (pin, else corner formula, else topic formula, else `VersionSchemes.DEFAULT`, fed by base version + reupload), `effectiveIosBuildNumber`, `effectiveIosMarketingVersion`, `effectiveDesktopBuildNumber`, `effectiveHasExplicitVersionCode` (true only when `pin` set), `versionFlowsTo(p)`. `shipped` values feed the existing release guards that today read `publishedVersionCode`/`publishedBuildNumber` (Task 10 re-points those reads).

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.yuroyami.kitessot

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionScopeTest {

    private fun ext(): KiteSsotExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.github.yuroyami.kitessot")
        return project.extensions.getByType(KiteSsotExtension::class.java)
    }

    @Test
    fun `default formula packs the version`() {
        val e = ext()
        e.version.set("1.4.0")
        assertEquals(1001004000, e.versionCode.get())
    }

    @Test
    fun `topic formula feeds all platforms and corner reupload feeds the formula`() {
        val e = ext()
        e.version("1.4.1") {
            formula { v -> 1_000_000 * v.major + 10_000 * v.minor + 100 * v.patch + v.reupload }
            android { reupload.set(2) }
            ios { }
        }
        assertEquals(1_040_102, e.versionCode.get())
        assertEquals("1040100", e.effectiveIosBuildNumber.get())
    }

    @Test
    fun `pin skips the formula and marks the code explicit`() {
        val e = ext()
        e.version("1.4.0") {
            android { pin.set(555) }
        }
        assertEquals(555, e.versionCode.get())
        assertTrue(e.effectiveHasExplicitVersionCode.get())
    }

    @Test
    fun `corner formula beats topic formula on its platform only`() {
        val e = ext()
        e.version("2.0.0") {
            formula { v -> 100 * v.minor + v.reupload }
            ios { formula { v -> 7 } }
        }
        assertEquals(0, e.versionCode.get())
        assertEquals("7", e.effectiveIosBuildNumber.get())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.VersionScopeTest'`
Expected: compilation failure.

- [ ] **Step 3: Write the scope**

```kotlin
package io.github.yuroyami.kitessot

import org.gradle.api.Action
import org.gradle.api.provider.Property
import javax.inject.Inject
import org.gradle.api.model.ObjectFactory

/** Details for [KiteSsotExtension.version]: the formula and platform corners. */
abstract class KiteVersionScope : KiteFlowScope() {

    /** The shared version-to-build-number formula. Corners can override it. */
    abstract val formulaProp: Property<VersionCodeScheme>

    fun formula(s: VersionCodeScheme) = formulaProp.set(s)

    abstract class AndroidCorner : KitePlatformRef {
        final override val platform = KitePlatform.ANDROID
        abstract val reupload: Property<Int>
        abstract val shipped: Property<Int>
        abstract val pin: Property<Int>
        abstract val formulaProp: Property<VersionCodeScheme>
        fun formula(s: VersionCodeScheme) = formulaProp.set(s)
    }

    abstract class IosCorner : KitePlatformRef {
        final override val platform = KitePlatform.IOS
        abstract val reupload: Property<Int>
        abstract val shipped: Property<String>
        abstract val pin: Property<String>
        abstract val marketingVersion: Property<String>
        abstract val formulaProp: Property<VersionCodeScheme>
        fun formula(s: VersionCodeScheme) = formulaProp.set(s)
    }

    abstract class DesktopCorner : KitePlatformRef {
        final override val platform = KitePlatform.DESKTOP
        abstract val reupload: Property<Int>
        abstract val shipped: Property<String>
        abstract val pin: Property<String>
        abstract val formulaProp: Property<VersionCodeScheme>
        fun formula(s: VersionCodeScheme) = formulaProp.set(s)
    }

    @get:Inject protected abstract val objects: ObjectFactory

    val android: AndroidCorner by lazy { objects.newInstance(AndroidCorner::class.java) }
    val ios: IosCorner by lazy { objects.newInstance(IosCorner::class.java) }
    val desktop: DesktopCorner by lazy { objects.newInstance(DesktopCorner::class.java) }

    fun android(action: Action<in AndroidCorner>) = action.execute(android)
    fun ios(action: Action<in IosCorner>) = action.execute(ios)
    fun desktop(action: Action<in DesktopCorner>) = action.execute(desktop)
}
```

- [ ] **Step 4: Rewrite the resolution chains in the extension**

Replace the bodies of `effectiveAndroidVersionCode`, `effectiveIosBuildNumber`, `effectiveIosMarketingVersion`, `effectiveDesktopBuildNumber`, and `effectiveHasExplicitVersionCode` (keep the names). Pattern, shown for Android; iOS and desktop mirror it with `.toString()` on the result and their own corners:

```kotlin
fun version(value: String, action: Action<in KiteVersionScope>) {
    if (version.isPresent) doubleSetWarnings.add("version")
    version.set(value)
    action.execute(versionScope)
}

internal val versionScope: KiteVersionScope
    get() = nested()

private fun platformFormula(corner: Provider<VersionCodeScheme>): Provider<VersionCodeScheme> =
    corner.orElse(versionScope.formulaProp).orElse(VersionSchemes.DEFAULT)

internal val effectiveAndroidVersionCode: Provider<Int>
    get() = versionScope.android.pin.orElse(
        version.zip(
            versionScope.android.reupload.orElse(0)
                .zip(platformFormula(versionScope.android.formulaProp)) { r, s -> r to s },
        ) { v, (reupload, scheme) -> computeVersionCode(scheme, v, reupload, "android") },
    )

internal val effectiveHasExplicitVersionCode: Provider<Boolean>
    get() = versionScope.android.pin.map { true }.orElse(false)

internal fun versionFlowsTo(p: KitePlatform): Provider<Boolean> = versionScope.flowsTo(p)
```

The old root `scheme` property, `android.rebuild`, `ios.rebuild`, `desktop.rebuild`, and the pin properties in the platform extensions stay untouched until Task 10 deletes them together with their chains.

- [ ] **Step 5: Run test, then commit**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.VersionScopeTest'`
Expected: PASS.

```bash
git add -A src/main/kotlin src/test/kotlin
git commit -m "feat: add the version topic scope with corners and formula"
```

---

### Task 6: locales topic scope

**Files:**
- Create: `src/main/kotlin/io/github/yuroyami/kitessot/KiteLocalesScope.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotExtension.kt`
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/LocalesScopeTest.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces: `fun locales(action: Action<KiteLocalesScope>)`; scope members `fun pin(vararg tags: String)`, `val filterAndroidRes: Property<Boolean>`; internal `effectiveLocales` re-pointed to the pinned list orElse the existing discovery provider, `effectiveFilterAndroidResources` re-pointed to `filterAndroidRes.orElse(false)`, `localesFlowsTo(p)`. `canonicalLocales` keeps working on top.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.yuroyami.kitessot

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalesScopeTest {

    private fun ext(): KiteSsotExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.github.yuroyami.kitessot")
        return project.extensions.getByType(KiteSsotExtension::class.java)
    }

    @Test
    fun `pin sets the list and canonicalization still applies`() {
        val e = ext()
        e.locales {
            pin("en", "en", "pt-BR")
        }
        assertEquals(listOf("en", "pt-BR"), e.canonicalLocales.get())
    }

    @Test
    fun `filterAndroidRes defaults off and turns on in the scope`() {
        val e = ext()
        assertFalse(e.effectiveFilterAndroidResources.get())
        e.locales { filterAndroidRes.set(true) }
        assertTrue(e.effectiveFilterAndroidResources.get())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.LocalesScopeTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```kotlin
package io.github.yuroyami.kitessot

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** Everything about locales: the pinned list, the Android res filter, flow. */
abstract class KiteLocalesScope : KiteFlowScope() {

    internal abstract val pinned: ListProperty<String>

    /** Hand list of BCP 47 tags. Detection from Compose resources is skipped. */
    fun pin(vararg tags: String) = pinned.addAll(*tags)

    /** Drop Android res folders whose locale is not in the list. Default: false. */
    abstract val filterAndroidRes: Property<Boolean>
}
```

Extension: add `fun locales(action: Action<in KiteLocalesScope>) = action.execute(localesScope)` plus the nested accessor, then re-point `effectiveLocales` so the pinned list wins over the old root `locales` list property (which Task 10 deletes) and over discovery, and re-point `effectiveFilterAndroidResources` to `localesScope.filterAndroidRes.orElse(false)`. Add `internal fun localesFlowsTo(p: KitePlatform) = localesScope.flowsTo(p)`.

- [ ] **Step 4: Run test, then commit**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.LocalesScopeTest'`
Expected: PASS.

```bash
git add -A src/main/kotlin src/test/kotlin
git commit -m "feat: add the locales topic scope"
```

---

### Task 7: logo topic reshape

**Files:**
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotLogoExtension.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotExtension.kt`
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/LogoScopeTest.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces: reshaped `KiteSsotLogoExtension` extending `KiteFlowScope`, keeping `foreground`, `background`, `backgroundColor`, adding corner `android { safeZone: Property<Double> }` (replaces `androidSafeZone`), corner `desktop { roundMac: Property<Boolean> }` (absorbs `desktop.roundMacOsIcon`), and `fun rewrite(action: Action<LogoRewrite>)` with `LogoRewrite { replaceOld: Property<Boolean> }` (replaces `enabled` + `takeOverLegacyIcons`). Internal model: `effectiveLogoRewriteArmed` (rewrite block configured), `effectiveDesktopIcons` = logo declared AND `logoFlowsTo(DESKTOP)` AND desktop platform flowing, `effectiveLogoSafeZone`, `effectiveTakeOverLegacyIcons` = armed AND `replaceOld`, `effectiveRoundMacIcon`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.yuroyami.kitessot

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogoScopeTest {

    private fun ext(): KiteSsotExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.github.yuroyami.kitessot")
        return project.extensions.getByType(KiteSsotExtension::class.java)
    }

    @Test
    fun `declaring art alone arms nothing`() {
        val e = ext()
        e.logo { backgroundColor.set("#101014") }
        assertFalse(e.effectiveLogoRewriteArmed.get())
    }

    @Test
    fun `rewrite block arms the source tasks and replaceOld rides on it`() {
        val e = ext()
        e.logo {
            rewrite { replaceOld.set(true) }
        }
        assertTrue(e.effectiveLogoRewriteArmed.get())
        assertTrue(e.effectiveTakeOverLegacyIcons.get())
    }

    @Test
    fun `desktop icons flow from presence and stop on skip`() {
        val e = ext()
        e.logo { backgroundColor.set("#101014") }
        assertTrue(e.effectiveDesktopIcons.get())
        val e2 = ext()
        e2.logo {
            backgroundColor.set("#101014")
            skip(desktop)
        }
        assertFalse(e2.effectiveDesktopIcons.get())
    }

    @Test
    fun `safe zone moves into the android corner`() {
        val e = ext()
        e.logo { android { safeZone.set(0.7) } }
        assertEquals(0.7, e.effectiveLogoSafeZone.get())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.LogoScopeTest'`
Expected: compilation failure.

- [ ] **Step 3: Reshape the logo extension**

In `KiteSsotLogoExtension.kt`: extend `KiteFlowScope`; delete `enabled`, `androidSafeZone`, `takeOverLegacyIcons`; add:

```kotlin
abstract class AndroidCorner : KitePlatformRef {
    final override val platform = KitePlatform.ANDROID
    /** Launcher icon safe-zone ratio. Default 66/108. */
    abstract val safeZone: Property<Double>
}

abstract class DesktopCorner : KitePlatformRef {
    final override val platform = KitePlatform.DESKTOP
    /** Round the generated macOS icon. Default false. */
    abstract val roundMac: Property<Boolean>
}

abstract class LogoRewrite {
    /** Also remove legacy launcher icons when installing. Default false. */
    abstract val replaceOld: Property<Boolean>
}
```

with `objects.newInstance` accessors and `fun android(action)`, `fun desktop(action)`, plus:

```kotlin
internal abstract val rewriteArmed: Property<Boolean>
internal abstract val declared: Property<Boolean>

fun rewrite(action: Action<in LogoRewrite>) {
    rewriteArmed.set(true)
    action.execute(rewriteSpec)
}
```

In `KiteSsotExtension.kt`: the existing `fun logo(action)` sets `logo.declared.set(true)` instead of `logoConfigured`; internal model getters become:

```kotlin
internal val effectiveLogoRewriteArmed: Provider<Boolean>
    get() = logo.rewriteArmed.orElse(false)

internal val effectiveTakeOverLegacyIcons: Provider<Boolean>
    get() = effectiveLogoRewriteArmed.zip(logo.rewriteSpec.replaceOld.orElse(false)) { a, r -> a && r }

internal val effectiveLogoSafeZone: Provider<Double>
    get() = logo.androidCorner.safeZone.orElse(DEFAULT_ANDROID_SAFE_ZONE)

internal val effectiveDesktopIcons: Provider<Boolean>
    get() = logo.declared.orElse(false)
        .zip(logo.flowsTo(KitePlatform.DESKTOP)) { d, f -> d && f }
```

`effectivePropagateLogo` keeps its name for now but delegates to `effectiveLogoRewriteArmed`; Task 10 renames call sites and deletes it. Move `roundMacOsIcon` reads (grep `roundMacOsIcon` in `DesktopWiring.kt` / `GenerateDesktopIconsTask.kt`) to `logo.desktopCorner.roundMac`; delete the property from `KiteSsotDesktopExtension`.

- [ ] **Step 4: Run test, then commit**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.LogoScopeTest'`
Expected: PASS. Also run `./gradlew test --tests '*Logo*'` to catch fallout in existing logo tests; fix references to deleted properties by switching them to the new members.

```bash
git add -A src/main/kotlin src/test/kotlin
git commit -m "feat: reshape logo into a topic with corners and an armed rewrite"
```

---

### Task 8: optIns rename, ioWorker and buildConfig presence

**Files:**
- Rename: `KiteSsotNativeOptInsExtension.kt` stays, block name changes in the plugin registration
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotExtension.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotIoWorkerExtension.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotBuildConfigExtension.kt`
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/PresenceConsentTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: extension accessor `optIns` (`fun optIns(action)`) replacing `nativeOptIns`; `enabled` deleted from `KiteSsotNativeOptInsExtension`, `KiteSsotIoWorkerExtension`, `KiteSsotBuildConfigExtension`; presence flags: `optInsDeclared`, `ioWorkerDeclared`, `buildConfigDeclared`, each set by its block function; internal getters `effectiveNativeOptInsEnabled`, `effectiveIoWorkerEnabled`, `effectiveBuildConfigEnabled` become plain reads of the presence flags.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.yuroyami.kitessot

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresenceConsentTest {

    private fun ext(): KiteSsotExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.github.yuroyami.kitessot")
        return project.extensions.getByType(KiteSsotExtension::class.java)
    }

    @Test
    fun `nothing declared, nothing on`() {
        val e = ext()
        assertFalse(e.effectiveBuildConfigEnabled.get())
        assertFalse(e.effectiveIoWorkerEnabled.get())
        assertFalse(e.effectiveNativeOptInsEnabled.get())
    }

    @Test
    fun `presence is the consent`() {
        val e = ext()
        e.buildConfig { }
        e.web { ioWorker { } }
        e.optIns { add("kotlinx.cinterop.ExperimentalForeignApi") }
        assertTrue(e.effectiveBuildConfigEnabled.get())
        assertTrue(e.effectiveIoWorkerEnabled.get())
        assertTrue(e.effectiveNativeOptInsEnabled.get())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.PresenceConsentTest'`
Expected: FAIL (`optIns` unresolved; enabled semantics differ).

- [ ] **Step 3: Implement**

- Extension: rename accessor pair `nativeOptIns` to `optIns` (property and function; grep the plugin for the registration string and rename the extension name there too). The function sets `optInsDeclared.set(true)` (rename of `nativeOptInsConfigured`).
- Delete `enabled` from the three extensions and delete every `.orElse(true)`/`enabled` zip in the corresponding `effective*Enabled` getters; each becomes `<declaredFlag>.orElse(false)`.
- `web { ioWorker { } }` function sets `ioWorkerDeclared.set(true)` (rename of the `configured` property on the ioWorker extension).
- `buildConfig { }` function sets `buildConfigDeclared.set(true)` (rename of `buildConfigConfigured`).

- [ ] **Step 4: Run test, then commit**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.PresenceConsentTest'` then `./gradlew test --tests '*OptIn*' --tests '*Worker*' --tests '*BuildConfig*'`
Expected: PASS after fixing existing tests that set the deleted `enabled` flags (delete those lines or convert to presence).

```bash
git add -A src/main/kotlin src/test/kotlin
git commit -m "feat: presence is the consent for optIns, ioWorker, and buildConfig"
```

---

### Task 9: platform blocks cleanup

**Files:**
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotAndroidExtension.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotIosExtension.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotIosSyncExtension.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotDesktopExtension.kt`
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/PlatformBlocksTest.kt`

**Interfaces:**
- Consumes: Tasks 4, 5, 7 (which absorbed suffixes, version fields, roundMac).
- Produces:
  - `KiteSsotAndroidExtension implements KitePlatformRef (ANDROID)`: keeps `minSdk`, `targetSdk`, `compileSdk`, `ndk`; gains `fun sdk(min: Int? = null, target: Int? = null, compile: Int? = null)`; loses `idSuffix`, `versionCode`, `rebuild`, `scheme`, `publishedVersionCode`, `applySdkLevels`, `filterResourcesToLocales`.
  - `KiteSsotIosExtension implements KitePlatformRef (IOS)`: keeps `deploymentTarget` and the five path properties; loses `bundleIdSuffix`, `marketingVersion`, `buildNumber`, `rebuild`, `scheme`, `publishedBuildNumber`; `sync` accessor renamed to `rewrite` returning the sync extension.
  - `KiteSsotIosSyncExtension`: renamed member `sanitizePlist` to `cleanPlist`; `enabled` deleted; arming flag `rewriteArmed` set by `ios.rewrite { }`.
  - `KiteSsotDesktopExtension implements KitePlatformRef (DESKTOP)`: keeps `linuxPackageName`, `deriveUpgradeUuid`; loses `enabled`, `configured`, `icons`, `roundMacOsIcon`, `idSuffix`, `buildNumber`, `rebuild`, `scheme`, `publishedBuildNumber`.
  - Extension internal model: `effectiveApplySdkLevels` becomes "any sdk level declared" (`minSdk.orElse(targetSdk).orElse(compileSdk).map { true }.orElse(false)` style), `effectiveSyncIos` becomes `ios.rewrite armed`, `effectiveSanitizeIosProject` reads `cleanPlist`, `effectiveDesktopEnabled` becomes "desktop app modules present AND rootFlowsTo(DESKTOP)" (the auto-flow switch; `rootFlowsTo` arrives properly in Task 10, use `Providers.of(true)` placeholder wired to a TODO-free constant `trueProvider` here and Task 10 replaces it).

Careful: that last sentence must not become a placeholder. Concretely in this task: `effectiveDesktopEnabled = effectiveDesktopApps.map { it.isNotEmpty() }`, and Task 10 zips the master flow in.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.yuroyami.kitessot

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformBlocksTest {

    private fun ext(): KiteSsotExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.github.yuroyami.kitessot")
        return project.extensions.getByType(KiteSsotExtension::class.java)
    }

    @Test
    fun `sdk function sets any subset`() {
        val e = ext()
        e.android { sdk(min = 26, target = 36) }
        assertEquals(26, e.android.minSdk.get())
        assertEquals(36, e.android.targetSdk.get())
        assertTrue(e.effectiveApplySdkLevels.get())
    }

    @Test
    fun `ios rewrite arms the xcode task and cleanPlist rides it`() {
        val e = ext()
        e.ios {
            rewrite {
                targets("iosApp")
                cleanPlist.set(true)
            }
        }
        assertTrue(e.effectiveSyncIos.get())
        assertTrue(e.effectiveSanitizeIosProject.get())
    }

    @Test
    fun `desktop flows automatically from module presence`() {
        val e = ext()
        e.modules { desktopApps(":desktopApp") }
        assertTrue(e.effectiveDesktopEnabled.get())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.PlatformBlocksTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

The `sdk` function on the android extension:

```kotlin
/** Set any subset of the three SDK levels in one line. */
fun sdk(min: Int? = null, target: Int? = null, compile: Int? = null) {
    min?.let(minSdk::set)
    target?.let(targetSdk::set)
    compile?.let(compileSdk::set)
}
```

`ios.rewrite` accessor (the sync extension class keeps its file, `enabled` deleted, `sanitizePlist` renamed `cleanPlist`, its KDoc reworded):

```kotlin
val rewrite: KiteSsotIosSyncExtension
    get() = nested()

fun rewrite(action: Action<in KiteSsotIosSyncExtension>) {
    rewrite.rewriteArmed.set(true)
    action.execute(rewrite)
}
```

Delete the listed properties from the three platform extensions. Chase every compile error into `KiteSsotExtension.kt`: each broken chain is one the earlier topic tasks already re-pointed (suffixes Task 4, version numbers Task 5, roundMac Task 7); delete the dead fallbacks. Update `effectiveSyncIos`, `effectiveSanitizeIosProject`, `effectiveApplySdkLevels`, `effectiveDesktopEnabled` as specified in Interfaces. `effectivePlistConflictPolicy` and the other sync-block reads move from `ios.sync.*` to `ios.rewrite.*`.

- [ ] **Step 4: Run test, then commit**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.PlatformBlocksTest'`
Expected: PASS.

```bash
git add -A src/main/kotlin src/test/kotlin
git commit -m "feat: clean platform blocks down to platform-exclusive facts"
```

---

### Task 10: root purge and engine rewiring

**Files:**
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotExtension.kt` (large deletion)
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotPropagateExtension.kt` (delete file)
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotWebExtension.kt` (keep, it hosts ioWorker)
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotPlugin.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/ClassicAndroidWiring.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KmpAndroidLibraryWiring.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/Agp8AndroidInputs.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/DesktopWiring.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotDiagnostics.kt`
- Test: existing suite plus `src/test/kotlin/io/github/yuroyami/kitessot/RootFlowTest.kt`

**Interfaces:**
- Consumes: everything above.
- Produces: the final public root: `appName`, `appName(v){}`, `jvmTarget`, `id`, `id(v){}`, `version`, `version(v){}`, `locales{}`, `logo{}`, `optIns{}`, `android{}`, `ios{}`, `desktop{}`, `web{}`, `buildConfig{}`, `modules{}`, `skip(...)`, `only(...)`, `dryRun`, `backups`, and the six derived read-only providers. Root master flow: `internal fun rootFlowsTo(p: KitePlatform): Provider<Boolean>` (root extension itself extends `KiteFlowScope`). Combined per-fact gates used by the engine: `internal fun flows(topic: KiteFlowScope, p: KitePlatform)` = root AND topic.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.yuroyami.kitessot

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootFlowTest {

    private fun ext(): KiteSsotExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.github.yuroyami.kitessot")
        return project.extensions.getByType(KiteSsotExtension::class.java)
    }

    @Test
    fun `root skip silences a platform for every fact`() {
        val e = ext()
        e.appName.set("Jetzy")
        e.skip(e.desktop)
        assertFalse(e.appNameFlowsTo(KitePlatform.DESKTOP).get())
        assertFalse(e.versionFlowsTo(KitePlatform.DESKTOP).get())
        assertTrue(e.appNameFlowsTo(KitePlatform.ANDROID).get())
    }

    @Test
    fun `deprecated 2x surface is gone`() {
        // Compile-time check: this test file must NOT reference versionName,
        // bundleIdBase, propagateAppName, syncIos. Their absence is verified by
        // the extension source no longer declaring them; grep in step 3 below.
        assertTrue(true)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.RootFlowTest'`
Expected: compilation failure (`skip` not on the extension yet).

- [ ] **Step 3: Purge and rewire**

1. `KiteSsotExtension` extends `KiteFlowScope`. Every `<topic>FlowsTo(p)` gains a zip with `flowsTo(p)` of the root:
   ```kotlin
   internal fun appNameFlowsTo(p: KitePlatform): Provider<Boolean> =
       flowsTo(p).zip(appNameScope.flowsTo(p)) { root, topic -> root && topic }
   ```
   Same for `idFlowsTo`, `versionFlowsTo`, `localesFlowsTo`, and logo's desktop-icons gate. `effectiveDesktopEnabled` zips in `flowsTo(DESKTOP)`.
2. Delete from `KiteSsotExtension.kt`: the whole `DEPRECATED 2.x` region, `appId` (replaced by `id` in Task 4; keep a grep check that nothing references it), `scheme` property and `fun scheme`, `dryRunOverride`/`backupsOverride` STAY (CLI), `logoConfigured`/`nativeOptInsConfigured`/`buildConfigConfigured` already renamed, every `orElse(<deprecated>)` link. Delete `KiteSsotPropagateExtension.kt` and its registration in the plugin; delete the `propagate` accessors.
3. Rewire consumers. For each file, grep the old symbol and replace:
   - `ClassicAndroidWiring.kt`, `KmpAndroidLibraryWiring.kt`, `Agp8AndroidInputs.kt`: `effectivePropagateAppName` -> `appNameFlowsTo(KitePlatform.ANDROID)`, `effectivePropagateBundleId` -> `idFlowsTo(ANDROID)`, `effectivePropagateVersion` -> `versionFlowsTo(ANDROID)`, `effectivePropagateLocales` -> `localesFlowsTo(ANDROID)`, `effectiveAppName` -> `effectiveAppNameFor(ANDROID)`, `effectiveAppId`/`androidApplicationId` chains -> `effectiveIdFor(ANDROID)`.
   - `DesktopWiring.kt`: same substitutions with `DESKTOP`; the enable gate is the new `effectiveDesktopEnabled`; `roundMacOsIcon` -> logo desktop corner (done in Task 7, verify).
   - iOS-facing code (`KiteSsotPlugin.kt` task wiring, `SyncIosConfigTask` inputs): `effectiveAppName` -> `effectiveAppNameFor(IOS)`, `effectiveIosBundleSuffix` chain replaced by `effectiveIdFor(IOS)`, propagate gates -> `...FlowsTo(IOS)`.
   - `KiteSsotDiagnostics.kt`: update every mention of removed properties; doctor gains the double-set warning: if `doubleSetWarnings` is non-empty, emit one warning per entry: `"<fact> was set more than once; the last write wins."` Keep diagnostic ids unique (see `DiagnosticIdUniquenessTest`).
4. Grep gate before running tests: `grep -rn "propagate\|versionName\|bundleIdBase\|appLogoPng\|syncIos\|applySdkLevels\|bundleIdSuffix\|idSuffix\|rebuild\b" src/main/kotlin --include='*.kt'` must return only the new-world members (`reupload`, `suffix` corners). Anything else is an unfinished rewire.

- [ ] **Step 4: Run the full unit suite**

Run: `./gradlew test`
Expected: unit tests PASS; functional tests may still fail on old DSL fixtures (Task 12 migrates them). If only fixture-based functional tests fail, proceed.

- [ ] **Step 5: Commit**

```bash
git add -A src/main/kotlin src/test/kotlin
git commit -m "feat: purge the 2.x layer and rewire the engine to per-platform flow"
```

---

### Task 11: task renames and umbrellas

**Files:**
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotPlugin.kt` (registration names)
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotDiagnostics.kt` (messages that name tasks)
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/TaskNamesTest.kt`

**Interfaces:**
- Consumes: Task 10.
- Produces: registered task names `kiteCheck`, `kiteDoctor`, `kiteVerify`, `kitePlan`, `kiteRewriteLogo` (umbrella, dependsOn the internal android-logo, ios-logo, and legacy-cleanup tasks), `kiteRewriteXcode` (umbrella, dependsOn the internal ios-config and plist-clean tasks). Internal task registration names keep their descriptive strings but move to `internal` naming: `kiteInternalAndroidLogo`, `kiteInternalIosLogo`, `kiteInternalIosConfig`, `kiteInternalPlistClean`, `kiteInternalLegacyIconCleanup`. Generators stay wired automatically: `generateKiteSsotBuildConfig` -> `kiteInternalBuildConfig`, `generateKiteSsotDesktopIcons` -> `kiteInternalDesktopIcons`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.yuroyami.kitessot

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TaskNamesTest {

    @Test
    fun `new entry points exist and old names are gone`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.github.yuroyami.kitessot")
        (project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        listOf("kiteCheck", "kiteDoctor", "kiteVerify", "kitePlan", "kiteRewriteLogo", "kiteRewriteXcode")
            .forEach { assertNotNull(project.tasks.findByName(it), "missing $it") }
        listOf("kiteSsotCheck", "kiteSsotDoctor", "kiteSsotPlan", "kiteSsotSyncIosConfig")
            .forEach { assertNull(project.tasks.findByName(it), "$it should be gone") }
    }
}
```

If existing tests instantiate tasks differently (see `KiteSsotPluginFunctionalTest.kt` for the established pattern), follow that pattern instead of `ProjectInternal.evaluate()`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.TaskNamesTest'`
Expected: FAIL.

- [ ] **Step 3: Rename registrations**

In `KiteSsotPlugin.kt` change each `tasks.register("...")` string per the Interfaces table, add the two umbrella `tasks.register` calls with `dependsOn` on their internal tasks and group `"kite ssot"`, description one-liners: `"Installs the logo into Android res and the iOS asset catalog."` / `"Rewrites the Xcode project files from the declared facts."`. Update every diagnostics/console message that names an old task (grep `kiteSsot` string literals in main).

- [ ] **Step 4: Run test, then commit**

Run: `./gradlew test --tests 'io.github.yuroyami.kitessot.TaskNamesTest'` then `./gradlew test`
Expected: PASS (functional fixtures may still be red until Task 12).

```bash
git add -A src/main/kotlin src/test/kotlin
git commit -m "feat: rename tasks to the kite verbs with rewrite umbrellas"
```

---

### Task 12: migrate fixtures and functional tests

**Files:**
- Modify: `src/test/kotlin/io/github/yuroyami/kitessot/KiteSsotPluginFunctionalTest.kt`
- Modify: `src/test/kotlin/io/github/yuroyami/kitessot/AgpCompatibilityFunctionalTest.kt`
- Modify: `src/test/kotlin/io/github/yuroyami/kitessot/RealXcodeProjectCompatibilityTest.kt`
- Modify: every other test that writes a `kiteSsot { }` block or references renamed members (grep list in Step 1)

**Interfaces:**
- Consumes: the final surface from Tasks 3-11.
- Produces: a green full suite on the new surface only.

- [ ] **Step 1: Build the hit list**

Run: `grep -rln "appId\|scheme\|rebuild\|propagate\|idSuffix\|bundleIdSuffix\|sanitizePlist\|takeOverLegacyIcons\|nativeOptIns\|applySdkLevels\|versionCodeOverride\|sync {" src/test`
Every listed file gets migrated with these mechanical substitutions (build-script strings inside GradleRunner fixtures use `=` assignment; plain Kotlin uses `.set(...)`):

| Old fixture text | New fixture text |
|---|---|
| `appId = "x"` | `id = "x"` |
| `scheme { v -> ... }` | inside `version("...") { formula { v -> ... } }` |
| `android { idSuffix = ".a" }` | `id("base") { android { suffix = ".a" } }` |
| `android { rebuild = 1 }` | `version("...") { android { reupload = 1 } }` |
| `android { publishedVersionCode = N }` | `version("...") { android { shipped = N } }` |
| `android { versionCode = N }` | `version("...") { android { pin = N } }` |
| `ios { bundleIdSuffix = ".i" }` | `id("base") { ios { suffix = ".i" } }` |
| `ios { buildNumber = "n" }` | `version("...") { ios { pin = "n" } }` |
| `ios { sync { sanitizePlist = true } }` | `ios { rewrite { cleanPlist = true } }` |
| `logo { takeOverLegacyIcons = true }` | `logo { rewrite { replaceOld = true } }` |
| `logo { androidSafeZone = 0.6 }` | `logo { android { safeZone = 0.6 } }` |
| `nativeOptIns { ... }` | `optIns { ... }` |
| `propagate { version = false }` | `version(...) { skip(android); skip(ios); skip(desktop) }` or targeted `skip` per what the test asserts |
| `desktop { }` opt-in for identity | delete, auto-flow covers it; keep only real desktop facts |
| task name strings `kiteSsot...` | new names from Task 11 |

- [ ] **Step 2: Migrate file by file, running each as you go**

Run per file: `./gradlew test --tests 'io.github.yuroyami.kitessot.<TestClass>'`
Expected: PASS before moving to the next file.

- [ ] **Step 3: Full suite**

Run: `./gradlew test`
Expected: everything green.

- [ ] **Step 4: Commit**

```bash
git add -A src/test
git commit -m "test: migrate the suite to the reshaped DSL surface"
```

---

### Task 13: documentation overhaul

**Files:**
- Modify: KDoc in every public surface file touched above (`KiteSsotExtension.kt`, all scope and extension classes)
- Modify: `README.md`, `CHANGELOG.md`, `FEATURES.md`, `docs/index.md`, `OVERHAUL.md` (add a pointer note at the top: superseded by the reshape spec)
- Test: `./gradlew test --tests 'io.github.yuroyami.kitessot.KdocExampleCompilationTest' --tests 'io.github.yuroyami.kitessot.MessageHygieneTest'` plus a Dokka build

**Interfaces:**
- Consumes: the final surface and the spec's Documentation requirements section.
- Produces: class-level KDoc on `KiteSsotExtension` containing, verbatim from the spec: the three-rule law, the full canonical surface block, and the injectability matrix as a markdown table. Each scope class KDoc: its slice of the surface block plus a member table with columns `member | meaning | default | flow class`. Each property KDoc: 1 to 3 lines. No em-dashes anywhere.

- [ ] **Step 1: Write the extension KDoc**

Replace the existing class KDoc of `KiteSsotExtension` with: one intro sentence, the law as a 3-item list, the full surface block in a ```kotlin fence copied from the spec section "The full surface", the injectability matrix table copied from the spec, and the read-back provider list. Keep the `@see` tags pointing at the scope classes.

- [ ] **Step 2: Write the scope KDocs**

For each scope class, copy only its topic's lines from the surface block into the class KDoc fence, then the member table. Example for `KiteLocalesScope`:

```
| member | meaning | default | flow class |
|---|---|---|---|
| pin(tags) | hand locale list, detection skipped | auto-detect | memory |
| filterAndroidRes | drop Android res outside the list | false | memory |
| skip(p) / only(p) | flow control | flow everywhere | n/a |
```

- [ ] **Step 3: Update the prose docs**

`README.md` quick-start becomes the three-line setup plus one sentence of the law. `CHANGELOG.md` gets the 3.x reshape entry: the law, the rename table (copy from spec), the death list. `OVERHAUL.md` gets a two-line banner at the top: "Superseded: the DSL described below was replaced by the reshape. See specs/2026-08-28-dsl-reshape-design.md." `FEATURES.md` and `docs/index.md`: update every DSL snippet to the new surface.

- [ ] **Step 4: Verify**

Run: `./gradlew test --tests '*Kdoc*' --tests '*MessageHygiene*' && ./gradlew dokkaGenerate 2>/dev/null || ./gradlew dokkaHtml`
Expected: PASS, Dokka builds without KDoc syntax warnings.

- [ ] **Step 5: Full suite one last time, then commit**

Run: `./gradlew build`
Expected: green.

```bash
git add -A
git commit -m "docs: rewrite the surface docs around the one-law DSL"
```

---

## Self-review notes

- Spec coverage: law (Tasks 1, 10), dual forms and double-set warning (3, 4, 5, 10), vocabulary renames (2, 5, 7, 8, 9), topic scoping (3-9), desktop auto-flow (9, 10), presence consent (7, 8), task renames (11), documentation requirements (13), testing section (each task plus 12). Splash: deliberately deferred to its own plan.
- The extension keeps `dryRunOverride`/`backupsOverride`: CLI parity is a spec requirement.
- `web { }` survives (spec: home of ioWorker). `KiteSsotWebExtension.kt` is modified, not deleted.
- Type consistency: `KitePlatform`, `KitePlatformRef`, `KiteFlowScope.flowsTo`, `effectiveAppNameFor`, `appNameFlowsTo`, `effectiveIdFor`, `idFlowsTo`, `versionFlowsTo`, `localesFlowsTo`, `effectiveLogoRewriteArmed`, `doubleSetWarnings` are used with the same names across Tasks 1-11.
