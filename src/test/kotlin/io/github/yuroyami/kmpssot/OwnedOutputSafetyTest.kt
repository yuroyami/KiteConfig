package io.github.yuroyami.kmpssot

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class OwnedOutputSafetyTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `source installer destination must stay beneath the project root`() {
        val project = directory.resolve("project").apply { mkdirs() }
        val outside = directory.resolve("outside/res")

        val error = assertThrows(GradleException::class.java) {
            OwnedOutputSafety.requireInstallerInsideProject(outside, project, "Android logo output")
        }

        assertTrue(error.message.orEmpty().contains("outside the root project directory"), error.message)
    }

    @Test
    fun `package move removes only the checksummed prior generated file`() {
        val allowed = directory.resolve("build/generated/kmpssot")
        val output = allowed.resolve("commonMain/kotlin")
        val old = "com/old/AppConfig.kt"
        val moved = "com/new/AppConfig.kt"

        OwnedOutputSafety.replaceGeneratedTree(output, allowed, "test", mapOf(old to "old".toByteArray()))
        OwnedOutputSafety.replaceGeneratedTree(output, allowed, "test", mapOf(moved to "new".toByteArray()))

        assertFalse(output.resolve(old).exists())
        assertTrue(output.resolve(moved).readText() == "new")
        assertTrue(output.resolve(OwnedOutputSafety.OWNERSHIP_MANIFEST_NAME).isFile)
    }

    @Test
    fun `modified owned output is preserved and generation fails closed`() {
        val allowed = directory.resolve("build/generated/kmpssot")
        val output = allowed.resolve("jsMain/kotlin")
        val relative = "generated/Worker.kt"
        OwnedOutputSafety.replaceGeneratedTree(output, allowed, "test", mapOf(relative to "generated".toByteArray()))
        output.resolve(relative).writeText("hand edit")

        val error = assertThrows(GradleException::class.java) {
            OwnedOutputSafety.replaceGeneratedTree(output, allowed, "test", mapOf(relative to "next".toByteArray()))
        }

        assertTrue(error.message.orEmpty().contains("modified generated output"), error.message)
        assertTrue(output.resolve(relative).readText() == "hand edit")
    }

    @Test
    fun `owned output replacement rolls back every file and manifest on commit failure`() {
        val allowed = directory.resolve("build/generated/kmpssot")
        val output = allowed.resolve("commonMain/kotlin")
        val manifest = output.resolve(OwnedOutputSafety.OWNERSHIP_MANIFEST_NAME)
        OwnedOutputSafety.replaceGeneratedTree(
            output,
            allowed,
            "test",
            mapOf("old/One.kt" to "one".toByteArray(), "old/Two.kt" to "two".toByteArray()),
        )
        val oldManifest = manifest.readBytes()

        val failure = assertThrows(GradleException::class.java) {
            OwnedOutputSafety.replaceGeneratedTree(
                output,
                allowed,
                "test",
                mapOf("new/One.kt" to "next-one".toByteArray(), "new/Two.kt" to "next-two".toByteArray()),
            ) { index, _ ->
                if (index == 1) throw java.io.IOException("injected second-write failure")
            }
        }

        assertTrue(failure.message.orEmpty().contains("previous output set was restored"), failure.message)
        assertTrue(output.resolve("old/One.kt").readText() == "one")
        assertTrue(output.resolve("old/Two.kt").readText() == "two")
        assertFalse(output.resolve("new/One.kt").exists())
        assertFalse(output.resolve("new/Two.kt").exists())
        assertArrayEquals(oldManifest, manifest.readBytes())
    }

    @Test
    fun `concurrent edit after transaction snapshot is preserved`() {
        val allowed = directory.resolve("build/generated/kmpssot")
        val output = allowed.resolve("commonMain/kotlin")
        val relative = "generated/App.kt"
        val target = output.resolve(relative)
        OwnedOutputSafety.replaceGeneratedTree(output, allowed, "test", mapOf(relative to "old".toByteArray()))

        assertThrows(GradleException::class.java) {
            OwnedOutputSafety.replaceGeneratedTree(
                output,
                allowed,
                "test",
                mapOf(relative to "new".toByteArray()),
            ) { _, mutationTarget ->
                if (mutationTarget.toFile().canonicalFile == target.canonicalFile) {
                    mutationTarget.toFile().writeText("external edit")
                }
            }
        }

        assertTrue(target.readText() == "external edit")
    }

    @Test
    fun `concurrent same-content symlink is never followed or deleted`() {
        val allowed = directory.resolve("build/generated/kmpssot")
        val output = allowed.resolve("commonMain/kotlin")
        val relative = "generated/App.kt"
        val original = "same owned bytes".toByteArray()
        OwnedOutputSafety.replaceGeneratedTree(output, allowed, "test", mapOf(relative to original))
        val targetFile = output.resolve(relative)
        // Normalize only through the parent so the selected entry remains
        // no-follow, while still accounting for macOS /var -> /private/var.
        val target = targetFile.parentFile.canonicalFile.toPath().resolve(targetFile.name)

        val external = directory.resolve("external/App.kt").apply {
            parentFile.mkdirs()
            writeBytes(original)
        }.toPath().toAbsolutePath().normalize()
        val probe = directory.resolve("symlink-probe").toPath()
        try {
            Files.createSymbolicLink(probe, external)
            Files.delete(probe)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "symbolic links are not supported")
        } catch (_: java.nio.file.FileSystemException) {
            assumeTrue(false, "symbolic links are not permitted")
        }

        val failure = assertThrows(GradleException::class.java) {
            OwnedOutputSafety.replaceGeneratedTree(
                output,
                allowed,
                "test",
                mapOf(relative to "replacement".toByteArray()),
            ) { _, mutationTarget ->
                if (mutationTarget.toAbsolutePath().normalize() == target) {
                    Files.delete(target)
                    Files.createSymbolicLink(target, external)
                }
            }
        }

        assertTrue(failure.message.orEmpty().contains("preserved newer external changes"), failure.message)
        assertTrue(Files.isSymbolicLink(target), "the concurrent symlink entry must remain at the public path")
        assertTrue(Files.readSymbolicLink(target) == external)
        assertArrayEquals(original, Files.readAllBytes(external))
        assertFalse(
            Files.list(target.parent).use { entries ->
                entries.anyMatch { it.fileName.toString().contains(".rollback-current-") }
            },
            "the parked symlink must have been restored instead of leaked",
        )
    }

    @Test
    fun `owned batch revalidates each target immediately before mutation`() {
        val allowed = directory.resolve("build/generated/kmpssot")
        val output = allowed.resolve("commonMain/kotlin")
        val first = output.resolve("generated/A.kt")
        val second = output.resolve("generated/B.kt")
        OwnedOutputSafety.replaceGeneratedTree(
            output,
            allowed,
            "test",
            mapOf("generated/A.kt" to "old-a".toByteArray(), "generated/B.kt" to "old-b".toByteArray()),
        )

        assertThrows(GradleException::class.java) {
            OwnedOutputSafety.replaceGeneratedTree(
                output,
                allowed,
                "test",
                mapOf("generated/A.kt" to "new-a".toByteArray(), "generated/B.kt" to "new-b".toByteArray()),
            ) { index, _ ->
                if (index == 1) second.writeText("external-b")
            }
        }

        assertTrue(first.readText() == "old-a")
        assertTrue(second.readText() == "external-b")
    }

    @Test
    fun `owned rollback preserves an external edit made after an earlier write`() {
        val allowed = directory.resolve("build/generated/kmpssot")
        val output = allowed.resolve("commonMain/kotlin")
        val first = output.resolve("generated/A.kt")
        val second = output.resolve("generated/B.kt")
        OwnedOutputSafety.replaceGeneratedTree(
            output,
            allowed,
            "test",
            mapOf("generated/A.kt" to "old-a".toByteArray(), "generated/B.kt" to "old-b".toByteArray()),
        )

        val failure = assertThrows(GradleException::class.java) {
            OwnedOutputSafety.replaceGeneratedTree(
                output,
                allowed,
                "test",
                mapOf("generated/A.kt" to "new-a".toByteArray(), "generated/B.kt" to "new-b".toByteArray()),
            ) { index, _ ->
                if (index == 1) {
                    first.writeText("external after write")
                    throw java.io.IOException("injected second-write failure")
                }
            }
        }

        assertTrue(failure.message.orEmpty().contains("preserved newer external changes"), failure.message)
        assertTrue(first.readText() == "external after write")
        assertTrue(second.readText() == "old-b")
    }

    @Test
    fun `unowned file created after transaction snapshot is not overwritten`() {
        val allowed = directory.resolve("build/generated/kmpssot")
        val output = allowed.resolve("commonMain/kotlin")
        OwnedOutputSafety.replaceGeneratedTree(output, allowed, "test", mapOf("old/App.kt" to "old".toByteArray()))
        val unowned = output.resolve("new/App.kt")

        assertThrows(GradleException::class.java) {
            OwnedOutputSafety.replaceGeneratedTree(
                output,
                allowed,
                "test",
                mapOf("new/App.kt" to "generated".toByteArray()),
            ) { _, mutationTarget ->
                if (mutationTarget.toFile().canonicalFile == unowned.canonicalFile) {
                    mutationTarget.parent.toFile().mkdirs()
                    mutationTarget.toFile().writeText("mine")
                }
            }
        }

        assertTrue(unowned.readText() == "mine")
        assertTrue(output.resolve("old/App.kt").readText() == "old")
    }

    @Test
    fun `failed first-contact takeover restores from its verified backup`() {
        val resources = directory.resolve("android/src/main/res")
        val target = resources.resolve("mipmap-mdpi/ic_launcher.png")
        target.parentFile.mkdirs()
        val original = byteArrayOf(7, 6, 5, 4)
        target.writeBytes(original)

        assertThrows(GradleException::class.java) {
            OwnedOutputSafety.replaceInstalledFilesWithBackup(
                installationRoot = resources,
                manifestFile = resources.resolve(".kmpssot-icons"),
                backupRoot = directory.resolve("build/recovery"),
                projectRoot = directory,
                owner = "INVALID OWNER",
                files = mapOf("mipmap-mdpi/ic_launcher.png" to byteArrayOf(1, 2, 3)),
            )
        }

        assertArrayEquals(original, target.readBytes())
    }

    @Test
    fun `failed first-contact takeover preserves a source recreated after removal`() {
        val resources = directory.resolve("android/src/main/res")
        val target = resources.resolve("mipmap-mdpi/ic_launcher.png")
        target.parentFile.mkdirs()
        val original = byteArrayOf(7, 6, 5, 4)
        val external = byteArrayOf(9, 8, 7, 6)
        target.writeBytes(original)

        val failure = assertThrows(GradleException::class.java) {
            OwnedOutputSafety.replaceInstalledFilesWithBackup(
                installationRoot = resources,
                manifestFile = resources.resolve(".kmpssot-icons"),
                backupRoot = directory.resolve("build/recovery"),
                projectRoot = directory,
                owner = "test",
                files = mapOf("mipmap-mdpi/ic_launcher.png" to byteArrayOf(1, 2, 3)),
                afterTakeoverBeforeInstall = {
                    target.writeBytes(external)
                    throw java.io.IOException("injected install failure after external recreation")
                },
            )
        }

        assertTrue(failure.message.orEmpty().contains("preserved newer external changes"), failure.message)
        assertArrayEquals(external, target.readBytes())
        val recoveryCopy = directory.resolve("build/recovery").walkTopDown()
            .first { it.isFile && it.name == target.name }
        assertArrayEquals(original, recoveryCopy.readBytes())
    }

    @Test
    fun `unowned generated-tree content is never removed`() {
        val allowed = directory.resolve("build/generated/kmpssot")
        val output = allowed.resolve("commonMain/kotlin")
        val unowned = output.resolve("keep/me.kt")
        unowned.parentFile.mkdirs()
        unowned.writeText("mine")

        assertThrows(GradleException::class.java) {
            OwnedOutputSafety.replaceGeneratedTree(
                output,
                allowed,
                "test",
                mapOf("generated/App.kt" to "generated".toByteArray()),
            )
        }
        assertTrue(unowned.exists())
        assertTrue(unowned.readText() == "mine")
    }

    @Test
    fun `symlinked output cannot redirect cleanup`() {
        val allowed = directory.resolve("build/generated/kmpssot")
        allowed.mkdirs()
        val handwritten = directory.resolve("handwritten").apply { mkdirs() }
        val sentinel = handwritten.resolve("Keep.kt").apply { writeText("keep") }
        val output = allowed.toPath().resolve("commonMain")
        try {
            Files.createSymbolicLink(output, handwritten.toPath())
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "symbolic links are not supported")
        } catch (_: java.nio.file.FileSystemException) {
            assumeTrue(false, "symbolic links are not permitted")
        }

        assertThrows(GradleException::class.java) {
            OwnedOutputSafety.replaceGeneratedTree(
                output.toFile(),
                allowed,
                "test",
                mapOf("Generated.kt" to "generated".toByteArray()),
            )
        }
        assertTrue(sentinel.exists())
        assertTrue(sentinel.readText() == "keep")
    }

    @Test
    fun `generated output symlink to another allowed descendant is rejected lexically`() {
        val allowed = directory.resolve("build/generated/kmpssot").apply { mkdirs() }
        val realOutput = allowed.resolve("real/commonMain").apply { mkdirs() }
        val selectedOutput = allowed.resolve("selected")
        try {
            Files.createSymbolicLink(selectedOutput.toPath(), realOutput.toPath())
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "symbolic links are not supported")
        } catch (_: java.nio.file.FileSystemException) {
            assumeTrue(false, "symbolic links are not permitted")
        }

        assertThrows(GradleException::class.java) {
            OwnedOutputSafety.replaceGeneratedTree(
                selectedOutput,
                allowed,
                "test",
                mapOf("Generated.kt" to "generated".toByteArray()),
            )
        }
        assertFalse(realOutput.resolve("Generated.kt").exists())
    }

    @Test
    fun `exclusive output traversal enforces depth and all-entry bounds`() {
        val root = directory.resolve("generated").apply { mkdirs() }
        root.resolve("a/b/file.kt").apply { parentFile.mkdirs(); writeText("x") }

        val depthFailure = assertThrows(GradleException::class.java) {
            OwnedOutputSafety.walkRegularFilesNoFollow(root.toPath(), maximumDepth = 1, maximumEntries = 100)
        }
        assertTrue(depthFailure.message.orEmpty().contains("deeper than 1"), depthFailure.message)

        val entryFailure = assertThrows(GradleException::class.java) {
            OwnedOutputSafety.walkRegularFilesNoFollow(root.toPath(), maximumDepth = 10, maximumEntries = 3)
        }
        assertTrue(entryFailure.message.orEmpty().contains("more than 3"), entryFailure.message)
    }

    @Test
    fun `oversized desired output is rejected before its file or manifest is installed`() {
        val allowed = directory.resolve("build/generated/kmpssot")
        val output = allowed.resolve("commonMain/kotlin")
        val oversized = ByteArray(64 * 1024 * 1024 + 1)

        val failure = assertThrows(GradleException::class.java) {
            OwnedOutputSafety.replaceGeneratedTree(
                output,
                allowed,
                "test",
                mapOf("Huge.bin" to oversized),
            )
        }

        assertTrue(failure.message.orEmpty().contains("owned output larger than"), failure.message)
        assertFalse(output.resolve("Huge.bin").exists())
        assertFalse(output.resolve(OwnedOutputSafety.OWNERSHIP_MANIFEST_NAME).exists())
    }

    @Test
    fun `symlinked ownership metadata cannot escape the project`() {
        val project = directory.resolve("project").apply { mkdirs() }
        val resources = project.resolve("android/src/main/res").apply { mkdirs() }
        val outside = directory.resolve("outside-metadata").apply { mkdirs() }
        val metadata = resources.parentFile.resolve(".kmpssot")
        try {
            Files.createSymbolicLink(metadata.toPath(), outside.toPath())
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "symbolic links are not supported")
        } catch (_: java.nio.file.FileSystemException) {
            assumeTrue(false, "symbolic links are not permitted")
        }

        assertThrows(GradleException::class.java) {
            OwnedOutputSafety.replaceInstalledFiles(
                installationRoot = resources,
                manifestFile = metadata.resolve("owned-files-v1"),
                projectRoot = project,
                owner = "test",
                files = mapOf("mipmap-mdpi/icon.png" to byteArrayOf(1)),
            )
        }
        assertTrue(outside.listFiles().isNullOrEmpty())
    }

    @Test
    fun `symlinked recovery root cannot redirect backups`() {
        val project = directory.resolve("project").apply { mkdirs() }
        val resources = project.resolve("android/src/main/res")
        val candidate = resources.resolve("mipmap-mdpi/icon.webp").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val outside = directory.resolve("outside-backup").apply { mkdirs() }
        val backup = project.resolve("build/recovery")
        backup.parentFile.mkdirs()
        try {
            Files.createSymbolicLink(backup.toPath(), outside.toPath())
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "symbolic links are not supported")
        } catch (_: java.nio.file.FileSystemException) {
            assumeTrue(false, "symbolic links are not permitted")
        }

        assertThrows(GradleException::class.java) {
            OwnedOutputSafety.backupThenRemove(resources, backup, project, listOf(candidate), dryRun = false)
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), candidate.readBytes())
        assertTrue(outside.listFiles().isNullOrEmpty())
    }

    @Test
    fun `migration candidate symlink to an in-root file is rejected before normalization`() {
        val project = directory.resolve("project").apply { mkdirs() }
        val resources = project.resolve("android/src/main/res").apply { mkdirs() }
        val real = resources.resolve("mipmap-mdpi/real.webp").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val selected = resources.resolve("mipmap-mdpi/selected.webp")
        try {
            Files.createSymbolicLink(selected.toPath(), real.toPath())
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "symbolic links are not supported")
        } catch (_: java.nio.file.FileSystemException) {
            assumeTrue(false, "symbolic links are not permitted")
        }
        val backup = project.resolve("build/recovery")

        assertThrows(GradleException::class.java) {
            OwnedOutputSafety.backupThenRemove(
                installationRoot = resources,
                backupRoot = backup,
                projectRoot = project,
                candidates = listOf(selected),
                dryRun = false,
            )
        }

        assertTrue(Files.isSymbolicLink(selected.toPath()))
        assertArrayEquals(byteArrayOf(1, 2, 3), real.readBytes())
        assertFalse(backup.exists())
    }

    @Test
    fun `same-named iOS catalogs receive distinct ownership namespaces`() {
        val project = directory.resolve("project").apply { mkdirs() }
        val first = project.resolve("ios/App/Assets.xcassets/AppIcon.appiconset")
        val second = project.resolve("ios/Widget/Assets.xcassets/AppIcon.appiconset")

        val firstIdentity = SyncIosLogoTask.catalogIdentity(project, first)
        val secondIdentity = SyncIosLogoTask.catalogIdentity(project, second)

        assertTrue(firstIdentity != secondIdentity)
        assertTrue(firstIdentity.startsWith("ios-appicon-") && secondIdentity.startsWith("ios-appicon-"))
    }

    @Test
    fun `migration verifies backup and records provenance before removal`() {
        val resources = directory.resolve("android/src/main/res")
        val candidate = resources.resolve("mipmap-mdpi/ic_launcher.webp")
        candidate.parentFile.mkdirs()
        val original = byteArrayOf(1, 2, 3, 4, 5)
        candidate.writeBytes(original)
        val backups = directory.resolve("build/kmpssot/backups")

        val removed = OwnedOutputSafety.backupThenRemove(
            installationRoot = resources,
            backupRoot = backups,
            projectRoot = directory,
            candidates = listOf(candidate),
            dryRun = false,
        )

        assertTrue(removed.size == 1 && removed.single().canonicalFile == candidate.canonicalFile)
        assertFalse(candidate.exists())
        assertTrue(backups.resolve("removal-provenance.tsv").isFile)
        val backup = backups.walkTopDown().first { it.isFile && it.name == candidate.name }
        assertArrayEquals(original, backup.readBytes())
    }

    @Test
    fun `migration backup contains planned bytes when source changes before backup`() {
        val resources = directory.resolve("android/src/main/res")
        val candidate = resources.resolve("mipmap-mdpi/ic_launcher.webp")
        candidate.parentFile.mkdirs()
        val planned = byteArrayOf(1, 2, 3, 4)
        val external = byteArrayOf(9, 8, 7)
        candidate.writeBytes(planned)
        val backups = directory.resolve("build/kmpssot/backups")

        assertThrows(GradleException::class.java) {
            OwnedOutputSafety.backupThenRemove(
                installationRoot = resources,
                backupRoot = backups,
                projectRoot = directory,
                candidates = listOf(candidate),
                dryRun = false,
            ) { _, _ -> candidate.writeBytes(external) }
        }

        val backup = backups.walkTopDown().first { it.isFile && it.name == candidate.name }
        assertArrayEquals(planned, backup.readBytes())
        assertArrayEquals(external, candidate.readBytes())
    }

    @Test
    fun `dry-run migration leaves source and backup tree untouched`() {
        val resources = directory.resolve("android/src/main/res")
        val candidate = resources.resolve("drawable/ic_launcher.xml")
        candidate.parentFile.mkdirs()
        candidate.writeText("user content")
        val backups = directory.resolve("build/kmpssot/backups")

        OwnedOutputSafety.backupThenRemove(resources, backups, directory, listOf(candidate), dryRun = true)

        assertTrue(candidate.exists())
        assertFalse(backups.exists())
    }

    @Test
    fun `batch deletion rolls back earlier removals when a later operation fails`() {
        val sourceOne = directory.resolve("res/one.webp").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2)) }
        val sourceTwo = directory.resolve("res/two.webp").apply { writeBytes(byteArrayOf(3, 4)) }
        val backupOne = directory.resolve("backup/one.webp").apply { parentFile.mkdirs(); writeBytes(sourceOne.readBytes()) }
        val backupTwo = directory.resolve("backup/two.webp").apply { writeBytes(sourceTwo.readBytes()) }
        val sources = listOf(sourceOne.toPath(), sourceTwo.toPath())
        val backups = mapOf(sourceOne.toPath() to backupOne.toPath(), sourceTwo.toPath() to backupTwo.toPath())
        val hashes = sources.associateWith { path ->
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        val error = assertThrows(GradleException::class.java) {
            OwnedOutputSafety.deleteWithRollback(sources, backups, hashes) { index, _ ->
                if (index == 1) throw java.io.IOException("injected second-delete failure")
            }
        }

        assertTrue(error.message.orEmpty().contains("all removed source artifacts were restored"), error.message)
        assertArrayEquals(byteArrayOf(1, 2), sourceOne.readBytes())
        assertArrayEquals(byteArrayOf(3, 4), sourceTwo.readBytes())
    }
}
