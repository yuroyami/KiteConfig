package io.github.yuroyami.kitessot;

import com.android.build.api.dsl.ApplicationDefaultConfig;
import com.android.build.api.dsl.LibraryDefaultConfig;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.LibraryAndroidComponentsExtension;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Classic Android adapter compiled against the supported AGP 8.5.2 floor.
 *
 * <p>AGP 9 narrowed generic DSL return descriptors. Keeping this adapter in a
 * separately compiled source set preserves binary calls that AGP 8 implements,
 * without weakening the typed AGP 9 adapter or reflecting over mutable DSL
 * state.</p>
 */
final class Agp8ClassicAndroidWiring {
    private Agp8ClassicAndroidWiring() { }

    static void wireApplication(Project project, KiteSsotExtension ext) {
        ApplicationAndroidComponentsExtension components = project.getExtensions()
            .findByType(ApplicationAndroidComponentsExtension.class);
        if (components == null) return;

        components.finalizeDsl(android -> {
            ApplicationDefaultConfig dc = android.getDefaultConfig();
            List<String> selectedApplications = ext.getAndroidApplicationProjects().get();
            boolean receivesAppScopedValues = selectedApplications.isEmpty()
                || selectedApplications.contains(project.getPath());
            if (receivesAppScopedValues) {
                if (ext.getPropagateBundleId().get() && ext.getBundleIdBase().isPresent()) {
                    dc.setApplicationId(ext.getAndroidApplicationId().get());
                }
                if (ext.getPropagateVersion().get()) {
                    if (ext.getVersionName().isPresent()) dc.setVersionName(ext.getVersionName().get());
                    Integer versionCode = ext.getVersionCode().getOrNull();
                    if (versionCode != null) dc.setVersionCode(versionCode);
                }
                if (ext.getPropagateAppName().get() && ext.getAppName().isPresent()) {
                    dc.getManifestPlaceholders().put("appName", ext.getAppName().get());
                }
                if (ext.getFilterAndroidResources().get()) {
                    List<String> localeFilters = ext.getCanonicalLocales().get().stream()
                        .map(LocaleTagsKt::bcp47ToAndroidQualifier)
                        .collect(Collectors.toList());
                    if (localeFilters.isEmpty()) {
                        throw new IllegalArgumentException(
                            "[kiteSsot] filterAndroidResources=true requires at least one canonical locale."
                        );
                    }
                    // AGP 8 multiplexes locale, density, ABI, and other legacy
                    // resource filters in this one collection. Replace only the
                    // locale subset; clearing everything would silently discard
                    // an application's unrelated packaging policy.
                    dc.getResourceConfigurations().removeIf(
                        LocaleTagsKt::looksLikeUnambiguousLocaleQualifier
                    );
                    dc.getResourceConfigurations().addAll(localeFilters);
                }
            }
            if (ext.getPropagateAndroidSdk().get()) {
                KiteSsotAndroidExtension sdk = ext.getAndroid();
                if (sdk.getCompileSdk().isPresent()) android.setCompileSdk(sdk.getCompileSdk().get());
                if (sdk.getNdkVersion().isPresent()) android.setNdkVersion(sdk.getNdkVersion().get());
                if (sdk.getMinSdk().isPresent()) dc.setMinSdk(sdk.getMinSdk().get());
                if (sdk.getTargetSdk().isPresent()) dc.setTargetSdk(sdk.getTargetSdk().get());
            }
            if (ext.getJavaVersion().isPresent()) {
                JavaVersion javaVersion = JavaVersion.toVersion(
                    SsotValidationKt.validateJavaVersion(ext.getJavaVersion().get())
                );
                android.getCompileOptions().setSourceCompatibility(javaVersion);
                android.getCompileOptions().setTargetCompatibility(javaVersion);
            }
        });
    }

    static void wireLibrary(Project project, KiteSsotExtension ext) {
        LibraryAndroidComponentsExtension components = project.getExtensions()
            .findByType(LibraryAndroidComponentsExtension.class);
        if (components == null) return;

        components.finalizeDsl(android -> {
            LibraryDefaultConfig dc = android.getDefaultConfig();
            if (ext.getPropagateAndroidSdk().get()) {
                KiteSsotAndroidExtension sdk = ext.getAndroid();
                if (sdk.getCompileSdk().isPresent()) android.setCompileSdk(sdk.getCompileSdk().get());
                if (sdk.getNdkVersion().isPresent()) android.setNdkVersion(sdk.getNdkVersion().get());
                if (sdk.getMinSdk().isPresent()) dc.setMinSdk(sdk.getMinSdk().get());
            }
            if (ext.getJavaVersion().isPresent()) {
                JavaVersion javaVersion = JavaVersion.toVersion(
                    SsotValidationKt.validateJavaVersion(ext.getJavaVersion().get())
                );
                android.getCompileOptions().setSourceCompatibility(javaVersion);
                android.getCompileOptions().setTargetCompatibility(javaVersion);
            }
        });
    }
}
