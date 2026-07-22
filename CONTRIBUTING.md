# Contributing

Thank you for improving kitessot. Start with an issue for material API or behavior
changes so platform scope, defaults, compatibility, and migration semantics are
agreed before implementation.

## Development baseline

- Use the checked-in Gradle wrapper and a supported JDK.
- Keep AGP/KGP integrations `compileOnly`; real consumer fixtures prove linkage.
- Preserve unrelated working-tree changes and never commit generated credentials,
  signing material, local SDK paths, recovery files, or build output.
- Public APIs need accurate KDoc, README/FEATURES/CHANGELOG updates, and an
  intentional Kotlin ABI baseline change.

Run focused tests while iterating, then the release-sized local gate:

```shell
./gradlew test agpCompatibilityTest validatePlugins \
  --configuration-cache --configuration-cache-problems=fail
./gradlew checkKotlinAbi
```

Run the identical configuration-cache command twice when changing plugin lifecycle
or task wiring and confirm the second invocation reuses the cache. Release/build
changes must also regenerate and review dependency locks, verification metadata,
SBOMs, POMs, and publication artifacts as applicable.

## Safety requirements

Source-tree mutations must be opt-in, target-selected, bounded, no-follow, and
planned completely before commit. They must preserve unknown files, use durable
recovery/provenance where ownership is taken, roll back without overwriting newer
external edits, and provide actionable dry-run or diagnostic output. Add tests for
malformed input, ambiguity, races/rollback, and idempotence—not only the happy path.

## Pull requests

Keep commits reviewable and explain the developer-facing outcome. Complete the PR
template with exact commands and compatibility environments. Do not update ABI,
locks, or verification metadata merely to silence a failing gate; review why every
change is expected.
