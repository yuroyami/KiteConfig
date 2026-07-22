package io.github.yuroyami.kmpssot

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
 * Conditionally ensures a source iOS `Info.plist` has the SSOT references enabled
 * by this task's propagation inputs. Each enabled entry points at its build
 * variable:
 *
 *   CFBundleDisplayName        → $(PRODUCT_NAME)
 *   CFBundleName               → $(PRODUCT_NAME)
 *   CFBundleShortVersionString → $(MARKETING_VERSION)
 *   CFBundleVersion            → $(CURRENT_PROJECT_VERSION)
 *
 * App-name propagation enables the first two entries; marketing-version and
 * build-number propagation independently enable the remaining entries. When no
 * string entry or optional Boolean flag is enabled, the task is a no-op.
 * Existing conflicts follow [conflictPolicy]. The default is fail-closed: one
 * conflict aborts the entire plan and leaves the plist byte-for-byte untouched.
 *
 * Also propagates DSL-driven boolean feature flags from `kmpSsot { ios { } }`:
 *
 *   ITSAppUsesNonExemptEncryption        ← ios.usesNonExemptEncryption
 *   CADisableMinimumFrameDurationOnPhone ← ios.proMotion120Hz
 *
 * The plist is parsed with mandatory XML hardening ([sanitizeInfoPlist]). Binary,
 * malformed, duplicate-key, unsafe-entity, and non-lossless inputs fail without
 * writing. Generated plists are unsupported by this source-file migration task.
 */
@DisableCachingByDefault(because = "Mutates user-owned source files and must execute current safety checks.")
abstract class SanitizeIosProjectTask : DefaultTask() {

    init {
        group = "kmp-ssot"
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
                    "For GENERATE_INFOPLIST_FILE projects, disable sanitizeIosProject and configure Xcode build settings instead."
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
        result.warnings.forEach { logger.warn("[kmpSsot] $it") }
        if (result.errors.isNotEmpty()) {
            throw GradleException("Info.plist migration refused:\n- ${result.errors.joinToString("\n- ")}")
        }

        val newText = result.text ?: run {
            logger.info("[kmpSsot] Info.plist already sanitized.")
            return
        }

        val plan = planTextChange(root, file, newText, "Info.plist", snapshot) ?: return
        val applied = applyTextRewritePlan(root, listOf(plan), backup.get(), dryRun.get(), logger)
        if (applied.written > 0) {
            val parts = buildList {
                if (result.inserted.isNotEmpty()) add("inserted ${result.inserted.joinToString(", ")}")
                if (result.overwritten.isNotEmpty()) add("overwrote ${result.overwritten.joinToString(", ")}")
            }
            logger.lifecycle("[kmpSsot] Info.plist sanitized: ${parts.joinToString("; ")}.")
        } else if (applied.dryRun) {
            logger.lifecycle("[kmpSsot] Info.plist plan contains one change; dry-run left the file untouched.")
        }
    }
}
