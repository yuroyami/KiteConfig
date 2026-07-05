package io.github.yuroyami.kmpssot

/**
 * True for the Kotlin/Native tasks that produce the iOS framework the app links
 * against — the points where the iOS SSOT sync must have already run.
 *
 * Covers all three shapes:
 *  - **CocoaPods**: `linkPodReleaseFrameworkIosArm64`, `linkPodDebugFrameworkIos…`
 *  - **plain `binaries.framework()`** (no CocoaPods — the direction KMP is moving):
 *    `linkReleaseFrameworkIosArm64`, `linkDebugFrameworkIosSimulatorArm64`, …
 *  - **`embedAndSignAppleFrameworkForXcode`** — the Xcode "Run Script" entry point.
 *  - **XCFramework assembly** — `assemble<Name><Type>XCFramework` (SPM / binary dist).
 *
 * `link…FrameworkIos…` (not just `linkPod…`) is the key widening: previously a
 * non-CocoaPods project never had its iOS files synced by `./gradlew build`.
 * Name-based on purpose — a type-based match would pull KGP task types into a
 * method descriptor and break plugin decoration when KGP is absent.
 */
internal fun isIosFrameworkLinkTaskName(name: String): Boolean =
    (name.startsWith("link") && name.contains("FrameworkIos")) ||
        name == "embedAndSignAppleFrameworkForXcode" ||
        (name.startsWith("assemble") && name.endsWith("XCFramework"))
