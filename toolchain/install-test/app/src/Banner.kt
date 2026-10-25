package io.github.yuroyami.kiteconfig.installtest

import kiteconfig.generated.KiteBuildConfig

/** A line that shows the generated values reach the module. */
fun banner(): String = "${KiteBuildConfig.appName} ${KiteBuildConfig.versionName} (${KiteBuildConfig.versionCode})"
