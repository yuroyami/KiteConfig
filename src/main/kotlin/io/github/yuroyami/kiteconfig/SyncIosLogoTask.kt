package io.github.yuroyami.kiteconfig

import io.github.yuroyami.kiteconfig.core.OwnedOutputSafety
import io.github.yuroyami.kiteconfig.core.diagnosticSafeText
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * Composites the FG/BG layers into the iOS AppIcon.appiconset:
 *  - BG *covers* the 1024² canvas, FG *contains* (aspect-preserving) on top.
 *  - Flattened to opaque RGB: App Store marketing icons MUST NOT have alpha,
 *    so any FG/BG transparency is composited against white.
 *  - Writes `AppIcon-1024.png` + a single-image universal `Contents.json`.
 *
 * Requires Xcode 14+ and an iOS deployment target of at least 12.0 (validated by
 * the root model). This is deliberately non-cacheable because it installs into a
 * user-owned Xcode asset catalog and must execute configured recovery, ownership,
 * orphan, and path-safety checks on every invocation.
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
 *
 * The same class installs the debug catalog, `AppIcon-Debug.appiconset`, with
 * [debugVariant] on, and takes it back out when [install] is off.
 */
@DisableCachingByDefault(because = "Installs and validates files in a user-owned Xcode asset catalog.")
internal abstract class SyncIosLogoTask : DefaultTask() {

    init {
        group = "kiteconfig"
        description = "Composite FG+BG app-logo PNGs into the iOS AppIcon.appiconset (single 1024 universal)."
        outputs.upToDateWhen { false }
        backupDir.convention(project.layout.projectDirectory.dir(".kiteconfig/recovery/ios-appicon"))
        projectRootDir.convention(project.layout.projectDirectory)
        install.convention(true)
        debugVariant.convention(false)
    }

    /** Validated explicitly so configured-but-missing files receive actionable plugin diagnostics. */
    @get:Internal
    abstract val foregroundPng: RegularFileProperty

    @get:Internal
    abstract val backgroundPng: RegularFileProperty

    @get:Internal
    abstract val overlayPng: RegularFileProperty

    @get:[Input Optional] abstract val backgroundColorHex: Property<String>

    /** How much of the canvas the mark fills. Unset keeps the whole canvas. */
    @get:[Input Optional] abstract val foregroundScale: Property<Double>

    /** False takes back a catalog this task installed earlier. The release installer never sets it. */
    @get:Input abstract val install: Property<Boolean>

    /** True when this task installs the debug catalog, so messages name `debug { logo { ... } }`. */
    @get:Input abstract val debugVariant: Property<Boolean>
    @get:Internal abstract val dryRun: Property<Boolean>
    @get:Internal abstract val backup: Property<Boolean>

    @get:Internal abstract val appiconsetDir: DirectoryProperty
    @get:Internal abstract val backupDir: DirectoryProperty
    @get:Internal abstract val projectRootDir: DirectoryProperty

    /** Compatibility surface for diagnostics/wiring; source-tree files are not Gradle task outputs. */
    @get:Internal abstract val outputFiles: ConfigurableFileCollection

    @TaskAction
    fun sync() {
        val dry = dryRun.get()
        if (!install.getOrElse(true)) {
            removeInstalledCatalog(dry)
            return
        }
        val fgFile = foregroundPng.asFile.orNull
        if (fgFile == null) {
            logger.lifecycle("[kiteConfig] iOS logo sync skipped: ${member("foreground")} is not configured.")
            return
        }
        if (!fgFile.exists()) {
            throw GradleException(
                "[kiteConfig] ${member("foreground")} points to a missing file: " +
                    "${displayProjectPath(projectRootDir.asFile.get(), fgFile)}. " +
                    "Fix the path or disable logo propagation.",
            )
        }
        val outDir = appiconsetDir.asFile.get()
        OwnedOutputSafety.requireInstallerInsideProject(
            outDir,
            projectRootDir.asFile.get(),
            "iOS AppIcon output",
        )
        OwnedOutputSafety.requireInputOutsideOutput(fgFile, outDir, member("foreground"))
        val fgSnapshot = decodeLogo(fgFile, member("foreground"))
        val overlaySnapshot = overlayPng.asFile.orNull?.let { overlayFile ->
            if (!overlayFile.exists()) {
                throw GradleException(
                    "[kiteConfig] ${member("overlay")} points to a missing file: " +
                        "${displayProjectPath(projectRootDir.asFile.get(), overlayFile)}. " +
                        "Fix the path or remove the overlay.",
                )
            }
            OwnedOutputSafety.requireInputOutsideOutput(overlayFile, outDir, member("overlay"))
            decodeLogo(overlayFile, member("overlay"))
        }
        val fg = overlaySnapshot?.let { applyLogoOverlay(fgSnapshot.image, it.image) } ?: fgSnapshot.image

        val bgDescription: String
        var bgSnapshot: LogoPngSnapshot? = null
        val bg = if (backgroundColorHex.isPresent) {
            val hex = backgroundColorHex.get()
            bgDescription = "color $hex"
            var color = parseLogoBackgroundColor(hex)
            if (color.alpha < 255) {
                logger.warn(
                    "[kiteConfig] ${member("backgroundColor")} $hex is semi-transparent. " +
                        "flattening over white because App Store icons must be opaque.",
                )
                color = flattenOverWhite(color)
            }
            solidColorImage(IOS_SIZE, color)
        } else {
            val bgFile = backgroundPng.asFile.orNull
            if (bgFile == null) {
                throw GradleException(
                    "[kiteConfig] iOS logo requires ${member("background")} or ${member("backgroundColor")}.",
                )
            }
            if (!bgFile.exists()) {
                throw GradleException(
                    "[kiteConfig] ${member("background")} points to a missing file: " +
                        "${displayProjectPath(projectRootDir.asFile.get(), bgFile)}. " +
                        "Fix the path or configure ${member("backgroundColor")}.",
                )
            }
            OwnedOutputSafety.requireInputOutsideOutput(bgFile, outDir, member("background"))
            bgDescription = bgFile.name
            decodeLogo(bgFile, member("background")).also { bgSnapshot = it }.image
        }

        // INT_RGB so any residual alpha is composed against opaque white.
        val composite = BufferedImage(IOS_SIZE, IOS_SIZE, BufferedImage.TYPE_INT_RGB).withGraphics {
            color = Color.WHITE
            fillRect(0, 0, IOS_SIZE, IOS_SIZE)
            drawCover(bg, 0, 0, IOS_SIZE, IOS_SIZE)
            drawContainScaled(fg, IOS_SIZE, foregroundScale.getOrElse(1.0))
        }

        OwnedOutputSafety.requireSafePath(outDir, "iOS AppIcon output")
        val pngBytes = encodePng(composite, "iOS app icon")
        val rendered = linkedMapOf(
            "AppIcon-1024.png" to pngBytes,
            "Contents.json" to CONTENTS_JSON.toByteArray(StandardCharsets.UTF_8),
        )
        val configuredColor = backgroundColorHex.orNull
        val inputFingerprint = logoInputFingerprint(
            rendererVersion = RENDERER_FINGERPRINT_VERSION,
            foregroundSha256 = fgSnapshot.sha256,
            backgroundSha256 = bgSnapshot?.sha256,
            backgroundColor = configuredColor,
            parameters = overlayFingerprintParameter(overlaySnapshot?.sha256),
        )
        warnOnOrphanIcons(outDir)

        if (dry) {
            rendered.keys.forEach { logger.lifecycle("[kiteConfig][dry-run] would write iOS AppIcon file: $it") }
            return
        }

        val catalogIdentity = catalogIdentity(projectRootDir.asFile.get(), outDir)
        val metadataBase = (outDir.parentFile?.parentFile ?: outDir.parentFile ?: outDir)
            .resolve(".kiteconfig/$catalogIdentity")
        val ownershipManifest = metadataBase.resolve("owned-files-v1")
        if (backup.getOrElse(true)) {
            OwnedOutputSafety.replaceInstalledFilesWithBackup(
                installationRoot = outDir,
                manifestFile = ownershipManifest,
                backupRoot = backupDir.asFile.get().resolve(catalogIdentity),
                projectRoot = projectRootDir.asFile.get(),
                owner = catalogIdentity,
                files = rendered,
                inputFingerprint = inputFingerprint,
            )
        } else {
            OwnedOutputSafety.replaceInstalledFiles(
                installationRoot = outDir,
                manifestFile = ownershipManifest,
                projectRoot = projectRootDir.asFile.get(),
                owner = catalogIdentity,
                files = rendered,
                inputFingerprint = inputFingerprint,
            )
        }

        val catalogLabel = if (debugVariant.getOrElse(false)) "iOS debug AppIcon" else "iOS AppIcon"
        logger.lifecycle("[kiteConfig] $catalogLabel synced from ${fgFile.name} + $bgDescription (1024×1024 opaque).")
    }

    private fun decodeLogo(file: File, label: String): LogoPngSnapshot = try {
        readBoundedLogoPngSnapshot(file, label)
    } catch (e: IllegalArgumentException) {
        throw GradleException("[kiteConfig] ${e.message}", e)
    }

    /** The DSL member a message should name, for the release or the debug icon. */
    private fun member(name: String): String =
        if (debugVariant.getOrElse(false)) "debug { logo { $name } }" else "logo { $name }"

    /**
     * Warn about `.png` files left in the appiconset that the new single-universal
     * `Contents.json` no longer references, otherwise Xcode shows "unassigned
     * children" warnings and the stale icons stay in the built app.
     */
    private fun warnOnOrphanIcons(outDir: File) {
        val directory = outDir.toPath()
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return
        val orphans = Files.newDirectoryStream(directory).use { entries ->
            val found = mutableListOf<File>()
            var entryCount = 0
            for (path in entries) {
                entryCount += 1
                if (entryCount > MAX_CATALOG_SCAN_ENTRIES) {
                    throw GradleException(
                        "[kiteConfig] Refusing to scan an AppIcon catalog with more than " +
                            "$MAX_CATALOG_SCAN_ENTRIES entries: ${diagnosticSafeText(directory.toString())}",
                    )
                }
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (attrs.isSymbolicLink || attrs.isOther) {
                    throw GradleException(
                        "[kiteConfig] Refusing unsafe AppIcon catalog entry: " +
                            diagnosticSafeText(path.toString()),
                    )
                }
                path.toFile().takeIf {
                    attrs.isRegularFile && it.extension.equals("png", ignoreCase = true) &&
                        it.name !in OUTPUT_FILE_NAMES
                }?.let(found::add)
            }
            found.sortedBy(File::getName)
        }
        if (orphans.isEmpty()) return
        val displayed = orphans.take(MAX_DISPLAYED_ORPHANS)
        val omitted = orphans.size - displayed.size
        logger.warn(
            "[kiteConfig] ${orphans.size} icon file(s) in the appiconset are no longer referenced by the " +
                    "generated Contents.json:\n" +
                    displayed.joinToString("\n") { "    ${diagnosticSafeText(it.name)}" } +
                    (if (omitted > 0) "\n    … and $omitted more" else "") +
                    "\n  Delete them to silence Xcode's \"unassigned children\" warning."
        )
    }

    /**
     * Takes back a catalog this task installed and nobody wants now.
     *
     * Only files the receipt still vouches for are removed; the folder goes too
     * once it is empty. Without a receipt there is nothing of ours to remove.
     */
    private fun removeInstalledCatalog(dry: Boolean) {
        val outDir = appiconsetDir.asFile.get()
        val root = projectRootDir.asFile.get()
        val catalogIdentity = runCatching { catalogIdentity(root, outDir) }.getOrNull() ?: return
        val manifest = (outDir.parentFile?.parentFile ?: outDir.parentFile ?: outDir)
            .resolve(".kiteconfig/$catalogIdentity/owned-files-v1")
        if (!manifest.isFile) return
        if (dry) {
            logger.lifecycle("[kiteConfig][dry-run] would remove the iOS debug AppIcon owned by $catalogIdentity.")
            return
        }
        val removed = OwnedOutputSafety.removeInstalledFiles(
            installationRoot = outDir,
            manifestFile = manifest,
            projectRoot = root,
            owner = catalogIdentity,
        )
        if (outDir.isDirectory && outDir.list().isNullOrEmpty()) outDir.delete()
        if (removed.isNotEmpty()) {
            logger.lifecycle(
                "[kiteConfig] The iOS debug icon is off, so ${removed.size} file(s) it installed were removed from " +
                    displayProjectPath(root, outDir) + ".",
            )
        }
    }

    companion object {
        internal const val IOS_SIZE = 1024
        internal const val RENDERER_FINGERPRINT_VERSION = "ios-appicon-renderer-v1"
        private const val MAX_CATALOG_SCAN_ENTRIES = 10_000
        private const val MAX_DISPLAYED_ORPHANS = 20
        internal val OUTPUT_FILE_NAMES = listOf("AppIcon-1024.png", "Contents.json")

        /** Stable namespace for catalogs that may share the same final directory name. */
        internal fun catalogIdentity(projectRoot: File, appiconset: File): String {
            val root = projectRoot.toPath().toAbsolutePath().normalize()
            val output = appiconset.toPath().toAbsolutePath().normalize()
            require(output.startsWith(root)) { "AppIcon catalog is outside project root: $output" }
            val relative = root.relativize(output).joinToString("/") { it.toString() }
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(relative.toByteArray(StandardCharsets.UTF_8))
                .take(10)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "ios-appicon-$hash"
        }

        private val CONTENTS_JSON = """
            |{
            |  "images" : [
            |    {
            |      "filename" : "AppIcon-1024.png",
            |      "idiom" : "universal",
            |      "platform" : "ios",
            |      "size" : "1024x1024"
            |    }
            |  ],
            |  "info" : {
            |    "author" : "kiteconfig",
            |    "version" : 1
            |  }
            |}
            |""".trimMargin()
    }
}
