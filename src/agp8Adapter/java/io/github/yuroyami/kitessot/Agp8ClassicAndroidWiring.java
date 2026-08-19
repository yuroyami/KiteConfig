package io.github.yuroyami.kitessot;

import com.android.build.api.dsl.ApplicationDefaultConfig;
import com.android.build.api.dsl.LibraryDefaultConfig;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.LibraryAndroidComponentsExtension;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;

import java.util.function.Supplier;

/**
 * Classic Android adapter compiled against the supported AGP 8.5.2 floor.
 *
 * <p>AGP 9 narrowed generic DSL return descriptors. Keeping this adapter in a
 * separately compiled source set preserves binary calls that AGP 8 implements,
 * without weakening the typed AGP 9 adapter or reflecting over mutable DSL
 * state.</p>
 *
 * <p>Values arrive as an already-resolved {@link Agp8AndroidInputs} snapshot so
 * that both AGP lines share one resolution path. The supplier is invoked inside
 * {@code finalizeDsl}, which keeps reads as lazy as they were before.</p>
 */
final class Agp8ClassicAndroidWiring {
    private Agp8ClassicAndroidWiring() { }

    static void wireApplication(Project project, Supplier<Agp8AndroidInputs> inputs) {
        ApplicationAndroidComponentsExtension components = project.getExtensions()
            .findByType(ApplicationAndroidComponentsExtension.class);
        if (components == null) return;

        components.finalizeDsl(android -> {
            Agp8AndroidInputs in = inputs.get();
            ApplicationDefaultConfig dc = android.getDefaultConfig();
            if (in.applyApplicationId && in.applicationId != null) {
                dc.setApplicationId(in.applicationId);
            }
            if (in.applyVersion) {
                if (in.versionName != null) dc.setVersionName(in.versionName);
                if (in.versionCode != null) dc.setVersionCode(in.versionCode);
            }
            if (in.applyAppName && in.appName != null) {
                dc.getManifestPlaceholders().put("appName", in.appName);
            }
            if (in.filterResources) {
                if (in.localeFilters.isEmpty()) {
                    throw new IllegalArgumentException(
                        "[kiteSsot] android { filterResourcesToLocales } requires at least one canonical locale."
                    );
                }
                // AGP 8 multiplexes locale, density, ABI, and other legacy
                // resource filters in this one collection. Replace only the
                // locale subset; clearing everything would silently discard
                // an application's unrelated packaging policy.
                dc.getResourceConfigurations().removeIf(
                    LocaleTagsKt::looksLikeUnambiguousLocaleQualifier
                );
                dc.getResourceConfigurations().addAll(in.localeFilters);
            }
            if (in.applySdkLevels) {
                if (in.compileSdk != null) android.setCompileSdk(in.compileSdk);
                if (in.ndk != null) android.setNdkVersion(in.ndk);
                if (in.minSdk != null) dc.setMinSdk(in.minSdk);
                if (in.targetSdk != null) dc.setTargetSdk(in.targetSdk);
            }
            applyJavaVersion(android.getCompileOptions(), in.javaVersion);
        });
    }

    static void wireLibrary(Project project, Supplier<Agp8AndroidInputs> inputs) {
        LibraryAndroidComponentsExtension components = project.getExtensions()
            .findByType(LibraryAndroidComponentsExtension.class);
        if (components == null) return;

        components.finalizeDsl(android -> {
            Agp8AndroidInputs in = inputs.get();
            LibraryDefaultConfig dc = android.getDefaultConfig();
            if (in.applySdkLevels) {
                if (in.compileSdk != null) android.setCompileSdk(in.compileSdk);
                if (in.ndk != null) android.setNdkVersion(in.ndk);
                if (in.minSdk != null) dc.setMinSdk(in.minSdk);
                // Library modules have no targetSdk (AGP removed it).
            }
            applyJavaVersion(android.getCompileOptions(), in.javaVersion);
        });
    }

    private static void applyJavaVersion(
        com.android.build.api.dsl.CompileOptions compileOptions,
        Integer javaVersion
    ) {
        if (javaVersion == null) return;
        JavaVersion resolved = JavaVersion.toVersion(javaVersion);
        compileOptions.setSourceCompatibility(resolved);
        compileOptions.setTargetCompatibility(resolved);
    }
}
