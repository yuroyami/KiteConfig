# `desktop { }` Block Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give KiteSSOT a `desktop { }` block that propagates app name, version, bundle ID, build number and generated icons into Compose Desktop, for both the JVM and the native application models.

**Architecture:** Compose exposes identity as plain `var` fields and configures desktop inside its own `afterEvaluate`, so KiteSSOT writes resolved values from a callback registered earlier, out of the root project's `allprojects` block. Icons are the exception: `iconFile` is a real `RegularFileProperty`, so icons are generated into `build/` and wired lazily, which keeps the source tree untouched and needs no ownership or backup machinery.

**Tech Stack:** Kotlin 2.4.10, Gradle 8.5+, JUnit 5, Gradle TestKit, `org.jetbrains.compose:compose-gradle-plugin` as `compileOnly`, pure JDK image code (`java.awt` + `ImageIO`).

**Spec:** [specs/2026-08-24-desktop-block-design.md](2026-08-24-desktop-block-design.md)

**Plan location note:** the skill default is `docs/superpowers/plans/`. That is not used here because `mkdocs.yml` sets `docs_dir: docs`, so any file under `docs/` is published to the public site. Plan and spec live together in `specs/`.

## Global Constraints

- Gradle 8.5 and newer. AGP 8.5.2 through 9.3.x. KGP 2.4.x. JDK 17 and 21.
- The plugin has **zero runtime dependencies**. Every peer plugin is `compileOnly`. `runtimeClasspath` must stay in the `empty=` line of `gradle.lockfile`.
- `dependencyLocking { lockAllConfigurations() }` is on. Any new dependency requires a lockfile regeneration.
- An ordinary build must never write outside `build/`. CI asserts the tracked working tree is unchanged.
- The plugin applies to the **root project only**.
- Compose-typed code must live in its own file, never on `KiteSsotPlugin`, or Gradle fails to decorate the plugin when Compose is absent.
- All new validation throws `org.gradle.api.GradleException`, never `IllegalArgumentException`. Message format: start with `kiteSsot`, name the DSL path in braces, say what is required.
- Tests are JUnit 5 with `org.junit.jupiter.api.Assertions`, `@TempDir` and backtick test names. On `assertTrue` and `assertThrows`, pass the offending **dynamic** value as the message argument, for example `assertTrue(cond, result.output)`, never a fixed sentence. On `assertEquals`, pass no message argument at all: JUnit already prints expected against actual, and the existing unit tests in this repo omit it.
- **No em-dashes** in any Markdown, KDoc, or comment.
- `MessageHygieneTest` rejects retired 2.x property names inside string literals in `src/main/kotlin`.
- `KdocExampleCompilationTest` runs every `kiteSsot { }` KDoc fence through a real build. New examples may reference only `:shared` and `art/logo_fg.png`.
- Public API changes require `./gradlew updateKotlinAbi` and a committed `api/kitessot.api` diff.
- Next free hard-failure code is `[KITESSOT-COMPAT-007]`. Codes 001 to 006 are taken.
- Free diagnostic IDs: `KMPS080` to `KMPS089` for engine checks, `KMPS941` and up for provider resolution.

---

## File Structure

**Created:**

| File | Responsibility |
| --- | --- |
| `src/main/kotlin/io/github/yuroyami/kitessot/IconContainers.kt` | Pure ICNS and ICO container writers. No Gradle types. |
| `src/main/kotlin/io/github/yuroyami/kitessot/DesktopIdentity.kt` | Pure desktop rules: format version legality, Linux slug, UUIDv5. No Gradle types. |
| `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotDesktopExtension.kt` | The `desktop { }` DSL type. |
| `src/main/kotlin/io/github/yuroyami/kitessot/DesktopWiring.kt` | The only file that touches Compose types. Detection plus identity writes. |
| `src/main/kotlin/io/github/yuroyami/kitessot/GenerateDesktopIconsTask.kt` | The `build/` icon generator task. |
| `src/test/kotlin/io/github/yuroyami/kitessot/IconContainersTest.kt` | Parses produced bytes back and asserts structure. |
| `src/test/kotlin/io/github/yuroyami/kitessot/DesktopIdentityTest.kt` | Unit tests for the pure desktop rules. |

**Modified:**

| File | Change |
| --- | --- |
| `ImageOps.kt` | Gains shared `encodePng`, `padToSafeZone`, `applyRoundedRectMask`. |
| `SyncAndroidLogoTask.kt`, `SyncIosLogoTask.kt` | Drop their private copies of the lifted helpers. |
| `PluginCompatibility.kt` | Gains `isSupportedComposeVersion`. |
| `VersionResolution.kt:44-47` | Gains a `"desktop"` branch. |
| `KiteSsotModulesExtension.kt` | Gains `desktopApps`. |
| `KiteSsotExtension.kt` | Gains the `desktop` accessor, the configure function, and the `effective*` providers. |
| `KiteSsotPlugin.kt` | Extension creation, wiring, task registration, `modelValues()`, diagnostics binding. |
| `KiteSsotDiagnostics.kt` | Context fields plus `diagnoseDesktop`. |
| `KiteSsotVerifyTask.kt` | A `Desktop` section. |
| `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.lockfile`, `api/kitessot.api` | Dependency and baseline updates. |
| `README.md`, `FEATURES.md`, `CHANGELOG.md` | Documentation. |

---

## Task 1: Share the PNG encoder

Two tasks copy the same `encodePng` body. The desktop writers would make a third copy. Lift it first.

**Files:**
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/ImageOps.kt` (append)
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/SyncAndroidLogoTask.kt:297-303`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/SyncIosLogoTask.kt:200-206`
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/ImageOpsTest.kt`

**Interfaces:**
- Produces: `internal fun encodePng(image: BufferedImage, label: String): ByteArray`

- [ ] **Step 1: Write the failing test**

Append to `ImageOpsTest.kt`:

```kotlin
@Test
fun `encodePng produces bytes that decode back to the same size`() {
    val source = BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB)
    val encoded = encodePng(source, "test icon")
    val decoded = ImageIO.read(ByteArrayInputStream(encoded))
    assertEquals(24, decoded.width, "width changed during PNG round trip")
    assertEquals(24, decoded.height, "height changed during PNG round trip")
}
```

Add imports `java.io.ByteArrayInputStream` and `javax.imageio.ImageIO` if absent.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*ImageOpsTest*'`
Expected: FAIL, unresolved reference `encodePng`.

- [ ] **Step 3: Write minimal implementation**

Append to `ImageOps.kt`:

```kotlin
/** Encode [image] as PNG bytes. [label] names the artifact in the failure message. */
internal fun encodePng(image: BufferedImage, label: String): ByteArray {
    val output = ByteArrayOutputStream()
    if (!ImageIO.write(image, "PNG", output)) {
        throw GradleException("[kiteSsot] This JDK has no PNG encoder; cannot render $label.")
    }
    return output.toByteArray()
}
```

Add imports: `java.io.ByteArrayOutputStream`, `javax.imageio.ImageIO`, `org.gradle.api.GradleException`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*ImageOpsTest*'`
Expected: PASS.

- [ ] **Step 5: Remove both private copies**

In `SyncAndroidLogoTask.kt`, delete the private `encodePng` and change every call site from `encodePng(x)` to `encodePng(x, "Android logo")`.

In `SyncIosLogoTask.kt`, delete the private `encodePng` and change every call site to `encodePng(x, "iOS app icon")`.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test`
Expected: PASS, including the logo functional tests that assert byte stability.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/io/github/yuroyami/kitessot/ImageOps.kt src/main/kotlin/io/github/yuroyami/kitessot/SyncAndroidLogoTask.kt src/main/kotlin/io/github/yuroyami/kitessot/SyncIosLogoTask.kt src/test/kotlin/io/github/yuroyami/kitessot/ImageOpsTest.kt
git commit -m "refactor: share one PNG encoder between the logo installers"
```

---

## Task 2: Share the safe-zone inset and add a rounded mask

**Files:**
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/ImageOps.kt` (append)
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/SyncAndroidLogoTask.kt:276-280`
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/ImageOpsTest.kt`

**Interfaces:**
- Consumes: `newArgb`, `drawContain`, `withGraphics` from Task 1's file.
- Produces:
  - `internal fun padToSafeZone(fg: BufferedImage, canvasSize: Int, ratio: Double): BufferedImage`
  - `internal fun applyRoundedRectMask(src: BufferedImage, cornerRatio: Double): BufferedImage`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `padToSafeZone centers the foreground inside the ratio`() {
    val fg = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        .withGraphics { color = Color.RED; fillRect(0, 0, 10, 10) }
    val padded = padToSafeZone(fg, 100, 0.5)
    assertEquals(100, padded.width)
    assertEquals(0, padded.getRGB(2, 2) ushr 24)
    assertEquals(255, padded.getRGB(50, 50) ushr 24)
}

@Test
fun `applyRoundedRectMask cuts a corner of the documented radius`() {
    val square = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
        .withGraphics { color = Color.RED; fillRect(0, 0, 100, 100) }
    val rounded = applyRoundedRectMask(square, 0.25)
    assertEquals(0, rounded.getRGB(0, 0) ushr 24)
    assertEquals(255, rounded.getRGB(50, 50) ushr 24)
    // Pins the radius itself, not merely that some rounding happened. With a
    // radius of 25 the corner circle is centred at (25,25), so (5,5) lies
    // 28.3 away and is cut. A half-size radius would leave (5,5) opaque.
    assertEquals(0, rounded.getRGB(5, 5) ushr 24)
}
```

Add import `java.awt.Color`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*ImageOpsTest*'`
Expected: FAIL, unresolved references.

- [ ] **Step 3: Write minimal implementation**

Append to `ImageOps.kt`:

```kotlin
/** Centre [fg] inside a square canvas, filling only [ratio] of each side. */
internal fun padToSafeZone(fg: BufferedImage, canvasSize: Int, ratio: Double): BufferedImage {
    val safe = (canvasSize * ratio).toInt().coerceAtLeast(1)
    val offset = (canvasSize - safe) / 2
    return newArgb(canvasSize).withGraphics { drawContain(fg, offset, offset, safe, safe) }
}

/** Clip [src] to a rounded square. [cornerRatio] is the corner radius as a fraction of the side. */
internal fun applyRoundedRectMask(src: BufferedImage, cornerRatio: Double): BufferedImage {
    val side = minOf(src.width, src.height).toFloat()
    // RoundRectangle2D takes the arc DIAMETER, so a radius fraction doubles here.
    val arc = (side * cornerRatio * 2).toFloat()
    return BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB).withGraphics {
        clip = RoundRectangle2D.Float(0f, 0f, src.width.toFloat(), src.height.toFloat(), arc, arc)
        drawImage(src, 0, 0, null)
    }
}
```

Add import `java.awt.geom.RoundRectangle2D`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests '*ImageOpsTest*'`
Expected: PASS.

- [ ] **Step 5: Remove the private copy**

Delete `private fun padToSafeZone` from `SyncAndroidLogoTask.kt`. Its call site already matches the lifted signature.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test`
Expected: PASS. The Android logo bytes must not change.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/io/github/yuroyami/kitessot/ImageOps.kt src/main/kotlin/io/github/yuroyami/kitessot/SyncAndroidLogoTask.kt src/test/kotlin/io/github/yuroyami/kitessot/ImageOpsTest.kt
git commit -m "refactor: share the safe-zone inset and add a rounded-rect mask"
```

---

## Task 3: ICNS container writer

ICNS layout: 8 byte header (`icns` magic, then total length as big-endian u32 including the header), then entries. Each entry is a 4 byte OSType, a big-endian u32 length that includes the entry's own 8 byte header, then the PNG payload.

**Files:**
- Create: `src/main/kotlin/io/github/yuroyami/kitessot/IconContainers.kt`
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/IconContainersTest.kt`

**Interfaces:**
- Consumes: `encodePng` from Task 1, `resize` from `ImageOps.kt`.
- Produces:
  - `internal val ICNS_ENTRIES: List<Pair<String, Int>>`
  - `internal fun writeIcns(square: BufferedImage): ByteArray`

- [ ] **Step 1: Write the failing test**

Create `IconContainersTest.kt`:

```kotlin
package io.github.yuroyami.kitessot

import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IconContainersTest {

    private fun square(size: Int): BufferedImage =
        BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)

    @Test
    fun `writeIcns emits a parseable container with every declared entry`() {
        val bytes = writeIcns(square(1024))

        assertEquals("icns", String(bytes, 0, 4, Charsets.US_ASCII))
        val declared = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.BIG_ENDIAN).int
        assertEquals(bytes.size, declared)

        val seen = mutableListOf<String>()
        var offset = 8
        while (offset < bytes.size) {
            val type = String(bytes, offset, 4, Charsets.US_ASCII)
            val length = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.BIG_ENDIAN).int
            assertTrue(length > 8, "entry $type has no payload")
            assertTrue(offset + length <= bytes.size, "entry $type runs past the end")
            val payload = bytes.copyOfRange(offset + 8, offset + length)
            assertEquals(0x89.toByte(), payload[0], "entry $type payload is not PNG")
            assertEquals("PNG", String(payload, 1, 3, Charsets.US_ASCII), "entry $type payload is not PNG")
            seen += type
            offset += length
        }
        assertEquals(bytes.size, offset)
        assertEquals(ICNS_ENTRIES.map { it.first }, seen)
    }

    @Test
    fun `writeIcns skips the legacy small PNG types`() {
        val types = ICNS_ENTRIES.map { it.first }
        assertTrue("icp4" !in types, "icp4 handles PNG inconsistently and must not be emitted")
        assertTrue("icp5" !in types, "icp5 handles PNG inconsistently and must not be emitted")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*IconContainersTest*'`
Expected: FAIL, unresolved references `writeIcns` and `ICNS_ENTRIES`.

- [ ] **Step 3: Write minimal implementation**

Create `IconContainers.kt`:

```kotlin
package io.github.yuroyami.kitessot

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream

/**
 * The ICNS entry types KiteSSOT emits, each with its pixel size.
 *
 * `icp4` and `icp5` are deliberately absent. They nominally accept PNG at 16
 * and 32 pixels, and readers disagree about that. `ic11` and `ic12` cover the
 * same pixel sizes as retina entries and are read consistently.
 */
internal val ICNS_ENTRIES: List<Pair<String, Int>> = listOf(
    "ic07" to 128,
    "ic08" to 256,
    "ic09" to 512,
    "ic10" to 1024,
    "ic11" to 32,
    "ic12" to 64,
    "ic13" to 256,
    "ic14" to 512,
)

private fun bigEndian(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(),
    (value ushr 16).toByte(),
    (value ushr 8).toByte(),
    value.toByte(),
)

/** Build a macOS `.icns` container from one square source image. */
internal fun writeIcns(square: BufferedImage): ByteArray {
    val body = ByteArrayOutputStream()
    for ((type, size) in ICNS_ENTRIES) {
        val payload = encodePng(resize(square, size, size), "macOS icon entry $type")
        body.write(type.toByteArray(Charsets.US_ASCII))
        body.write(bigEndian(payload.size + 8))
        body.write(payload)
    }
    val entries = body.toByteArray()
    val out = ByteArrayOutputStream()
    out.write("icns".toByteArray(Charsets.US_ASCII))
    out.write(bigEndian(entries.size + 8))
    out.write(entries)
    return out.toByteArray()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*IconContainersTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/yuroyami/kitessot/IconContainers.kt src/test/kotlin/io/github/yuroyami/kitessot/IconContainersTest.kt
git commit -m "feat: write macOS icns containers from a square source image"
```

---

## Task 4: ICO container writer

ICO layout: a 6 byte `ICONDIR` (reserved u16 zero, type u16 one, count u16), then one 16 byte `ICONDIRENTRY` per image, then the payloads. All multi-byte fields are little-endian. A 256 pixel side is stored as a `0` byte.

**Files:**
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/IconContainers.kt` (append)
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/IconContainersTest.kt` (append)

**Interfaces:**
- Produces:
  - `internal val ICO_SIZES: List<Int>`
  - `internal fun writeIco(square: BufferedImage): ByteArray`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `writeIco emits a directory whose offsets and lengths address real PNG payloads`() {
    val bytes = writeIco(square(256))
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    assertEquals(0, buffer.getShort(0).toInt())
    assertEquals(1, buffer.getShort(2).toInt())
    assertEquals(ICO_SIZES.size, buffer.getShort(4).toInt())

    ICO_SIZES.forEachIndexed { index, size ->
        val entry = 6 + index * 16
        val storedWidth = bytes[entry].toInt() and 0xff
        assertEquals(if (size == 256) 0 else size, storedWidth, "width byte for $size")
        assertEquals(32, buffer.getShort(entry + 6).toInt(), "bit count for $size")
        val length = buffer.getInt(entry + 8)
        val offset = buffer.getInt(entry + 12)
        assertTrue(offset + length <= bytes.size, "payload for $size runs past the end")
        assertEquals(0x89.toByte(), bytes[offset], "payload for $size is not PNG")
        assertEquals("PNG", String(bytes, offset + 1, 3, Charsets.US_ASCII), "payload for $size is not PNG")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*IconContainersTest*'`
Expected: FAIL, unresolved references `writeIco` and `ICO_SIZES`.

- [ ] **Step 3: Write minimal implementation**

Append to `IconContainers.kt`:

```kotlin
/** The Windows `.ico` entry sizes KiteSSOT emits. */
internal val ICO_SIZES: List<Int> = listOf(16, 24, 32, 48, 64, 128, 256)

private fun littleEndianShort(value: Int): ByteArray =
    byteArrayOf(value.toByte(), (value ushr 8).toByte())

private fun littleEndianInt(value: Int): ByteArray = byteArrayOf(
    value.toByte(),
    (value ushr 8).toByte(),
    (value ushr 16).toByte(),
    (value ushr 24).toByte(),
)

/** Build a Windows `.ico` container from one square source image. */
internal fun writeIco(square: BufferedImage): ByteArray {
    val payloads = ICO_SIZES.map { size ->
        encodePng(resize(square, size, size), "Windows icon entry ${size}px")
    }
    val directoryBytes = 6 + ICO_SIZES.size * 16
    val out = ByteArrayOutputStream()
    out.write(littleEndianShort(0))
    out.write(littleEndianShort(1))
    out.write(littleEndianShort(ICO_SIZES.size))

    var offset = directoryBytes
    ICO_SIZES.forEachIndexed { index, size ->
        // A 256 pixel side is stored as 0, which is what the format reserves for it.
        val stored = if (size == 256) 0 else size
        out.write(byteArrayOf(stored.toByte(), stored.toByte(), 0, 0))
        out.write(littleEndianShort(1))
        out.write(littleEndianShort(32))
        out.write(littleEndianInt(payloads[index].size))
        out.write(littleEndianInt(offset))
        offset += payloads[index].size
    }
    payloads.forEach(out::write)
    return out.toByteArray()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*IconContainersTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/yuroyami/kitessot/IconContainers.kt src/test/kotlin/io/github/yuroyami/kitessot/IconContainersTest.kt
git commit -m "feat: write Windows ico containers from a square source image"
```

---

## Task 5: Desktop identity rules

Three pure rules: which target formats reject a version, the Debian package slug, and the derived upgrade UUID. The macOS bundle ID needs no new rule, because the existing `validateAppleBundleId` already restricts segments to `[A-Za-z0-9][A-Za-z0-9-]*`, which is stricter than the `[A-Za-z0-9\-\.]+` Compose requires.

**Files:**
- Create: `src/main/kotlin/io/github/yuroyami/kitessot/DesktopIdentity.kt`
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/DesktopIdentityTest.kt`

**Interfaces:**
- Produces:
  - `internal const val KITESSOT_UPGRADE_UUID_NAMESPACE: String`
  - `internal fun validateDesktopPackageVersion(version: String, targetFormats: Set<String>): String`
  - `internal fun deriveLinuxPackageName(appName: String): String`
  - `internal fun deriveUpgradeUuid(appId: String): String`

- [ ] **Step 1: Write the failing tests**

Create `DesktopIdentityTest.kt`:

```kotlin
package io.github.yuroyami.kitessot

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopIdentityTest {

    @Test
    fun `a normal version passes every format`() {
        val formats = setOf("Msi", "Exe", "Dmg", "Pkg", "Deb", "Rpm", "AppImage")
        assertEquals("1.4.0", validateDesktopPackageVersion("1.4.0", formats))
    }

    @Test
    fun `a minor above the Windows cap fails and names the limits`() {
        val failure = assertThrows(GradleException::class.java) {
            validateDesktopPackageVersion("1.300.0", setOf("Msi"))
        }
        assertTrue(failure.message!!.contains("1.300.0"), failure.message)
        assertTrue(failure.message!!.contains("255"), failure.message)
        assertTrue(failure.message!!.contains("targetFormats"), failure.message)
    }

    @Test
    fun `the same version passes when no Windows format is requested`() {
        assertEquals("1.300.0", validateDesktopPackageVersion("1.300.0", setOf("Dmg", "Deb")))
    }

    @Test
    fun `a build above the Windows cap fails`() {
        assertThrows(GradleException::class.java) {
            validateDesktopPackageVersion("1.0.70000", setOf("Exe"))
        }
    }

    @Test
    fun `the Linux slug lowercases and replaces punctuation`() {
        assertEquals("jetzy", deriveLinuxPackageName("Jetzy"))
        assertEquals("my-app", deriveLinuxPackageName("My App"))
        assertEquals("acme-tool", deriveLinuxPackageName("Acme_Tool"))
    }

    @Test
    fun `a slug that cannot start with an alphanumeric fails and names the escape hatch`() {
        val failure = assertThrows(GradleException::class.java) { deriveLinuxPackageName("!!!") }
        assertTrue(failure.message!!.contains("desktop { linuxPackageName }"), failure.message)
    }

    @Test
    fun `the derived upgrade uuid is stable and depends on appId`() {
        assertEquals(deriveUpgradeUuid("com.acme.app"), deriveUpgradeUuid("com.acme.app"))
        assertNotEquals(deriveUpgradeUuid("com.acme.app"), deriveUpgradeUuid("com.acme.other"))
        assertTrue(
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                .matches(deriveUpgradeUuid("com.acme.app")),
            deriveUpgradeUuid("com.acme.app"),
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*DesktopIdentityTest*'`
Expected: FAIL, unresolved references.

- [ ] **Step 3: Write minimal implementation**

Create `DesktopIdentity.kt`:

```kotlin
package io.github.yuroyami.kitessot

import java.security.MessageDigest
import org.gradle.api.GradleException

/**
 * The fixed namespace for derived Windows upgrade codes.
 *
 * This is a published contract, not an implementation detail. Changing it would
 * change every upgrade code KiteSSOT has ever derived, which breaks MSI upgrades
 * for already-installed users.
 */
internal const val KITESSOT_UPGRADE_UUID_NAMESPACE = "6b0f4c1e-6d4a-5a2f-9c3d-1f6b2a8e7d40"

private val WINDOWS_FORMATS = setOf("Msi", "Exe")
private val LINUX_SLUG_INVALID = Regex("[^a-z0-9+.-]")

/**
 * Reject a version the enabled installers cannot accept, before Compose sees it.
 *
 * Windows is the only real failure mode. `version` is always a strict `x.y.z`,
 * so the Debian, RPM and macOS rules can never fail on it.
 */
internal fun validateDesktopPackageVersion(version: String, targetFormats: Set<String>): String {
    if (targetFormats.none { it in WINDOWS_FORMATS }) return version
    val parts = version.split('.')
    // An absent component is 0, but a present one that will not parse fails
    // closed. Treating "1.abc.0" as "1.0.0" would let it through the cap.
    val major = parts.getOrNull(0)?.toIntOrNull()
    val minor = if (parts.size > 1) parts[1].toIntOrNull() else 0
    val build = if (parts.size > 2) parts[2].toIntOrNull() else 0
    if (major == null || minor == null || build == null ||
        major > 255 || minor > 255 || build > 65_535
    ) {
        throw GradleException(
            "kiteSsot { version } is \"${diagnosticSafeText(version, 64)}\", which Windows installers " +
                "reject. MSI and EXE accept MAJOR.MINOR.BUILD with limits 255, 255 and 65535. " +
                "Either lower the component, or drop Msi and Exe from targetFormats.",
        )
    }
    return version
}

/** Turn an app name into a Debian-legal package name. */
internal fun deriveLinuxPackageName(appName: String): String {
    val slug = LINUX_SLUG_INVALID.replace(appName.lowercase(), "-").trim('-')
    if (slug.isEmpty() || !slug.first().isLetterOrDigit()) {
        throw GradleException(
            "kiteSsot cannot derive a Debian package name from appName " +
                "\"${diagnosticSafeText(appName, 64)}\". Debian names must start with a letter or " +
                "digit. Set it yourself with desktop { linuxPackageName }.",
        )
    }
    return slug
}

/** Derive a stable UUIDv5 upgrade code from the application identifier. */
internal fun deriveUpgradeUuid(appId: String): String {
    val namespace = KITESSOT_UPGRADE_UUID_NAMESPACE.replace("-", "")
    val namespaceBytes = ByteArray(16) { index ->
        namespace.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    val digest = MessageDigest.getInstance("SHA-1").apply {
        update(namespaceBytes)
        update(appId.toByteArray(Charsets.UTF_8))
    }.digest()
    digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
    digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests '*DesktopIdentityTest*'`
Expected: PASS.

- [ ] **Step 5: Add the desktop branch to the version error text**

In `VersionResolution.kt:44-47`, change:

```kotlin
    val property = when (platform) {
        "ios" -> "ios { buildNumber = ... }"
        "desktop" -> "desktop { buildNumber = ... }"
        else -> "android { versionCode = ... }"
    }
```

- [ ] **Step 6: Add a test for that branch**

Append to `src/test/kotlin/io/github/yuroyami/kitessot/VersionCodeTest.kt`:

```kotlin
@Test
fun `a desktop failure names the desktop override, not the android one`() {
    val failure = assertThrows(GradleException::class.java) {
        computeVersionCode(VersionSchemes.DEFAULT, "1.2.3-rc1", 0, "desktop")
    }
    assertTrue(failure.message!!.contains("desktop { buildNumber"), failure.message)
}
```

- [ ] **Step 7: Run the full suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/io/github/yuroyami/kitessot/DesktopIdentity.kt src/main/kotlin/io/github/yuroyami/kitessot/VersionResolution.kt src/test/kotlin/io/github/yuroyami/kitessot/DesktopIdentityTest.kt src/test/kotlin/io/github/yuroyami/kitessot/VersionCodeTest.kt
git commit -m "feat: add desktop version, package-name and upgrade-code rules"
```

---

## Task 6: Compose version support range

**Files:**
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/PluginCompatibility.kt`
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/PluginCompatibilityTest.kt`

**Interfaces:**
- Consumes: `ToolVersion`, `parseToolVersion`.
- Produces: `internal fun isSupportedComposeVersion(value: String): Boolean`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `the compose range accepts the tested line and its pre-release builds`() {
    assertTrue(isSupportedComposeVersion("1.11.0"))
    assertTrue(isSupportedComposeVersion("1.12.0"))
    assertTrue(isSupportedComposeVersion("1.12.0-rc01"), "Compose ships long rc lines people build on")
    assertTrue(isSupportedComposeVersion("1.12.0-beta03"))
}

@Test
fun `the compose range rejects versions outside it`() {
    assertFalse(isSupportedComposeVersion("1.10.9"))
    assertFalse(isSupportedComposeVersion("1.13.0"))
    assertFalse(isSupportedComposeVersion("not-a-version"))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*PluginCompatibilityTest*'`
Expected: FAIL, unresolved reference `isSupportedComposeVersion`.

- [ ] **Step 3: Write minimal implementation**

Append to `PluginCompatibility.kt`:

```kotlin
private val COMPOSE_RUNTIME_VERSION = Regex(
    """^(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-(?:alpha|beta|rc|dev)[0-9.]*)?$""",
)

/**
 * Compose ships long alpha, beta and rc lines that real projects build on, so
 * unlike the AGP rule this one accepts a pre-release suffix and compares only
 * the numeric part.
 */
internal fun isSupportedComposeVersion(value: String): Boolean {
    if (!COMPOSE_RUNTIME_VERSION.matches(value.trim())) return false
    val version = parseToolVersion(value) ?: return false
    return version >= ToolVersion(1, 11, 0) && version < ToolVersion(1, 13, 0)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests '*PluginCompatibilityTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/yuroyami/kitessot/PluginCompatibility.kt src/test/kotlin/io/github/yuroyami/kitessot/PluginCompatibilityTest.kt
git commit -m "feat: add the supported Compose Gradle plugin range"
```

---

## Task 7: The Compose dependency and classpath probe

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts:215-229` and `:370-381`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotPlugin.kt` companion, near `:1455-1504`
- Regenerate: `gradle.lockfile`

**Interfaces:**
- Produces: `internal val COMPOSE_ON_CLASSPATH: Boolean`, `private fun runtimeComposeVersion(): String?`

- [ ] **Step 1: Add the version catalog entries**

In `gradle/libs.versions.toml`, under `[versions]`:

```toml
compose = "1.12.0-rc01"
```

Under `[libraries]`:

```toml
compose-gradle-plugin = { module = "org.jetbrains.compose:compose-gradle-plugin", version.ref = "compose" }
```

- [ ] **Step 2: Add the compileOnly dependency**

In `build.gradle.kts`, inside `dependencies { }`, after the KGP lines:

```kotlin
    // Compose Desktop identity propagation needs the typed DSL classes at compile
    // time only. Consumers bring their own Compose Gradle plugin.
    compileOnly(libs.compose.gradle.plugin)
```

And in the TestKit block, so fixtures can apply it:

```kotlin
dependencies {
    testKitPluginClasspath(libs.kotlin.gradle.plugin)
    testKitPluginClasspath(libs.compose.gradle.plugin)
}
```

- [ ] **Step 3: Add the classpath probe**

In the `KiteSsotPlugin` companion, next to `KGP_ON_CLASSPATH`:

```kotlin
/**
 * Whether the (compileOnly) Compose Gradle plugin classes are loadable from
 * kitessot's own classloader. False when the consumer declares
 * org.jetbrains.compose only in a subproject, which puts it in a sibling
 * classloader.
 */
internal val COMPOSE_ON_CLASSPATH: Boolean = try {
    Class.forName(
        "org.jetbrains.compose.desktop.DesktopExtension",
        false,
        KiteSsotPlugin::class.java.classLoader,
    )
    true
} catch (_: ClassNotFoundException) {
    false
} catch (_: LinkageError) {
    false
}

private fun runtimeComposeVersion(): String? = runCatching {
    Class.forName(
        "org.jetbrains.compose.ComposePlugin",
        false,
        KiteSsotPlugin::class.java.classLoader,
    ).`package`?.implementationVersion
}.getOrNull()
```

- [ ] **Step 4: Regenerate the lockfile**

Run: `./gradlew dependencies --write-locks`

- [ ] **Step 5: Verify the runtime classpath stayed empty**

Run: `grep -n "^empty=" gradle.lockfile`
Expected: the line still lists `runtimeClasspath`. If it does not, the dependency was added in the wrong scope. Fix before continuing, because the SBOM claims zero runtime dependencies.

- [ ] **Step 6: Build**

Run: `./gradlew build`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts gradle.lockfile src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotPlugin.kt
git commit -m "build: add the Compose Gradle plugin as a compileOnly peer"
```

---

## Task 8: The `desktop { }` DSL type

**Files:**
- Create: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotDesktopExtension.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotModulesExtension.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotExtension.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotPlugin.kt` (extension creation, `modelValues`)
- Modify: `api/kitessot.api`
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/KiteSsotPluginFunctionalTest.kt`

**Interfaces:**
- Produces on `KiteSsotDesktopExtension`: `enabled`, `configured` (internal), `idSuffix`, `buildNumber`, `rebuild`, `scheme` (property plus `fun scheme(s: VersionCodeScheme)`), `publishedBuildNumber`, `icons`, `roundMacOsIcon`, `linuxPackageName`, `deriveUpgradeUuid`.
- Produces on `KiteSsotModulesExtension`: `desktopApps: ListProperty<String>` plus `fun desktopApps(vararg paths: String)`.
- Produces on `KiteSsotExtension`: `desktop` accessor, `fun desktop(action: Action<in KiteSsotDesktopExtension>)`, `effectiveDesktopEnabled`, `effectiveDesktopApps`, `desktopBundleId`, `effectiveDesktopBuildNumber`, `effectiveDesktopIcons`.

- [ ] **Step 1: Write the failing functional test**

Append to `KiteSsotPluginFunctionalTest.kt`:

```kotlin
@Test
fun `the desktop block is accepted and reported by verify`() {
    write("settings.gradle.kts", settingsWithShared())
    write(
        "build.gradle.kts",
        """
        plugins {
            id("org.jetbrains.kotlin.multiplatform") apply false
            id("io.github.yuroyami.kitessot")
        }
        kiteSsot {
            modules { shared = ":shared" }
            appName = "Demo"
            version = "1.2.3"
            appId = "com.acme.app"
            desktop {
                idSuffix = ".desktop"
                rebuild = 2
            }
        }
        """.trimIndent(),
    )
    write("shared/build.gradle.kts", """
        plugins { id("org.jetbrains.kotlin.multiplatform") }
        kotlin { jvm() }
    """.trimIndent())

    val result = run("kiteSsotVerify")
    assertTrue(result.output.contains("com.acme.app.desktop"), result.output)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*KiteSsotPluginFunctionalTest*desktop*'`
Expected: FAIL, `desktop` is not a known DSL member.

- [ ] **Step 3: Create the extension type**

Create `KiteSsotDesktopExtension.kt`. Give every property KDoc that states whether it is optional, its default, and what it needs. Mirror `KiteSsotIosExtension.kt` for `buildNumber`, `rebuild`, `scheme` and `publishedBuildNumber`, and `KiteSsotIoWorkerExtension.kt` for `enabled` plus `configured`:

```kotlin
package io.github.yuroyami.kitessot

import org.gradle.api.provider.Property

abstract class KiteSsotDesktopExtension {
    abstract val enabled: Property<Boolean>
    internal abstract val configured: Property<Boolean>
    abstract val idSuffix: Property<String>
    abstract val buildNumber: Property<String>
    abstract val rebuild: Property<Int>
    abstract val scheme: Property<VersionCodeScheme>
    abstract val publishedBuildNumber: Property<String>
    abstract val icons: Property<Boolean>
    abstract val roundMacOsIcon: Property<Boolean>
    abstract val linuxPackageName: Property<String>
    abstract val deriveUpgradeUuid: Property<Boolean>

    /** Same as assigning [scheme]. Lets both the Kotlin and Groovy DSLs write `scheme { v -> ... }`. */
    fun scheme(s: VersionCodeScheme) {
        scheme.set(s)
    }
}
```

- [ ] **Step 4: Add the selector**

In `KiteSsotModulesExtension.kt`, following `androidApps`:

```kotlin
abstract val desktopApps: ListProperty<String>

/** This adds, never replaces, so repeated calls pile up. */
fun desktopApps(vararg paths: String) {
    desktopApps.addAll(*paths)
}
```

- [ ] **Step 5: Expose it on the root extension**

In `KiteSsotExtension.kt`, after the `buildConfig` accessor:

```kotlin
val desktop: KiteSsotDesktopExtension
    get() = nested()

fun desktop(action: Action<in KiteSsotDesktopExtension>) {
    desktop.configured.set(true)
    action.execute(desktop)
}
```

And in the internal model section:

```kotlin
internal val effectiveDesktopEnabled: Provider<Boolean>
    get() = desktop.configured.orElse(false)
        .zip(desktop.enabled.orElse(true)) { configured, enabled -> configured && enabled }

internal val effectiveDesktopApps: Provider<List<String>>
    get() = modules.desktopApps

val desktopBundleId: Provider<String>
    get() = effectiveAppId.zip(desktop.idSuffix.orElse("")) { base, suffix ->
        validateAppleBundleId(base + suffix)
    }

internal val effectiveDesktopBuildNumber: Provider<String>
    get() = desktop.buildNumber.orElse(
        effectiveVersion.zip(
            desktop.rebuild.orElse(0).zip(desktop.scheme.orElse(schemeOrDefault)) { r, s -> r to s },
        ) { version, (rebuild, activeScheme) ->
            computeVersionCode(activeScheme, version, rebuild, "desktop").toString()
        },
    )

internal val effectiveDesktopIcons: Provider<Boolean>
    get() = effectiveDesktopEnabled
        .zip(desktop.icons.orElse(true)) { on, icons -> on && icons }
        .zip(effectivePropagateLogo) { wanted, logo -> wanted && logo }
```

- [ ] **Step 6: Create the extension in the plugin**

In `KiteSsotPlugin.apply`, after the `buildConfig` creation:

```kotlin
extAware.extensions.create<KiteSsotDesktopExtension>("desktop").apply {
    rebuild.convention(0)
}
```

And in the `modules` creation block, add `desktopApps.convention(emptyList())`.

- [ ] **Step 7: Register every new value for model freezing**

Add to `modelValues(ext)`:

```kotlin
    ext.desktop.enabled, ext.desktop.configured, ext.desktop.idSuffix,
    ext.desktop.buildNumber, ext.desktop.rebuild, ext.desktop.scheme,
    ext.desktop.publishedBuildNumber, ext.desktop.icons,
    ext.desktop.roundMacOsIcon, ext.desktop.linuxPackageName,
    ext.desktop.deriveUpgradeUuid, ext.modules.desktopApps,
```

A property missing here stays mutable after root evaluation, and nothing catches it.

- [ ] **Step 8: Add the Desktop section to verify**

In `KiteSsotVerifyTask.kt`, add `@get:Internal` properties for the desktop bundle ID, build number and icon state, then a `Desktop` section after `App logo`, using `show(...)` for scalars. Bind them in `registerVerifyTask`.

- [ ] **Step 9: Run the test**

Run: `./gradlew test --tests '*KiteSsotPluginFunctionalTest*desktop*'`
Expected: PASS.

- [ ] **Step 10: Update the ABI baseline**

Run: `./gradlew updateKotlinAbi`
Then review the diff. It should add `KiteSsotDesktopExtension`, two members on `KiteSsotExtension`, two on `KiteSsotModulesExtension`, and nothing else. `configured` is `internal` and must NOT appear.

- [ ] **Step 11: Run the full suite**

Run: `./gradlew build`
Expected: PASS, including `checkKotlinAbi`.

- [ ] **Step 12: Commit**

```bash
git add src/main api/kitessot.api src/test
git commit -m "feat: add the desktop DSL block and its resolved model"
```

---

## Task 9: Detection and identity writes

**Files:**
- Create: `src/main/kotlin/io/github/yuroyami/kitessot/DesktopWiring.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotPlugin.kt` (`allprojects`, `projectsEvaluated`)
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/KiteSsotPluginFunctionalTest.kt`

**Interfaces:**
- Consumes: `effectiveDesktopEnabled`, `effectiveDesktopApps`, `desktopBundleId`, `effectiveDesktopBuildNumber`, `effectiveAppName`, `effectiveVersion`, the four `propagate` providers, `validateDesktopPackageVersion`, `deriveLinuxPackageName`.
- Produces: `internal object DesktopWiring { fun write(project: Project, ext: KiteSsotExtension, resilient: Boolean) ; fun isDesktopApp(project: Project): Boolean }`

- [ ] **Step 1: Write the failing test that proves ordering**

This is the load-bearing test. It proves KiteSSOT's `afterEvaluate` runs before Compose reads the fields, and it covers the drift rule at the same time.

```kotlin
@Test
fun `the SSOT replaces a package name the desktop module declared itself`() {
    write("settings.gradle.kts", settingsWithShared().replace("include(\":shared\")", "include(\":shared\")\ninclude(\":desktopApp\")"))
    write(
        "build.gradle.kts",
        """
        plugins {
            id("org.jetbrains.kotlin.multiplatform") apply false
            id("org.jetbrains.compose") apply false
            id("io.github.yuroyami.kitessot")
        }
        kiteSsot {
            modules { shared = ":shared" }
            appName = "Demo"
            version = "1.2.3"
            appId = "com.acme.app"
            desktop { }
        }
        """.trimIndent(),
    )
    write("shared/build.gradle.kts", """
        plugins { id("org.jetbrains.kotlin.multiplatform") }
        kotlin { jvm() }
    """.trimIndent())
    write(
        "desktopApp/build.gradle.kts",
        """
        plugins {
            id("org.jetbrains.kotlin.multiplatform")
            id("org.jetbrains.compose")
        }
        kotlin { jvm() }
        compose.desktop {
            application {
                mainClass = "MainKt"
                nativeDistributions { packageName = "StaleName" }
            }
        }
        tasks.register("printPackageName") {
            val resolved = provider { compose.desktop.application.nativeDistributions.packageName }
            doLast { println("RESOLVED=" + resolved.get()) }
        }
        """.trimIndent(),
    )

    val result = run(":desktopApp:printPackageName")
    assertTrue(result.output.contains("RESOLVED=Demo"), result.output)
    assertTrue(result.output.contains("StaleName"), "the drift warning should name what was replaced: ${result.output}")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*SSOT replaces a package name*'`
Expected: FAIL, output contains `RESOLVED=StaleName`.

- [ ] **Step 3: Write the wiring**

Create `DesktopWiring.kt`. Keep every Compose type inside this file.

Detection must read the initialization flag reflectively **before** touching `getApplication()`, because touching it initializes the lazy delegate and makes Compose register packaging tasks the user never asked for:

```kotlin
private fun initialized(desktop: Any, accessor: String): Boolean = runCatching {
    val method = desktop.javaClass.getMethod(accessor)
    method.invoke(desktop) as Boolean
}.getOrDefault(false)

internal fun isDesktopApp(project: Project): Boolean {
    val compose = project.extensions.findByName("compose") as? ExtensionAware ?: return false
    val desktop = compose.extensions.findByName("desktop") ?: return false
    return initialized(desktop, "get_isJvmApplicationInitialized\$compose") ||
        initialized(desktop, "get_isNativeApplicationInitialized\$compose")
}
```

Writes go through `project.wireValueGroup(resilient, "<group>") { }` and record replacements through `SsotDriftLog`, matching `ClassicAndroidWiring.kt:60-94`. Write into `AbstractDistributions` and `AbstractMacOSPlatformSettings` so one code path serves both the JVM and native application models. Only the JVM path touches `windows` and `linux`.

- [ ] **Step 4: Register the callback early in the plugin**

In `KiteSsotPlugin.apply`, inside the existing `target.allprojects { }` block, and **not** inside a `plugins.withId` callback:

```kotlin
// Registered during root evaluation, so it lands before the Compose plugin
// registers its own afterEvaluate in this subproject. Inside plugins.withId
// it would be registered after Compose's, and every write would be too late.
consumerProject.afterEvaluate {
    if (!COMPOSE_ON_CLASSPATH || !KGP_ON_CLASSPATH) return@afterEvaluate
    if (!ext.effectiveDesktopEnabled.get()) return@afterEvaluate
    DesktopWiring.write(consumerProject, ext, isResilientDiagnosticInvocation(target))
}
```

- [ ] **Step 5: Add the census checks**

In `gradle.projectsEvaluated`, after the resilient early return, add the fail-closed selection rules from spec section 7: several detected desktop apps with no selector, and a selected path that is not a desktop app. Mirror the `KMPS070` message wording. Add the hard `[KITESSOT-COMPAT-007]` failure for a requested desktop feature with Compose missing from the shared classloader.

- [ ] **Step 6: Run the test**

Run: `./gradlew test --tests '*SSOT replaces a package name*'`
Expected: PASS.

- [ ] **Step 7: Add the remaining functional tests**

Add, from the spec section 13 table: the native application variant, the two-apps selection failure, and a Compose UI module that is not an app getting no packaging tasks.

- [ ] **Step 8: Run the full suite**

Run: `./gradlew build`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main src/test
git commit -m "feat: propagate desktop identity into Compose Desktop"
```

---

## Task 10: The icon generator and its lazy wiring

**Files:**
- Create: `src/main/kotlin/io/github/yuroyami/kitessot/GenerateDesktopIconsTask.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/DesktopWiring.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotPlugin.kt`
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/KiteSsotPluginFunctionalTest.kt`

**Interfaces:**
- Consumes: `writeIcns`, `writeIco`, `encodePng`, `resize`, `applyRoundedRectMask`, `readBoundedLogoPngSnapshot`, `parseLogoBackgroundColor`, `solidColorImage`, `OwnedOutputSafety.replaceGeneratedTree`.
- Produces: `abstract class GenerateDesktopIconsTask : DefaultTask()` with `@get:OutputDirectory outputDir: DirectoryProperty`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `packaging depends on generated desktop icons without an explicit dependsOn`() {
    // fixture as in Task 9, plus:
    //   kiteSsot { logo { foreground = file("art/logo_fg.png"); backgroundColor = "#102A43" } }
    // and writePng("art/logo_fg.png", 512)
    val result = run(":desktopApp:packageDistributionForCurrentOS", "--dry-run")
    assertTrue(
        result.output.contains(":desktopApp:generateKiteSsotDesktopIcons SKIPPED"),
        "the icon task must be in the packaging graph: ${result.output}",
    )
}
```

Use `packageDistributionForCurrentOS`, never `packageDmg`. The per-format tasks only exist on a matching host.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*packaging depends on generated desktop icons*'`
Expected: FAIL, the task is not in the graph.

- [ ] **Step 3: Write the task**

Follow `GenerateIoWorkerTask.kt` exactly: `@CacheableTask`, `group = "kitessot"`, a `@get:Internal generatedRoot` conventioned to `project.layout.buildDirectory.dir("generated/kitessot")`, a `@get:OutputDirectory outputDir`, `@get:Internal dryRun` that is deliberately ignored with an info log, and `OwnedOutputSafety.replaceGeneratedTree(owner = "desktop-icons", ...)`.

Compose the square once, then emit three files:

```kotlin
val base = /* foreground contained over covered background, flattened to opaque, 1024px */
val macSource = if (roundMacOsIcon.get()) applyRoundedRectMask(base, MACOS_CORNER_RATIO) else base
val files = mapOf(
    "app.icns" to writeIcns(macSource),
    "app.ico" to writeIco(base),
    "app.png" to encodePng(resize(base, 512, 512), "Linux app icon"),
)
```

- [ ] **Step 4: Register it and wire the providers**

Register the task on each selected desktop app project. Wire with providers so Gradle infers the dependency:

```kotlin
macOS.iconFile.set(iconTask.flatMap { it.outputDir.file("app.icns") })
windows.iconFile.set(iconTask.flatMap { it.outputDir.file("app.ico") })
linux.iconFile.set(iconTask.flatMap { it.outputDir.file("app.png") })
```

Never call `.get()` on the directory at configuration time.

- [ ] **Step 5: Add the icons-without-logo failure**

In the root `afterEvaluate` validation block, next to the existing logo checks:

```kotlin
if (ext.desktop.icons.orNull == true && !ext.effectivePropagateLogo.get()) {
    throw GradleException(
        "kiteSsot { desktop { icons = true } } needs a logo { } block with a foreground and " +
            "exactly one of background or backgroundColor. Configure logo { }, or remove " +
            "desktop { icons }.",
    )
}
```

Note this fires only for an explicit `icons = true`. Leaving `logo { }` unconfigured turns desktop icons off quietly.

- [ ] **Step 6: Add a test for that failure**

```kotlin
@Test
fun `desktop icons without a logo block fail and name both blocks`() {
    // fixture with desktop { icons = true } and no logo { }
    val result = runAndFail("kiteSsotVerify")
    assertTrue(result.output.contains("desktop { icons = true }"), result.output)
    assertTrue(result.output.contains("logo { }"), result.output)
}
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew build`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main src/test
git commit -m "feat: generate desktop app icons into build and wire them lazily"
```

---

## Task 11: The derived Windows upgrade code

**Files:**
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/DesktopWiring.kt`
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/KiteSsotPluginFunctionalTest.kt`

**Interfaces:**
- Consumes: `deriveUpgradeUuid` from Task 5.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `the derived upgrade uuid is applied only when asked and never overwrites an explicit value`() {
    // fixture with desktop { deriveUpgradeUuid = true }, and a task printing
    // compose.desktop.application.nativeDistributions.windows.upgradeUuid
    val result = run(":desktopApp:printUpgradeUuid")
    assertTrue(result.output.contains("RESOLVED=" + deriveUpgradeUuid("com.acme.app")), result.output)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*derived upgrade uuid*'`
Expected: FAIL, the value is null.

- [ ] **Step 3: Write the implementation**

In the JVM-only part of `DesktopWiring`, gated on `propagate { bundleId }`:

```kotlin
if (ext.desktop.deriveUpgradeUuid.getOrElse(false) && windows.upgradeUuid == null) {
    windows.upgradeUuid = deriveUpgradeUuid(ext.effectiveAppId.get())
}
```

The `== null` check is what keeps an explicit user value, per spec section 5.

- [ ] **Step 4: Run the test**

Run: `./gradlew test --tests '*derived upgrade uuid*'`
Expected: PASS.

- [ ] **Step 5: Write the failing test for the release guard**

`desktop { publishedBuildNumber }` is declared and documented in Task 8 but nothing consumes it yet. A release
guard that silently does nothing is worse than no guard, because the user believes they are protected. This
step gives it teeth, mirroring `ios { publishedBuildNumber }`.

```kotlin
@Test
fun `a desktop build number that does not beat the published baseline fails the build`() {
    // fixture with desktop { publishedBuildNumber = "9999999999" } and version = "1.2.3",
    // whose resolved desktop build number is far lower
    val result = runAndFail("kiteSsotVerify")
    assertTrue(result.output.contains("9999999999"), result.output)
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew test --tests '*published baseline*'`
Expected: FAIL, because the build currently succeeds. The property is inert today.

- [ ] **Step 7: Implement the guard**

In `KiteSsotPlugin`, inside `gradle.projectsEvaluated` and after the resilient-diagnostic early return, beside
the existing Android and iOS guards. Reuse the existing validator, do not write a second one:

```kotlin
if (detectedDesktopApplications.isNotEmpty() && ext.effectivePropagateVersion.get()) {
    ext.desktop.publishedBuildNumber.orNull?.let { published ->
        validatePublishedBuildNumber(ext.effectiveDesktopBuildNumber.orNull, published)
    }
}
```

This matches `KiteSsotPlugin.kt:520-524`, which does the same for iOS. Scoping it on detection plus the
propagate switch is deliberate: an unconfigured desktop build must never fail on a stale baseline.

- [ ] **Step 8: Run the test**

Run: `./gradlew test --tests '*published baseline*'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main src/test
git commit -m "feat: derive a stable Windows upgrade code from appId"
```

Then a second commit for the guard:

```bash
git commit -m "feat: enforce the desktop published build number baseline"
```

- [ ] **Step 10: Write the failing test for the root-project edge case**

Design section 6 requires this and no earlier brief covered it. The whole ordering guarantee rests on the root
project being evaluated before subprojects, so a callback registered from `allprojects { }` always beats
Compose's own. That reasoning collapses when Compose is applied to the ROOT project itself: ordering then
depends on the order of entries in the root `plugins { }` block. Writing values that may or may not land is
exactly the silent failure this design exists to prevent, so KiteSSOT must detect it and fail loudly.

```kotlin
@Test
fun `applying Compose to the root project fails with a clear message`() {
    // fixture: root build.gradle.kts applies org.jetbrains.compose WITHOUT apply false,
    // alongside the kitessot plugin, and configures a desktop { } block
    val result = runAndFail("kiteSsotVerify")
    assertTrue(result.output.contains("root project"), result.output)
    assertTrue(result.output.contains("apply false"), result.output)
}
```

- [ ] **Step 11: Run it to verify it fails**

Run: `./gradlew test --tests '*Compose to the root project*'`
Expected: FAIL, because the build currently succeeds and writes values with undefined ordering.

- [ ] **Step 12: Implement the detection**

In `KiteSsotPlugin`, inside `gradle.projectsEvaluated`, beside the other desktop census checks, gated so it
only fires when desktop work is actually requested:

```kotlin
if (ext.effectiveDesktopEnabled.get() &&
    target.plugins.hasPlugin("org.jetbrains.compose") &&
    DesktopWiring.isDesktopApp(target)
) {
    throw GradleException(
        "kiteSsot cannot propagate desktop identity when org.jetbrains.compose is applied to the root " +
            "project and the root project is itself the desktop app. Ordering against Compose is only " +
            "guaranteed for subprojects. Declare Compose at the root with `apply false` and move the " +
            "application into its own module.",
    )
}
```

Fail loudly rather than writing values whose landing order is undefined. Note the message must not merely say
what is wrong; it must give the one line that fixes it, matching the `KMPS070` wording style used elsewhere.

- [ ] **Step 13: Run the test and commit**

Run: `./gradlew test --tests '*Compose to the root project*'`
Expected: PASS.

```bash
git commit -m "feat: refuse desktop propagation when Compose is applied at the root"
```

---

## Task 12: Diagnostics

**Files:**
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotDiagnostics.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotDoctorTask.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kitessot/KiteSsotPlugin.kt` (`bindDiagnosticInputs`, `configurePlanTask`)
- Test: `src/test/kotlin/io/github/yuroyami/kitessot/KiteSsotDiagnosticsTest.kt`

- [ ] **Step 1: Write the failing tests**

One test per ID, asserting the severity and that the ID appears. Follow the existing `KiteSsotDiagnosticsTest` style, which builds a `KiteSsotDiagnosticContext` directly and calls `KiteSsotDiagnosticEngine.evaluate`.

```kotlin
@Test
fun `desktop identity reports SKIPPED when the block is off`() {
    val findings = KiteSsotDiagnosticEngine.evaluate(baseContext.copy(propagateDesktop = false))
    val finding = findings.single { it.id == "KMPS080" }
    assertEquals(KiteSsotDiagnosticSeverity.SKIPPED, finding.severity, finding.toString())
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*KiteSsotDiagnosticsTest*'`
Expected: FAIL, no finding with that ID.

- [ ] **Step 3: Implement the checks**

Add the context fields, then a `private fun MutableList<KiteSsotDiagnostic>.diagnoseDesktop(context)` added to the `evaluate` `buildList`. IDs, one title each, kept stable because SARIF turns them into rules:

| ID | Title |
| --- | --- |
| `KMPS080` | Desktop identity propagation |
| `KMPS081` | Desktop app icons |
| `KMPS082` | Compose Gradle plugin compatibility |
| `KMPS083` | Desktop application selection |
| `KMPS084` | Desktop bundle identifier |
| `KMPS085` | Desktop package version |
| `KMPS086` | Desktop Linux package name |
| `KMPS087` | Windows upgrade code |

Emit exactly one finding per ID per subject, including a `SKIPPED` finding when the feature is off. Never silently omit.

`KMPS082` mirrors `KMPS061`. Use `resolve("KMPS941", ...)` and up in `bindDiagnosticInputs` for each new provider read.

- [ ] **Step 4: Add the plan policy entry**

In `configurePlanTask`, add a `policies` entry `"desktop.identity" to "<enabled|disabled>"`. Do **not** add anything to `operations` or `mutationPaths`. Desktop writes go to `build/`, and `kiteSsotPlan` lists source mutations only.

- [ ] **Step 5: Run the tests**

Run: `./gradlew build`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main src/test
git commit -m "feat: diagnose the desktop block in doctor and check"
```

---

## Task 13: Documentation

**Files:**
- Modify: `README.md:178`, `:224-233`, `:247-259`, and the root `plugins { }` example at `:38-46`
- Modify: `FEATURES.md:13-16`, `:25-35`, `:56-89`, a new section near `:396`, `:199-208`, `:594-604`, `:639+`
- Modify: `CHANGELOG.md:7`

- [ ] **Step 1: Add the DSL block to the README**

After `buildConfig { }`, in the same annotated style, with a trailing `// default: ...` on every line. Also add `id("org.jetbrains.compose") version "1.12.0" apply false` to the root plugins example, and explain that the root declaration is required for the same classloader reason Kotlin and AGP already carry.

- [ ] **Step 2: Add the task table row**

```
| `generateKiteSsotDesktopIcons` | `build/` | The desktop `.icns`, `.ico` and `.png` icons |
```

- [ ] **Step 3: Classify it in the two-tiers section**

Desktop identity and icons are in the automatic tier, alongside `buildConfig` and `web { ioWorker }`. Say so explicitly, because `logo { }` being an authorization gate for Android and Apple makes the desktop behaviour surprising otherwise.

- [ ] **Step 4: Write the FEATURES.md section**

Follow the house template: an H2 prose name, a short intro naming the block, `When enabled, KiteSSOT:` then semicolon-terminated bullets, then prose for requirements and rejections. State the Windows version caps and the bundle ID charset explicitly, and record the UUID namespace constant. Add the new IDs to the diagnostic family table at `:594-604`.

- [ ] **Step 5: Add the limits**

In `README.md` "Limits" and `FEATURES.md` "Current limitations", state: the scheme result reaches macOS only; locales are not propagated; `vendor`, `description` and `copyright` are not managed; detection reads Kotlin `internal` members reflectively and degrades to explicit selection if that breaks; nobody has yet verified a real signed DMG, a real MSI upgrade across two versions, or a real Debian install.

- [ ] **Step 6: Add the changelog entry**

A new `## [3.1.0]` at line 7 with `### Added`. Lead with the real-world problem, as the existing entries do.

- [ ] **Step 7: Check style**

Run:

```bash
grep -c "—\|–" README.md FEATURES.md CHANGELOG.md specs/2026-08-24-desktop-block-design.md
```

Expected: `0` for every file.

- [ ] **Step 8: Run the full build**

Run: `./gradlew build`
Expected: PASS, including `KdocExampleCompilationTest` and `MessageHygieneTest`.

- [ ] **Step 9: Commit**

```bash
git add README.md FEATURES.md CHANGELOG.md
git commit -m "docs: document the desktop block"
```

---

## Self-Review

**Spec coverage.** Every spec section maps to a task: section 4 to Task 8; section 5 to Tasks 9 and 11; section 6 to Task 9; section 7 to Task 9; section 8 to Tasks 1 through 4 and 10; section 9 to Tasks 5 and 11; section 10 to Task 5; section 11 to Tasks 6 and 7; section 12 to Task 12; section 13 spread across the tasks that add each test; section 14 to Task 13.

**Deviation from the spec's step order, deliberate.** Spec section 16 lists 10 steps; this plan has 13 tasks. The spec's step 1 is split into Tasks 1 and 2, because the two lifts touch different call sites and each deserves its own green build. The spec's step 2 is split into Tasks 3 and 4, one container format each. Everything else maps one to one.

**Type consistency check.** `encodePng(image, label)` is defined in Task 1 and used with that exact two-argument shape in Tasks 3, 4 and 10. `resize(src, w, h)` is pre-existing. `writeIcns(square)` and `writeIco(square)` take one argument in Tasks 3 and 4 and are called that way in Task 10. `applyRoundedRectMask(src, cornerRatio)` is defined in Task 2 and called with two arguments in Task 10. `deriveUpgradeUuid(appId)` is defined in Task 5 and used in Tasks 5 and 11. `validateAppleBundleId` is pre-existing and reused rather than duplicated.

**Known open item for Task 10.** `MACOS_CORNER_RATIO` is referenced in the Task 10 code sketch and must be declared in `GenerateDesktopIconsTask.kt` as a private constant. Apple's icon grid puts the corner radius near `0.225` of the side. The exact value is a visual judgement, so Task 10 step 3 should set it, render once, and confirm on a real macOS Dock before committing.

## Execution Handoff

Plan complete and saved to `specs/2026-08-24-desktop-block-plan.md`. Two execution options:

1. **Subagent-Driven (recommended).** A fresh subagent per task, with review between tasks and fast iteration.
2. **Inline Execution.** Tasks executed in this session with batch checkpoints for review.
