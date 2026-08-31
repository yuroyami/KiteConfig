# KiteConfig 1.0.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the KiteSSOT plugin to KiteConfig and give it a read-only API so any build file can read the resolved configuration it already computes.

**Architecture:** Two commit series in one release. Series 1 (Tasks 1 to 6) is a mechanical rename that changes no behaviour. Series 2 (Tasks 7 to 11) adds a `KiteConfigValues` interface implemented by the existing extension, reached through a `Project.kiteConfig` accessor. The interface is a narrower view of the same object, so no resolution logic is duplicated. Task 12 installs the result to local Maven and hands off without publishing.

**Tech Stack:** Kotlin, Gradle plugin (`kotlin-dsl`, `java-gradle-plugin`), JUnit 5, Gradle TestKit, `ProjectBuilder` test fixtures, Kotlin ABI validation.

**Spec:** `docs/superpowers/specs/2026-08-31-kiteconfig-1.0.0-design.md`

## Current state

The repository is at a **clean KiteSSOT baseline**. Verified: the working tree
has no uncommitted changes, and there is zero code difference between the 4.2.0
release commit and HEAD. Source still lives under
`src/main/kotlin/io/github/yuroyami/kitessot/`, the plugin id is still
`io.github.yuroyami.kitessot`, and 94 `KMPS###` codes are untouched.

Start at Task 1 and work through in order. Nothing is pre-applied.

### History worth knowing

An earlier attempt applied most of Series 1, was lost to a git accident, and was
reverted. Nothing survived except two lessons, both already folded into this
plan: the five files listed below, and the bare `Ssot` rule in Task 1.

One process rule came out of it. **Only one agent or session may work this
repository at a time.** The loss happened because two were editing the same
working tree and one committed the other's staged files. When committing, always
scope the pathspec explicitly, for example
`git commit -- docs/superpowers/plans/`, because a bare `git commit` writes the
entire index including anything another process staged.

## Files this plan first missed

The already-applied rename touched these, and the original task list did not
name them. Any re-run must include them:

- `dokka-templates/includes/header.ftl`
- `FEATURES.md`
- `.gitignore`
- `docs/requirements.txt`
- `src/test/resources/fixtures/RealApp.xcodeproj/project.pbxproj`

Also add a bare `Ssot` to `Config` rule, applied after the `KiteSsot` rule, to
catch names like `SsotValidation` that carry no `Kite` prefix.

## Global Constraints

- **Zero consumers.** The plugin has no users other than its author. No deprecation shims, no compatibility layer, no migration guide. Clean breaks everywhere.
- **Old name mapping**, applied everywhere except the exemptions below:
  - `io.github.yuroyami.kitessot` becomes `io.github.yuroyami.kiteconfig` (plugin id, Maven coordinate, package).
  - `KiteSsot` class prefix becomes `KiteConfig`.
  - `kiteSsot` becomes `kiteConfig` (DSL block, Gradle property prefix, plugin registration name).
  - `kitessot` becomes `kiteconfig` (lowercase: property prefixes, backup dir, generated package).
  - `KiteSSOT` becomes `KiteConfig` (display text).
  - `KMPS` diagnostic prefix becomes `KTCNFG`, keeping the three-digit numbers, so `KMPS021` becomes `KTCNFG021`.
- **Deliberately unchanged:** task names (`kiteDoctor`, `kiteRewriteLogo`, `kiteRewriteXcode`, `kitePlan`, `kiteCheck`), the `DiscouragedKiteApi` annotation, and all diagnostic code numbers.
- **Rename exemptions.** These keep their original wording as historical record: the README history section, `CHANGELOG.md`, `OVERHAUL.md`, `SOLAUDIT.md`, and everything under `docs/superpowers/specs/`.
- **Version is `1.0.0`** under the new coordinate `io.github.yuroyami:kiteconfig`.
- **No em-dashes** in any Markdown, KDoc, or code comment.
- **ABI validation is enabled.** Any public API change requires `./gradlew updateKotlinAbi` and committing the regenerated dump, or `checkKotlinAbi` fails CI.
- **The unset contract.** Read-back accessors supply no defaults and return no nulls. Documentation must never present `.orNull` as the normal way to read them.
- **Do not publish.** Never run `publishPlugins`, never push a `v*` tag, and never trigger the publish workflow. The author releases only after consuming the plugin from local Maven in every consumer repository on their machine and confirming each one builds. The plan ends at a local install plus a handoff.

## Two traps that will bite

Read these before starting. Both are real and both are silent until a test fails.

**Trap 1: `MessageHygieneTest` scans string literals for retired 2.x names.** Its retired list includes `iosBuildNumber` and `iosMarketingVersion`, which Series 2 adds as public members. Identifiers are fine because the scan only looks inside double quotes. The pardon list is keyed by **filename**, and `KiteSsotExtension.kt` is pardoned for every retired name. Two consequences: the pardon key must be renamed to `KiteConfigExtension.kt` in Task 1, and the new `KiteConfigValues.kt` is **not** pardoned, so no string literal in it may contain `iosBuildNumber` or `iosMarketingVersion`.

**Trap 2: `KdocExampleCompilationTest` extracts and executes every `kiteSsot { }` fence** from main source KDoc and runs it through a real Gradle build. It contains the literal string `kiteSsot {` as its fence delimiter. Rename that delimiter in Task 1 or every KDoc example silently stops being collected and the test passes while checking nothing.

---

## Series 1: the rename

### Task 1: Rename the package, classes, and DSL block across all source

This is one atomic task because a package rename that touches main but not tests leaves the build uncompilable. Everything moves together.

**Files:**
- Move: `src/main/kotlin/io/github/yuroyami/kitessot/` to `src/main/kotlin/io/github/yuroyami/kiteconfig/` (65 files)
- Move: `src/test/kotlin/io/github/yuroyami/kitessot/` to `src/test/kotlin/io/github/yuroyami/kiteconfig/` (47 files)
- Move: `src/agp8Adapter/java/io/github/yuroyami/kitessot/` to `src/agp8Adapter/java/io/github/yuroyami/kiteconfig/` (1 file: `Agp8ClassicAndroidWiring.java`)
- Modify: `src/test/kotlin/io/github/yuroyami/kiteconfig/MessageHygieneTest.kt` (pardon key)
- Modify: `src/test/kotlin/io/github/yuroyami/kiteconfig/KdocExampleCompilationTest.kt` (fence delimiter)

**Interfaces:**
- Consumes: nothing.
- Produces: the `io.github.yuroyami.kiteconfig` package, the `KiteConfigExtension` and `KiteConfigPlugin` classes, and the `kiteConfig { }` DSL block. Every later task depends on these names.

- [ ] **Step 1: Move the three source directories with git**

```bash
git mv src/main/kotlin/io/github/yuroyami/kitessot src/main/kotlin/io/github/yuroyami/kiteconfig
git mv src/test/kotlin/io/github/yuroyami/kitessot src/test/kotlin/io/github/yuroyami/kiteconfig
git mv src/agp8Adapter/java/io/github/yuroyami/kitessot src/agp8Adapter/java/io/github/yuroyami/kiteconfig
```

- [ ] **Step 2: Rewrite the identifiers inside every moved file**

Order matters. `KiteSSOT` is replaced before `KiteSsot` so the all-caps display spelling is not left half-converted.

```bash
find src -type f \( -name '*.kt' -o -name '*.java' \) -print0 \
  | xargs -0 sed -i '' \
    -e 's/io\.github\.yuroyami\.kitessot/io.github.yuroyami.kiteconfig/g' \
    -e 's/KITE_SSOT/KITE_CONFIG/g' \
    -e 's/KITESSOT/KITECONFIG/g' \
    -e 's/KiteSSOT/KiteConfig/g' \
    -e 's/KiteSsot/KiteConfig/g' \
    -e 's/kiteSsot/kiteConfig/g' \
    -e 's/kitessot/kiteconfig/g' \
    -e 's/Ssot/Config/g' \
    -e 's/SSOT/KiteConfig/g'
```

The order is load-bearing. Each rule consumes its spelling before a broader rule
can reach it, so nothing is half-converted and nothing doubles up into
`KiteKiteConfig`. Four of these catch spellings the first draft of this plan
missed entirely:

- `KITE_SSOT_REQUEST_ID` and `KITE_SSOT_DEFAULT_TIMEOUT_MILLIS` are constants in
  the **generated** IO worker source (`IoWorkerGen.kt`), so they land in consumer
  code. They become `KITE_CONFIG_*`.
- `KITESSOT-COMPAT-001` through `-006` are a second diagnostic family, separate
  from the `KMPS` codes, and appear in user-facing AGP compatibility errors.
  They become `KITECONFIG-COMPAT-###`.
- Bare `Ssot` catches names with no `Kite` prefix: `SsotValidation`,
  `SsotVersion`, `SsotDriftLog`, `parseSsotVersion`, and several test helpers.
- Bare `SSOT` is prose in KDoc, for example "uses the SSOT value". It becomes
  "KiteConfig" so the docs stop using an acronym the plugin no longer carries.

- [ ] **Step 3: Rename the class files themselves**

```bash
for f in $(find src -name 'KiteSsot*'); do
  git mv "$f" "$(dirname "$f")/$(basename "$f" | sed 's/^KiteSsot/KiteConfig/')"
done
```

- [ ] **Step 4: Fix the `MessageHygieneTest` pardon key**

The map is keyed by filename. Without this the pardon silently stops applying and every retired name inside the extension is reported as an offence.

In `src/test/kotlin/io/github/yuroyami/kiteconfig/MessageHygieneTest.kt`, confirm Step 2 rewrote the key:

```kotlin
    private val allowed = mapOf(
        "KiteConfigExtension.kt" to retiredNames.toSet(),
        "BuildConfigGen.kt" to setOf("versionName"),
        "ClassicAndroidWiring.kt" to setOf("versionName"),
    )
```

- [ ] **Step 5: Confirm the KDoc fence delimiter moved**

In `src/test/kotlin/io/github/yuroyami/kiteconfig/KdocExampleCompilationTest.kt`, the fence delimiter must now read `kiteConfig {`. Step 2's `kiteSsot` rule handles this, but verify it, because a stale delimiter makes the test collect zero examples and pass while checking nothing:

```bash
grep -n 'kiteConfig {' src/test/kotlin/io/github/yuroyami/kiteconfig/KdocExampleCompilationTest.kt
```

Expected: at least one match. Zero matches means the delimiter was missed.

- [ ] **Step 6: Verify no old identifier survives in source**

```bash
grep -rn 'kitessot\|kiteSsot\|KiteSsot\|KiteSSOT' src/ || echo "CLEAN"
```

Expected: `CLEAN`.

- [ ] **Step 7: Compile**

The build config still names the old plugin class, so this step compiles source only.

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL. If it fails on `implementationClass`, that is expected and fixed in Task 2. Any other failure is a rename miss.

- [ ] **Step 8: Commit**

```bash
git add -A src/
git commit -m "refactor!: rename the package, classes, and DSL block to KiteConfig"
```

---

### Task 2: Rename the build configuration and release endpoints

Every string here is load-bearing at publish time. A miss fails the release, not the build.

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Move: `api/kitessot.api` to `api/kiteconfig.api`

**Interfaces:**
- Consumes: `KiteConfigPlugin` from Task 1.
- Produces: plugin id `io.github.yuroyami.kiteconfig`, Gradle properties `kiteConfig.version` and `kiteConfig.releaseTag`. Task 3 must use these exact property names.

- [ ] **Step 1: Rename the root project**

In `settings.gradle.kts`:

```kotlin
rootProject.name = "kiteconfig"
```

- [ ] **Step 2: Rewrite `build.gradle.kts`**

```bash
sed -i '' \
  -e 's/io\.github\.yuroyami\.kitessot/io.github.yuroyami.kiteconfig/g' \
  -e 's/yuroyami\/KiteSSOT/yuroyami\/KiteConfig/g' \
  -e 's/KiteSSOT/KiteConfig/g' \
  -e 's/KiteSsot/KiteConfig/g' \
  -e 's/kiteSsot/kiteConfig/g' \
  -e 's/kitessot/kiteconfig/g' \
  build.gradle.kts
```

- [ ] **Step 3: Verify all eight release endpoints changed**

These are the ones that fail the pipeline rather than the build.

```bash
grep -n 'website\|vcsUrl\|maven.pkg.github.com\|Implementation-Title\|moduleName\|footerMessage\|connection.set\|url.set\|displayName\|^            id = ' build.gradle.kts
```

Expected: every line reads `KiteConfig` or `kiteconfig`. Specifically confirm the plugin id is `io.github.yuroyami.kiteconfig`, the implementation class is `io.github.yuroyami.kiteconfig.KiteConfigPlugin`, and the GitHub Packages URL is `https://maven.pkg.github.com/yuroyami/KiteConfig`.

- [ ] **Step 4: Update the plugin portal description**

The description still says "Single source of truth". Keep the meaning but drop the acronym framing, in `build.gradle.kts`:

```kotlin
            description = "Single source of truth for Kotlin Multiplatform app configuration (identity, version, bundle ids, locales, launcher assets, generated BuildConfig) propagated to Android + iOS."
```

This line needs no change. It never contained the old name. Confirm and move on.

- [ ] **Step 5: Rename the ABI dump file**

```bash
git mv api/kitessot.api api/kiteconfig.api
```

- [ ] **Step 6: Regenerate the ABI dump and build**

Run: `./gradlew updateKotlinAbi build`
Expected: BUILD SUCCESSFUL. The dump now lists `io/github/yuroyami/kiteconfig/...` classes.

- [ ] **Step 7: Commit**

```bash
git add -A build.gradle.kts settings.gradle.kts api/
git commit -m "build!: rename the plugin coordinate and release endpoints to KiteConfig"
```

---

### Task 3: Rename the CI workflows and project metadata

**Files:**
- Modify: `.github/workflows/publish.yml`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/docs.yml`
- Modify: `.github/ISSUE_TEMPLATE/bug_report.yml`
- Modify: `mkdocs.yml`

**Interfaces:**
- Consumes: the Gradle property names `kiteConfig.version` and `kiteConfig.releaseTag` from Task 2.
- Produces: a publish pipeline that matches the new artifact name.

- [ ] **Step 1: Rewrite all five files**

```bash
sed -i '' \
  -e 's/KITE_SSOT_VERSION/KITE_CONFIG_VERSION/g' \
  -e 's/yuroyami\/KiteSSOT/yuroyami\/KiteConfig/g' \
  -e 's/KiteSSOT/KiteConfig/g' \
  -e 's/kiteSsot/kiteConfig/g' \
  -e 's/kitessot/kiteconfig/g' \
  .github/workflows/publish.yml .github/workflows/ci.yml .github/workflows/docs.yml \
  .github/ISSUE_TEMPLATE/bug_report.yml mkdocs.yml
```

- [ ] **Step 2: Verify the publish workflow's jar filename checks**

This is exactly what broke the 4.1.1 release. The workflow unzips jars by literal filename.

```bash
grep -n 'kiteconfig-\|KITE_CONFIG_VERSION\|PkiteConfig' .github/workflows/publish.yml
```

Expected: the jar checks read `build/libs/kiteconfig-$KITE_CONFIG_VERSION.jar` and `build/libs/kiteconfig-$KITE_CONFIG_VERSION-javadoc.jar`, the Maven path reads `build/unsigned-release-candidate/io/github/yuroyami/kiteconfig/$KITE_CONFIG_VERSION/kiteconfig-$KITE_CONFIG_VERSION.pom`, and the Gradle flags read `-PkiteConfig.version` and `-PkiteConfig.releaseTag`.

- [ ] **Step 3: Verify no old name survives outside the exemptions**

```bash
grep -rn 'kitessot\|kiteSsot\|KiteSSOT' .github/ mkdocs.yml || echo "CLEAN"
```

Expected: `CLEAN`.

- [ ] **Step 4: Commit**

```bash
git add -A .github/ mkdocs.yml
git commit -m "ci!: rename the workflow variables and artifact paths to KiteConfig"
```

---

### Task 4: Change the diagnostic code prefix to KTCNFG

94 distinct codes, 360 references across source, tests, and docs. The numbers do not change.

**Files:**
- Modify: all files under `src/main/kotlin/io/github/yuroyami/kiteconfig/` containing `KMPS`
- Modify: all files under `src/test/kotlin/io/github/yuroyami/kiteconfig/` containing `KMPS`
- Modify: `src/test/kotlin/io/github/yuroyami/kiteconfig/DiagnosticIdUniquenessTest.kt` (the extraction regex)
- Modify: `README.md`, `docs/index.md`

**Interfaces:**
- Consumes: the renamed package from Task 1.
- Produces: diagnostic codes in the form `KTCNFG###`.

- [ ] **Step 1: Rewrite every code reference**

```bash
grep -rl 'KMPS' src/ README.md docs/index.md \
  | xargs sed -i '' 's/KMPS\([0-9]\{3\}\)/KTCNFG\1/g'
```

- [ ] **Step 2: Fix the extraction regex**

`DiagnosticIdUniquenessTest` parses codes out of source with a literal pattern. Step 1's rule does not match it because the regex contains `KMPS\d{3}`, not `KMPS` followed by three literal digits.

In `src/test/kotlin/io/github/yuroyami/kiteconfig/DiagnosticIdUniquenessTest.kt`:

```kotlin
    private val resolveId = Regex("""resolve(?:<[^>]*>)?\("(KTCNFG\d{3})"""")
```

- [ ] **Step 3: Verify no bare KMPS survives**

```bash
grep -rn 'KMPS' src/ README.md docs/index.md || echo "CLEAN"
```

Expected: `CLEAN`.

- [ ] **Step 4: Confirm the codes are still found and unique**

If the regex fix in Step 2 were missed, this test would parse zero codes and pass vacuously, so check the count is non-zero too.

```bash
grep -roh 'KTCNFG[0-9]\{3\}' src/main | sort -u | wc -l
```

Expected: `94`.

Run: `./gradlew test --tests '*DiagnosticIdUniquenessTest*'`
Expected: PASS.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A src/ README.md docs/index.md
git commit -m "refactor!: change the diagnostic code prefix to KTCNFG"
```

---

### Task 5: Rename the generated BuildConfig class

The generated class currently shares its simple name with AGP's own `BuildConfig`, so an import can silently pick the wrong one.

**Files:**
- Modify: `src/main/kotlin/io/github/yuroyami/kiteconfig/KiteConfigPlugin.kt:96-101`
- Modify: `src/main/kotlin/io/github/yuroyami/kiteconfig/KiteConfigExtension.kt` (the `DEFAULT_GENERATED_PACKAGE` constant)
- Test: `src/test/kotlin/io/github/yuroyami/kiteconfig/BuildConfigGenTest.kt`

**Interfaces:**
- Consumes: `KiteConfigPlugin` from Task 1.
- Produces: the generated class `kiteconfig.generated.KiteBuildConfig`.

- [ ] **Step 1: Write the failing test**

Add to `src/test/kotlin/io/github/yuroyami/kiteconfig/BuildConfigGenTest.kt`:

```kotlin
    @Test
    fun `the generated class defaults to KiteBuildConfig in the kiteconfig package`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        val ext = project.extensions.getByType(KiteConfigExtension::class.java)

        assertEquals("KiteBuildConfig", ext.buildConfig.className.get())
        assertEquals("kiteconfig.generated", ext.buildConfig.packageName.get())
    }
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew test --tests '*BuildConfigGenTest*'`
Expected: FAIL, `expected: <KiteBuildConfig> but was: <BuildConfig>`.

- [ ] **Step 3: Change the convention**

In `src/main/kotlin/io/github/yuroyami/kiteconfig/KiteConfigPlugin.kt`, inside the `buildConfig` extension setup:

```kotlin
        extAware.extensions.create<KiteConfigBuildConfigExtension>("buildConfig").apply {
            packageName.convention(KiteConfigExtension.DEFAULT_GENERATED_PACKAGE)
            className.convention("KiteBuildConfig")
            includeIdentity.convention(true)
            allowBuildCache.convention(false)
            fields.convention(emptyList())
        }
```

`DEFAULT_GENERATED_PACKAGE` already reads `kiteconfig.generated` after Task 1's lowercase rule. Verify:

```bash
grep -n 'DEFAULT_GENERATED_PACKAGE' src/main/kotlin/io/github/yuroyami/kiteconfig/KiteConfigExtension.kt
```

Expected: `internal const val DEFAULT_GENERATED_PACKAGE: String = "kiteconfig.generated"`.

- [ ] **Step 4: Run the test**

Run: `./gradlew test --tests '*BuildConfigGenTest*'`
Expected: PASS.

- [ ] **Step 5: Run the full suite**

Other tests assert on generated output paths and may hard-code the old class name.

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. Fix any assertion still expecting `BuildConfig.kt` as a generated filename.

- [ ] **Step 6: Commit**

```bash
git add -A src/
git commit -m "feat!: rename the generated class to KiteBuildConfig"
```

---

### Task 6: Rewrite the docs and repoint the git remote

**Files:**
- Modify: `README.md`
- Modify: `docs/index.md`
- Modify: `SECURITY.md`, `CONTRIBUTING.md`, `FEATURES.md`
- Modify: `dokka-templates/includes/header.ftl`, `.gitignore`, `docs/requirements.txt`
- Modify: `src/test/resources/fixtures/RealApp.xcodeproj/project.pbxproj`
- Not modified: `OVERHAUL.md`, `SOLAUDIT.md`, `CHANGELOG.md` (historical record, exempt)

**Interfaces:**
- Consumes: the plugin id from Task 2.
- Produces: docs that name the plugin correctly, and a README history section.

- [ ] **Step 1: Rewrite the four current docs**

```bash
sed -i '' \
  -e 's/io\.github\.yuroyami\.kitessot/io.github.yuroyami.kiteconfig/g' \
  -e 's/yuroyami\/KiteSSOT/yuroyami\/KiteConfig/g' \
  -e 's/KiteSSOT/KiteConfig/g' \
  -e 's/kiteSsot/kiteConfig/g' \
  -e 's/kitessot/kiteconfig/g' \
  README.md docs/index.md SECURITY.md CONTRIBUTING.md FEATURES.md \
  dokka-templates/includes/header.ftl .gitignore docs/requirements.txt \
  src/test/resources/fixtures/RealApp.xcodeproj/project.pbxproj
```

- [ ] **Step 2: Bump the pinned version in both install snippets**

In `README.md` and `docs/index.md`, the `plugins { }` snippet must read:

```kotlin
plugins {
    id("io.github.yuroyami.kiteconfig") version "1.0.0"
}
```

- [ ] **Step 3: Add the history section at the very end of `README.md`**

```markdown
## Name and version history

This plugin has had three names. It shipped as `kmp-ssot` at 0.1.0, became
KiteSSOT at 1.0.0 of that line, and is now KiteConfig. The `KTCNFG` diagnostic
prefix replaced an older `KMPS` prefix left over from the first name.

The version resets to 1.0.0 here because the DSL surface has reached a stable
form that is not planned to change. Earlier version numbers belong to the older
names and their published artifacts stay where they are.
```

- [ ] **Step 4: Verify the exemptions were left alone**

```bash
grep -c 'KiteSSOT' OVERHAUL.md SOLAUDIT.md CHANGELOG.md
```

Expected: non-zero counts. These files keep their original wording.

- [ ] **Step 5: Repoint the git remote**

The GitHub repository is already renamed to `yuroyami/KiteConfig`. GitHub redirects the old URL, so this is tidiness rather than a fix.

```bash
git remote set-url origin https://github.com/yuroyami/KiteConfig.git
git remote -v
```

- [ ] **Step 6: Run the docs example test**

This executes every KDoc and README example against a real Gradle build, so it catches a stale snippet that the rename missed.

Run: `./gradlew test --tests '*KdocExampleCompilationTest*'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A README.md docs/index.md SECURITY.md CONTRIBUTING.md
git commit -m "docs!: rename the docs to KiteConfig and add the history section"
```

---

## Series 2: the read-back API

### Task 7: Add the KiteConfigValues interface

**Files:**
- Create: `src/main/kotlin/io/github/yuroyami/kiteconfig/KiteConfigValues.kt`
- Modify: `src/main/kotlin/io/github/yuroyami/kiteconfig/KiteConfigExtension.kt`
- Create: `src/test/kotlin/io/github/yuroyami/kiteconfig/ReadBackValuesTest.kt`

**Interfaces:**
- Consumes: `KiteConfigExtension`, `KiteConfigPlugin`, and the public `KitePlatform` enum from Task 1.
- Produces: the `KiteConfigValues` interface with 18 members. Task 8 returns this type from its accessor. Task 9 reflects over it.

**Constraint from Trap 1:** no string literal in `KiteConfigValues.kt` may contain `iosBuildNumber` or `iosMarketingVersion`. KDoc prose and identifiers are safe; only quoted strings are scanned.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/io/github/yuroyami/kiteconfig/ReadBackValuesTest.kt`:

```kotlin
package io.github.yuroyami.kiteconfig

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadBackValuesTest {

    private fun values(): KiteConfigExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(KiteConfigPlugin::class.java)
        return project.extensions.getByType(KiteConfigExtension::class.java)
    }

    @Test
    fun `the version family reads back through the interface`() {
        val e = values()
        e.version.set("1.4.0")
        val read: KiteConfigValues = e

        assertEquals("1.4.0", read.version.get())
        assertEquals(1001004000, read.versionCode.get())
        assertEquals("1001004000", read.iosBuildNumber.get())
        assertEquals("1.4.0", read.iosMarketingVersion.get())
        assertEquals("1001004000", read.desktopBuildNumber.get())
    }

    @Test
    fun `identity reads back with its platform suffixes applied`() {
        val e = values()
        e.appName.set("Syncplay")
        e.id("com.example.app") {
            android { suffix.set(".droid") }
        }
        val read: KiteConfigValues = e

        assertEquals("Syncplay", read.appName.get())
        assertEquals("Syncplay", read.appNameFor(KitePlatform.ANDROID).get())
        assertEquals("com.example.app", read.id.get())
        assertEquals("com.example.app.droid", read.androidApplicationId.get())
        assertEquals("com.example.app", read.iosBundleId.get())
        assertEquals("com.example.app", read.desktopBundleId.get())
    }

    @Test
    fun `build values read back including the sdk levels`() {
        val e = values()
        e.jvmTarget.set(17)
        e.locales { pinned.set(listOf("en", "fr")) }
        e.android { sdk(min = 24, target = 35, compile = 35) }
        val read: KiteConfigValues = e

        assertEquals(listOf("en", "fr"), read.canonicalLocales.get())
        assertEquals(17, read.jvmTarget.get())
        assertEquals(24, read.minSdk.get())
        assertEquals(35, read.targetSdk.get())
        assertEquals(35, read.compileSdk.get())
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew test --tests '*ReadBackValuesTest*'`
Expected: FAIL to compile, `unresolved reference: KiteConfigValues`.

- [ ] **Step 3: Create the interface**

Create `src/main/kotlin/io/github/yuroyami/kiteconfig/KiteConfigValues.kt`:

```kotlin
package io.github.yuroyami.kiteconfig

import org.gradle.api.provider.Provider

/**
 * Everything KiteConfig resolved, as a read-only view.
 *
 * Reach it from any build file with the [kiteConfig] accessor. Every member is a
 * lazy [Provider], so wiring one into another task's property never forces a
 * value at configuration time.
 *
 * ## Reading a value
 *
 * These accessors supply no defaults and never return null. A value the root
 * build file never declared has no value at all, and reading it fails the build:
 *
 * ```kotlin
 * kiteConfig.version.get()   // declared     -> the value
 *                            // not declared -> throws, the build stops
 * ```
 *
 * That is deliberate. Reading a value the root never set is a mistake in the
 * consuming build file, and a silent fallback would put a second copy of the
 * value in the consumer, which is what this plugin exists to prevent.
 */
interface KiteConfigValues {

    // ------------------------------------------------------------------ version

    /** The declared version string, for example `1.4.0`. */
    val version: Provider<String>

    /** The resolved Android `versionCode` for this build. */
    val versionCode: Provider<Int>

    /** The resolved Apple build number, `CFBundleVersion`. */
    val iosBuildNumber: Provider<String>

    /** The resolved Apple marketing version, `CFBundleShortVersionString`. */
    val iosMarketingVersion: Provider<String>

    /** The resolved desktop build number. */
    val desktopBuildNumber: Provider<String>

    // ----------------------------------------------------------------- identity

    /** The declared app name, before any platform corner overrides it. */
    val appName: Provider<String>

    /** The app name as [platform] receives it, corner overrides applied. */
    fun appNameFor(platform: KitePlatform): Provider<String>

    /** The declared base id, before any platform suffix. */
    val id: Provider<String>

    /** Android application id: [id] plus its android corner suffix. */
    val androidApplicationId: Provider<String>

    /** Apple bundle id: [id] plus its ios corner suffix. */
    val iosBundleId: Provider<String>

    /** Desktop bundle id: [id] plus its desktop corner suffix. */
    val desktopBundleId: Provider<String>

    // -------------------------------------------------------------------- build

    /** Normalized, de-duplicated locale tags. */
    val canonicalLocales: Provider<List<String>>

    /** The Java release level applied to JVM compilation. */
    val jvmTarget: Provider<Int>

    /** The selected shared KMP project path. */
    val resolvedSharedProjectPath: Provider<String>

    /** Lowest Android API level the app runs on. */
    val minSdk: Provider<Int>

    /** Android API level the app targets. */
    val targetSdk: Provider<Int>

    /** Android API level the app compiles against. */
    val compileSdk: Provider<Int>

    /** Pinned Android NDK major version. */
    val ndk: Provider<Int>
}
```

- [ ] **Step 4: Make the extension implement it**

In `src/main/kotlin/io/github/yuroyami/kiteconfig/KiteConfigExtension.kt`, change the class declaration:

```kotlin
abstract class KiteConfigExtension : KiteFlowScope(), KiteConfigValues {
```

Add `override` to the four public `Property` declarations. Narrowing a `val`'s type in an override is legal because `Property` extends `Provider`:

```kotlin
    abstract override val appName: Property<String>
    abstract override val version: Property<String>
    abstract override val jvmTarget: Property<Int>
    abstract override val id: Property<String>
```

Add `override` to the six providers that are already public:

```kotlin
    override val androidApplicationId: Provider<String>
        get() = effectiveIdFor(KitePlatform.ANDROID)

    override val iosBundleId: Provider<String>
        get() = effectiveIdFor(KitePlatform.IOS)

    override val desktopBundleId: Provider<String>
        get() = effectiveIdFor(KitePlatform.DESKTOP).map(::validateAppleBundleId)

    override val versionCode: Provider<Int>
        get() = effectiveAndroidVersionCode

    override val canonicalLocales: Provider<List<String>>
        get() = effectiveLocales.map(::canonicalizeLocales)

    override val resolvedSharedProjectPath: Provider<String>
        get() = effectiveSharedProjectPath
```

Add the eight new public members. Place them next to the existing read-back block:

```kotlin
    override val iosBuildNumber: Provider<String>
        get() = effectiveIosBuildNumber

    override val iosMarketingVersion: Provider<String>
        get() = effectiveIosMarketingVersion

    override val desktopBuildNumber: Provider<String>
        get() = effectiveDesktopBuildNumber

    override fun appNameFor(platform: KitePlatform): Provider<String> =
        effectiveAppNameFor(platform)

    override val minSdk: Provider<Int>
        get() = android.minSdk

    override val targetSdk: Provider<Int>
        get() = android.targetSdk

    override val compileSdk: Provider<Int>
        get() = android.compileSdk

    override val ndk: Provider<Int>
        get() = android.ndk
```

- [ ] **Step 5: Run the test**

Run: `./gradlew test --tests '*ReadBackValuesTest*'`
Expected: PASS.

- [ ] **Step 6: Confirm the hygiene scan still passes**

This is Trap 1. `KiteConfigValues.kt` is not on the pardon list, so a quoted `iosBuildNumber` anywhere in it fails here.

Run: `./gradlew test --tests '*MessageHygieneTest*'`
Expected: PASS.

- [ ] **Step 7: Regenerate the ABI dump**

Run: `./gradlew updateKotlinAbi`
Expected: `api/kiteconfig.api` gains `KiteConfigValues` and the eight new members.

- [ ] **Step 8: Commit**

```bash
git add -A src/ api/
git commit -m "feat: add the KiteConfigValues read-only view"
```

---

### Task 8: Add the kiteConfig accessor and delete the old one

**Files:**
- Modify: `src/main/kotlin/io/github/yuroyami/kiteconfig/KiteConfigValues.kt`
- Delete: `src/main/kotlin/io/github/yuroyami/kiteconfig/KiteConfigAccess.kt`
- Create: `src/test/kotlin/io/github/yuroyami/kiteconfig/ReadBackAccessorTest.kt`

**Interfaces:**
- Consumes: `KiteConfigValues` from Task 7.
- Produces: `val Project.kiteConfig: KiteConfigValues`, the accessor consumers import.

- [ ] **Step 1: Write the failing functional test**

Create `src/test/kotlin/io/github/yuroyami/kiteconfig/ReadBackAccessorTest.kt`:

```kotlin
package io.github.yuroyami.kiteconfig

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReadBackAccessorTest {

    @TempDir
    lateinit var projectDir: File

    private fun write(path: String, text: String) {
        val file = File(projectDir, path)
        file.parentFile.mkdirs()
        file.writeText(text.trimIndent())
    }

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--stacktrace")

    @Test
    fun `a submodule reads the resolved version code with no deprecation warning`() {
        write("settings.gradle.kts", """
            rootProject.name = "fixture"
            include(":app")
        """)
        write("build.gradle.kts", """
            plugins { id("io.github.yuroyami.kiteconfig") }
            kiteConfig {
                id("com.example.app")
                version("1.4.0")
            }
        """)
        write("app/build.gradle.kts", """
            import io.github.yuroyami.kiteconfig.kiteConfig

            tasks.register("readBack") {
                val code = kiteConfig.versionCode
                val appId = kiteConfig.androidApplicationId
                doLast {
                    println("CODE=" + code.get())
                    println("APPID=" + appId.get())
                }
            }
        """)

        val result = runner(":app:readBack").build()

        assertTrue(result.output.contains("CODE=1001004000"), result.output)
        assertTrue(result.output.contains("APPID=com.example.app"), result.output)
        assertFalse(result.output.contains("deprecat", ignoreCase = true), result.output)
    }

    @Test
    fun `reading an undeclared value fails the build instead of defaulting`() {
        write("settings.gradle.kts", """
            rootProject.name = "fixture"
            include(":app")
        """)
        write("build.gradle.kts", """
            plugins { id("io.github.yuroyami.kiteconfig") }
            kiteConfig { id("com.example.app") }
        """)
        write("app/build.gradle.kts", """
            import io.github.yuroyami.kiteconfig.kiteConfig

            tasks.register("readBack") {
                val name = kiteConfig.appName
                doLast { println("NAME=" + name.get()) }
            }
        """)

        val result = runner(":app:readBack").buildAndFail()

        assertTrue(result.output.contains("no value"), result.output)
        assertFalse(result.output.contains("NAME="), result.output)
    }

    @Test
    fun `the accessor explains itself when the plugin is missing from the root`() {
        write("settings.gradle.kts", """
            rootProject.name = "fixture"
            include(":app")
        """)
        write("build.gradle.kts", "")
        write("app/build.gradle.kts", """
            import io.github.yuroyami.kiteconfig.kiteConfig

            tasks.register("readBack") {
                doLast { println(kiteConfig.versionCode.get()) }
            }
        """)

        val result = runner(":app:readBack").buildAndFail()

        assertTrue(result.output.contains("io.github.yuroyami.kiteconfig"), result.output)
        assertTrue(result.output.contains("root project"), result.output)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew test --tests '*ReadBackAccessorTest*'`
Expected: FAIL, the build scripts cannot resolve `kiteConfig` as an import.

- [ ] **Step 3: Add the accessor**

Append to `src/main/kotlin/io/github/yuroyami/kiteconfig/KiteConfigValues.kt`:

```kotlin
/**
 * Everything KiteConfig resolved, readable from any project in the build.
 *
 * ```kotlin
 * import io.github.yuroyami.kiteconfig.kiteConfig
 *
 * android {
 *     defaultConfig {
 *         versionCode = kiteConfig.versionCode.get()
 *     }
 * }
 * ```
 *
 * The returned view is read-only: configure the plugin in the root build file
 * and read it everywhere else. Values are frozen before subprojects are
 * evaluated, so what you read here is what the build uses.
 *
 * This reads across projects, so it is not compatible with Gradle Isolated
 * Projects. Neither is the rest of the plugin.
 *
 * @throws org.gradle.api.GradleException if the plugin is not applied to the
 *   root project.
 */
val Project.kiteConfig: KiteConfigValues
    get() = rootProject.extensions.findByType(KiteConfigExtension::class.java)
        ?: throw GradleException(
            "KiteConfig values are unavailable in '$path': apply the " +
                "io.github.yuroyami.kiteconfig plugin to the root project first."
        )
```

Add the two imports at the top of the file:

```kotlin
import org.gradle.api.GradleException
import org.gradle.api.Project
```

- [ ] **Step 4: Delete the old accessor**

```bash
git rm src/main/kotlin/io/github/yuroyami/kiteconfig/KiteConfigAccess.kt
```

- [ ] **Step 5: Run the test**

Run: `./gradlew test --tests '*ReadBackAccessorTest*'`
Expected: PASS, all three tests.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. Nothing referenced the deleted accessor, so nothing else should break.

- [ ] **Step 7: Regenerate the ABI dump**

Run: `./gradlew updateKotlinAbi`
Expected: the old `KiteConfigAccessKt` entry disappears and a `KiteConfigValuesKt` entry appears.

- [ ] **Step 8: Commit**

```bash
git add -A src/ api/
git commit -m "feat!: add the kiteConfig accessor and drop the deprecated one"
```

---

### Task 9: Lock the read-only boundary with a reflection test

Without this, a later edit can add a `Property` member to the interface and quietly reopen the write path.

**Files:**
- Create: `src/test/kotlin/io/github/yuroyami/kiteconfig/ReadBackBoundaryTest.kt`

**Interfaces:**
- Consumes: `KiteConfigValues` from Task 7.
- Produces: nothing. This is a guard.

- [ ] **Step 1: Write the test**

Create `src/test/kotlin/io/github/yuroyami/kiteconfig/ReadBackBoundaryTest.kt`:

```kotlin
package io.github.yuroyami.kiteconfig

import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The read-only view must stay read-only. A member typed as `Property` would
 * hand a consumer a setter and reopen the write path this interface closes.
 */
class ReadBackBoundaryTest {

    @Test
    fun `every member returns a Provider and never a Property`() {
        val offences = KiteConfigValues::class.java.declaredMethods
            .filter { !it.isSynthetic }
            .filterNot { Provider::class.java.isAssignableFrom(it.returnType) }
            .map { "${it.name} returns ${it.returnType.simpleName}" }

        assertEquals(emptyList<String>(), offences, offences.joinToString("\n"))

        val leaks = KiteConfigValues::class.java.declaredMethods
            .filter { !it.isSynthetic }
            .filter { Property::class.java.isAssignableFrom(it.returnType) }
            .map { it.name }

        assertEquals(emptyList<String>(), leaks, leaks.joinToString("\n"))
    }

    @Test
    fun `the interface exposes all eighteen values`() {
        val names = KiteConfigValues::class.java.declaredMethods
            .filter { !it.isSynthetic }
            .map { it.name }
            .toSet()

        val expected = setOf(
            "getVersion", "getVersionCode", "getIosBuildNumber",
            "getIosMarketingVersion", "getDesktopBuildNumber",
            "getAppName", "appNameFor", "getId", "getAndroidApplicationId",
            "getIosBundleId", "getDesktopBundleId",
            "getCanonicalLocales", "getJvmTarget", "getResolvedSharedProjectPath",
            "getMinSdk", "getTargetSdk", "getCompileSdk", "getNdk",
        )

        assertEquals(18, expected.size)
        assertTrue(names.containsAll(expected), "missing: ${expected - names}")
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew test --tests '*ReadBackBoundaryTest*'`
Expected: PASS. A failure here means Task 7 declared a member with the wrong type or missed one.

- [ ] **Step 3: Commit**

```bash
git add -A src/test/
git commit -m "test: lock the read-only boundary of KiteConfigValues"
```

---

### Task 10: Document the read-back API

**Files:**
- Modify: `README.md`
- Modify: `docs/index.md`

**Interfaces:**
- Consumes: the accessor from Task 8.
- Produces: nothing. Documentation only.

- [ ] **Step 1: Replace the buried read-back paragraph in `README.md`**

Find the existing paragraph that begins "Read-back providers worth wiring into your own tasks" and delete it along with its code fence. Add this as its own top-level section, placed directly after the DSL section and before "## Tasks":

````markdown
## Reading values back

Everything KiteConfig resolves is readable from any build file in the project.
One import, then use it:

```kotlin
import io.github.yuroyami.kiteconfig.kiteConfig

android {
    defaultConfig {
        versionCode = kiteConfig.versionCode.get()
    }
}
```

Eighteen values are available.

| Group | Values |
| --- | --- |
| Version | `version`, `versionCode`, `iosBuildNumber`, `iosMarketingVersion`, `desktopBuildNumber` |
| Identity | `appName`, `appNameFor(platform)`, `id`, `androidApplicationId`, `iosBundleId`, `desktopBundleId` |
| Build | `canonicalLocales`, `jvmTarget`, `resolvedSharedProjectPath`, `minSdk`, `targetSdk`, `compileSdk`, `ndk` |

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

That is on purpose. Reading a value you never declared is a mistake in the
build file, and quietly falling back to something like `?: 24` would put a
second copy of that number in the consumer, which is the duplication this
plugin exists to remove.

### Limits

Configure the plugin in the root build file and read it everywhere else. The
view is read-only, and the model is frozen before subprojects are evaluated, so
what you read is what the build uses.

Reading across projects means this is not compatible with Gradle Isolated
Projects. Neither is the rest of the plugin.
````

- [ ] **Step 2: Add the same section to `docs/index.md`**

Copy the section from Step 1 verbatim into `docs/index.md`, placed to match that file's existing section order.

- [ ] **Step 3: Verify the examples actually run**

`KdocExampleCompilationTest` executes README fences against a real Gradle build.

Run: `./gradlew test --tests '*KdocExampleCompilationTest*'`
Expected: PASS.

- [ ] **Step 4: Check for em-dashes**

```bash
grep -c '—' README.md docs/index.md
```

Expected: `0` for both.

- [ ] **Step 5: Commit**

```bash
git add -A README.md docs/index.md
git commit -m "docs: document the read-back API"
```

---

### Task 11: Add the rename sweep, cut the changelog, and verify the release

**Files:**
- Create: `src/test/kotlin/io/github/yuroyami/kiteconfig/RenameSweepTest.kt`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: everything.
- Produces: a tagged, releasable `1.0.0`.

- [ ] **Step 1: Write the sweep test**

Create `src/test/kotlin/io/github/yuroyami/kiteconfig/RenameSweepTest.kt`:

```kotlin
package io.github.yuroyami.kiteconfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * No old name may survive outside the historical record.
 *
 * The exempt files are kept as written on purpose: they describe what the
 * plugin was called at the time, so rewriting them would make them lie.
 */
class RenameSweepTest {

    private val exempt = setOf(
        "CHANGELOG.md", "OVERHAUL.md", "SOLAUDIT.md",
    )

    private val exemptDirs = listOf("docs/superpowers/", "build/", ".git/", ".gradle/")

    private val oldNames = listOf("kitessot", "kiteSsot", "KiteSsot", "KiteSSOT", "KMPS")

    @Test
    fun `no old name survives outside the historical record`() {
        val root = File(".").canonicalFile
        val offences = root.walkTopDown()
            .onEnter { dir -> exemptDirs.none { dir.invariantPath(root).startsWith(it) } }
            .filter { it.isFile }
            .filter { it.extension in setOf("kt", "kts", "java", "md", "yml", "yaml", "api") }
            .filterNot { it.name in exempt }
            .filterNot { it.name == "RenameSweepTest.kt" }
            .flatMap { file ->
                val readmeHistory = file.name == "README.md"
                file.readLines().withIndex().mapNotNull { (index, line) ->
                    val hit = oldNames.firstOrNull { line.contains(it) } ?: return@mapNotNull null
                    if (readmeHistory && line.contains("KiteSSOT")) return@mapNotNull null
                    "${file.invariantPath(root)}:${index + 1} says \"$hit\""
                }
            }
            .toList()

        assertEquals(emptyList<String>(), offences, offences.joinToString("\n"))
    }

    private fun File.invariantPath(root: File): String =
        canonicalPath.removePrefix(root.canonicalPath).removePrefix("/")
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew test --tests '*RenameSweepTest*'`
Expected: PASS. Any failure names the exact file and line that the rename missed. Fix those, do not widen the exemption list.

- [ ] **Step 3: Cut the changelog entry**

The release guard requires a `## 1.0.0` heading matching the tag exactly. Add at the top of `CHANGELOG.md`, directly under the intro paragraph:

```markdown
## 1.0.0

The plugin is now KiteConfig. The previous name, KiteSSOT, published its last
release as 4.2.0 and stays on the Gradle Plugin Portal under the old
coordinate. The version resets here because the DSL surface has reached a
stable form that is not planned to change.

### Renames

- Plugin id `io.github.yuroyami.kitessot` is now `io.github.yuroyami.kiteconfig`.
- The `kiteSsot { }` block is now `kiteConfig { }`.
- Gradle properties `-Pkitessot.dryRun`, `.backups`, and `.color` are now
  `-Pkiteconfig.*`.
- Diagnostic codes keep their numbers behind a new prefix, so `KMPS021` is now
  `KTCNFG021`.
- The generated class is now `kiteconfig.generated.KiteBuildConfig`, which no
  longer collides with the `BuildConfig` that AGP generates.

### Reading values back

`kiteConfig` reads everything the plugin resolved, from any build file:

```kotlin
import io.github.yuroyami.kiteconfig.kiteConfig

versionCode = kiteConfig.versionCode.get()
```

Eighteen values are exposed, covering version, identity, locales, the shared
module path, and the Android SDK levels. Every one is a lazy `Provider`. None
supplies a default: reading a value the root build file never declared stops
the build instead of substituting one.

The deprecated `kiteSsot` cross-project accessor is removed. It handed back the
mutable model, and writes through it already failed against the frozen model.
```

- [ ] **Step 4: Run the whole suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Verify the release metadata guard accepts 1.0.0**

This runs the same check the publish pipeline runs, so a mismatch surfaces now rather than on the tag.

Run: `./gradlew verifyReleaseMetadata -PkiteConfig.version=1.0.0 -PkiteConfig.releaseTag=v1.0.0`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Verify the ABI dump is current**

Run: `./gradlew checkKotlinAbi`
Expected: BUILD SUCCESSFUL. If it fails, run `./gradlew updateKotlinAbi` and commit the result.

- [ ] **Step 7: Confirm the artifact name the pipeline expects**

The publish workflow unzips `build/libs/kiteconfig-$KITE_CONFIG_VERSION.jar` by literal name. This is what broke 4.1.1.

```bash
./gradlew jar -PkiteConfig.version=1.0.0 -PkiteConfig.releaseTag=v1.0.0
ls build/libs/
```

Expected: `kiteconfig-1.0.0.jar` exists.

- [ ] **Step 8: Commit**

```bash
git add -A src/test/ CHANGELOG.md
git commit -m "docs: cut the 1.0.0 changelog entry and add the rename sweep"
```

- [ ] **Step 9: Stop here**

Do not tag. Do not push a `v*` tag. Do not run `publishPlugins`. Task 12 installs
the plugin locally so the author can verify it against their own projects first.

---

### Task 12: Install locally so the author can verify consumers

The author verifies the plugin against every consumer project on their machine
before any release. This task makes that possible and then stops.

The release guard is wired only to `publishPlugins` and `publish*Repository`
tasks, so `publishToMavenLocal` needs no release tag and no signing key. It runs
clean on a developer machine.

**Files:**
- No file changes. This task installs and hands off.

**Interfaces:**
- Consumes: the verified build from Task 11.
- Produces: `io.github.yuroyami:kiteconfig:1.0.0` and its plugin marker in
  `~/.m2/repository`.

- [ ] **Step 1: Install to local Maven**

```bash
./gradlew publishToMavenLocal -PkiteConfig.version=1.0.0
```

Expected: BUILD SUCCESSFUL. No signing runs, because no signing key is present
locally, and that is by design.

- [ ] **Step 2: Confirm both publications landed**

`java-gradle-plugin` publishes the implementation and a separate plugin marker.
A consumer resolves the marker first, so both must exist.

```bash
ls ~/.m2/repository/io/github/yuroyami/kiteconfig/1.0.0/
ls ~/.m2/repository/io/github/yuroyami/kiteconfig/io.github.yuroyami.kiteconfig.gradle.plugin/1.0.0/
```

Expected: the first lists `kiteconfig-1.0.0.jar`, its `.pom`, and its `.module`.
The second lists the marker `.pom`.

- [ ] **Step 3: Report the consumer-side wiring to the author**

Each consumer repository needs `mavenLocal()` in its plugin resolution before it
can see the local install. In that project's `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
```

Then in its root `build.gradle.kts`:

```kotlin
plugins {
    id("io.github.yuroyami.kiteconfig") version "1.0.0"
}
```

- [ ] **Step 4: Hand off, and stop**

Report to the author:

- The branch is ready and every check in the verification checklist passed.
- `1.0.0` is installed in local Maven and ready to consume.
- The consumer-side changes each repository needs, from Step 3.
- Their existing consumer build files need the rename applied: `kiteSsot { }`
  becomes `kiteConfig { }`, and any generated-class import becomes
  `kiteconfig.generated.KiteBuildConfig`.

Do not tag, do not push a `v*` tag, and do not run `publishPlugins`. The author
gives the green light for the portal release after their consumer checks pass.

When that green light comes, releasing means pushing `main` and then an exact
`v1.0.0` tag. Two things to expect on that first release:

- A brand-new plugin id goes through the portal's manual approval queue, unlike
  a new version of an existing plugin. Expect `io.github.yuroyami.kiteconfig`
  1.0.0 to sit pending before it is publicly visible. That is an availability
  delay, not a pipeline failure.
- Publishing is irreversible. A portal version cannot be deleted or replaced,
  which is exactly how 4.1.1 became unrecoverable.

---

## Verification checklist

Run at the end. Every item must pass before the release is considered ready.

- [ ] `./gradlew build` succeeds.
- [ ] `./gradlew checkKotlinAbi` succeeds.
- [ ] `./gradlew test --tests '*RenameSweepTest*'` succeeds.
- [ ] `./gradlew test --tests '*MessageHygieneTest*'` succeeds.
- [ ] `./gradlew test --tests '*KdocExampleCompilationTest*'` succeeds and collects a non-zero number of examples.
- [ ] `./gradlew test --tests '*DiagnosticIdUniquenessTest*'` succeeds and 94 distinct `KTCNFG` codes exist.
- [ ] `./gradlew verifyReleaseMetadata -PkiteConfig.version=1.0.0 -PkiteConfig.releaseTag=v1.0.0` succeeds.
- [ ] `build/libs/kiteconfig-1.0.0.jar` is produced.
- [ ] `git remote -v` points at `yuroyami/KiteConfig`.
- [ ] No em-dash appears in any Markdown file.
- [ ] `./gradlew publishToMavenLocal -PkiteConfig.version=1.0.0` succeeds and both publications appear under `~/.m2/repository`.
- [ ] Nothing was tagged, nothing was pushed as a `v*` tag, and `publishPlugins` was never run.
