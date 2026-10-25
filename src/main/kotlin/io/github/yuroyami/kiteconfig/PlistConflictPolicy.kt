package io.github.yuroyami.kiteconfig

/**
 * Policy for an existing plist key whose value differs from the requested value.
 *
 * | Value | Existing value | Build |
 * |---|---|---|
 * | [FAIL] | untouched | fails, and the whole plist plan is abandoned |
 * | [KEEP] | kept | continues, with a warning naming the key |
 * | [REPLACE] | overwritten | continues |
 *
 * Default: [FAIL], so nothing you did not agree to lose is ever replaced.
 *
 * @see KiteConfigIosSyncExtension.onConflict for where this is selected.
 */
public enum class PlistConflictPolicy {
    /** Abort the entire plist plan and leave the file byte-for-byte untouched. */
    FAIL,
    /** Preserve the existing value and report a warning. */
    KEEP,
    /** Replace the existing value with the requested value. */
    REPLACE,
}
