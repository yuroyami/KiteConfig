package io.github.yuroyami.kiteconfig

import org.gradle.api.provider.Property

/**
 * The JVM topic, inside `kiteConfig { jvm { ... } }`.
 *
 * | member | meaning | default |
 * |---|---|---|
 * | `toolchain` | the JDK that compiles and runs tests | whatever the module already uses |
 * | `target` | the bytecode level, for Java and Kotlin together | whatever the module already uses |
 *
 * ```kotlin
 * kiteConfig {
 *     jvm {
 *         toolchain = 21   // build with JDK 21
 *         target = 17      // produce class files Java 17 can load
 *     }
 * }
 * ```
 *
 * [target] is set on the Java and the Kotlin side at once. Setting only one of
 * them is the mistake this block exists to prevent: a module with both
 * languages then fails with "inconsistent JVM-target compatibility", and the
 * message does not say which half moved.
 *
 * Android and desktop can each override either value. iOS cannot: there is no
 * JVM there, and `ios { jvm { } }` does not compile.
 */
@KiteDsl
public abstract class KiteJvmScope {

    /**
     * The JDK used to compile and to run host tests, as a major version.
     *
     * Unset leaves the module's own toolchain alone. This does not change the
     * JDK Gradle itself runs on: that is chosen before this build script runs.
     */
    public abstract val toolchain: Property<Int>

    /**
     * The bytecode level, applied to Java and Kotlin together.
     *
     * Unset leaves the module's own settings alone. An explicit value is not
     * raised to match a newer [toolchain]; compiling on a new JDK for an older
     * runtime is the point.
     */
    public abstract val target: Property<Int>
}
