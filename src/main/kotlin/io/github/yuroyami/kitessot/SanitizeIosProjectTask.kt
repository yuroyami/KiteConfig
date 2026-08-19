package io.github.yuroyami.kitessot

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Ensures a source iOS `Info.plist` holds the SSOT references that this task's
 * propagation inputs enable. Each enabled entry points at its build variable:
 *
 * ```
 * CFBundleDisplayName        → $(PRODUCT_NAME)
 * CFBundleName               → $(PRODUCT_NAME)
 * CFBundleShortVersionString → $(MARKETING_VERSION)
 * CFBundleVersion            → $(CURRENT_PROJECT_VERSION)
 * ```
 *
 * App-name propagation enables the first two entries. Marketing-version and
 * build-number propagation independently enable the remaining entries. When no
 * string entry or optional Boolean flag is enabled, the task is a no-op.
 * Existing conflicts follow [conflictPolicy], which defaults to fail-closed, so
 * one conflict aborts the entire plan and leaves the plist byte-for-byte
 * untouched.
 *
 * It also propagates the Boolean feature flags from `kiteSsot { ios { } }`:
 *
 * ```
 * ITSAppUsesNonExemptEncryption        ← ios.usesNonExemptEncryption
 * CADisableMinimumFrameDurationOnPhone ← ios.proMotion120Hz
 * ```
 *
 * The plist is parsed with mandatory XML hardening ([sanitizeInfoPlist]). Binary,
 * malformed, duplicate-key, unsafe-entity, and non-lossless inputs fail without
 * writing. Generated plists are unsupported by this source-file migration task.
 *
 * ## Safety rails on every source-writing task
 *
 * | Rail | Behaviour |
 * |---|---|
 * | Explicit only | never runs as part of an ordinary build; you invoke it by name |
 * | Authorized | the matching DSL block must be configured, or the task is skipped |
 * | `-Pkitessot.dryRun=true` | reports what it would write **and remove**, changes nothing |
 * | `-Pkitessot.backups=true` | keeps a recovery copy before replacing anything (default) |
 * | Ownership | refuses to overwrite or delete a file it does not own |
 * | Atomic | staged then swapped, so an interrupted run leaves the tree intact |
 *
 * Both `-P` switches accept exactly `true` or `false`; anything else fails the
 * build rather than being read as `false`.
 */
@DisableCachingByDefault(because = "Mutates user-owned source files and must execute current safety checks.")
abstract class SanitizeIosProjectTask : DefaultTask() {

    init {
        group = "kitessot"
        description = "Explicitly plan/apply SSOT keys and ios{} flags to a source XML Info.plist."
        conflictPolicy.convention(PlistConflictPolicy.FAIL)
        projectRootDir.convention(project.layout.projectDirectory)
    }

    @get:Internal abstract val projectRootDir: DirectoryProperty
    @get:Internal abstract val infoPlistFile: RegularFileProperty

    @get:Input abstract val propagateAppName: Property<Boolean>
    @get:Input abstract val propagateMarketingVersion: Property<Boolean>
    @get:Input abstract val propagateBuildNumber: Property<Boolean>

    // Read at execution via isPresent; @Optional only applies to input annotations
    // and conflicts with @Internal under Gradle 9.
    @get:Input @get:Optional abstract val usesNonExemptEncryption: Property<Boolean>
    @get:Input @get:Optional abstract val proMotion120Hz: Property<Boolean>

    @get:Input abstract val conflictPolicy: Property<PlistConflictPolicy>
    @get:Input abstract val dryRun: Property<Boolean>
    @get:Input abstract val backup: Property<Boolean>

    @TaskAction
    fun sanitize() {
        val root = projectRootDir.asFile.get()
        val configuredFile = infoPlistFile.asFile.get()
        val file = try {
            requireContainedPath(root, configuredFile, mustExist = configuredFile.exists())
        } catch (failure: IllegalArgumentException) {
            throw GradleException("Unsafe Info.plist path: ${failure.message}", failure)
        }
        if (!file.exists()) {
            throw GradleException(
                "Configured source Info.plist does not exist: ${file.path}. " +
                    "For GENERATE_INFOPLIST_FILE projects, disable ios { sync { sanitizePlist } } and configure Xcode build settings instead."
            )
        }

        val stringEntries = buildList {
            if (propagateAppName.get()) {
                add(PlistStringEntry("CFBundleDisplayName", "\$(PRODUCT_NAME)"))
                add(PlistStringEntry("CFBundleName", "\$(PRODUCT_NAME)"))
            }
            if (propagateMarketingVersion.get()) {
                add(PlistStringEntry("CFBundleShortVersionString", "\$(MARKETING_VERSION)"))
            }
            if (propagateBuildNumber.get()) {
                add(PlistStringEntry("CFBundleVersion", "\$(CURRENT_PROJECT_VERSION)"))
            }
        }
        val boolEntries = buildList {
            if (usesNonExemptEncryption.isPresent) {
                add(PlistBoolEntry("ITSAppUsesNonExemptEncryption", usesNonExemptEncryption.get()))
            }
            if (proMotion120Hz.isPresent) {
                add(PlistBoolEntry("CADisableMinimumFrameDurationOnPhone", proMotion120Hz.get()))
            }
        }
        if (stringEntries.isEmpty() && boolEntries.isEmpty()) return

        val snapshot = readUtf8SnapshotStrict(file)
        val result = sanitizeInfoPlist(snapshot.text, stringEntries, boolEntries, conflictPolicy.get())
        result.warnings.forEach { logger.warn("[kiteSsot] $it") }
        if (result.errors.isNotEmpty()) {
            throw GradleException("Info.plist migration refused:\n- ${result.errors.joinToString("\n- ")}")
        }

        val newText = result.text ?: run {
            logger.info("[kiteSsot] Info.plist already sanitized.")
            return
        }

        val plan = planTextChange(root, file, newText, "Info.plist", snapshot) ?: return
        val applied = applyTextRewritePlan(root, listOf(plan), backup.get(), dryRun.get(), logger)
        if (applied.written > 0) {
            val parts = buildList {
                if (result.inserted.isNotEmpty()) add("inserted ${result.inserted.joinToString(", ")}")
                if (result.overwritten.isNotEmpty()) add("overwrote ${result.overwritten.joinToString(", ")}")
            }
            logger.lifecycle("[kiteSsot] Info.plist sanitized: ${parts.joinToString("; ")}.")
        } else if (applied.dryRun) {
            logger.lifecycle("[kiteSsot] Info.plist plan contains one change; dry-run left the file untouched.")
        }
    }
}
