package io.github.yuroyami.kiteconfig.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    // --- T03: build settings are scoped to the application target. A test target
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

        // App target: updated.
        assertTrue(r.text.contains("PRODUCT_NAME = \"Probe\";"), r.text)
        assertTrue(r.text.contains("PRODUCT_BUNDLE_IDENTIFIER = com.probe.app;"), r.text)
        assertTrue(r.text.contains("MARKETING_VERSION = 1.2.3;"), r.text)
        assertTrue(r.text.contains("CURRENT_PROJECT_VERSION = 27;"), r.text)

        // Test + extension targets: byte-for-byte untouched.
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
        assertTrue(r.errors.any { it.contains("first component that is not zero") }, r.errors.toString())
    }

    /** One application target with one build configuration holding [settings]. */
    private fun appProject(settings: String, knownRegions: String = "( Base, en, )") = """
        {
            objects = {
                AA00000000000000000000AA = {
                    isa = PBXNativeTarget;
                    name = App;
                    buildConfigurationList = AA00000000000000000000A1;
                    productType = "com.apple.product-type.application";
                };
                AA00000000000000000000A1 = {
                    isa = XCConfigurationList;
                    buildConfigurations = ( AA00000000000000000000A2, );
                };
                AA00000000000000000000A2 = {
                    isa = XCBuildConfiguration;
                    buildSettings = { $settings };
                };
                EE00000000000000000000EE = {
                    isa = PBXProject;
                    knownRegions = $knownRegions;
                };
            };
            rootObject = EE00000000000000000000EE;
        }
    """.trimIndent()

    @Test
    fun `settings that already hold the configured value are not reported`() {
        val r = rewritePbxproj(multiTarget, "0.0.1", "1", "Probe", "com.demo.app", null)

        assertTrue(r.errors.isEmpty(), r.errors.toString())
        // Only PRODUCT_NAME moves, from $(TARGET_NAME) to Probe. The rest is already right.
        assertEquals(setOf("PRODUCT_NAME"), r.changedSettings)
        assertEquals(emptySet<String>(), r.reformattedSettings)
    }

    @Test
    fun `a difference in quotes only is reformatting`() {
        // Same values, other spelling: the name without quotes, the version with them.
        val original = appProject("MARKETING_VERSION = \"1.4.0\"; PRODUCT_NAME = Jetzy;")

        val r = rewritePbxproj(original, "1.4.0", null, "Jetzy", null, null)

        assertTrue(r.errors.isEmpty(), r.errors.toString())
        assertEquals(emptySet<String>(), r.changedSettings)
        assertEquals(setOf("MARKETING_VERSION", "PRODUCT_NAME"), r.reformattedSettings)
    }

    @Test
    fun `knownRegions in a different order is reformatting`() {
        // Xcode lists en before Base; KiteConfig writes Base first.
        val original = appProject("PRODUCT_NAME = App;", knownRegions = "( en, Base, )")

        val r = rewritePbxproj(original, null, null, null, null, listOf("en"))

        assertTrue(r.errors.isEmpty(), r.errors.toString())
        assertEquals(emptySet<String>(), r.changedSettings)
        assertEquals(setOf("knownRegions"), r.reformattedSettings)
    }

    @Test
    fun `a setting Xcode could read differently still counts as a change`() {
        // \b is a control character, a bare é is invalid, and an escape in the key can spell another key.
        listOf(
            "PRODUCT_NAME = \"Pro\\be\";" to "Probe",
            "PRODUCT_NAME = Café;" to "Café",
            "\"PROD\\UCT_NAME\" = Probe;" to "Probe",
        ).forEach { (setting, name) ->
            val r = rewritePbxproj(appProject(setting), null, null, name, null, null)
            assertEquals(setOf("PRODUCT_NAME"), r.changedSettings, setting)
        }
    }

    @Test
    fun `knownRegions Xcode could read differently still count as a change`() {
        // "e\n" ends in a newline, and Xcode reads 'fr' as fr while this reader keeps the quotes.
        listOf("( \"e\\n\", Base, )", "( 'fr', en, Base, )").forEach { regions ->
            val r = rewritePbxproj(
                appProject("PRODUCT_NAME = App;", knownRegions = regions),
                null, null, null, null, listOf("en"),
            )
            assertEquals(setOf("knownRegions"), r.changedSettings, regions)
        }
    }

    @Test
    fun `a key that changes in one configuration is not also listed as reformatted`() {
        // One configuration only lacks the quotes; the other still holds an old name.
        val aligned = checkNotNull(javaClass.getResourceAsStream("/fixtures/AlignedApp.xcodeproj/project.pbxproj"))
            .bufferedReader().use { it.readText() }
        val lastAssignment = aligned.lastIndexOf("PRODUCT_NAME = Jetzy;")
        val mixed = aligned.replaceRange(lastAssignment, lastAssignment + "PRODUCT_NAME = Jetzy;".length, "PRODUCT_NAME = Old;")

        val r = rewritePbxproj(mixed, "1.4.0", "1001004000", "Jetzy", "com.example.jetzy", null)

        assertTrue(r.errors.isEmpty(), r.errors.toString())
        assertEquals(setOf("PRODUCT_NAME"), r.changedSettings)
        assertEquals(emptySet<String>(), r.reformattedSettings)
    }

    /** Two configurations on one application target, so a per-configuration value has somewhere to differ. */
    private fun twoConfigurationProject(debugSettings: String, releaseSettings: String) = """
        {
            objects = {
                AA00000000000000000000AA = {
                    isa = PBXNativeTarget;
                    name = App;
                    buildConfigurationList = AA00000000000000000000A1;
                    productType = "com.apple.product-type.application";
                };
                AA00000000000000000000A1 = {
                    isa = XCConfigurationList;
                    buildConfigurations = ( AA00000000000000000000A2, AA00000000000000000000A3, );
                };
                AA00000000000000000000A2 = {
                    isa = XCBuildConfiguration;
                    buildSettings = { $debugSettings };
                    name = Debug;
                };
                AA00000000000000000000A3 = {
                    isa = XCBuildConfiguration;
                    buildSettings = { $releaseSettings };
                    name = Release;
                };
                EE00000000000000000000EE = {
                    isa = PBXProject;
                    knownRegions = ( Base, en, );
                };
            };
            rootObject = EE00000000000000000000EE;
        }
    """.trimIndent()

    private val sharedSettings =
        "PRODUCT_BUNDLE_IDENTIFIER = com.old.id; PRODUCT_NAME = Old; ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;"

    @Test
    fun `a named configuration gets its own values and the others keep the target-wide ones`() {
        val original = twoConfigurationProject(sharedSettings, sharedSettings)

        val r = rewritePbxproj(
            original = original,
            marketingVersion = null,
            buildNumber = null,
            appName = "Jetzy",
            bundleId = "com.example.jetzy",
            locales = null,
            appIconName = "AppIcon",
            configurationOverrides = mapOf(
                "Debug" to PbxConfigurationOverride(
                    appName = "Jetzy Dev",
                    bundleId = "com.example.jetzy.debug",
                    appIconName = "AppIcon-Debug",
                ),
            ),
        )

        assertTrue(r.errors.isEmpty(), r.errors.toString())
        val debug = r.text.substringBefore("name = Debug;")
        val release = r.text.substringAfter("name = Debug;").substringBefore("name = Release;")
        assertTrue(debug.contains("PRODUCT_BUNDLE_IDENTIFIER = com.example.jetzy.debug;"), r.text)
        assertTrue(debug.contains("PRODUCT_NAME = \"Jetzy Dev\";"), r.text)
        assertTrue(debug.contains("ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon-Debug;"), r.text)
        assertTrue(release.contains("PRODUCT_BUNDLE_IDENTIFIER = com.example.jetzy;"), r.text)
        assertTrue(release.contains("PRODUCT_NAME = \"Jetzy\";"), r.text)
        assertTrue(release.contains("ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;"), r.text)
        assertEquals(
            setOf("PRODUCT_BUNDLE_IDENTIFIER", "PRODUCT_NAME", "ASSETCATALOG_COMPILER_APPICON_NAME"),
            r.changedSettings,
        )
    }

    @Test
    fun `an override only refines the keys it names`() {
        val original = twoConfigurationProject(sharedSettings, sharedSettings)

        val r = rewritePbxproj(
            original, null, null, "Jetzy", "com.example.jetzy", null,
            appIconName = "AppIcon",
            configurationOverrides = mapOf("Debug" to PbxConfigurationOverride(bundleId = "com.example.jetzy.debug")),
        )

        assertTrue(r.errors.isEmpty(), r.errors.toString())
        val debug = r.text.substringBefore("name = Debug;")
        assertTrue(debug.contains("PRODUCT_BUNDLE_IDENTIFIER = com.example.jetzy.debug;"), r.text)
        assertTrue(debug.contains("PRODUCT_NAME = \"Jetzy\";"), r.text)
        assertTrue(debug.contains("ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;"), r.text)
    }

    @Test
    fun `an override alone rewrites only its own configuration`() {
        val original = twoConfigurationProject(sharedSettings, sharedSettings)

        val r = rewritePbxproj(
            original, null, null, null, null, null,
            configurationOverrides = mapOf("Debug" to PbxConfigurationOverride(bundleId = "com.example.jetzy.debug")),
        )

        assertTrue(r.errors.isEmpty(), r.errors.toString())
        assertTrue(r.text.substringBefore("name = Debug;").contains("PRODUCT_BUNDLE_IDENTIFIER = com.example.jetzy.debug;"), r.text)
        assertTrue(r.text.substringAfter("name = Debug;").contains("PRODUCT_BUNDLE_IDENTIFIER = com.old.id;"), r.text)
    }

    @Test
    fun `an override for a configuration the target does not have fails closed`() {
        val original = twoConfigurationProject(sharedSettings, sharedSettings)

        val r = rewritePbxproj(
            original, null, null, null, "com.example.jetzy", null,
            configurationOverrides = mapOf("Staging" to PbxConfigurationOverride(bundleId = "com.example.jetzy.staging")),
        )

        assertEquals(original, r.text)
        assertTrue(r.errors.any { it.contains("no build configuration named 'Staging'") }, r.errors.toString())
    }

    @Test
    fun `an invalid override value is refused like a target-wide one`() {
        val original = twoConfigurationProject(sharedSettings, sharedSettings)

        val r = rewritePbxproj(
            original, null, null, null, "com.example.jetzy", null,
            configurationOverrides = mapOf("Debug" to PbxConfigurationOverride(bundleId = "bad id")),
        )

        assertEquals(original, r.text)
        assertTrue(r.errors.any { it.contains("bundle identifier") }, r.errors.toString())
    }

    @Test
    fun `applying an override twice changes nothing the second time`() {
        val original = twoConfigurationProject(sharedSettings, sharedSettings)
        val overrides = mapOf("Debug" to PbxConfigurationOverride(bundleId = "com.example.jetzy.debug", appName = "Jetzy Dev"))

        val first = rewritePbxproj(original, null, null, "Jetzy", "com.example.jetzy", null, configurationOverrides = overrides)
        val second = rewritePbxproj(first.text, null, null, "Jetzy", "com.example.jetzy", null, configurationOverrides = overrides)

        assertTrue(second.errors.isEmpty(), second.errors.toString())
        assertEquals(first.text, second.text)
        assertEquals(emptySet<String>(), second.changedSettings)
        assertEquals(emptySet<String>(), second.reformattedSettings)
    }

    @Test
    fun `pbxproj knownRegions receives the mapped apple tags`() {
        val src = """
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
        val r = rewritePbxproj(src, null, null, null, null, listOf("en", "pt-rBR"))
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        assertTrue(r.text.contains("pt-BR"), r.text)
        assertFalse(r.text.contains("pt-rBR"), r.text)
    }
}
