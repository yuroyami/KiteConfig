package io.github.yuroyami.kiteconfig.core

/** Where core code writes progress lines. Each plugin connects it to its own console. */
@InternalKiteCoreApi
public interface KiteLog {
    public fun lifecycle(message: String)
    public fun info(message: String)
}
