package io.github.yuroyami.kmpssot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PbxprojTargetScopeTest {

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
            	};
            }
        """.trimIndent()
        val spans = findObjectSpans(text)
        // Exactly the two real objects — the nested buildSettings block is not one.
        assertEquals(setOf("AA00000000000000000000AA", "BB00000000000000000000BB"), spans.keys)
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
            	};
            }
        """.trimIndent()
        val spans = findObjectSpans(text)
        assertEquals(setOf("AA00000000000000000000AA", "BB00000000000000000000BB"), spans.keys)
    }

    @Test
    fun `resolves the application target's build config spans`() {
        val text = """
            {
            	objects = {
            		AA00000000000000000000AA = {
            			isa = PBXNativeTarget;
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
            	};
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
            			buildConfigurationList = AA00000000000000000000A1;
            			productType = "com.apple.product-type.application";
            		};
            		DD00000000000000000000DD = {
            			isa = PBXNativeTarget;
            			buildConfigurationList = DD00000000000000000000D1;
            			productType = "com.apple.product-type.application";
            		};
            		AA00000000000000000000A2 = { isa = XCBuildConfiguration; buildSettings = { PRODUCT_NAME = Phone; }; };
            		DD00000000000000000000D2 = { isa = XCBuildConfiguration; buildSettings = { PRODUCT_NAME = Tv; }; };
            		AA00000000000000000000A1 = { isa = XCConfigurationList; buildConfigurations = ( AA00000000000000000000A2, ); };
            		DD00000000000000000000D1 = { isa = XCConfigurationList; buildConfigurations = ( DD00000000000000000000D2, ); };
            	};
            }
        """.trimIndent()
        assertEquals(2, applicationBuildConfigSpans(text).size)
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
            	};
            }
        """.trimIndent()
        assertTrue(applicationBuildConfigSpans(text).isEmpty())
    }
}
