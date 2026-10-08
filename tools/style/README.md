# Readability tools

Run these commands from PowerShell at the repository root:

```powershell
./setup-style.ps1
./format.ps1
./verify.ps1
```

Set `JAVA_HOME` to JDK 23 if it is not installed at
`$env:USERPROFILE/.jdks/openjdk-23.0.1`. Setup downloads the versions and SHA-256
checksums recorded in [tools.json](tools.json) into ignored `out/style-tools/`.
Normal formatting and verification are offline and reject missing or corrupted archives.
Rerun setup to restore tools. No Maven, Gradle, Python, or editor plugin is required.
The launch helper suppresses child consoles and handles repository paths containing spaces.

The full gate is `./verify.ps1`. It runs style checks followed by `scene-check.ps1`,
which includes `engine-check.ps1`. These are 14 selected suites and two headless
consumer examples, not the complete test inventory. Run relevant additional suites
for each functional change. GUI/editor acceptance remains separate. CI is not yet
configured; when introduced, it should run this same command as a required check.

## Formatting

google-java-format 1.24.0 uses `--aosp` for four-space indentation. `format.ps1`
rewrites all Java files in `src/` and `tst/`; `style-check.ps1` only checks them.
Both use exactly the same options. Match that version and AOSP style if configuring
an IntelliJ formatter plugin. Formatting is enforced for the whole tree, without
grandfathered files. Java uses LF line endings, enforced by both the gate and
`.gitattributes` for consistent Windows/WSL results. Keep bulk formatting in a separate
commit when shipping.

## Rules and baseline

Checkstyle 13.0.0 checks required braces, multiple statements per line, multiple
variable declarations, and wildcard imports. PMD 7.7.0 runs with `java-23-preview`
and these initial thresholds:

| Rule | Reporting threshold |
| --- | --- |
| Cognitive complexity | 15 |
| Nested if statements | Depth 3 |
| Method NCSS count | 60 statements/declarations, independent of wrapping |
| Class NCSS count | 1500 |
| Parameter list | 8 parameters |

The initial pilot recorded 1380 finding groups: 893 braces, 265 declarations,
116 wildcard imports, 67 cognitive complexity, 21 parameter lists, 16 NCSS counts,
and 2 nested-if findings. Repeated identical findings are counted inside a group.
This is existing debt accepted for gradual cleanup, not a declaration that the code
meets the readability goals. These conservative starter thresholds still need human
assessment on future refactors; they are not performance or correctness guarantees.

After restoring the work over the editor merge, the current baseline contains 2012
groups. [The migration record](baseline-migration.md) explains the 687 new/increased
groups already present in merged main and the independent source comparison used
to establish that they were not introduced by the restoration. Rules are unchanged.

[baseline.json](baseline.json) records each group's file, rule, code anchor, diagnostic,
count and numeric ceiling. Line numbers are only report context, never identity.
Checkstyle anchors normalize whitespace on the reported source line. PMD complexity
and size anchors use the enclosing class and reported signature, distinguishing
overloads; measured values may only decrease. Parameter-list and nested-if anchors
use the reported declaration/block, so editing an already exceptional element may
require explicit review even if an edit improves it. Moving identical code within
one file can retain its allowance; this mechanism is a regression guard, not a proof
of semantic equivalence or a replacement for review.

New identities, increased counts and increased numeric ceilings fail. Parser errors,
tool failures and incomplete reports also fail. Reports and current findings are in
`out/style/`; the first failing stage stops the gate. Fix formatting before reviewing
structural findings.

After fixing or reducing existing findings, run:

```powershell
./style-check.ps1 -PruneBaseline
```

This reruns checks and only shrinks the baseline. It refuses to accept new/increased
findings. Review and include the resulting diff. There is deliberately no automatic
accept-new command. A necessary exception requires a reviewed edit to the specific
baseline entry using the current report, with its rationale recorded in the change
description and near the code when it describes a lasting invariant. For hot paths,
include benchmark evidence. Do not exclude directories or regenerate the baseline
to bypass a failed check. Changes to rule configuration and tool versions are policy
changes and require the same explicit review.

## Compatibility adjustments

The full-tree pilot found that the selected formatter and PMD could not parse the
implicit-class forms of `Main` and `Workbench`. They now use explicit classes with
the same main methods; Workbench's migration tests remain disabled. `PixelRaster`
qualifies its inherited `Raster.Readable` type to avoid a PMD resolution error.
Checkstyle could not parse the constructor's statements before `this(...)` in
`CameraProjection`, even in 13.0.0. Its validation now runs in a named static helper
passed to the delegating constructor, preserving validation order and behavior.
Other Java 23 preview usage remains enabled in the build and PMD.

## Verification of the gate

`./style-test.ps1` runs tools against an isolated fixture repository under `out/`,
including a path containing spaces. It checks formatting idempotence and drift,
real Checkstyle and PMD violations, baseline line-shift stability, shrinking and
increasing metrics, refusal to accept new debt while pruning, stale entries, malformed
source, missing/corrupted tools, and real passing/failing harness child processes.
It does not mutate real sources or the real baseline. Logs remain under `out/style-tests/`.

`SuiteRunner` reports all test results before throwing on failures. Both the Bash
`test` launcher and PowerShell verification scripts therefore receive a failing
process status. Existing log scans remain as an additional safeguard.

On restricted Windows sandboxes, the JDK can report `Cannot close compiler resources`.
The pilot engine build succeeded outside that sandbox. Follow the repository's
Windows access instructions rather than interpreting this as a source failure.
