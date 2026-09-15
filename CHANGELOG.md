# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-09-16

### Added

- First implementation of the native TestNG reporter. Registers itself through TestNG's
  `ServiceLoader` support, so installing the dependency is the whole setup — no
  `@Listeners`, no `-listener` flag, no `testng.xml` edit.
- Retries recorded as per-attempt history with flakiness. TestNG delivers a retried
  failure to `onTestSkipped` with `wasRetried()` set, so a reporter that trusted the
  callback name would report two skips and a pass, losing the failures entirely.
- Distinct statuses for timeout (`onTestFailedWithTimeout`) and configuration failure
  (`onConfigurationFailure`, emitted as `[config] <FQCN>#<method>`), neither of which
  JUnit XML can express.
- DataProvider rows as separate cases — identity is `class#method(params)`, so rows do
  not collapse into one record.
- Author-facing metadata through `Qualflare`: labels, tags, links, priority, description,
  parameters and masked parameters, nested steps, and file attachments. No method throws,
  and every call is inert when no reporter is listening.

[Unreleased]: https://github.com/Qualflare/qualflare-testng/compare/v0.1.0...main
[0.1.0]: https://github.com/Qualflare/qualflare-testng/releases/tag/v0.1.0
