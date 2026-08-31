package io.github.yuroyami.kiteconfig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PbxprojTargetScopeTest {

    private fun strictProject(projectSettings: String = "knownRegions = ( Base, en, );"): String = """
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
                    buildSettings = { PRODUCT_NAME = App; };
                };
                EE00000000000000000000EE = {
                    isa = PBXProject;
                    $projectSettings
                };
            };
            rootObject = EE00000000000000000000EE;
        }
    """.trimIndent()

    @Test
    fun `finds top-level object spans and skips nested braces`() {
        val text = """
            {
                objects = {
                    AA00000000000000000000AA = {
                        isa = XCBuildConfiguration;
                        buildSettings = {
                            OTHER_LDFLAGS = "-Wl,{keep balanced}";
                        };
                    };
                        BB00000000000000000000BB = {
                            isa = PBXNativeTarget;
                        };
                        EE00000000000000000000EE = { isa = PBXProject; };
                    };
                    rootObject = EE00000000000000000000EE;
                }
        """.trimIndent()
        val spans = findObjectSpans(text)
        // Exactly the three real objects. The nested buildSettings block is not one.
        assertEquals(
            setOf("AA00000000000000000000AA", "BB00000000000000000000BB", "EE00000000000000000000EE"),
            spans.keys,
        )
        // The first object's span must contain its whole body incl. the quoted brace.
        assertTrue(text.substring(spans.getValue("AA00000000000000000000AA")).contains("keep balanced"))
    }

    @Test
    fun `braces inside comments do not unbalance the walk`() {
        val text = """
            {
                objects = {
                    AA00000000000000000000AA /* a { dangling brace in a comment */ = {
                        isa = PBXNativeTarget;
                    };
                        BB00000000000000000000BB = {
                            isa = XCConfigurationList;
                        };
                        EE00000000000000000000EE = { isa = PBXProject; };
                    };
                    rootObject = EE00000000000000000000EE;
                }
        """.trimIndent()
        val spans = findObjectSpans(text)
        assertEquals(
            setOf("AA00000000000000000000AA", "BB00000000000000000000BB", "EE00000000000000000000EE"),
            spans.keys,
        )
    }

    @Test
    fun `resolves the application target's build config spans`() {
        val text = """
            {
                objects = {
                    AA00000000000000000000AA = {
                        isa = PBXNativeTarget;
                        name = Phone;
                        buildConfigurationList = AA00000000000000000000A1;
                        productType = "com.apple.product-type.application";
                    };
                    BB00000000000000000000BB = {
                        isa = PBXNativeTarget;
                        buildConfigurationList = BB00000000000000000000B1;
                        productType = "com.apple.product-type.bundle.unit-test";
                    };
                    AA00000000000000000000A2 = {
                        isa = XCBuildConfiguration;
                        buildSettings = { PRODUCT_NAME = App; };
                    };
                    BB00000000000000000000B2 = {
                        isa = XCBuildConfiguration;
                        buildSettings = { PRODUCT_NAME = AppTests; };
                    };
                    AA00000000000000000000A1 = {
                        isa = XCConfigurationList;
                        buildConfigurations = ( AA00000000000000000000A2, );
                    };
                        BB00000000000000000000B1 = {
                            isa = XCConfigurationList;
                            buildConfigurations = ( BB00000000000000000000B2, );
                        };
                        EE00000000000000000000EE = { isa = PBXProject; };
                    };
                    rootObject = EE00000000000000000000EE;
                }
        """.trimIndent()
        val spans = applicationBuildConfigSpans(text)
        assertEquals(1, spans.size)
        val body = text.substring(spans.single())
        assertTrue(body.contains("PRODUCT_NAME = App;"), body)
        assertTrue(!body.contains("AppTests"), body)
    }

    @Test
    fun `handles two application targets (phone + tv)`() {
        val text = """
            {
                objects = {
                    AA00000000000000000000AA = {
                        isa = PBXNativeTarget;
                        name = Phone;
                        buildConfigurationList = AA00000000000000000000A1;
                        productType = "com.apple.product-type.application";
                    };
                    DD00000000000000000000DD = {
                        isa = PBXNativeTarget;
                        name = TV;
                        buildConfigurationList = DD00000000000000000000D1;
                        productType = "com.apple.product-type.application";
                    };
                    AA00000000000000000000A2 = { isa = XCBuildConfiguration; buildSettings = { PRODUCT_NAME = Phone; }; };
                    DD00000000000000000000D2 = { isa = XCBuildConfiguration; buildSettings = { PRODUCT_NAME = Tv; }; };
                        AA00000000000000000000A1 = { isa = XCConfigurationList; buildConfigurations = ( AA00000000000000000000A2, ); };
                        DD00000000000000000000D1 = { isa = XCConfigurationList; buildConfigurations = ( DD00000000000000000000D2, ); };
                        EE00000000000000000000EE = { isa = PBXProject; };
                    };
                    rootObject = EE00000000000000000000EE;
                }
        """.trimIndent()
        val ambiguous = resolveApplicationBuildConfigSpans(text)
        assertTrue(ambiguous.spans.isEmpty())
        assertTrue(ambiguous.errors.any { it.contains("multiple application targets") })

        val selected = resolveApplicationBuildConfigSpans(text, setOf("Phone", "TV"))
        assertEquals(2, selected.spans.size)
        assertTrue(selected.errors.isEmpty(), selected.errors.toString())
    }

    @Test
    fun `no application target yields no spans`() {
        val text = """
            {
                objects = {
                        AA00000000000000000000AA = {
                            isa = PBXNativeTarget;
                            buildConfigurationList = AA00000000000000000000A1;
                            productType = "com.apple.product-type.framework";
                        };
                        EE00000000000000000000EE = { isa = PBXProject; };
                    };
                    rootObject = EE00000000000000000000EE;
                }
        """.trimIndent()
        assertTrue(applicationBuildConfigSpans(text).isEmpty())
    }

    @Test
    fun `knownRegions on a non-project object is never treated as project metadata`() {
        val text = strictProject(projectSettings = "").replace(
            "buildSettings = { PRODUCT_NAME = App; };",
            "buildSettings = { PRODUCT_NAME = App; }; knownRegions = ( Base, en, );",
        )

        val result = rewritePbxproj(text, null, null, null, null, listOf("fr"))

        assertEquals(text, result.text)
        assertTrue(result.errors.any { it.contains("knownRegions") }, result.errors.toString())
    }

    @Test
    fun `duplicate knownRegions fails even when only one list is well formed`() {
        val text = strictProject(
            projectSettings = "knownRegions = ( Base, en, ); knownRegions = ( Base garbage );",
        )

        val result = rewritePbxproj(text, null, null, null, null, listOf("fr"))

        assertEquals(text, result.text)
        assertTrue(result.errors.any { it.contains("2 direct knownRegions") }, result.errors.toString())
    }

    @Test
    fun `garbage in buildConfigurations list fails closed`() {
        val text = strictProject().replace(
            "buildConfigurations = ( AA00000000000000000000A2, );",
            "buildConfigurations = ( AA00000000000000000000A2, garbage, );",
        )

        val result = resolveApplicationBuildConfigSpans(text)

        assertTrue(result.spans.isEmpty())
        assertTrue(result.errors.any { it.contains("garbage") }, result.errors.toString())
    }

    @Test
    fun `non-hex 24-character object ids parse and resolve`() {
        // Xcode only requires object ids to be unique 24-character tokens; external
        // generators and hand edits produce non-hex ids (real case: Peerora's JZWA…).
        val text = """
            {
                objects = {
                    JZWA01000001000000000002 = {
                        isa = PBXNativeTarget;
                        name = App;
                        buildConfigurationList = JZWA01000001000000000003;
                        productType = "com.apple.product-type.application";
                    };
                    JZWA01000001000000000003 = {
                        isa = XCConfigurationList;
                        buildConfigurations = ( JZWA01000001000000000004, );
                    };
                    JZWA01000001000000000004 = {
                        isa = XCBuildConfiguration;
                        buildSettings = { PRODUCT_NAME = App; };
                    };
                    JZRT01000001000000000001 = { isa = PBXProject; };
                };
                rootObject = JZRT01000001000000000001;
            }
        """.trimIndent()

        val result = resolveApplicationBuildConfigSpans(text)

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1, result.spans.size)
    }

    @Test
    fun `malformed direct object entry invalidates the graph`() {
        val text = strictProject().replace(
            "EE00000000000000000000EE = {",
            "NOT_AN_OBJECT = garbage; EE00000000000000000000EE = {",
        )

        val result = resolveApplicationBuildConfigSpans(text)

        assertTrue(result.spans.isEmpty())
        assertTrue(result.errors.any { it.contains("malformed entry") }, result.errors.toString())
    }
}
