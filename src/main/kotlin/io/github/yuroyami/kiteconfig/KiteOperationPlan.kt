package io.github.yuroyami.kiteconfig

import io.github.yuroyami.kiteconfig.core.BACKUP_SUFFIX
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import java.io.File

/**
 * One change KiteConfig may make to a file you commit.
 *
 * Every operation carries the reason it exists, because a preview that lists
 * what will happen without saying why is a list the reader has to reverse
 * engineer from their own build script.
 */
internal enum class KiteOperation(val description: String, val platform: KitePlatform) {
    IOS_PLIST("maintain the managed keys in the source Info.plist", KitePlatform.IOS),
    IOS_PROJECT("write the selected Xcode build settings", KitePlatform.IOS),
    IOS_SHARED_MODULE("rename the Pod and Swift module references", KitePlatform.IOS),
    IOS_APP_ICON("install the Apple AppIcon assets", KitePlatform.IOS),
    IOS_SPLASH("install the iOS launch screen and its assets", KitePlatform.IOS),
    IOS_SPLASH_REMOVAL("remove the iOS launch screen this plugin installed earlier", KitePlatform.IOS),
    IOS_DEBUG_APP_ICON("install the Apple debug AppIcon assets", KitePlatform.IOS),
    IOS_DEBUG_APP_ICON_REMOVAL("remove the Apple debug AppIcon this plugin installed earlier", KitePlatform.IOS),
    ANDROID_LAUNCHER_ICON("install the Android launcher icons", KitePlatform.ANDROID),
    ANDROID_LEGACY_ICONS("take over the launcher icons already at those paths", KitePlatform.ANDROID),
}

/**
 * What an apply would do, derived once from the resolved model.
 *
 * The preview and the tasks that do the work read the same object. They used to
 * decide separately from the same inputs, which is a drift waiting to happen:
 * a plan that says one thing while the task does another is worse than no plan.
 */
internal data class KiteOperationPlan(
    val operations: List<KiteOperation>,
    val reasons: Map<KiteOperation, String>,
    val paths: List<String>,
    val targets: List<String>,
    val policies: Map<String, String>,
) {
    operator fun contains(operation: KiteOperation): Boolean = operation in operations
}

/** Derives [KiteOperationPlan] from the resolved model. Reads nothing else. */
internal object KiteOperationPlanner {

    /**
     * Whether [operation] is part of the plan, as a lazy provider.
     *
     * The apply tasks gate on this, so "will it run" and "does the preview list
     * it" are the same question asked once.
     */
    fun enabled(ext: KiteConfigExtension, operation: KiteOperation): Provider<Boolean> = when (operation) {
        // Both gates already require iOS to be selected.
        KiteOperation.IOS_PLIST -> ext.effectiveSanitizeIosProject

        KiteOperation.IOS_PROJECT -> ext.effectiveSyncIos

        KiteOperation.IOS_SHARED_MODULE -> ext.effectiveSyncIos
            .zip(ext.effectivePropagateSharedModule) { ios, rename -> ios && rename }

        KiteOperation.IOS_APP_ICON -> ext.effectiveIosLogoInstall
        KiteOperation.IOS_SPLASH -> ext.effectiveIosSplash

        // Reconciliation runs when the feature is off but iOS is still selected,
        // so a launch screen that was installed and then turned off is taken back
        // out rather than left wired up.
        KiteOperation.IOS_SPLASH_REMOVAL -> ext.effectiveSyncIos
            .zip(ext.effectiveIosSplash) { ios, splash -> ios && !splash }

        KiteOperation.IOS_DEBUG_APP_ICON -> ext.debugLogoFlowsTo(KitePlatform.IOS)

        // The debug catalog is taken back out only while the release icon is still
        // managed. With no logo at all, Xcode's Debug setting still points at it.
        KiteOperation.IOS_DEBUG_APP_ICON_REMOVAL -> ext.effectiveSyncIos
            .zip(ext.effectiveIosLogoInstall) { ios, releaseIcon -> ios && releaseIcon }
            .zip(ext.debugLogoFlowsTo(KitePlatform.IOS)) { managed, on -> managed && !on }

        KiteOperation.ANDROID_LAUNCHER_ICON -> ext.effectiveAndroidLogoInstall
        KiteOperation.ANDROID_LEGACY_ICONS -> ext.effectiveTakeOverLegacyIcons
    }

    /** Why [operation] is in the plan, phrased as the configuration that asked for it. */
    fun reason(operation: KiteOperation): String = when (operation) {
        KiteOperation.IOS_PLIST -> "appName, version, or an ios { infoPlist } fact reaches iOS"
        KiteOperation.IOS_PROJECT -> "iOS is a selected platform"
        KiteOperation.IOS_SHARED_MODULE -> "ios { renameSharedModule } was called"
        KiteOperation.IOS_APP_ICON -> "logo { foreground } reaches iOS"
        KiteOperation.IOS_SPLASH -> "splash { enabled } is true for iOS"
        KiteOperation.IOS_SPLASH_REMOVAL -> "splash is off for iOS, so anything installed earlier is removed"
        KiteOperation.IOS_DEBUG_APP_ICON -> "debug { logo } reaches iOS"
        KiteOperation.IOS_DEBUG_APP_ICON_REMOVAL -> "the debug icon is off for iOS, so anything installed earlier is removed"
        KiteOperation.ANDROID_LAUNCHER_ICON -> "logo { foreground } reaches Android"
        KiteOperation.ANDROID_LEGACY_ICONS -> "installing the launcher icon claims the paths it occupies"
    }

    /** The whole plan, for the preview task. */
    fun derive(
        root: Project,
        ext: KiteConfigExtension,
        detectedApplications: List<String>,
        androidResourceDirectories: List<File>,
    ): KiteOperationPlan {
        val operations = KiteOperation.entries
            .filter { enabled(ext, it).get() }
            // A removal with nothing installed is noise, so it is listed only when
            // a receipt says something is there to take back out.
            .filter { it != KiteOperation.IOS_SPLASH_REMOVAL || installedSplashReceipt(root, ext) != null }
            .filter { it != KiteOperation.IOS_DEBUG_APP_ICON_REMOVAL || installedDebugIconReceipt(root, ext) != null }
        return KiteOperationPlan(
            operations = operations,
            reasons = operations.associateWith(::reason),
            paths = paths(root, ext, operations, androidResourceDirectories),
            targets = ext.effectiveAndroidApps.get().ifEmpty { detectedApplications }
                .map { "Android project $it" } +
                ext.effectiveIosTargets.get().map { "Xcode target $it" },
            policies = mapOf(
                "backups" to ext.effectiveBackups.get().toString(),
                "dryRun" to ext.effectiveDryRun.get().toString(),
                "autoApply.android" to ext.effectiveAutoApplyFor(KitePlatform.ANDROID).get().toString(),
                "autoApply.ios" to ext.effectiveAutoApplyFor(KitePlatform.IOS).get().toString(),
                "ios.plist.policy" to ext.effectivePlistConflictPolicy.get().name,
                "pbxprojScope" to if (ext.effectiveIosTargets.get().isEmpty()) {
                    "sole application target only"
                } else {
                    "explicit targets"
                },
                "ios.debugConfigurations" to ext.effectiveIosDebugConfigurations.get().joinToString(),
                "desktop.identity" to if (ext.effectiveDesktopEnabled.get()) "enabled" else "disabled",
            ),
        )
    }

    /** The receipt for a launch screen this plugin installed earlier, when one exists. */
    private fun installedSplashReceipt(root: Project, ext: KiteConfigExtension): File? {
        val assets = runCatching { ext.effectiveIosAppIconDirectory.get().asFile.parentFile }.getOrNull()
            ?: return null
        val identity = runCatching { iosSplashCatalogIdentity(root.projectDir, assets) }.getOrNull()
            ?: return null
        return iosSplashManifestFile(assets, identity).takeIf(File::isFile)
    }

    /** The receipt for a debug catalog this plugin installed earlier, when one exists. */
    private fun installedDebugIconReceipt(root: Project, ext: KiteConfigExtension): File? {
        val icons = runCatching { ext.iosDebugAppIconDirectory.get().asFile }.getOrNull() ?: return null
        val identity = runCatching { SyncIosLogoTask.catalogIdentity(root.projectDir, icons) }.getOrNull() ?: return null
        return (icons.parentFile?.parentFile ?: icons.parentFile ?: icons)
            .resolve(".kiteconfig/$identity/owned-files-v1")
            .takeIf(File::isFile)
    }

    /** Every path the plan may touch, including receipts, locks, and recovery copies. */
    private fun paths(
        root: Project,
        ext: KiteConfigExtension,
        operations: List<KiteOperation>,
        androidResourceDirectories: List<File>,
    ): List<String> = buildList {
        val backups = ext.effectiveBackups.get()
        fun addTextTarget(file: File) {
            add(file.path)
            if (backups) add(file.path + BACKUP_SUFFIX)
        }

        if (KiteOperation.IOS_PROJECT in operations) {
            addTextTarget(ext.effectiveIosPbxproj.get().asFile)
            add(root.layout.projectDirectory.file(".gradle/kiteconfig/rewrite.lock").asFile.path)
        }
        if (KiteOperation.IOS_PLIST in operations) {
            addTextTarget(ext.effectiveIosInfoPlist.get().asFile)
        }
        if (KiteOperation.IOS_SPLASH in operations || KiteOperation.IOS_SPLASH_REMOVAL in operations) {
            val assets = ext.effectiveIosAppIconDirectory.get().asFile.parentFile
            iosSplashOwnedPaths(assets, ext.effectiveSplashDarkImage.isPresent).forEach { add(it.path) }
            val identity = iosSplashCatalogIdentity(root.projectDir, assets)
            add(iosSplashManifestFile(assets, identity).path)
        }
        if (KiteOperation.IOS_SHARED_MODULE in operations) {
            addTextTarget(ext.effectiveIosPodfile.get().asFile)
            add(ext.effectiveIosAppDirectory.get().asFile.path)
        }
        fun addAppIconPaths(icons: File) {
            add(icons.path)
            val identity = SyncIosLogoTask.catalogIdentity(root.projectDir, icons)
            val metadata = (icons.parentFile?.parentFile ?: icons.parentFile ?: icons)
                .resolve(".kiteconfig/$identity")
            val manifest = metadata.resolve("owned-files-v1")
            add(manifest.path)
            add(manifest.path + ".lock")
            if (backups) add(root.projectDir.resolve(".kiteconfig/recovery/ios-appicon/$identity").path)
        }
        if (KiteOperation.IOS_APP_ICON in operations) {
            addAppIconPaths(ext.effectiveIosAppIconDirectory.get().asFile)
        }
        if (KiteOperation.IOS_DEBUG_APP_ICON in operations || KiteOperation.IOS_DEBUG_APP_ICON_REMOVAL in operations) {
            addAppIconPaths(ext.iosDebugAppIconDirectory.get().asFile)
        }
        if (KiteOperation.ANDROID_LAUNCHER_ICON in operations) {
            androidResourceDirectories.forEach { res ->
                add(res.path)
                val manifest = res.parentFile.resolve(".kiteconfig/android-logo-owned-files-v1")
                add(manifest.path)
                add(manifest.path + ".lock")
            }
        }
        if (KiteOperation.ANDROID_LEGACY_ICONS in operations) {
            val recovery = root.projectDir.resolve(".kiteconfig/recovery/android-logo")
            add(recovery.path)
            add(recovery.resolve("removal-provenance.tsv").path)
            add(recovery.resolve(".migration.lock").path)
        }
    }
}
