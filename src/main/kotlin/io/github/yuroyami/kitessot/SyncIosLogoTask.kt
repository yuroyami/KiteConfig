package io.github.yuroyami.kitessot

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
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import javax.imageio.ImageIO

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
 */
@DisableCachingByDefault(because = "Installs and validates files in a user-owned Xcode asset catalog.")
abstract class SyncIosLogoTask : DefaultTask() {

    init {
        group = "kitessot"
        description = "Composite FG+BG app-logo PNGs into the iOS AppIcon.appiconset (single 1024 universal)."
        outputs.upToDateWhen { false }
        backupDir.convention(project.layout.projectDirectory.dir(".kitessot/recovery/ios-appicon"))
        projectRootDir.convention(project.layout.projectDirectory)
    }

    /** Validated explicitly so configured-but-missing files receive actionable plugin diagnostics. */
    @get:Internal
    abstract val foregroundPng: RegularFileProperty

    @get:Internal
    abstract val backgroundPng: RegularFileProperty

    @get:[Input Optional] abstract val backgroundColorHex: Property<String>
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
        val fgFile = foregroundPng.asFile.orNull
        if (fgFile == null) {
            logger.lifecycle("[kiteSsot] iOS logo sync skipped: logo { foreground } is not configured.")
            return
        }
        if (!fgFile.exists()) {
            throw GradleException(
                "[kiteSsot] logo { foreground } points to a missing file: " +
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
        OwnedOutputSafety.requireInputOutsideOutput(fgFile, outDir, "appLogoPngForeground")
        val fgSnapshot = decodeLogo(fgFile, "appLogoPngForeground")
        val fg = fgSnapshot.image

        val bgDescription: String
        var bgSnapshot: LogoPngSnapshot? = null
        val bg = if (backgroundColorHex.isPresent) {
            val hex = backgroundColorHex.get()
            bgDescription = "color $hex"
            var color = parseLogoBackgroundColor(hex)
            if (color.alpha < 255) {
                logger.warn(
                    "[kiteSsot] logo { backgroundColor } $hex is semi-transparent. " +
                        "flattening over white because App Store icons must be opaque.",
                )
                color = flattenOverWhite(color)
            }
            solidColorImage(IOS_SIZE, color)
        } else {
            val bgFile = backgroundPng.asFile.orNull
            if (bgFile == null) {
                throw GradleException(
                    "[kiteSsot] iOS logo requires logo { background } or logo { backgroundColor }.",
                )
            }
            if (!bgFile.exists()) {
                throw GradleException(
                    "[kiteSsot] logo { background } points to a missing file: " +
                        "${displayProjectPath(projectRootDir.asFile.get(), bgFile)}. " +
                        "Fix the path or configure logo { backgroundColor }.",
                )
            }
            OwnedOutputSafety.requireInputOutsideOutput(bgFile, outDir, "appLogoPngBackground")
            bgDescription = bgFile.name
            decodeLogo(bgFile, "appLogoPngBackground").also { bgSnapshot = it }.image
        }

        // INT_RGB so any residual alpha is composed against opaque white.
        val composite = BufferedImage(IOS_SIZE, IOS_SIZE, BufferedImage.TYPE_INT_RGB).withGraphics {
            color = Color.WHITE
            fillRect(0, 0, IOS_SIZE, IOS_SIZE)
            drawCover(bg, 0, 0, IOS_SIZE, IOS_SIZE)
            drawContain(fg, 0, 0, IOS_SIZE, IOS_SIZE)
        }

        OwnedOutputSafety.requireSafePath(outDir, "iOS AppIcon output")
        val pngBytes = encodePng(composite)
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
        )
        warnOnOrphanIcons(outDir)

        if (dry) {
            rendered.keys.forEach { logger.lifecycle("[kiteSsot][dry-run] would write iOS AppIcon file: $it") }
            return
        }

        val catalogIdentity = catalogIdentity(projectRootDir.asFile.get(), outDir)
        val metadataBase = (outDir.parentFile?.parentFile ?: outDir.parentFile ?: outDir)
            .resolve(".kitessot/$catalogIdentity")
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

        logger.lifecycle("[kiteSsot] iOS AppIcon synced from ${fgFile.name} + $bgDescription (1024×1024 opaque).")
    }

    private fun decodeLogo(file: File, label: String): LogoPngSnapshot = try {
        readBoundedLogoPngSnapshot(file, label)
    } catch (e: IllegalArgumentException) {
        throw GradleException("[kiteSsot] ${e.message}", e)
    }

    private fun encodePng(image: BufferedImage): ByteArray {
        val output = ByteArrayOutputStream()
        if (!ImageIO.write(image, "PNG", output)) {
            throw GradleException("[kiteSsot] This JDK has no PNG encoder; cannot render iOS logo.")
        }
        return output.toByteArray()
    }

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
                        "[kiteSsot] Refusing to scan an AppIcon catalog with more than " +
                            "$MAX_CATALOG_SCAN_ENTRIES entries: ${diagnosticSafeText(directory.toString())}",
                    )
                }
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (attrs.isSymbolicLink || attrs.isOther) {
                    throw GradleException(
                        "[kiteSsot] Refusing unsafe AppIcon catalog entry: " +
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
            "[kiteSsot] ${orphans.size} icon file(s) in the appiconset are no longer referenced by the " +
                    "generated Contents.json:\n" +
                    displayed.joinToString("\n") { "    ${diagnosticSafeText(it.name)}" } +
                    (if (omitted > 0) "\n    … and $omitted more" else "") +
                    "\n  Delete them to silence Xcode's \"unassigned children\" warning."
        )
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
            |    "author" : "kitessot",
            |    "version" : 1
            |  }
            |}
            |""".trimMargin()
    }
}
