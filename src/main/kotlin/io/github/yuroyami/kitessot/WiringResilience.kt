package io.github.yuroyami.kitessot

import org.gradle.api.Project

/**
 * Runs one SSOT value group inside an AGP `finalizeDsl` callback.
 *
 * On a normal build a resolution failure aborts configuration, exactly as it
 * always did. On a resilient diagnostic invocation (`kiteSsotVerify`,
 * `kiteSsotDoctor`, `kiteSsotCheck`, `kiteSsotPlan`) the failure is logged and
 * the group is skipped instead: those tasks resolve the same providers
 * themselves and report the problem as a finding, and aborting configuration
 * here would kill the very task that explains what is wrong. Every group that
 * does resolve cleanly is still applied, so AGP's own DSL validation (a module
 * without `compileSdk`, desugaring without a minimum SDK) keeps passing on
 * diagnostic runs when those values live only in `kiteSsot { }`.
 */
internal fun Project.wireValueGroup(resilient: Boolean, group: String, write: () -> Unit) {
    if (!resilient) return write()
    try {
        write()
    } catch (failure: Exception) {
        logger.info("[kiteSsot] $path: $group not applied on this diagnostic invocation: ${failure.message}")
    }
}

/**
 * Collects module declarations that the single source of truth is about to
 * replace with a different value, and reports them as one warning per project.
 *
 * A replaced declaration is dead code with a misleading face value: the build
 * uses the SSOT value while the module file still shows the old one. One line
 * names every such value so the declarations can be deleted. The AGP 8 adapter
 * (`Agp8ClassicAndroidWiring`) emits the same line; keep the wording in sync.
 */
internal class SsotDriftLog(private val project: Project) {

    private val replaced = mutableListOf<String>()

    /** Records [dslName] when the module declared [declared] and the SSOT applies a different [applied]. */
    fun observe(dslName: String, declared: Any?, applied: Any?) {
        if (declared != null && declared != applied) replaced += "$dslName $declared -> $applied"
    }

    fun report() {
        if (replaced.isEmpty()) return
        project.logger.warn(
            "[kiteSsot] ${project.path} declares values the single source of truth replaces: " +
                "${replaced.joinToString()}. Delete these module declarations; the kiteSsot { } block owns them."
        )
    }
}
