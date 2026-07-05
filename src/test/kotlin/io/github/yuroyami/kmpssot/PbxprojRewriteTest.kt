package io.github.yuroyami.kmpssot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PbxprojRewriteTest {

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
        val r = rewritePbxproj(sample, "2.3.4", deriveVersionCode("2.3.4"), "New App", "com.new.id", listOf("en", "fr"))
        assertTrue(r.text.contains("MARKETING_VERSION = 2.3.4;"))
        assertTrue(r.text.contains("CURRENT_PROJECT_VERSION = 1002003004;"))
        assertTrue(r.text.contains("PRODUCT_NAME = \"New App\";"))
        assertTrue(r.text.contains("INFOPLIST_KEY_CFBundleDisplayName = \"New App\";"))
        assertTrue(r.text.contains("PRODUCT_BUNDLE_IDENTIFIER = com.new.id;"))
        assertTrue(r.text.contains("fr"))
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun `dollar in app name does not crash and stays literal`() {
        // The bug: replace(Regex, String) treats $ as a group reference and throws.
        val r = rewritePbxproj(sample, null, null, "Cost\$ Money", null, null)
        assertTrue(r.text.contains("PRODUCT_NAME = \"Cost\$ Money\";"), r.text)
    }

    @Test
    fun `backslash and quote in app name are pbxproj-escaped`() {
        val r = rewritePbxproj(sample, null, null, "A\"B\\C", null, null)
        assertTrue(r.text.contains("PRODUCT_NAME = \"A\\\"B\\\\C\";"), r.text)
    }

    @Test
    fun `missing knownRegions block warns instead of silently dropping`() {
        val r = rewritePbxproj("PRODUCT_NAME = X;", null, null, null, null, listOf("en"))
        assertEquals(1, r.warnings.size)
    }

    @Test
    fun `untouched when nothing requested`() {
        val r = rewritePbxproj(sample, null, null, null, null, null)
        assertEquals(sample, r.text)
        assertTrue(r.warnings.isEmpty())
    }

    // --- F2: keys are anchored on the left, so a sibling key that *ends with*
    // the target name is never corrupted. --------------------------------------
    @Test
    fun `does not corrupt a sibling key that ends with the target name`() {
        val src = "MY_PRODUCT_NAME = KeepMe;\nPRODUCT_NAME = Old;"
        val r = rewritePbxproj(src, null, null, "New", null, null)
        assertTrue(r.text.contains("MY_PRODUCT_NAME = KeepMe;"), r.text)
        assertTrue(r.text.contains("PRODUCT_NAME = \"New\";"), r.text)
    }

    // --- F3: a quoted value containing ';' is not split mid-string. ------------
    @Test
    fun `rewrites a quoted value that contains a semicolon without splitting it`() {
        val src = "PRODUCT_NAME = \"App; Inc\";"
        val r = rewritePbxproj(src, null, null, "New", null, null)
        assertEquals("PRODUCT_NAME = \"New\";", r.text)
    }

    // --- F3: a value missing its terminating ';' can't swallow later lines. ----
    @Test
    fun `a setting missing its semicolon does not eat the following line`() {
        val src = "PRODUCT_NAME = brokenNoSemicolon\nPRODUCT_BUNDLE_IDENTIFIER = com.keep.me;"
        val r = rewritePbxproj(src, null, null, "New", "com.new.id", null)
        // PRODUCT_NAME line has no ';' before the newline → left untouched, not merged.
        assertTrue(r.text.contains("PRODUCT_NAME = brokenNoSemicolon"), r.text)
        assertTrue(r.text.contains("PRODUCT_BUNDLE_IDENTIFIER = com.new.id;"), r.text)
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
        	};
        }
    """.trimIndent()

    @Test
    fun `rewrites only the application target's build configs`() {
        val r = rewritePbxproj(multiTarget, "1.2.3", deriveVersionCode("1.2.3"), "Probe", "com.probe.app", null)

        // App target — updated.
        assertTrue(r.text.contains("PRODUCT_NAME = \"Probe\";"), r.text)
        assertTrue(r.text.contains("PRODUCT_BUNDLE_IDENTIFIER = com.probe.app;"), r.text)
        assertTrue(r.text.contains("MARKETING_VERSION = 1.2.3;"), r.text)
        assertTrue(r.text.contains("CURRENT_PROJECT_VERSION = 1001002003;"), r.text)

        // Test + extension targets — byte-for-byte untouched.
        assertTrue(r.text.contains("PRODUCT_BUNDLE_IDENTIFIER = com.demo.app.tests;"), r.text)
        assertTrue(r.text.contains("PRODUCT_BUNDLE_IDENTIFIER = com.demo.app.widget;"), r.text)
        assertTrue(r.text.contains("PRODUCT_NAME = WidgetExtension;"), r.text)
        // The host app's id must appear exactly once now (only the app config), never
        // leaking into the two child targets.
        assertEquals(1, Regex("PRODUCT_BUNDLE_IDENTIFIER = com\\.probe\\.app;").findAll(r.text).count(), r.text)
        assertTrue(r.warnings.isEmpty(), r.warnings.toString())
    }

    // --- T04: a lone versionCode (from versionCodeOverride, no versionName) writes
    // CURRENT_PROJECT_VERSION without touching MARKETING_VERSION. -----------------
    @Test
    fun `versionCode alone updates CURRENT_PROJECT_VERSION only`() {
        val r = rewritePbxproj(sample, null, 42, null, null, null)
        assertTrue(r.text.contains("CURRENT_PROJECT_VERSION = 42;"), r.text)
        assertTrue(r.text.contains("MARKETING_VERSION = 0.0.1;"), r.text) // untouched
    }

    @Test
    fun `global fallback warns only when the input looks like a real pbxproj`() {
        // Objects present but no application target → fallback + warning.
        val libOnly = multiTarget.replace("com.apple.product-type.application", "com.apple.product-type.framework")
        val r = rewritePbxproj(libOnly, null, null, "Probe", null, null)
        assertTrue(r.warnings.any { it.contains("no application target") }, r.warnings.toString())
        // Bare fragment (no object graph) → silent global rewrite.
        val bare = rewritePbxproj("PRODUCT_NAME = Old;", null, null, "Probe", null, null)
        assertTrue(bare.warnings.isEmpty(), bare.warnings.toString())
        assertTrue(bare.text.contains("PRODUCT_NAME = \"Probe\";"))
    }
}
