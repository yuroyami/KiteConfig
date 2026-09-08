package io.github.yuroyami.kiteconfig

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
import java.awt.Color
import java.awt.image.BufferedImage
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
                "[kiteConfig] iOS text migration exceeds the cumulative $maximumBytes-byte " +
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
 *
 * When [splashEnabled] is on, the same plist transaction also writes the
 * `UILaunchScreen` dictionary, and the splash color/image sets are installed in
 * the asset catalog that holds the configured [appiconsetDir].
 *
 * ## Safety rails on every source-writing task
 *
 * | Rail | Behaviour |
 * |---|---|
 * | Explicit only | never runs as part of an ordinary build; you invoke it by name |
 * | Authorized | the matching DSL block must be configured, or the task is skipped |
 * | `-Pkiteconfig.dryRun=true` | reports what it would write **and remove**, changes nothing |
 * | `-Pkiteconfig.backups=true` | keeps a recovery copy before replacing anything (default) |
 * | Ownership | refuses to overwrite or delete a file it does not own |
 * | Atomic | staged then swapped, so an interrupted run leaves the tree intact |
 *
 * Both `-P` switches accept exactly `true` or `false`; anything else fails the
 * build rather than being read as `false`.
 */
@DisableCachingByDefault(because = "Explicit migration mutates user-owned source files and must execute its safety checks.")
abstract class SyncIosConfigTask : DefaultTask() {

    init {
        group = "kiteconfig"
        description = "Plan/apply a fail-closed iOS project and explicit shared-module migration."
        targetNames.convention(emptyList())
        projectRootDir.convention(project.layout.projectDirectory)
        splashEnabled.convention(false)
        infoPlistExplicit.convention(false)
        pbxprojExplicit.convention(false)
        backupDir.convention(project.layout.projectDirectory.dir(".kiteconfig/recovery/ios-splash"))
    }

    @get:Internal abstract val projectRootDir: DirectoryProperty
    @get:Internal abstract val pbxprojFile: RegularFileProperty
    @get:Internal abstract val infoPlistFile: RegularFileProperty
    @get:Internal abstract val podfile: RegularFileProperty
    @get:Internal abstract val iosAppDir: DirectoryProperty
    @get:Internal abstract val appiconsetDir: DirectoryProperty

    /** Durable recovery copies taken before this installer claims an existing asset. */
    @get:Internal abstract val backupDir: DirectoryProperty

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

    /** Off by default, so a project without `splash { enabled }` stays byte-identical. */
    @get:Input abstract val splashEnabled: Property<Boolean>

    /** Whether the plist path came from the build script rather than the default. */
    @get:Input abstract val infoPlistExplicit: Property<Boolean>

    /** Whether the Xcode project path came from the build script rather than the default. */
    @get:Input abstract val pbxprojExplicit: Property<Boolean>

    @get:Input @get:Optional abstract val splashColor: Property<String>
    @get:Input @get:Optional abstract val splashDarkColor: Property<String>

    /** Validated at execution like the other source paths, so a missing file gets an actionable message. */
    @get:Internal abstract val splashImage: RegularFileProperty
    @get:Internal abstract val splashDarkImage: RegularFileProperty

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
        if (sanitizeSourcePlist.get() || splashEnabled.get()) planInfoPlist(root, plan, summary, budget)
        planPbxproj(root, plan, summary, budget)
        if (propagateSharedModule.get()) {
            val iosRoot = try {
                requireContainedPath(root, iosAppDir.asFile.get(), mustExist = true, requireRegularFile = false)
            } catch (failure: IllegalArgumentException) {
                throw GradleException("Unsafe iosAppDir: ${failure.message}", failure)
            }
            planSharedModuleMigration(iosRoot, plan, summary, budget)
        }

        // Rendered and validated before anything is committed. This used to run
        // after the plist and project file were already written, so art that failed
        // to decode left metadata referring to an asset that was never installed.
        val splash = if (splashEnabled.get()) prepareSplashAssets(root) else null

        if (plan.isEmpty()) {
            logger.info("[kiteConfig] iOS migration plan is empty; no source files need changes.")
        } else {
            budget.verifyBeforeCommit(plan)
            val applied = applyTextRewritePlan(root, plan, backup.get(), dryRun.get(), logger)
            if (applied.dryRun) {
                logger.lifecycle("[kiteConfig] iOS migration plan: ${applied.planned} file(s); dry-run left all files untouched.")
            } else {
                logger.lifecycle("[kiteConfig] iOS migration committed ${applied.written} file(s): ${summary.joinToString("; ")}.")
            }
        }
        // The catalog entries follow the committed plist, so a refused plist plan
        // never leaves splash assets behind that nothing references.
        if (splash != null) installSplashAssets(root, splash) else removeSplashAssets(root)
    }

    /**
     * Takes back a launch screen this plugin installed and nobody wants now.
     *
     * Turning a feature off used to leave its files on disk and its keys in the
     * plist, so the app kept showing something the build script no longer
     * mentions. Only files the receipt still vouches for are removed.
     */
    private fun removeSplashAssets(root: java.io.File) {
        val appicon = appiconsetDir.asFile.orNull ?: return
        val assets = appicon.parentFile ?: return
        if (!assets.isDirectory) return
        val identity = runCatching { iosSplashCatalogIdentity(root, assets) }.getOrNull() ?: return
        val manifest = iosSplashManifestFile(assets, identity)
        if (!manifest.isFile) return

        if (dryRun.get()) {
            logger.lifecycle("[kiteConfig][dry-run] would remove the iOS splash assets owned by $identity.")
            return
        }
        val removed = OwnedOutputSafety.removeInstalledFiles(
            installationRoot = assets,
            manifestFile = manifest,
            projectRoot = root,
            owner = identity,
        )
        if (removed.isNotEmpty()) {
            logger.lifecycle(
                "[kiteConfig] iOS splash is off, so ${removed.size} asset(s) it installed were removed from " +
                    displayProjectPath(root, assets) + ".",
            )
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
            // No source plist means a generated one, which KiteConfig does not
            // maintain. Only say something when the path was chosen by hand.
            if (infoPlistExplicit.getOrElse(false)) {
                throw GradleException(
                    "kiteConfig { ios { infoPlist { path } } } points at a file that does not exist: " +
                        "${file.path}. Fix the path, or remove it if the project generates its plist.",
                )
            }
            logger.info(
                "[kiteConfig] No source Info.plist at ${file.path}; the project generates one, so " +
                    "KiteConfig left plist keys alone.",
            )
            return
        }
        val sanitize = sanitizeSourcePlist.get()
        val splash = splashEnabled.get()
        val stringEntries = buildList {
            if (sanitize && propagateAppName.get()) {
                add(PlistStringEntry("CFBundleDisplayName", "\$(PRODUCT_NAME)"))
                add(PlistStringEntry("CFBundleName", "\$(PRODUCT_NAME)"))
            }
            if (sanitize && propagateVersion.get() && marketingVersion.isPresent) {
                add(PlistStringEntry("CFBundleShortVersionString", "\$(MARKETING_VERSION)"))
            }
            if (sanitize && propagateVersion.get() && buildNumber.isPresent) {
                add(PlistStringEntry("CFBundleVersion", "\$(CURRENT_PROJECT_VERSION)"))
            }
        }
        val boolEntries = buildList {
            if (sanitize && usesNonExemptEncryption.isPresent) {
                add(PlistBoolEntry("ITSAppUsesNonExemptEncryption", usesNonExemptEncryption.get()))
            }
            if (sanitize && proMotion120Hz.isPresent) {
                add(PlistBoolEntry("CADisableMinimumFrameDurationOnPhone", proMotion120Hz.get()))
            }
        }
        if (stringEntries.isEmpty() && boolEntries.isEmpty() && !splash) return
        val snapshot = readUtf8SnapshotStrict(file)
        budget.recordSnapshot(snapshot, "Info.plist")
        val policy = plistConflictPolicy.get()
        val inserted = mutableListOf<String>()
        val overwritten = mutableListOf<String>()
        var text = snapshot.text

        val result = sanitizeInfoPlist(snapshot.text, stringEntries, boolEntries, policy)
        result.warnings.forEach { logger.warn("[kiteConfig] $it") }
        if (result.errors.isNotEmpty()) {
            throw GradleException("Info.plist migration refused:\n- ${result.errors.joinToString("\n- ")}")
        }
        text = result.text ?: text
        inserted += result.inserted
        overwritten += result.overwritten

        // Chained on the sanitized text so both edits land in one planned change;
        // Info.plist may appear only once in a rewrite plan.
        if (splash) {
            val splashResult = mergeIosSplashLaunchScreen(text, conflictPolicy = policy)
            splashResult.warnings.forEach { logger.warn("[kiteConfig] $it") }
            if (splashResult.errors.isNotEmpty()) {
                throw GradleException(
                    "Info.plist splash migration refused:\n- ${splashResult.errors.joinToString("\n- ")}",
                )
            }
            text = splashResult.text ?: text
            inserted += splashResult.inserted
            overwritten += splashResult.overwritten
        }

        if (text == snapshot.text) return
        budget.recordOutput(text, "Info.plist")
        planTextChange(root, file, text, "Info.plist", snapshot)?.let {
            plan += it
            summary += "Info.plist inserted=${inserted.joinToString().ifEmpty { "none" }} " +
                "replaced=${overwritten.joinToString().ifEmpty { "none" }}"
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
            // A project with no Xcode tree has no build settings to migrate. Say
            // something only when the path was chosen by hand.
            if (pbxprojExplicit.getOrElse(false)) {
                throw GradleException(
                    "kiteConfig { ios { pbxproj } } points at a file that does not exist: " +
                        "${configured.path}. Fix the path, or exclude iOS with skip(ios).",
                )
            }
            logger.info("[kiteConfig] No Xcode project at ${configured.path}; iOS project settings were left alone.")
            return
        }
        val file = try {
            requireContainedPath(root, configured)
        } catch (failure: IllegalArgumentException) {
            throw GradleException("Unsafe pbxproj path: ${failure.message}", failure)
        }
        val requestedTargets = targetNames.get().also { names ->
            val invalid = names.filter { it.isBlank() || it.any { char -> char.isISOControl() } }
            if (invalid.isNotEmpty()) throw GradleException("ios { xcodeTargets } contains invalid name(s): ${invalid.joinToString()}")
            if (names.size != names.distinct().size) throw GradleException("ios { xcodeTargets } contains duplicates")
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
        result.warnings.forEach { logger.warn("[kiteConfig] $it") }
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
                "[kiteConfig] Shared-module migration skipped: call " +
                    "ios { rewrite { renameSharedModule(from, to) } } " +
                    "for an explicit from/to migration. Podfile auto-detection is intentionally disabled."
            )
            return
        }
        try {
            requireSwiftModuleIdentifier(oldName, "renameSharedModule from")
            requireSwiftModuleIdentifier(newName, "renameSharedModule to")
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
            logger.info("[kiteConfig] Explicit shared-module migration '$oldName' -> '$newName' found no exact references.")
        }
    }

    /** Everything the splash installer will write, rendered and checked but not yet on disk. */
    private class PreparedSplashAssets(
        val assets: java.io.File,
        val rendered: Map<String, ByteArray>,
        val hasDarkImage: Boolean,
    )

    /**
     * Resolves the catalog, validates the art, and renders every splash byte.
     *
     * Nothing here touches the working tree, so a missing path or an image that
     * will not decode fails before the plist and project file are committed.
     */
    private fun prepareSplashAssets(root: java.io.File): PreparedSplashAssets {
        val appicon = appiconsetDir.asFile.get()
        val assets = appicon.parentFile ?: throw GradleException(
            "[kiteConfig] iOS splash needs an Assets.xcassets directory; ${appicon.path} has no parent catalog.",
        )
        OwnedOutputSafety.requireInstallerInsideProject(assets, root, "iOS splash asset catalog")
        OwnedOutputSafety.requireSafePath(assets, "iOS splash asset catalog")

        val colorHex = splashColor.orNull ?: throw GradleException(
            "[kiteConfig] The iOS splash has no plate color. " +
                "Set splash { backgroundColor } or logo { backgroundColor }.",
        )
        val imageFile = splashImage.asFile.orNull ?: throw GradleException(
            "[kiteConfig] The iOS splash has no art. Set splash { image } or logo { foreground }.",
        )
        requireSplashArt(root, imageFile, "splash { image }", assets)
        val darkImageFile = splashDarkImage.asFile.orNull
            ?.also { requireSplashArt(root, it, "splash { dark { image } }", assets) }

        val rendered = renderIosSplashAssets(
            color = splashColorOf(colorHex, "splash { backgroundColor }"),
            darkColor = splashDarkColor.orNull?.let { splashColorOf(it, "splash { dark { backgroundColor } }") },
            image = decodeSplashArt(imageFile, "splash { image }"),
            darkImage = darkImageFile?.let { decodeSplashArt(it, "splash { dark { image } }") },
        )
        return PreparedSplashAssets(assets, rendered, darkImageFile != null)
    }

    /**
     * Installs the prepared color set and image set into the asset catalog that
     * holds [appiconsetDir]. Respects dryRun and backups like every other installer.
     */
    private fun installSplashAssets(root: java.io.File, prepared: PreparedSplashAssets) {
        if (dryRun.get()) {
            iosSplashOwnedPaths(prepared.assets, prepared.hasDarkImage).forEach {
                logger.lifecycle("[kiteConfig][dry-run] would write iOS splash asset: ${it.path}")
            }
            return
        }
        val owned = writeIosSplashAssets(
            assetsDir = prepared.assets,
            rendered = prepared.rendered,
            projectRoot = root,
            backupRoot = backupDir.asFile.get(),
            backup = backup.get(),
            logger = logger,
        )
        logger.lifecycle(
            "[kiteConfig] iOS splash installed into ${displayProjectPath(root, prepared.assets)}: " +
                "$IOS_SPLASH_COLORSET.colorset and $IOS_SPLASH_IMAGESET.imageset (${owned.size} owned file(s)).",
        )
    }

    private fun requireSplashArt(root: java.io.File, file: java.io.File, label: String, assets: java.io.File) {
        if (!file.exists()) {
            throw GradleException(
                "[kiteConfig] $label points to a missing file: ${displayProjectPath(root, file)}. " +
                    "Fix the path or drop splash { enabled }.",
            )
        }
        OwnedOutputSafety.requireInputOutsideOutput(file, assets, label)
    }

    private fun splashColorOf(hex: String, label: String): Color = try {
        parseIosSplashColor(hex, label)
    } catch (failure: IllegalArgumentException) {
        throw GradleException("[kiteConfig] ${failure.message}", failure)
    }

    private fun decodeSplashArt(file: java.io.File, label: String): BufferedImage = try {
        readBoundedLogoPng(file, label)
    } catch (failure: IllegalArgumentException) {
        throw GradleException("[kiteConfig] ${failure.message}", failure)
    }
}
