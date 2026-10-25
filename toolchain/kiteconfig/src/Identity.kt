package io.github.yuroyami.kiteconfig.toolchain

/** The Android application id, or a failure that names the setting to add. */
internal fun androidIdOf(appId: String?, androidApplicationId: String?): String =
    requireNotNull(androidApplicationId ?: appId) {
        "Set plugins: kiteconfig: appId (or androidApplicationId) in module.yaml."
    }

/** The iOS bundle id, or a failure that names the setting to add. */
internal fun iosIdOf(appId: String?, iosBundleId: String?): String =
    requireNotNull(iosBundleId ?: appId) {
        "Set plugins: kiteconfig: appId (or iosBundleId) in module.yaml."
    }
