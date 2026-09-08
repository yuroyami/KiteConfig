package io.github.yuroyami.kiteconfig

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register

/** Manifest placeholder syntax. A label containing this would be substituted, not printed. */
private val MANIFEST_PLACEHOLDER = Regex("""\$\{[^}]*}""")

/**
 * Writes the `<application>` attributes KiteConfig owns into a manifest of its
 * own, which AGP merges over the module's `AndroidManifest.xml`.
 *
 * This is what removes the two hand-written lines a 1.0.0 project needed
 * (`android:label="${'$'}{appName}"` and `android:theme="${'$'}{kiteSplashTheme}"`).
 * A generated manifest outranks the module's own, and `tools:replace` names the
 * attributes it is allowed to take over, so the value in `kiteConfig { }` reaches
 * the built app whether or not the manifest mentions it.
 *
 * Output stays in `build/`, so this needs no ownership receipt and no backups.
 */
@CacheableTask
internal abstract class GenerateAndroidManifestOverlayTask : DefaultTask() {

    init {
        group = "kiteconfig"
        description = "Generate the Android manifest overlay for the app label and splash theme."
    }

    /** The app label. Absent when `appName` does not reach Android. */
    @get:[Input Optional]
    abstract val label: Property<String>

    /** The generated splash theme. Absent when the splash does not reach Android. */
    @get:[Input Optional]
    abstract val theme: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val attributes = buildList {
            label.orNull?.let { add("android:label" to validatedLabel(it)) }
            theme.orNull?.let { add("android:theme" to it) }
        }
        outputFile.get().asFile.also { it.parentFile?.mkdirs() }.writeText(manifestText(attributes))
    }

    /**
     * A label is free text, so `${'$'}{...}` in it would read as a manifest placeholder
     * and fail the merge somewhere far from the cause.
     */
    private fun validatedLabel(value: String): String {
        if (MANIFEST_PLACEHOLDER.containsMatchIn(value)) {
            throw GradleException(
                "[kiteConfig] The app name '$value' contains Android manifest placeholder syntax, " +
                    "which Android would try to substitute instead of showing. Remove the \${...} " +
                    "from appName.",
            )
        }
        return value
    }

    private fun manifestText(attributes: List<Pair<String, String>>): String {
        if (attributes.isEmpty()) {
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" />\n"
        }
        val written = attributes.joinToString("\n") { (name, value) ->
            "        $name=\"${escapeXmlAttribute(value)}\""
        }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
            append("    xmlns:tools=\"http://schemas.android.com/tools\">\n")
            append("    <application\n")
            append(written)
            append("\n        tools:replace=\"")
            append(attributes.joinToString(",") { it.first })
            append("\" />\n")
            append("</manifest>\n")
        }
    }
}

/** XML attribute escaping. An app name may legally contain any of these. */
private fun escapeXmlAttribute(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

/**
 * Wires the generated overlay onto one `com.android.application` module.
 *
 * The task is registered unconditionally but only realized when a variant
 * actually takes it, so a failing provider behind a feature that is off never
 * runs. Pass `resilient = true` on the diagnostic invocations, as the rest of
 * the Android wiring does.
 */
internal fun wireAndroidManifestOverlay(
    project: Project,
    ext: KiteConfigExtension,
    resilient: Boolean = false,
) {
    val components = project.extensions
        .findByType(ApplicationAndroidComponentsExtension::class.java) ?: return

    val overlay = project.tasks.register<GenerateAndroidManifestOverlayTask>("kiteInternalAndroidManifestOverlay") {
        if (ext.appNameFlowsTo(KitePlatform.ANDROID).get()) {
            label.set(ext.effectiveAppNameFor(KitePlatform.ANDROID))
        }
        if (ext.effectiveAndroidSplash.get()) {
            theme.set("@style/$ANDROID_SPLASH_STYLE")
        }
        outputFile.set(project.layout.buildDirectory.file("generated/kiteconfig/manifest/AndroidManifest.xml"))
    }

    components.onVariants { variant ->
        // Fails closed: when the gate cannot resolve on a diagnostic run, the
        // overlay is not attached and the module's own manifest is untouched.
        var overlayFlows = false
        project.wireValueGroup(resilient, "the Android manifest overlay") {
            val selectedApplications = ext.effectiveAndroidApps.get()
            val selectedHere = selectedApplications.isEmpty() || project.path in selectedApplications
            val carriesLabel = ext.appNameFlowsTo(KitePlatform.ANDROID).get() &&
                ext.effectiveAppNameFor(KitePlatform.ANDROID).isPresent
            overlayFlows = selectedHere && (carriesLabel || ext.effectiveAndroidSplash.get())
        }
        if (overlayFlows) {
            variant.sources.manifests.addGeneratedManifestFile(overlay) { it.outputFile }
        }
    }
}
