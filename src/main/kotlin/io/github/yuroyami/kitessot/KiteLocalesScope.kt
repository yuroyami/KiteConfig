package io.github.yuroyami.kitessot

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Everything about locales: the pinned list, the Android res filter, flow.
 *
 * | member | meaning | default | flow class |
 * |---|---|---|---|
 * | `pin(tags)` | hand locale list, detection skipped | auto-detect | memory |
 * | `filterAndroidRes` | drop Android res outside the list | false | memory |
 * | `skip(p)` / `only(p)` | flow control | flow everywhere | n/a |
 */
abstract class KiteLocalesScope : KiteFlowScope() {

    internal abstract val pinned: ListProperty<String>

    /** Hand list of BCP 47 tags. Detection from Compose resources is skipped. */
    fun pin(vararg tags: String) = pinned.addAll(*tags)

    /** Drop Android res folders whose locale is not in the list. Default: false. */
    abstract val filterAndroidRes: Property<Boolean>
}
