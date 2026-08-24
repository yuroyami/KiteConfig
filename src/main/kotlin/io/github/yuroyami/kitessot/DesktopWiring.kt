package io.github.yuroyami.kitessot

import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.jetbrains.compose.desktop.DesktopExtension
import org.jetbrains.compose.desktop.application.dsl.AbstractDistributions
import org.jetbrains.compose.desktop.application.dsl.AbstractMacOSPlatformSettings
import org.jetbrains.compose.desktop.application.dsl.JvmApplicationDistributions

/**
 * Writes the single source of truth into Compose Desktop.
 *
 * Kept in its **own file** for the reason [ClassicAndroidWiring] states: Compose
 * DSL types appear in these bodies and lambdas, which puts them into synthetic
 * method descriptors. Were those on [KiteSsotPlugin] itself, Gradle would fail to
 * *decorate* the plugin whenever Compose is absent from the classpath.
 *
 * **Authority:** Compose holds identity as plain `var` fields, not lazy
 * `Property` objects, and reads them inside its own `afterEvaluate`. There is no
 * provider to hand over, so KiteSSOT writes resolved values from a callback
 * registered earlier than Compose's (see [KiteSsotPlugin]). That registration
 * order is the whole mechanism; without it every write below lands too late.
 *
 * **Who wins:** core identity (`packageName`, `packageVersion`, `macOS.bundleID`,
 * `macOS.packageBuildVersion`) replaces whatever the module declared, and
 * [SsotDriftLog] names the replacement once per project. Per-format extras
 * (`linux.packageName`, `windows.upgradeUuid`) only ever fill a blank, because
 * they are not shared truths.
 */
internal object DesktopWiring {

    /**
     * The JVM-mangled names of `DesktopExtension._isJvmApplicationInitialized` and
     * `_isNativeApplicationInitialized`, which are Kotlin `internal`.
     */
    private const val JVM_APPLICATION_FLAG = "get_isJvmApplicationInitialized\$compose"
    private const val NATIVE_APPLICATION_FLAG = "get_isNativeApplicationInitialized\$compose"

    /** The formats whose package name must be a Debian-legal slug. */
    private val LINUX_FORMATS = setOf("Deb", "Rpm")

    /**
     * Whether this project declared a Compose Desktop application.
     *
     * Reads the initialization flags reflectively. That indirection is not
     * optional: calling `application` or `nativeApplication` initializes the lazy
     * delegate behind it, and Compose then configures packaging tasks in a module
     * that only draws UI. A flag that cannot be read counts as "not an
     * application", so the module degrades to explicit selection through
     * `modules { desktopApps(...) }` rather than guessing.
     */
    fun isDesktopApp(project: Project): Boolean {
        val desktop = desktopExtension(project) ?: return false
        return initialized(desktop, JVM_APPLICATION_FLAG) || initialized(desktop, NATIVE_APPLICATION_FLAG)
    }

    fun write(project: Project, ext: KiteSsotExtension, resilient: Boolean) {
        val desktop = desktopExtension(project) ?: return
        val jvmApplication = initialized(desktop, JVM_APPLICATION_FLAG)
        val nativeApplication = initialized(desktop, NATIVE_APPLICATION_FLAG)

        // Fails closed: when the selection cannot resolve on a diagnostic run, no
        // desktop value is written anywhere.
        var receivesDesktopValues = false
        project.wireValueGroup(resilient, "the desktop application selection") {
            val selected = ext.effectiveDesktopApps.get()
            receivesDesktopValues = if (selected.isEmpty()) {
                jvmApplication || nativeApplication
            } else {
                project.path in selected
            }
        }
        if (!receivesDesktopValues) return

        val drift = SsotDriftLog(project)
        // One task feeds every distributions model this project ends up with, so it is
        // registered once here rather than inside writeSharedIdentity/writeJvmOnlyIdentity.
        val iconTask = registerIconsTask(project, ext, resilient)
        // An explicit selector overrides detection, so a named project is treated as
        // a JVM application even when both flags read false. That is the escape
        // hatch for a Compose release that renames them.
        if (jvmApplication || !nativeApplication) {
            val distributions = desktop.application.nativeDistributions
            writeSharedIdentity(project, ext, resilient, drift, distributions, distributions.macOS, iconTask)
            writeJvmOnlyIdentity(project, ext, resilient, distributions, iconTask)
        }
        if (nativeApplication) {
            val distributions = desktop.nativeApplication.distributions
            writeSharedIdentity(project, ext, resilient, drift, distributions, distributions.macOS, iconTask)
        }
        drift.report()
    }

    /**
     * Registers the icon generator for this project, or returns null when
     * `desktop { icons }` resolves false. `logo { }` completeness is guaranteed by the
     * root validation that runs before this: it fails configuration for an explicit
     * `icons = true` with no usable logo, so by the time [KiteSsotExtension.effectiveDesktopIcons]
     * reads true here, foreground plus exactly one background is already in place.
     */
    private fun registerIconsTask(
        project: Project,
        ext: KiteSsotExtension,
        resilient: Boolean,
    ): TaskProvider<GenerateDesktopIconsTask>? {
        var iconsEnabled = false
        project.wireValueGroup(resilient, "the desktop icon selection") {
            iconsEnabled = ext.effectiveDesktopIcons.get()
        }
        if (!iconsEnabled) return null
        return project.tasks.register<GenerateDesktopIconsTask>("generateKiteSsotDesktopIcons") {
            foreground.set(ext.effectiveLogoForeground)
            background.set(ext.effectiveLogoBackground)
            backgroundColor.set(ext.effectiveLogoBackgroundColor)
            roundMacOsIcon.set(ext.desktop.roundMacOsIcon.orElse(true))
            outputDir.set(project.layout.buildDirectory.dir("generated/kitessot/desktop-icons"))
            dryRun.set(ext.effectiveDryRun)
        }
    }

    /** The values both application models share, typed against their common bases. */
    private fun writeSharedIdentity(
        project: Project,
        ext: KiteSsotExtension,
        resilient: Boolean,
        drift: SsotDriftLog,
        distributions: AbstractDistributions,
        macOS: AbstractMacOSPlatformSettings,
        iconTask: TaskProvider<GenerateDesktopIconsTask>?,
    ) {
        project.wireValueGroup(resilient, "the desktop package name") {
            if (ext.effectivePropagateAppName.get() && ext.effectiveAppName.isPresent) {
                val applied = ext.effectiveAppName.get()
                drift.observe("packageName", distributions.packageName, applied)
                distributions.packageName = applied
            }
        }
        project.wireValueGroup(resilient, "the desktop package version") {
            if (ext.effectivePropagateVersion.get() && ext.effectiveVersion.isPresent) {
                val formats = distributions.targetFormats.mapTo(mutableSetOf()) { it.name }
                val applied = validateDesktopPackageVersion(ext.effectiveVersion.get(), formats)
                drift.observe("packageVersion", distributions.packageVersion, applied)
                distributions.packageVersion = applied
            }
        }
        project.wireValueGroup(resilient, "the desktop bundle identifier") {
            if (ext.effectivePropagateBundleId.get() && ext.effectiveAppId.isPresent) {
                val applied = ext.desktopBundleId.get()
                drift.observe("macOS.bundleID", macOS.bundleID, applied)
                macOS.bundleID = applied
            }
        }
        project.wireValueGroup(resilient, "the desktop build number") {
            if (ext.effectivePropagateVersion.get()) {
                ext.effectiveDesktopBuildNumber.orNull?.let { applied ->
                    drift.observe("macOS.packageBuildVersion", macOS.packageBuildVersion, applied)
                    macOS.packageBuildVersion = applied
                }
            }
        }
        // A provider carrying task provenance, never a resolved File: this is what lets
        // Gradle infer that packaging depends on generateKiteSsotDesktopIcons.
        if (iconTask != null) {
            project.wireValueGroup(resilient, "the desktop macOS icon") {
                macOS.iconFile.set(iconTask.flatMap { it.outputDir.file("app.icns") })
            }
        }
    }

    /** Windows and Linux exist only on the JVM application model. */
    private fun writeJvmOnlyIdentity(
        project: Project,
        ext: KiteSsotExtension,
        resilient: Boolean,
        distributions: JvmApplicationDistributions,
        iconTask: TaskProvider<GenerateDesktopIconsTask>?,
    ) {
        project.wireValueGroup(resilient, "the Linux package name") {
            val linux = distributions.linux
            // Per-format extra: an explicit module value is always kept.
            if (linux.packageName != null) return@wireValueGroup
            val chosen = ext.desktop.linuxPackageName.orNull
            if (chosen != null) {
                linux.packageName = chosen
                return@wireValueGroup
            }
            val packagesForLinux = distributions.targetFormats.any { it.name in LINUX_FORMATS }
            if (packagesForLinux && ext.effectivePropagateAppName.get() && ext.effectiveAppName.isPresent) {
                linux.packageName = deriveLinuxPackageName(ext.effectiveAppName.get())
            }
        }
        project.wireValueGroup(resilient, "the Windows upgrade code") {
            val windows = distributions.windows
            if (windows.upgradeUuid != null) return@wireValueGroup
            if (ext.effectivePropagateBundleId.get() && ext.desktop.deriveUpgradeUuid.getOrElse(false) &&
                ext.effectiveAppId.isPresent
            ) {
                windows.upgradeUuid = deriveUpgradeUuid(ext.effectiveAppId.get())
            }
        }
        if (iconTask != null) {
            project.wireValueGroup(resilient, "the desktop Windows icon") {
                distributions.windows.iconFile.set(iconTask.flatMap { it.outputDir.file("app.ico") })
            }
            project.wireValueGroup(resilient, "the desktop Linux icon") {
                distributions.linux.iconFile.set(iconTask.flatMap { it.outputDir.file("app.png") })
            }
        }
    }

    private fun desktopExtension(project: Project): DesktopExtension? {
        val compose = project.extensions.findByName("compose") as? ExtensionAware ?: return null
        return compose.extensions.findByName("desktop") as? DesktopExtension
    }

    /** Reads one `internal` initialization flag without initializing what it guards. */
    private fun initialized(desktop: Any, accessor: String): Boolean = runCatching {
        desktop.javaClass.getMethod(accessor).invoke(desktop) as Boolean
    }.getOrDefault(false)
}
