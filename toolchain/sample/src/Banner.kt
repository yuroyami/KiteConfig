package io.github.yuroyami.kiteconfig.sample

import kiteconfig.generated.KiteBuildConfig

/** A line that shows the generated values reach common code. */
fun banner(): String = "${KiteBuildConfig.appName} ${KiteBuildConfig.versionName} (${KiteBuildConfig.versionCode})"
