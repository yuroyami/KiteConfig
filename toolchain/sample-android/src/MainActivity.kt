package io.github.yuroyami.kiteconfig.sampleandroid

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import kiteconfig.generated.KiteBuildConfig

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "${KiteBuildConfig.appName} ${KiteBuildConfig.versionName}" })
    }
}
