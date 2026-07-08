package io.github.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * End-to-end setup diagnostic. Validates the whole kmp-ssot wiring and prints a
 * PASS / WARN / FAIL line per check with the exact fix — the one command to run
 * when propagation "isn't reaching" a platform. Modifies nothing; never throws
 * (a doctor reports, it doesn't gate — use a build for that).
 */
@DisableCachingByDefault(because = "Diagnostic reporting task; inspects current state.")
abstract class KmpSsotDoctorTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Diagnose the kmp-ssot setup end-to-end (manifest, Info.plist, pbxproj, icons, locales)."
        outputs.upToDateWhen { false }
    }

    @get:Internal abstract val propagateAppName: Property<Boolean>
    @get:Internal abstract val appName: Property<String>
    @get:Internal abstract val propagateVersion: Property<Boolean>
    @get:Internal abstract val versionName: Property<String>
    @get:Internal abstract val hasVersionCodeOverride: Property<Boolean>
    @get:Internal abstract val propagateLocaleList: Property<Boolean>
    @get:Internal abstract val locales: ListProperty<String>
    @get:Internal abstract val syncIos: Property<Boolean>

    @get:Internal abstract val manifestFile: RegularFileProperty
    @get:Internal abstract val infoPlistFile: RegularFileProperty
    @get:Internal abstract val pbxprojFile: RegularFileProperty
    @get:Internal abstract val appiconsetDir: DirectoryProperty
    @get:Internal abstract val androidResDir: DirectoryProperty

    @get:Internal abstract val kgpOnClasspath: Property<Boolean>

    private enum class Status(val glyph: String) { PASS("PASS"), WARN("WARN"), FAIL("FAIL"), SKIP("—  ") }

    @TaskAction
    fun diagnose() {
        val lines = mutableListOf<String>()
        fun check(status: Status, label: String, detail: String) {
            lines += "  [${status.glyph}] $label${if (detail.isNotEmpty()) " — $detail" else ""}"
        }

        // Android manifest placeholder.
        if (propagateAppName.getOrElse(true) && appName.isPresent) {
            val manifest = manifestFile.asFile.orNull
            when {
                manifest == null || !manifest.exists() ->
                    check(Status.WARN, "Android manifest", "not found; can't verify the \${appName} placeholder")
                manifest.readText().contains("\${appName}") ->
                    check(Status.PASS, "Android manifest", "android:label uses the \${appName} placeholder")
                else ->
                    check(Status.WARN, "Android manifest", "no \${appName} placeholder — set android:label=\"\${appName}\" so appName propagates")
            }
        } else {
            check(Status.SKIP, "Android manifest", "appName propagation off or appName unset")
        }

        // iOS Info.plist SSOT references.
        val plist = infoPlistFile.asFile.orNull
        when {
            plist == null || !plist.exists() ->
                check(Status.SKIP, "iOS Info.plist", "not found (generated plist?) — nothing to check")
            else -> {
                val t = plist.readText()
                val refs = listOf("\$(PRODUCT_NAME)", "\$(MARKETING_VERSION)").filter { t.contains(it) }
                if (refs.size == 2) check(Status.PASS, "iOS Info.plist", "references \$(PRODUCT_NAME) and \$(MARKETING_VERSION)")
                else check(Status.WARN, "iOS Info.plist", "missing SSOT build-setting references — run sanitizeIosProject")
            }
        }

        // pbxproj application target.
        if (syncIos.getOrElse(true)) {
            val pbx = pbxprojFile.asFile.orNull
            when {
                pbx == null || !pbx.exists() ->
                    check(Status.FAIL, "iOS pbxproj", "not found at ${pbx?.path ?: "[unset]"} — iOS sync can't run")
                applicationBuildConfigSpans(pbx.readText()).isNotEmpty() ->
                    check(Status.PASS, "iOS pbxproj", "application target found; rewrites are target-scoped")
                else ->
                    check(Status.WARN, "iOS pbxproj", "no application target detected — settings would be applied globally")
            }
            val icons = appiconsetDir.asFile.orNull
            if (icons != null && icons.isDirectory) check(Status.PASS, "iOS appiconset", "found at ${icons.path}")
            else check(Status.WARN, "iOS appiconset", "not found — the icon will be created on first sync")
        } else {
            check(Status.SKIP, "iOS", "syncIos = false")
        }

        // Android launcher-icon template collisions.
        val res = androidResDir.asFile.orNull
        if (res != null && res.isDirectory) {
            val collisions = SyncAndroidLogoTask.collidingTemplateIcons(res)
            if (collisions.isEmpty()) check(Status.PASS, "Android launcher icons", "no template/generated collisions")
            else check(Status.WARN, "Android launcher icons", "${collisions.size} colliding template icon(s) — run cleanupLegacyAppLogoArtifacts")
        }

        // Locale qualifiers.
        if (propagateLocaleList.getOrElse(true)) {
            val bad = locales.getOrElse(emptyList()).filterNot { looksLikeLocaleQualifier(it) }
            if (bad.isEmpty()) check(Status.PASS, "Locales", "all tags look like locale qualifiers")
            else check(Status.WARN, "Locales", "non-locale-looking tag(s): ${bad.joinToString(", ")}")
        }

        // versionCode derivability.
        if (propagateVersion.getOrElse(true) && versionName.isPresent && !hasVersionCodeOverride.getOrElse(false)) {
            val vn = versionName.get()
            runCatching { deriveVersionCode(vn) }
                .onSuccess { check(Status.PASS, "versionCode", "\"$vn\" derives to $it") }
                .onFailure { check(Status.FAIL, "versionCode", "\"$vn\" is not derivable — set versionCodeOverride") }
        }

        // KGP visibility.
        if (kgpOnClasspath.getOrElse(false)) check(Status.PASS, "Kotlin plugin", "visible to kmp-ssot (interop/web/buildConfig enabled)")
        else check(Status.WARN, "Kotlin plugin", "not visible to kmp-ssot's classloader — declare kotlin(\"multiplatform\") apply false in the ROOT plugins block")

        val fails = lines.count { it.contains("[FAIL]") }
        val warns = lines.count { it.contains("[WARN]") }
        logger.lifecycle(
            buildString {
                appendLine("[kmpSsot] Doctor report:")
                lines.forEach { appendLine(it) }
                append("  Summary: ${if (fails == 0 && warns == 0) "all checks passed" else "$fails failure(s), $warns warning(s)"}.")
            }
        )
    }
}
