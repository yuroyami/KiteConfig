# Contributing

Thank you for improving KiteSSOT. Open an issue before you change the public API
or the behavior. That way we agree on platform scope, defaults, compatibility and
migration before anyone writes code.

## Development baseline

- Use the checked-in Gradle wrapper and a supported JDK.
- Keep the integrations with AGP (the Android Gradle plugin) and KGP (the Kotlin
  Gradle plugin) `compileOnly`. Real consumer fixtures prove that the plugin
  loads AGP and KGP correctly.
- Preserve unrelated working-tree changes. Never commit generated credentials,
  signing material, local SDK paths, recovery files or build output.
- A public API change needs accurate KDoc, updates to README, FEATURES and
  CHANGELOG, and a deliberate change to the checked-in Kotlin ABI baseline. The
  ABI is the binary shape of the public API.

Run focused tests while you iterate, then run the full local check that CI runs:

```shell
./gradlew test agpCompatibilityTest validatePlugins \
  --configuration-cache --configuration-cache-problems=fail
./gradlew checkKotlinAbi
```

Run that same configuration-cache command twice when you change the plugin
lifecycle, or when you change how tasks depend on each other. Confirm that the
second run reuses the cache.

A release or build change must also regenerate these, wherever they apply:

- the dependency locks
- the verification metadata
- the SBOMs, which are the machine-readable lists of the build's dependencies
- the POMs
- the publication artifacts

Review each regenerated file before you commit it.

## Safety requirements

Every source-tree mutation must meet all nine conditions below:

- **Opt-in.** It runs only when the user turns it on.
- **Target-selected.** It changes only the files the configuration names.
- **Bounded.** It stays inside the directory it was given.
- **No-follow.** It does not follow symlinks out of that directory.
- **Fully planned before it writes.** It computes the whole plan first, then
  writes.
- **Safe for files it does not recognize.** It must leave them alone.
- **Recoverable.** It must write durable recovery and provenance records wherever
  it takes ownership of a file.
- **Reversible.** It must roll back without overwriting a newer edit made outside
  the build.
- **Legible.** It must produce dry-run or diagnostic output that a reader can act
  on.

Add tests for malformed input, ambiguous input, races and rollback. Also test
idempotence: running the task twice must give the same result. Do not test only
the case where everything works.

## Pull requests

Keep commits reviewable, and explain the outcome for the developer using the
plugin. Complete the pull request template with the exact commands you ran and
the compatibility environments you tested.

Do not update the ABI, the locks or the verification metadata just to make a
failing check pass. First work out why each of those files changed.
