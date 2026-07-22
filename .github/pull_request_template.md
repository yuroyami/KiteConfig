## What changed

Describe the user-visible behavior and why the change is needed.

## Verification

- [ ] Focused tests cover the changed behavior and failure path.
- [ ] `./gradlew test validatePlugins --configuration-cache --configuration-cache-problems=fail` passes.
- [ ] Public API changes include KDoc, README/FEATURES/CHANGELOG updates, and an intentional ABI baseline update.
- [ ] Source-tree mutation changes remain explicit, bounded, no-follow, recoverable, and dry-run observable.
- [ ] Release/dependency changes update locks, verification metadata, SBOM expectations, and pinned workflow SHAs as applicable.
