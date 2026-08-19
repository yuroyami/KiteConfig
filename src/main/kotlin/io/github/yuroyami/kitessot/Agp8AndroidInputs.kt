package io.github.yuroyami.kitessot

/**
 * A resolved snapshot of everything the AGP 8 adapter writes into the classic
 * Android DSL.
 *
 * The adapter is compiled as Java against the AGP 8.5.2 floor, in its own source
 * set, so it cannot read KiteSSOT's internal Kotlin model directly. Resolving on
 * the Kotlin side and handing over a plain snapshot keeps one resolution path for
 * every AGP line, so an AGP 8 build and an AGP 9 build can never disagree about
 * what the DSL asked for.
 *
 * Not part of the supported API. It exists only so the two source sets can talk.
 */
class Agp8AndroidInputs internal constructor(
    /** Application project paths the app-scoped values apply to. Empty means every application. */
    @JvmField val selectedApplications: List<String>,
    /** Whether the resolved application id should be written. */
    @JvmField val applyApplicationId: Boolean,
    /** Resolved `applicationId`, or null when there is nothing to write. */
    @JvmField val applicationId: String?,
    /** Whether version values should be written. */
    @JvmField val applyVersion: Boolean,
    /** Resolved `versionName`, or null. */
    @JvmField val versionName: String?,
    /** Resolved `versionCode`, or null. */
    @JvmField val versionCode: Int?,
    /** Whether the app name placeholder should be written. */
    @JvmField val applyAppName: Boolean,
    /** Resolved display name, or null. */
    @JvmField val appName: String?,
    /** Whether packaged resources should be narrowed to [localeFilters]. */
    @JvmField val filterResources: Boolean,
    /** Locale filters already converted to Android qualifiers. */
    @JvmField val localeFilters: List<String>,
    /** Whether SDK and NDK levels should be written. */
    @JvmField val applySdkLevels: Boolean,
    /** Resolved `compileSdk`, or null. */
    @JvmField val compileSdk: Int?,
    /** Resolved `ndkVersion`, or null. */
    @JvmField val ndk: String?,
    /** Resolved `minSdk`, or null. */
    @JvmField val minSdk: Int?,
    /** Resolved `targetSdk`, or null. Applications only. */
    @JvmField val targetSdk: Int?,
    /** Validated Java level for `compileOptions`, or null. */
    @JvmField val javaVersion: Int?,
)

/**
 * Resolve the AGP 8 snapshot for [projectPath].
 *
 * Called from inside AGP's `finalizeDsl` callback, never at wiring time, so
 * every provider is read at the same moment the AGP 9 adapter would read it.
 */
internal fun KiteSsotExtension.resolveAgp8Inputs(projectPath: String): Agp8AndroidInputs {
    val selected = effectiveAndroidApps.getOrElse(emptyList())
    val appScoped = selected.isEmpty() || selected.contains(projectPath)
    val filterResources = appScoped && effectiveFilterAndroidResources.get()
    return Agp8AndroidInputs(
        selectedApplications = selected,
        applyApplicationId = appScoped && effectivePropagateBundleId.get() && effectiveAppId.isPresent,
        applicationId = androidApplicationId.orNull,
        applyVersion = appScoped && effectivePropagateVersion.get(),
        versionName = effectiveVersion.orNull,
        versionCode = effectiveAndroidVersionCode.orNull,
        applyAppName = appScoped && effectivePropagateAppName.get() && effectiveAppName.isPresent,
        appName = effectiveAppName.orNull,
        filterResources = filterResources,
        localeFilters = if (filterResources) {
            canonicalLocales.get().map(::bcp47ToAndroidQualifier)
        } else {
            emptyList()
        },
        applySdkLevels = effectiveApplySdkLevels.get(),
        compileSdk = android.compileSdk.orNull,
        ndk = android.ndk.orNull,
        minSdk = android.minSdk.orNull,
        targetSdk = android.targetSdk.orNull,
        javaVersion = effectiveJvmTarget.orNull?.let(::validateJavaVersion),
    )
}
