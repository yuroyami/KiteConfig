package io.github.yuroyami.kiteconfig.core

/** Marks code that the KiteConfig plugins share. Build scripts must not use it. */
@RequiresOptIn(
    message = "Shared by the KiteConfig plugins. It has no compatibility promise and can change in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
public annotation class InternalKiteCoreApi
