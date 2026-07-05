package io.github.yuroyami.kmpssot

import org.gradle.api.provider.Property

/**
 * Runtime build-info codegen. Nested under `kmpSsot { buildInfo { ... } }`.
 *
 * Closes the SSOT loop to *runtime*: the plugin already computes appName /
 * versionName / versionCode / bundle ids / locales for the build config, so it can
 * also emit them as a Kotlin object the app reads at runtime (About screen, crash
 * tags, analytics) — no `expect/actual BuildConfig` boilerplate per platform.
 *
 * Generated into a plugin-owned `commonMain` source dir on the shared module, wired
 * via the source set (never your hand-authored tree), mirroring `web { generateIoWorker }`.
 */
abstract class KmpSsotBuildInfoExtension {

    /** Generate `KmpSsotBuildInfo` into the shared module's commonMain. Default false. */
    abstract val enabled: Property<Boolean>

    /** Package for the generated object. Default `kmpssot.generated`. */
    abstract val packageName: Property<String>
}
