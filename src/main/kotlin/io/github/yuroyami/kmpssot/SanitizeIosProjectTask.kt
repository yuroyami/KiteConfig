package io.github.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Guarantees the iOS `Info.plist` has the keys the SSOT rewrite relies on, each
 * pointing at its build variable:
 *
 *   CFBundleDisplayName        → $(PRODUCT_NAME)
 *   CFBundleName               → $(PRODUCT_NAME)
 *   CFBundleShortVersionString → $(MARKETING_VERSION)
 *   CFBundleVersion            → $(CURRENT_PROJECT_VERSION)
 *
 * Append-only for those: existing keys are never overwritten; a warning is
 * logged if an existing value is a hardcoded literal that would defeat SSOT.
 *
 * Also propagates DSL-driven boolean feature flags from `kmpSsot { ios { } }`:
 *
 *   ITSAppUsesNonExemptEncryption        ← ios.usesNonExemptEncryption
 *   CADisableMinimumFrameDurationOnPhone ← ios.proMotion120Hz
 *
 * For those the DSL is the source of truth: insert if missing, overwrite if the
 * current plist value differs.
 *
 * The plist is parsed with a real XML parser ([sanitizeInfoPlist]) so a key whose
 * value is a dict/array/CDATA/integer is correctly recognised (no duplicate-key
 * insertion). If the Info.plist doesn't exist (e.g. GENERATE_INFOPLIST_FILE =
 * YES), the task exits quietly.
 */
@DisableCachingByDefault(because = "Trivial text rewrite; caching adds overhead without payoff.")
abstract class SanitizeIosProjectTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Insert/sync SSOT-pointing keys + ios{} flags into iOS Info.plist before syncIosConfig runs."
        outputs.upToDateWhen { false }
    }

    @get:Internal abstract val infoPlistFile: RegularFileProperty

    @get:Internal abstract val propagateAppName: Property<Boolean>
    @get:Internal abstract val propagateVersion: Property<Boolean>

    // Read at execution via isPresent; @Optional only applies to input annotations
    // and conflicts with @Internal under Gradle 9.
    @get:Internal abstract val usesNonExemptEncryption: Property<Boolean>
    @get:Internal abstract val proMotion120Hz: Property<Boolean>

    @get:Internal abstract val dryRun: Property<Boolean>
    @get:Internal abstract val backup: Property<Boolean>

    @TaskAction
    fun sanitize() {
        val file = infoPlistFile.asFile.get()
        if (!file.exists()) {
            logger.info("[kmpSsot] Info.plist not found at ${file.path} — skipping sanitization (generated plist?).")
            return
        }

        val stringEntries = buildList {
            if (propagateAppName.get()) {
                add(PlistStringEntry("CFBundleDisplayName", "\$(PRODUCT_NAME)"))
                add(PlistStringEntry("CFBundleName", "\$(PRODUCT_NAME)"))
            }
            if (propagateVersion.get()) {
                add(PlistStringEntry("CFBundleShortVersionString", "\$(MARKETING_VERSION)"))
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

        val result = sanitizeInfoPlist(file.readText(), stringEntries, boolEntries)
        result.warnings.forEach { logger.warn("[kmpSsot] $it") }

        val newText = result.text ?: run {
            logger.info("[kmpSsot] Info.plist already sanitized.")
            return
        }

        if (writeTextSafely(file, newText, backup.get(), dryRun.get(), logger, "Info.plist")) {
            val parts = buildList {
                if (result.inserted.isNotEmpty()) add("inserted ${result.inserted.joinToString(", ")}")
                if (result.overwritten.isNotEmpty()) add("overwrote ${result.overwritten.joinToString(", ")}")
            }
            logger.lifecycle("[kmpSsot] Info.plist sanitized: ${parts.joinToString("; ")}.")
        }
    }
}
