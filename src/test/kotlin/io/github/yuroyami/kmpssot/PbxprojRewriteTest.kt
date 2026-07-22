package io.github.yuroyami.kmpssot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PbxprojRewriteTest {

    private fun rewriteFragment(
        original: String,
        marketingVersion: String?,
        buildNumber: String?,
        appName: String?,
        bundleId: String?,
        locales: List<String>?,
    ) = rewritePbxproj(
        original, marketingVersion, buildNumber, appName, bundleId, locales,
        allowUnscopedFragment = true,
    )

    private val sample = """
        MARKETING_VERSION = 0.0.1;
        CURRENT_PROJECT_VERSION = 1;
        PRODUCT_NAME = Old;
        INFOPLIST_KEY_CFBundleDisplayName = Old;
        INFOPLIST_KEY_CFBundleName = Old;
        PRODUCT_BUNDLE_IDENTIFIER = com.old.id;
        knownRegions = (
                        Base,
                        en,
                    );
    """.trimIndent()

    @Test
    fun `rewrites version, name, bundle id, locales`() {
        val r = rewriteFragment(sample, "2.3.4", "42", "New App", "com.new.id", null)
        assertTrue(r.text.contains("MARKETING_VERSION = 2.3.4;"))
        assertTrue(r.text.contains("CURRENT_PROJECT_VERSION = 42;"))
        assertTrue(r.text.contains("PRODUCT_NAME = \"New App\";"))
        assertTrue(r.text.contains("INFOPLIST_KEY_CFBundleDisplayName = \"New App\";"))
        assertTrue(r.text.contains("PRODUCT_BUNDLE_IDENTIFIER = com.new.id;"))
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun `dollar in app name does not crash and stays literal`() {
        // The bug: replace(Regex, String) treats $ as a group reference and throws.
        val r = rewriteFragment(sample, null, null, "Cost\$ Money", null, null)
        assertTrue(r.text.contains("PRODUCT_NAME = \"Cost\$ Money\";"), r.text)
    }

    @Test
    fun `backslash and quote in app name are pbxproj-escaped`() {
        val r = rewriteFragment(sample, null, null, "A\"B\\C", null, null)
        assertTrue(r.text.contains("PRODUCT_NAME = \"A\\\"B\\\\C\";"), r.text)
    }

    @Test
    fun `missing knownRegions block warns instead of silently dropping`() {
        val r = rewriteFragment("PRODUCT_NAME = X;", null, null, null, null, listOf("en"))
        assertTrue(r.errors.any { it.contains("knownRegions") }, r.errors.toString())
        assertEquals("PRODUCT_NAME = X;", r.text)
    }

    @Test
    fun `untouched when nothing requested`() {
        val r = rewriteFragment(sample, null, null, null, null, null)
        assertEquals(sample, r.text)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun `invalid migration values are bounded and leave the project untouched`() {
        val hostile = "line\n" + "9".repeat(10_000)

        val r = rewriteFragment(sample, hostile, hostile, hostile, hostile, null)

        assertEquals(sample, r.text)
        assertTrue(r.errors.size == 4, r.errors.toString())
        assertTrue(r.errors.joinToString().length < 2_000, r.errors.joinToString().length.toString())
        assertTrue(r.errors.none { it.contains('\n') }, r.errors.toString())
    }

    // --- F2: keys are anchored on the left, so a sibling key that *ends with*
    // the target name is never corrupted. --------------------------------------
    @Test
    fun `does not corrupt a sibling key that ends with the target name`() {
        val src = "MY_PRODUCT_NAME = KeepMe;\nPRODUCT_NAME = Old;"
        val r = rewriteFragment(src, null, null, "New", null, null)
        assertTrue(r.text.contains("MY_PRODUCT_NAME = KeepMe;"), r.text)
        assertTrue(r.text.contains("PRODUCT_NAME = \"New\";"), r.text)
    }

    // --- F3: a quoted value containing ';' is not split mid-string. ------------
    @Test
    fun `rewrites a quoted value that contains a semicolon without splitting it`() {
        val src = "PRODUCT_NAME = \"App; Inc\";"
        val r = rewriteFragment(src, null, null, "New", null, null)
        assertEquals("PRODUCT_NAME = \"New\";", r.text)
    }

    // --- F3: a value missing its terminating ';' can't swallow later lines. ----
    @Test
    fun `a setting missing its semicolon does not eat the following line`() {
        val src = "PRODUCT_NAME = brokenNoSemicolon\nPRODUCT_BUNDLE_IDENTIFIER = com.keep.me;"
        val r = rewriteFragment(src, null, null, "New", "com.new.id", null)
        // A malformed requested assignment aborts the whole fragment plan; the
        // valid sibling is not partially committed.
        assertTrue(r.text.contains("PRODUCT_NAME = brokenNoSemicolon"), r.text)
        assertTrue(r.text.contains("PRODUCT_BUNDLE_IDENTIFIER = com.keep.me;"), r.text)
        assertTrue(r.errors.any { it.contains("PRODUCT_NAME") }, r.errors.toString())
    }

    // --- T03: build settings are scoped to the application target — a test target
    // and an app-extension keep their own PRODUCT_NAME and distinct bundle ids. ---
    private val multiTarget = """
        // !${'$'}*UTF8*${'$'}!
        {
            objects = {
        /* Begin PBXNativeTarget section */
                AA00000000000000000000AA /* App */ = {
                    isa = PBXNativeTarget;
                    buildConfigurationList = AA00000000000000000000A1 /* Build configuration list */;
                    productType = "com.apple.product-type.application";
                };
                BB00000000000000000000BB /* AppTests */ = {
                    isa = PBXNativeTarget;
                    buildConfigurationList = BB00000000000000000000B1 /* Build configuration list */;
                    productType = "com.apple.product-type.bundle.unit-test";
                };
                CC00000000000000000000CC /* Widget */ = {
                    isa = PBXNativeTarget;
                    buildConfigurationList = CC00000000000000000000C1 /* Build configuration list */;
                    productType = "com.apple.product-type.app-extension";
                };
        /* End PBXNativeTarget section */
        /* Begin XCBuildConfiguration section */
                AA00000000000000000000A2 /* Debug */ = {
                    isa = XCBuildConfiguration;
                    buildSettings = {
                        CURRENT_PROJECT_VERSION = 1;
                        MARKETING_VERSION = 0.0.1;
                        PRODUCT_BUNDLE_IDENTIFIER = com.demo.app;
                        PRODUCT_NAME = "${'$'}(TARGET_NAME)";
                    };
                    name = Debug;
                };
                BB00000000000000000000B2 /* Debug */ = {
                    isa = XCBuildConfiguration;
                    buildSettings = {
                        PRODUCT_BUNDLE_IDENTIFIER = com.demo.app.tests;
                        PRODUCT_NAME = "${'$'}(TARGET_NAME)";
                    };
                    name = Debug;
                };
                CC00000000000000000000C2 /* Debug */ = {
                    isa = XCBuildConfiguration;
                    buildSettings = {
                        PRODUCT_BUNDLE_IDENTIFIER = com.demo.app.widget;
                        PRODUCT_NAME = WidgetExtension;
                    };
                    name = Debug;
                };
        /* End XCBuildConfiguration section */
        /* Begin XCConfigurationList section */
                AA00000000000000000000A1 /* Build configuration list */ = {
                    isa = XCConfigurationList;
                    buildConfigurations = (
                        AA00000000000000000000A2 /* Debug */,
                    );
                };
                BB00000000000000000000B1 /* Build configuration list */ = {
                    isa = XCConfigurationList;
                    buildConfigurations = (
                        BB00000000000000000000B2 /* Debug */,
                    );
                };
                CC00000000000000000000C1 /* Build configuration list */ = {
                    isa = XCConfigurationList;
                    buildConfigurations = (
                        CC00000000000000000000C2 /* Debug */,
                    );
                };
        /* End XCConfigurationList section */
            EE00000000000000000000EE = {
                isa = PBXProject;
                knownRegions = ( Base, en, );
            };
             };
            rootObject = EE00000000000000000000EE;
        }
    """.trimIndent()

    @Test
    fun `rewrites only the application target's build configs`() {
        val r = rewritePbxproj(multiTarget, "1.2.3", "27", "Probe", "com.probe.app", null)

        // App target — updated.
        assertTrue(r.text.contains("PRODUCT_NAME = \"Probe\";"), r.text)
        assertTrue(r.text.contains("PRODUCT_BUNDLE_IDENTIFIER = com.probe.app;"), r.text)
        assertTrue(r.text.contains("MARKETING_VERSION = 1.2.3;"), r.text)
        assertTrue(r.text.contains("CURRENT_PROJECT_VERSION = 27;"), r.text)

        // Test + extension targets — byte-for-byte untouched.
        assertTrue(r.text.contains("PRODUCT_BUNDLE_IDENTIFIER = com.demo.app.tests;"), r.text)
        assertTrue(r.text.contains("PRODUCT_BUNDLE_IDENTIFIER = com.demo.app.widget;"), r.text)
        assertTrue(r.text.contains("PRODUCT_NAME = WidgetExtension;"), r.text)
        // The host app's id must appear exactly once now (only the app config), never
        // leaking into the two child targets.
        assertEquals(1, Regex("PRODUCT_BUNDLE_IDENTIFIER = com\\.probe\\.app;").findAll(r.text).count(), r.text)
        assertTrue(r.warnings.all { it.contains("INFOPLIST_KEY") }, r.warnings.toString())
    }

    @Test
    fun `aligns the selected application target with the configured AppIcon catalog`() {
        val configured = multiTarget.replace(
            "CURRENT_PROJECT_VERSION = 1;",
            "ASSETCATALOG_COMPILER_APPICON_NAME = LegacyIcon;\n                        CURRENT_PROJECT_VERSION = 1;",
        )

        val result = rewritePbxproj(
            original = configured,
            marketingVersion = null,
            buildNumber = null,
            appName = null,
            bundleId = null,
            locales = null,
            appIconName = "AppIcon",
        )

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(result.text.contains("ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;"), result.text)
        assertEquals(setOf("ASSETCATALOG_COMPILER_APPICON_NAME"), result.changedSettings)
    }

    @Test
    fun `missing selected AppIcon build setting aborts without changing the project`() {
        val result = rewritePbxproj(
            original = multiTarget,
            marketingVersion = null,
            buildNumber = null,
            appName = null,
            bundleId = null,
            locales = null,
            appIconName = "AppIcon",
        )

        assertEquals(multiTarget, result.text)
        assertTrue(
            result.errors.any { it.contains("missing required ASSETCATALOG_COMPILER_APPICON_NAME") },
            result.errors.toString(),
        )
    }

    @Test
    fun `settings and project metadata share one parsed object graph`() {
        var parseCount = 0

        val result = rewritePbxproj(
            original = multiTarget,
            marketingVersion = "1.2.3",
            buildNumber = null,
            appName = null,
            bundleId = null,
            locales = listOf("fr"),
            analysisFactory = { text ->
                parseCount++
                analyzePbxproj(text)
            },
        )

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1, parseCount)
        assertEquals(setOf("MARKETING_VERSION", "knownRegions"), result.changedSettings)
    }

    // A lone Apple build number writes CURRENT_PROJECT_VERSION without marketing version.
    // CURRENT_PROJECT_VERSION without touching MARKETING_VERSION. -----------------
    @Test
    fun `build number alone updates CURRENT_PROJECT_VERSION only`() {
        val r = rewriteFragment(sample, null, "42", null, null, null)
        assertTrue(r.text.contains("CURRENT_PROJECT_VERSION = 42;"), r.text)
        assertTrue(r.text.contains("MARKETING_VERSION = 0.0.1;"), r.text) // untouched
    }

    @Test
    fun `real pbxproj without an application target fails closed`() {
        val libOnly = multiTarget.replace("com.apple.product-type.application", "com.apple.product-type.framework")
        val r = rewritePbxproj(libOnly, null, null, "Probe", null, null)
        assertTrue(r.errors.any { it.contains("no application target") }, r.errors.toString())
        assertEquals(libOnly, r.text)
    }

    @Test
    fun `bare fragment requires an explicit test-only opt in`() {
        val bare = rewritePbxproj("PRODUCT_NAME = Old;", null, null, "Probe", null, null)
        assertTrue(bare.errors.isNotEmpty())
        assertEquals("PRODUCT_NAME = Old;", bare.text)
    }

    @Test
    fun `knownRegions is merged instead of deleting existing project regions`() {
        val project = """
            {
                objects = {
                    AA00000000000000000000AA = {
                        isa = PBXProject;
                        knownRegions = (
                            Base,
                            en,
                        );
                    };
                };
                rootObject = AA00000000000000000000AA;
            }
        """.trimIndent()
        val r = rewritePbxproj(project, null, null, null, null, listOf("pt-rBR", "fr"))
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        assertTrue(r.text.contains("en,"), r.text)
        assertTrue(r.text.contains("pt-BR,"), r.text)
        assertTrue(r.text.contains("fr,"), r.text)
    }

    @Test
    fun `invalid serialized identity returns original unchanged`() {
        val r = rewritePbxproj(multiTarget, "1.2-beta", "0", "Probe", "bad id", null)
        assertEquals(multiTarget, r.text)
        assertTrue(r.errors.size >= 3, r.errors.toString())
        assertTrue(r.errors.any { it.contains("positive first component") }, r.errors.toString())
    }
}
