package io.github.yuroyami.kitessot

import java.security.MessageDigest
import org.gradle.api.GradleException

/**
 * The fixed namespace for derived Windows upgrade codes.
 *
 * This is a published contract, not an implementation detail. Changing it would
 * change every upgrade code KiteSSOT has ever derived, which breaks MSI upgrades
 * for already-installed users.
 */
internal const val KITESSOT_UPGRADE_UUID_NAMESPACE = "6b0f4c1e-6d4a-5a2f-9c3d-1f6b2a8e7d40"

private val WINDOWS_FORMATS = setOf("Msi", "Exe")
private val LINUX_SLUG_INVALID = Regex("[^a-z0-9+.-]")
private const val MIN_DEBIAN_PACKAGE_NAME_CHARS = 2

/**
 * Reject a version the enabled installers cannot accept, before Compose sees it.
 *
 * Windows is the only real failure mode. `version` is always a strict `x.y.z`,
 * so the Debian, RPM and macOS rules can never fail on it.
 */
internal fun validateDesktopPackageVersion(version: String, targetFormats: Set<String>): String {
    if (targetFormats.none { it in WINDOWS_FORMATS }) return version
    val parts = version.split('.')
    // An absent component is 0, but a present one that will not parse fails
    // closed. Treating "1.abc.0" as "1.0.0" would let it through the cap.
    val major = parts.getOrNull(0)?.toIntOrNull()
    val minor = if (parts.size > 1) parts[1].toIntOrNull() else 0
    val build = if (parts.size > 2) parts[2].toIntOrNull() else 0
    if (major == null || minor == null || build == null ||
        major > 255 || minor > 255 || build > 65_535
    ) {
        throw GradleException(
            "kiteSsot { version } is \"${diagnosticSafeText(version, 64)}\", which Windows installers " +
                "reject. MSI and EXE accept MAJOR.MINOR.BUILD with limits 255, 255 and 65535. " +
                "Either lower the component, or drop Msi and Exe from targetFormats.",
        )
    }
    return version
}

/** Turn an app name into a Debian-legal package name. */
internal fun deriveLinuxPackageName(appName: String): String {
    val slug = LINUX_SLUG_INVALID.replace(appName.lowercase(), "-").trim('-')
    if (slug.length < MIN_DEBIAN_PACKAGE_NAME_CHARS || !slug.first().isLetterOrDigit()) {
        throw GradleException(
            "kiteSsot cannot derive a Debian package name from appName " +
                "\"${diagnosticSafeText(appName, 64)}\". Debian names must start with a letter or " +
                "digit and be at least $MIN_DEBIAN_PACKAGE_NAME_CHARS characters long. Set it " +
                "yourself with desktop { linuxPackageName }.",
        )
    }
    return slug
}

/** Derive a stable UUIDv5 upgrade code from the application identifier. */
internal fun deriveUpgradeUuid(appId: String): String {
    val namespace = KITESSOT_UPGRADE_UUID_NAMESPACE.replace("-", "")
    val namespaceBytes = ByteArray(16) { index ->
        namespace.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    val digest = MessageDigest.getInstance("SHA-1").apply {
        update(namespaceBytes)
        update(appId.toByteArray(Charsets.UTF_8))
    }.digest()
    digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
    digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
}
