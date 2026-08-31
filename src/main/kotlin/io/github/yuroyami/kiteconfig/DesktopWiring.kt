package io.github.yuroyami.kiteconfig

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.jetbrains.compose.desktop.DesktopExtension
import org.jetbrains.compose.desktop.application.dsl.AbstractDistributions
import org.jetbrains.compose.desktop.application.dsl.AbstractMacOSPlatformSettings
import org.jetbrains.compose.desktop.application.dsl.JvmApplicationDistributions

/**
 * Whether a project's Compose Desktop application flags could be read at all, kept
 * apart from what they said. An unreadable flag and a genuine "no application" are
 * different facts: an explicit `modules { desktopApps(...) }` selector must reject
 * the latter and stay lenient only for the former.
 */
internal enum class DesktopAppProbe { APPLICATION, NOT_APPLICATION, UNAVAILABLE }

/**
 * Writes the single source of truth into Compose Desktop.
 *
 * Kept in its **own file** for the reason [ClassicAndroidWiring] states: Compose
 * DSL types appear in these bodies and lambdas, which puts them into synthetic
 * method descriptors. Were those on [KiteConfigPlugin] itself, Gradle would fail to
 * *decorate* the plugin whenever Compose is absent from the classpath.
 *
 * **Authority:** Compose holds identity as plain `var` fields, not lazy
 * `Property` objects, and reads them inside its own `afterEvaluate`. There is no
 * provider to hand over, so KiteConfig writes resolved values from a callback
 * registered earlier than Compose's (see [KiteConfigPlugin]). That registration
 * order is the whole mechanism; without it every write below lands too late.
 *
 * **Who wins:** core identity (`packageName`, `packageVersion`, `macOS.bundleID`,
 * `macOS.packageBuildVersion`) replaces whatever the module declared, and
 * [ConfigDriftLog] names the replacement once per project. Per-format extras
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
     * that only draws UI. [DesktopAppProbe.UNAVAILABLE] counts as false here,
     * because the zero-selector census this feeds must stay conservative.
     */
    fun isDesktopApp(project: Project): Boolean = probe(project) == DesktopAppProbe.APPLICATION

    /**
     * The three-valued read behind [isDesktopApp]. An explicit
     * `modules { desktopApps(...) }` selector needs the extra state:
     * [DesktopAppProbe.NOT_APPLICATION] is grounds to reject a named path, while
     * [DesktopAppProbe.UNAVAILABLE] is not, since reflection breaking is the
     * escape hatch design section 15 relies on explicit selection for.
     */
    fun probe(project: Project): DesktopAppProbe {
        val desktop = desktopExtension(project) ?: return DesktopAppProbe.UNAVAILABLE
        val jvm = readFlag(desktop, JVM_APPLICATION_FLAG)
        val native = readFlag(desktop, NATIVE_APPLICATION_FLAG)
        return when {
            jvm == true || native == true -> DesktopAppProbe.APPLICATION
            jvm == false && native == false -> DesktopAppProbe.NOT_APPLICATION
            else -> DesktopAppProbe.UNAVAILABLE
        }
    }

    fun write(project: Project, ext: KiteConfigExtension, resilient: Boolean) {
        val desktop = desktopExtension(project) ?: return
        val jvmApplication = initialized(desktop, JVM_APPLICATION_FLAG)
        val nativeApplication = initialized(desktop, NATIVE_APPLICATION_FLAG)

        // Fails closed: when the selection cannot resolve on a diagnostic run, no
        // desktop value is written anywhere.
        var receivesDesktopValues = false
        project.wireValueGroup(resilient, "the desktop application selection") {
            val selected = ext.effectiveDesktopApps.get()
            val explicitlySelected = project.path in selected
            // Checked here, before anything below touches desktop.application: that access
            // initializes the lazy delegate as a side effect, so a later, project-census-wide
            // check would always see APPLICATION regardless of what this project configures.
            // UNAVAILABLE stays lenient; it is the reflection escape hatch explicit
            // selection exists for.
            if (explicitlySelected && probe(project) == DesktopAppProbe.NOT_APPLICATION) {
                throw GradleException(
                    "kiteConfig { modules { desktopApps } } names ${project.path}, which applies " +
                        "org.jetbrains.compose but configures no desktop application. Remove it " +
                        "from desktopApps, or configure compose.desktop.application { } or " +
                        "compose.desktop.nativeApplication { } there."
                )
            }
            receivesDesktopValues = if (selected.isEmpty()) {
                jvmApplication || nativeApplication
            } else {
                explicitlySelected
            }
        }
        if (!receivesDesktopValues) return

        val drift = ConfigDriftLog(project)
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
     * `icons = true` with no usable logo, so by the time [KiteConfigExtension.effectiveDesktopIcons]
     * reads true here, foreground plus exactly one background is already in place.
     */
    private fun registerIconsTask(
        project: Project,
        ext: KiteConfigExtension,
        resilient: Boolean,
    ): TaskProvider<GenerateDesktopIconsTask>? {
        var iconsEnabled = false
        project.wireValueGroup(resilient, "the desktop icon selection") {
            iconsEnabled = ext.effectiveDesktopIcons.get()
        }
        if (!iconsEnabled) return null
        return project.tasks.register<GenerateDesktopIconsTask>("kiteInternalDesktopIcons") {
            foreground.set(ext.effectiveLogoForeground)
            background.set(ext.effectiveLogoBackground)
            backgroundColor.set(ext.effectiveLogoBackgroundColor)
            roundMacOsIcon.set(ext.logo.desktop.roundMac.orElse(true))
            outputDir.set(project.layout.buildDirectory.dir("generated/kiteconfig/desktop-icons"))
            dryRun.set(ext.effectiveDryRun)
        }
    }

    /** The values both application models share, typed against their common bases. */
    private fun writeSharedIdentity(
        project: Project,
        ext: KiteConfigExtension,
        resilient: Boolean,
        drift: ConfigDriftLog,
        distributions: AbstractDistributions,
        macOS: AbstractMacOSPlatformSettings,
        iconTask: TaskProvider<GenerateDesktopIconsTask>?,
    ) {
        project.wireValueGroup(resilient, "the desktop package name") {
            if (ext.appNameFlowsTo(KitePlatform.DESKTOP).get() && ext.effectiveAppNameFor(KitePlatform.DESKTOP).isPresent) {
                val applied = ext.effectiveAppNameFor(KitePlatform.DESKTOP).get()
                drift.observe("packageName", distributions.packageName, applied)
                distributions.packageName = applied
            }
        }
        project.wireValueGroup(resilient, "the desktop package version") {
            if (ext.versionFlowsTo(KitePlatform.DESKTOP).get() && ext.effectiveVersion.isPresent) {
                val formats = distributions.targetFormats.mapTo(mutableSetOf()) { it.name }
                val applied = validateDesktopPackageVersion(ext.effectiveVersion.get(), formats)
                drift.observe("packageVersion", distributions.packageVersion, applied)
                distributions.packageVersion = applied
            }
        }
        project.wireValueGroup(resilient, "the desktop bundle identifier") {
            if (ext.idFlowsTo(KitePlatform.DESKTOP).get() && ext.id.isPresent) {
                val applied = ext.desktopBundleId.get()
                drift.observe("macOS.bundleID", macOS.bundleID, applied)
                macOS.bundleID = applied
            }
        }
        project.wireValueGroup(resilient, "the desktop build number") {
            if (ext.versionFlowsTo(KitePlatform.DESKTOP).get()) {
                ext.effectiveDesktopBuildNumber.orNull?.let { applied ->
                    drift.observe("macOS.packageBuildVersion", macOS.packageBuildVersion, applied)
                    macOS.packageBuildVersion = applied
                }
            }
        }
        // A provider carrying task provenance, never a resolved File: this is what lets
        // Gradle infer that packaging depends on kiteInternalDesktopIcons.
        if (iconTask != null) {
            project.wireValueGroup(resilient, "the desktop macOS icon") {
                macOS.iconFile.set(iconTask.flatMap { it.outputDir.file("app.icns") })
            }
        }
    }

    /** Windows and Linux exist only on the JVM application model. */
    private fun writeJvmOnlyIdentity(
        project: Project,
        ext: KiteConfigExtension,
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
            if (packagesForLinux && ext.appNameFlowsTo(KitePlatform.DESKTOP).get() && ext.effectiveAppNameFor(KitePlatform.DESKTOP).isPresent) {
                linux.packageName = deriveLinuxPackageName(ext.effectiveAppNameFor(KitePlatform.DESKTOP).get())
            }
        }
        project.wireValueGroup(resilient, "the Windows upgrade code") {
            val windows = distributions.windows
            if (windows.upgradeUuid != null) return@wireValueGroup
            if (ext.idFlowsTo(KitePlatform.DESKTOP).get() && ext.desktop.deriveUpgradeUuid.getOrElse(false) &&
                ext.id.isPresent
            ) {
                windows.upgradeUuid = deriveUpgradeUuid(ext.id.get())
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
    private fun initialized(desktop: Any, accessor: String): Boolean = readFlag(desktop, accessor) == true

    /** Null means the accessor is missing or reflection failed, distinct from a genuine false. */
    private fun readFlag(desktop: Any, accessor: String): Boolean? = try {
        desktop.javaClass.getMethod(accessor).invoke(desktop) as Boolean
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: LinkageError) {
        null
    }

    // ------------------------------------------------------------------ Splash
    //
    // Everything below is the splash topic and is purely additive. Nothing above
    // it changes, so the identity wiring keeps its registration-order contract.

    /** What the packaged launcher receives. jpackage expands `APPDIR` at run time. */
    private const val PACKAGED_SPLASH_JVM_ARG = "-splash:\$APPDIR/resources/splash.png"

    private const val SPLASH_JVM_ARG_PREFIX = "-splash:"

    /** Compose registers one `JavaExec` run task per build type: default plus release. */
    private val COMPOSE_RUN_TASKS = setOf("run", "runRelease")

    /** Where the generator writes the Compose app-resources root for this project. */
    private const val SPLASH_OUTPUT_DIR = "generated/kiteconfig/desktop-splash"

    /**
     * Registers the desktop splash generator and delivers its image as the
     * packaged application's JVM `-splash:`. No-op unless `splash { }` flows here.
     *
     * [resilient] mirrors [write]: on a diagnostic invocation a failing provider is
     * logged and its group skipped instead of aborting configuration.
     */
    fun wireDesktopSplash(project: Project, ext: KiteConfigExtension, resilient: Boolean = false) {
        val desktop = desktopExtension(project) ?: return
        // Read before anything below touches desktop.application: that access
        // initializes the lazy delegate, after which every probe reports
        // APPLICATION regardless of what this project actually configured.
        val appProbe = probe(project)
        val jvmApplication = initialized(desktop, JVM_APPLICATION_FLAG)
        val nativeApplication = initialized(desktop, NATIVE_APPLICATION_FLAG)

        var enabled = false
        project.wireValueGroup(resilient, "the desktop splash selection") {
            val selected = ext.effectiveDesktopApps.get()
            val receivesDesktopValues = if (selected.isEmpty()) {
                appProbe == DesktopAppProbe.APPLICATION
            } else {
                project.path in selected
            }
            enabled = receivesDesktopValues && ext.effectiveDesktopSplash.get()
        }
        // `-splash:` is a JVM launcher feature. The Kotlin/Native application model
        // has no jvmArgs and no JVM launcher to read them.
        if (!enabled || (nativeApplication && !jvmApplication)) return

        val application = desktop.application
        val distributions = application.nativeDistributions
        if (distributions.appResourcesRootDir.isPresent) {
            skipDesktopSplash(project, "it already sets nativeDistributions.appResourcesRootDir")
            return
        }
        if (application.jvmArgs.any { it.startsWith(SPLASH_JVM_ARG_PREFIX) }) {
            skipDesktopSplash(project, "it already passes its own -splash: launcher argument")
            return
        }

        val splashTask = project.tasks.register<GenerateDesktopSplashTask>("kiteInternalDesktopSplash") {
            image.set(ext.effectiveSplashImage)
            backgroundColor.set(ext.effectiveSplashColor)
            outputDir.set(project.layout.buildDirectory.dir(SPLASH_OUTPUT_DIR))
        }

        // A provider carrying task provenance, the same shape macOS.iconFile uses.
        // That is what makes Compose's prepareAppResources depend on the generator.
        project.wireValueGroup(resilient, "the desktop splash resources directory") {
            distributions.appResourcesRootDir.set(splashTask.flatMap { it.outputDir })
        }
        project.wireValueGroup(resilient, "the desktop splash launcher argument") {
            application.jvmArgs(PACKAGED_SPLASH_JVM_ARG)
        }
        wireDesktopSplashRunTasks(project, splashTask)
    }

    /** Names what blocked the write, the way [ConfigDriftLog] names a replaced value. */
    private fun skipDesktopSplash(project: Project, because: String) {
        project.logger.warn(
            "[kiteConfig] ${project.path} keeps its own desktop launch image because $because. " +
                "The kiteConfig { splash { } } desktop image was not wired here.",
        )
    }

    /**
     * `APPDIR` exists only inside the packaged launcher, so `run` gets the build
     * directory path instead. Applied in `doFirst` because Compose assigns the whole
     * `jvmArgs` list inside its own configuration action, which would drop an append.
     */
    private fun wireDesktopSplashRunTasks(
        project: Project,
        splashTask: TaskProvider<GenerateDesktopSplashTask>,
    ) {
        val splashArgument = splashTask.flatMap { it.outputDir.file(DESKTOP_SPLASH_RESOURCE_PATH) }
            .map { SPLASH_JVM_ARG_PREFIX + it.asFile.absolutePath }
        project.tasks.withType(JavaExec::class.java).configureEach {
            if (name !in COMPOSE_RUN_TASKS) return@configureEach
            dependsOn(splashTask)
            val exec: JavaExec = this
            doFirst { exec.jvmArgs(splashArgument.get()) }
        }
    }
}
