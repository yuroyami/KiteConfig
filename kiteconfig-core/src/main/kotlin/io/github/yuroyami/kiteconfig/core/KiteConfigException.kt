package io.github.yuroyami.kiteconfig.core

/** A KiteConfig rule failed. A plugin shows [message] to the user unchanged. */
@InternalKiteCoreApi
public class KiteConfigException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
