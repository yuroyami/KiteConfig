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
 * Resolve the AGP 8 snapshot for [project].
 *
 * Called from inside AGP's `finalizeDsl` callback, never at wiring time, so
 * every provider is read at the same moment the AGP 9 adapter would read it.
 *
 * With [resilient] set (the diagnostic invocations), each value group resolves
 * best-effort: a throwing provider becomes a null field, logged at info, and
 * the diagnostic task reports the underlying problem as a finding. This is the
 * AGP 8 twin of [wireValueGroup] in the AGP 9 adapter. The selection fails
 * closed: when it cannot resolve, no app-scoped value is written anywhere.
 */
internal fun KiteSsotExtension.resolveAgp8Inputs(project: org.gradle.api.Project, resilient: Boolean): Agp8AndroidInputs {
    fun <T> resolve(group: String, read: () -> T): T? = if (!resilient) {
        read()
    } else {
        try {
            read()
        } catch (failure: Exception) {
            project.logger.info(
                "[kiteSsot] ${project.path}: $group not applied on this diagnostic invocation: ${failure.message}"
            )
            null
        }
    }

    val selected = resolve("the application selection") { effectiveAndroidApps.get() }
    val appScoped = selected != null && (selected.isEmpty() || selected.contains(project.path))
    val filterResources = appScoped &&
        (resolve("the locale filters") { effectiveFilterAndroidResources.get() } ?: false)
    // Only touch the version providers when they are actually going to be
    // written. effectiveAndroidVersionCode can throw for a version its scheme
    // cannot encode; the AGP 9 adapter (ClassicAndroidWiring) never evaluates
    // it outside this same condition, so an AGP 8 build must not either, or a
    // build with propagate { version = false }, or a library module, would
    // fail on AGP 8 while the identical AGP 9 build succeeds.
    val applyVersion = appScoped &&
        (resolve("the version values") { versionFlowsTo(KitePlatform.ANDROID).get() } ?: false)
    val applyApplicationId = appScoped &&
        (resolve("applicationId") { idFlowsTo(KitePlatform.ANDROID).get() && id.isPresent } ?: false)
    val applicationId = if (applyApplicationId) {
        resolve("applicationId") { androidApplicationId.orNull }
    } else {
        null
    }
    val localeFilters = if (filterResources) {
        resolve("the locale filters") { canonicalLocales.get().map(::bcp47ToAndroidQualifier) }
    } else {
        null
    }
    val applySdkLevels = resolve("the SDK levels") { effectiveApplySdkLevels.get() } ?: false
    return Agp8AndroidInputs(
        selectedApplications = selected.orEmpty(),
        applyApplicationId = applyApplicationId,
        applicationId = applicationId,
        applyVersion = applyVersion,
        versionName = if (applyVersion) resolve("the version values") { effectiveVersion.orNull } else null,
        versionCode = if (applyVersion) resolve("the version values") { effectiveAndroidVersionCode.orNull } else null,
        applyAppName = appScoped &&
            (resolve("the appName placeholder") { appNameFlowsTo(KitePlatform.ANDROID).get() && effectiveAppNameFor(KitePlatform.ANDROID).isPresent } ?: false),
        appName = resolve("the appName placeholder") { effectiveAppNameFor(KitePlatform.ANDROID).orNull },
        filterResources = filterResources && localeFilters != null,
        localeFilters = localeFilters.orEmpty(),
        applySdkLevels = applySdkLevels,
        compileSdk = if (applySdkLevels) resolve("the SDK levels") { android.compileSdk.orNull } else null,
        ndk = if (applySdkLevels) resolve("the SDK levels") { android.ndk.orNull } else null,
        minSdk = if (applySdkLevels) resolve("the SDK levels") { android.minSdk.orNull } else null,
        targetSdk = if (applySdkLevels) resolve("the SDK levels") { android.targetSdk.orNull } else null,
        javaVersion = resolve("the Java compatibility level") { effectiveJvmTarget.orNull?.let(::validateJavaVersion) },
    )
}
