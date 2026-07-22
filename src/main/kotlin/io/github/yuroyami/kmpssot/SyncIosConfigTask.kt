package io.github.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

internal const val MAX_IOS_SWIFT_TRAVERSAL_DEPTH = 32
internal const val MAX_IOS_SWIFT_TRAVERSAL_ENTRIES = 10_000
internal const val MAX_IOS_TEXT_REWRITE_BATCH_BYTES = 256L * 1024L * 1024L

private val IOS_SWIFT_PRUNED_DIRS = setOf(
    "Pods", "Carthage", "Vendor", "build", ".build", "DerivedData", "xcuserdata",
    ".git", ".swiftpm", "SourcePackages", "checkouts", "artifacts", "Package.resolved",
)

/** Cumulative byte accounting for every text snapshot and rendered output before commit. */
internal class IosTextRewriteBudget(
    private val maximumBytes: Long = MAX_IOS_TEXT_REWRITE_BATCH_BYTES,
) {
    private var snapshotBytes = 0L
    private var outputBytes = 0L

    init {
        require(maximumBytes > 0) { "iOS text rewrite budget must be positive" }
    }

    fun recordSnapshot(snapshot: Utf8FileSnapshot, label: String) {
        snapshotBytes = addBounded(snapshotBytes, snapshot.bytes.size.toLong(), "snapshot", label)
    }

    fun recordOutput(text: String, label: String) {
        val size = text.toByteArray(StandardCharsets.UTF_8).size.toLong()
        outputBytes = addBounded(outputBytes, size, "output", label)
    }

    fun verifyBeforeCommit(changes: List<PlannedTextChange>) {
        val plannedSnapshots = changes.sumOf { it.originalBytes?.size?.toLong() ?: 0L }
        val plannedOutputs = changes.sumOf { it.newText.toByteArray(StandardCharsets.UTF_8).size.toLong() }
        check(plannedSnapshots <= snapshotBytes && plannedOutputs <= outputBytes) {
            "iOS text rewrite budget accounting omitted a planned snapshot or output"
        }
    }

    private fun addBounded(current: Long, added: Long, kind: String, label: String): Long {
        val usedBytes = snapshotBytes + outputBytes
        if (added > maximumBytes - usedBytes) {
            throw GradleException(
                "[kmpSsot] iOS text migration exceeds the cumulative $maximumBytes-byte " +
                    "snapshot/output budget while planning $kind for $label; no files were changed.",
            )
        }
        return current + added
    }
}

/** Bounded, explicitly no-follow discovery for source Swift files. */
internal fun discoverIosSwiftFiles(
    root: Path,
    maximumDepth: Int = MAX_IOS_SWIFT_TRAVERSAL_DEPTH,
    maximumEntries: Int = MAX_IOS_SWIFT_TRAVERSAL_ENTRIES,
): List<Path> {
    require(maximumDepth >= 1 && maximumDepth < Int.MAX_VALUE) { "Swift traversal depth must be in 1..<Int.MAX_VALUE" }
    require(maximumEntries > 0) { "Swift traversal entry limit must be positive" }
    val normalizedRoot = root.toAbsolutePath().normalize()
    if (Files.isSymbolicLink(normalizedRoot) || !Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
        throw GradleException("Refusing Swift discovery from a non-directory/symlink root: $normalizedRoot")
    }

    fun depth(path: Path): Int = if (path == normalizedRoot) 0 else normalizedRoot.relativize(path).nameCount
    fun requireDepth(path: Path) {
        if (depth(path) > maximumDepth) {
            throw GradleException(
                "Swift source traversal exceeds maximum depth $maximumDepth at $path; no files were changed.",
            )
        }
    }

    val files = mutableListOf<Path>()
    var visitedEntries = 0
    fun recordVisited(path: Path) {
        if (path == normalizedRoot) return
        if (visitedEntries >= maximumEntries) {
            throw GradleException(
                "Swift source discovery exceeds the maximum of $maximumEntries traversal entries at $path; " +
                    "no files were changed.",
            )
        }
        visitedEntries++
    }
    Files.walkFileTree(
        normalizedRoot,
        EnumSet.noneOf(FileVisitOption::class.java),
        maximumDepth + 1,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                requireDepth(dir)
                recordVisited(dir)
                if (dir != normalizedRoot && dir.fileName.toString() in IOS_SWIFT_PRUNED_DIRS) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                // A directory exactly one level beyond the configured limit is
                // surfaced as a file by walkFileTree's maxDepth contract.
                requireDepth(file)
                recordVisited(file)
                if (!file.fileName.toString().endsWith(".swift")) return FileVisitResult.CONTINUE
                if (attrs.isSymbolicLink || Files.isSymbolicLink(file) || !attrs.isRegularFile) {
                    throw GradleException("Refusing Swift migration through non-regular/symlink path: $file")
                }
                files.add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, failure: java.io.IOException): FileVisitResult {
                throw GradleException("Could not inspect Swift migration path $file: ${failure.message}", failure)
            }
        },
    )
    return files.sortedBy { normalizedRoot.relativize(it).toString() }
}

/**
 * Explicit legacy migration for a source Info.plist, `project.pbxproj`, Podfile,
 * and Swift imports. It is deliberately non-cacheable and always computes the
 * complete enabled text plan before writing. All targets are contained,
 * symlink-free, staged, locked, and committed as one recoverable batch; write-once
 * backups are created when the [backup] input is enabled.
 * Swift discovery does not follow links and is bounded by depth and visited-entry count;
 * all input snapshots and rendered outputs share a cumulative pre-commit budget.
 *
 * pbxproj changes are allowed only for [targetNames], or for the sole application
 * target when that list is empty. Parser uncertainty, missing settings, and target
 * ambiguity abort the whole plan. When [propagateLogo] is enabled, every selected
 * configuration must declare `ASSETCATALOG_COMPILER_APPICON_NAME`; it is aligned
 * to the configured [appiconsetDir] catalog rather than leaving installed assets
 * disconnected from the application. Shared-module migration requires both
 * [iosPreviousSharedModuleName] and [iosSharedModuleName]; it never guesses from
 * a Podfile.
 */
@DisableCachingByDefault(because = "Explicit migration mutates user-owned source files and must execute its safety checks.")
abstract class SyncIosConfigTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Plan/apply a fail-closed iOS project and explicit shared-module migration."
        targetNames.convention(emptyList())
        projectRootDir.convention(project.layout.projectDirectory)
    }

    @get:Internal abstract val projectRootDir: DirectoryProperty
    @get:Internal abstract val pbxprojFile: RegularFileProperty
    @get:Internal abstract val infoPlistFile: RegularFileProperty
    @get:Internal abstract val podfile: RegularFileProperty
    @get:Internal abstract val iosAppDir: DirectoryProperty
    @get:Internal abstract val appiconsetDir: DirectoryProperty

    @get:Input @get:Optional abstract val marketingVersion: Property<String>
    @get:Input @get:Optional abstract val buildNumber: Property<String>
    @get:Input @get:Optional abstract val appName: Property<String>
    @get:Input @get:Optional abstract val bundleId: Property<String>
    @get:Input abstract val locales: ListProperty<String>
    @get:Input abstract val targetNames: ListProperty<String>
    @get:Input @get:Optional abstract val iosSharedModuleName: Property<String>
    @get:Input @get:Optional abstract val iosPreviousSharedModuleName: Property<String>

    @get:Input abstract val propagateVersion: Property<Boolean>
    @get:Input abstract val propagateAppName: Property<Boolean>
    @get:Input abstract val propagateBundleId: Property<Boolean>
    @get:Input abstract val propagateLocaleList: Property<Boolean>
    @get:Input abstract val propagateSharedModule: Property<Boolean>
    @get:Input abstract val propagateLogo: Property<Boolean>
    @get:Input abstract val sanitizeSourcePlist: Property<Boolean>
    @get:Input @get:Optional abstract val usesNonExemptEncryption: Property<Boolean>
    @get:Input @get:Optional abstract val proMotion120Hz: Property<Boolean>
    @get:Input abstract val plistConflictPolicy: Property<PlistConflictPolicy>

    @get:Input abstract val dryRun: Property<Boolean>
    @get:Input abstract val backup: Property<Boolean>

    @TaskAction
    fun sync() {
        val root = try {
            requireContainedPath(projectRootDir.asFile.get(), projectRootDir.asFile.get(), requireRegularFile = false)
        } catch (failure: IllegalArgumentException) {
            throw GradleException("Unsafe iOS migration root: ${failure.message}", failure)
        }
        val plan = mutableListOf<PlannedTextChange>()
        val summary = mutableListOf<String>()
        val budget = IosTextRewriteBudget()
        if (sanitizeSourcePlist.get()) planInfoPlist(root, plan, summary, budget)
        planPbxproj(root, plan, summary, budget)
        if (propagateSharedModule.get()) {
            val iosRoot = try {
                requireContainedPath(root, iosAppDir.asFile.get(), mustExist = true, requireRegularFile = false)
            } catch (failure: IllegalArgumentException) {
                throw GradleException("Unsafe iosAppDir: ${failure.message}", failure)
            }
            planSharedModuleMigration(iosRoot, plan, summary, budget)
        }

        if (plan.isEmpty()) {
            logger.info("[kmpSsot] iOS migration plan is empty; no source files need changes.")
            return
        }
        budget.verifyBeforeCommit(plan)
        val applied = applyTextRewritePlan(root, plan, backup.get(), dryRun.get(), logger)
        if (applied.dryRun) {
            logger.lifecycle("[kmpSsot] iOS migration plan: ${applied.planned} file(s); dry-run left all files untouched.")
        } else {
            logger.lifecycle("[kmpSsot] iOS migration committed ${applied.written} file(s): ${summary.joinToString("; ")}.")
        }
    }

    private fun planInfoPlist(
        root: java.io.File,
        plan: MutableList<PlannedTextChange>,
        summary: MutableList<String>,
        budget: IosTextRewriteBudget,
    ) {
        val configured = infoPlistFile.asFile.get()
        val file = try {
            requireContainedPath(root, configured, mustExist = configured.exists())
        } catch (failure: IllegalArgumentException) {
            throw GradleException("Unsafe Info.plist path: ${failure.message}", failure)
        }
        if (!file.exists()) {
            throw GradleException(
                "Configured source Info.plist does not exist: ${file.path}. " +
                    "For a generated plist, disable sanitizeIosProject and configure Xcode build settings instead.",
            )
        }
        val stringEntries = buildList {
            if (propagateAppName.get()) {
                add(PlistStringEntry("CFBundleDisplayName", "\$(PRODUCT_NAME)"))
                add(PlistStringEntry("CFBundleName", "\$(PRODUCT_NAME)"))
            }
            if (propagateVersion.get() && marketingVersion.isPresent) {
                add(PlistStringEntry("CFBundleShortVersionString", "\$(MARKETING_VERSION)"))
            }
            if (propagateVersion.get() && buildNumber.isPresent) {
                add(PlistStringEntry("CFBundleVersion", "\$(CURRENT_PROJECT_VERSION)"))
            }
        }
        val boolEntries = buildList {
            if (usesNonExemptEncryption.isPresent) {
                add(PlistBoolEntry("ITSAppUsesNonExemptEncryption", usesNonExemptEncryption.get()))
            }
            if (proMotion120Hz.isPresent) {
                add(PlistBoolEntry("CADisableMinimumFrameDurationOnPhone", proMotion120Hz.get()))
            }
        }
        if (stringEntries.isEmpty() && boolEntries.isEmpty()) return
        val snapshot = readUtf8SnapshotStrict(file)
        budget.recordSnapshot(snapshot, "Info.plist")
        val result = sanitizeInfoPlist(
            snapshot.text,
            stringEntries,
            boolEntries,
            plistConflictPolicy.get(),
        )
        result.warnings.forEach { logger.warn("[kmpSsot] $it") }
        if (result.errors.isNotEmpty()) {
            throw GradleException("Info.plist migration refused:\n- ${result.errors.joinToString("\n- ")}")
        }
        result.text?.let { updated ->
            budget.recordOutput(updated, "Info.plist")
            planTextChange(root, file, updated, "Info.plist", snapshot)?.let {
                plan += it
                summary += "Info.plist inserted=${result.inserted.joinToString().ifEmpty { "none" }} " +
                    "replaced=${result.overwritten.joinToString().ifEmpty { "none" }}"
            }
        }
    }

    private fun planPbxproj(
        root: java.io.File,
        plan: MutableList<PlannedTextChange>,
        summary: MutableList<String>,
        budget: IosTextRewriteBudget,
    ) {
        val configured = pbxprojFile.asFile.get()
        if (!configured.exists()) {
            throw GradleException("Configured pbxproj does not exist: ${configured.path}")
        }
        val file = try {
            requireContainedPath(root, configured)
        } catch (failure: IllegalArgumentException) {
            throw GradleException("Unsafe pbxproj path: ${failure.message}", failure)
        }
        val requestedTargets = targetNames.get().also { names ->
            val invalid = names.filter { it.isBlank() || it.any { char -> char.isISOControl() } }
            if (invalid.isNotEmpty()) throw GradleException("ios.targetNames contains invalid name(s): ${invalid.joinToString()}")
            if (names.size != names.distinct().size) throw GradleException("ios.targetNames contains duplicates")
        }.toSet()
        val snapshot = readUtf8SnapshotStrict(file)
        budget.recordSnapshot(snapshot, "iOS pbxproj")
        val appIconName = if (propagateLogo.get()) {
            runCatching { iosAppIconCatalogName(appiconsetDir.asFile.get().name) }.getOrElse { failure ->
                throw GradleException(diagnosticExceptionSummary(failure), failure)
            }
        } else {
            null
        }
        val result = rewritePbxproj(
            original = snapshot.text,
            marketingVersion = marketingVersion.orNull.takeIf { propagateVersion.get() },
            buildNumber = buildNumber.orNull.takeIf { propagateVersion.get() },
            appName = appName.orNull.takeIf { propagateAppName.get() },
            bundleId = bundleId.orNull.takeIf { propagateBundleId.get() },
            locales = locales.get().takeIf { propagateLocaleList.get() },
            targetNames = requestedTargets,
            appIconName = appIconName,
        )
        result.warnings.forEach { logger.warn("[kmpSsot] $it") }
        if (result.errors.isNotEmpty()) {
            throw GradleException("pbxproj migration refused:\n- ${result.errors.joinToString("\n- ")}")
        }
        budget.recordOutput(result.text, "iOS pbxproj")
        planTextChange(root, file, result.text, "iOS pbxproj", snapshot)?.let {
            plan += it
            summary += "pbxproj targets=${result.selectedTargets.joinToString().ifEmpty { "project metadata only" }} keys=${result.changedSettings.joinToString()}"
        }
    }

    private fun planSharedModuleMigration(
        iosRoot: java.io.File,
        plan: MutableList<PlannedTextChange>,
        summary: MutableList<String>,
        budget: IosTextRewriteBudget,
    ) {
        if (!propagateSharedModule.get()) return
        val oldName = iosPreviousSharedModuleName.orNull
        val newName = iosSharedModuleName.orNull
        if (oldName == null || newName == null) {
            logger.info(
                "[kmpSsot] Shared-module migration skipped: set both iosPreviousSharedModuleName and " +
                    "iosSharedModuleName " +
                    "for an explicit from/to migration. Podfile auto-detection is intentionally disabled."
            )
            return
        }
        try {
            requireSwiftModuleIdentifier(oldName, "iosPreviousSharedModuleName")
            requireSwiftModuleIdentifier(newName, "iosSharedModuleName")
        } catch (failure: IllegalArgumentException) {
            throw GradleException(failure.message ?: "Invalid shared-module migration name", failure)
        }
        if (oldName == newName) return

        var podChanged = false
        val configuredPodfile = podfile.asFile.orNull
        if (configuredPodfile != null && configuredPodfile.exists()) {
            val file = try {
                requireContainedPath(iosRoot, configuredPodfile)
            } catch (failure: IllegalArgumentException) {
                throw GradleException("Unsafe Podfile path: ${failure.message}", failure)
            }
            val snapshot = readUtf8SnapshotStrict(file)
            budget.recordSnapshot(snapshot, "iOS Podfile")
            val podPlan = planPodfileRewrite(snapshot.text, oldName, newName)
            if (podPlan.matchingCount > 1) {
                throw GradleException(
                    "Podfile contains ${podPlan.matchingCount} exact local-pod declarations for '$oldName'; " +
                        "refusing an ambiguous migration. Consolidate/select the declaration explicitly first."
                )
            }
            budget.recordOutput(podPlan.text, "iOS Podfile")
            planTextChange(iosRoot, file, podPlan.text, "iOS Podfile", snapshot)?.let {
                plan += it
                podChanged = true
            }
        }

        val swiftFiles = discoverIosSwiftFiles(iosRoot.toPath())
        var swiftChanged = 0
        swiftFiles.forEach { path ->
            val file = requireContainedPath(iosRoot, path.toFile())
            val snapshot = readUtf8SnapshotStrict(file)
            val relativePath = iosRoot.toPath().relativize(path).toString()
            budget.recordSnapshot(snapshot, "Swift source $relativePath")
            val updated = rewriteSwiftImport(snapshot.text, oldName, newName)
            budget.recordOutput(updated, "Swift source $relativePath")
            planTextChange(
                iosRoot,
                file,
                updated,
                "Swift import in $relativePath",
                snapshot,
            )?.let {
                plan += it
                swiftChanged++
            }
        }
        if (podChanged || swiftChanged > 0) {
            summary += "shared module '$oldName' -> '$newName' (Podfile=${if (podChanged) 1 else 0}, Swift=$swiftChanged)"
        } else {
            logger.info("[kmpSsot] Explicit shared-module migration '$oldName' -> '$newName' found no exact references.")
        }
    }

}
