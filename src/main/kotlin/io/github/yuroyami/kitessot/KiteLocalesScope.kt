package io.github.yuroyami.kitessot

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** Everything about locales: the pinned list, the Android res filter, flow. */
abstract class KiteLocalesScope : KiteFlowScope() {

    internal abstract val pinned: ListProperty<String>

    /** Hand list of BCP 47 tags. Detection from Compose resources is skipped. */
    fun pin(vararg tags: String) = pinned.addAll(*tags)

    /** Drop Android res folders whose locale is not in the list. Default: false. */
    abstract val filterAndroidRes: Property<Boolean>
}
