# Security policy

## Supported versions

Security fixes are made on the default branch and the latest published release
line. Older release lines may receive a fix only when the maintainer explicitly
announces extended support. Upgrade to the newest release before reporting a
problem that is already resolved there.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability. Use the repository's
[private security advisory form](https://github.com/yuroyami/kmp-ssot/security/advisories/new)
and include affected versions, impact, a minimal reproduction, and any proposed
mitigation. Never attach real signing keys, tokens, credentials, private project
files, or customer data.

The maintainer will acknowledge a complete report as soon as practical, keep the
reporter informed while impact and a fix are assessed, and coordinate disclosure
after patched artifacts are available. Please allow time for a safe release before
publishing exploit details.

## Scope

High-priority reports include path traversal or symlink escapes, overwriting or
deleting unowned source, unsafe parser behavior, dependency/workflow compromise,
credential exposure, and generated-code injection. A task doing exactly what an
explicitly documented destructive migration authorizes is not itself a security
issue, but bypassing its containment, ownership, backup, or dry-run contract is.
