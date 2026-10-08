# Code readability and verification plan

Status: Initial mechanisms implemented. Human assessment of readability and targeted
structural refactoring remain ongoing. The proposal below records the intent;
[tool usage and current policy](tools/style/README.md) describe the installed gate.

## Initial implementation

`setup-style.ps1`, `format.ps1`, `style-check.ps1`, `style-test.ps1`, and `verify.ps1`
now provide pinned tool setup, whole-tree formatting, structural regression checks,
gate regression tests, and the existing scene/engine verification chain. `AGENTS.md`
records the completion command and readability expectations. The test harness now
throws after reporting failures so child processes return nonzero.

The selected tools are google-java-format 1.24.0 with AOSP layout, Checkstyle 13.0.0,
and PMD 7.7.0 in Java 23 preview mode. Full-tree parser checks required explicit
classes for two entry points, an explicitly qualified inherited type, and a named
camera-constructor validation helper. These compatibility changes preserve behavior
and are documented separately from formatting in the tool guide.

All Java sources and tests were formatted. The initial structural baseline contains
1380 finding groups; new findings, increased numeric complexity/size, and additional
copies fail verification. Resolved or reduced findings require explicit pruning.
The gate never automatically accepts new exceptions. CI configuration and architectural
libraries remain deferred. Initial thresholds are operational starter values; human
review must still judge whether future refactors improve understanding.

The stashed implementation was restored after editor merge `36f7bf3`. Its current
baseline contains 2012 finding groups, with the merged branch's existing debt
documented in [the baseline migration record](tools/style/baseline-migration.md).
The rule set and thresholds remain unchanged.

## Intent

The human maintainer needs to be able to read, explain, and safely change the code
produced by coding agents. Passing compilation and behavioral tests is necessary,
but does not establish that the implementation is understandable.

Make readability a repeatable part of development: apply consistent formatting,
enforce a small set of useful structural rules, and review the meaning of names,
abstractions, and explanations. Apply the same expectations to human and agent edits.
Preserve rendering behavior, numerical accuracy, concurrency guarantees, and measured
performance while improving how the implementation communicates those things.

Success means a maintainer can follow a change without first expanding compressed
statements, decoding unrelated responsibilities, or reconstructing hidden assumptions.
Lower warning counts alone are not sufficient evidence of success.

## Verification before implementation

At planning time, the repository already had the following verification entry points.
A new build system was not required to introduce readability checks.

| Entry point | Current responsibility |
| --- | --- |
| [build](build) | Compile sources and tests with Java 23 preview features; report compiler warnings. |
| [test](test) | Run one named test suite using compiled classes. |
| [engine-build.ps1](engine-build.ps1) | Reject selected engine imports and compile the engine plus independent consumers. |
| [engine-check.ps1](engine-check.ps1) | Run the engine boundary build, compile all sources/tests, run seven selected suites, and render a headless example. |
| [scene-check.ps1](scene-check.ps1) | Run engine checks, seven additional selected suites, and a scene document example. |

At that point, there was no checked-in formatter/linter configuration or CI workflow.
The selected suites did not constitute a complete project test suite.
[SuiteRunner](tst/harness/SuiteRunner.java) logged test failures without returning a
failing process status; the PowerShell verification scripts compensated by scanning logs.

Readability issues are visible in
[FocusController](src/engine/FocusController.java), including grouped declarations,
multiple operations on one line, and nested conditional expressions, and in
[ViewportCommand](src/scenes/viewport/ViewportCommand.java), where command branches
compress validation and state changes. These are useful pilot files for assessing
whether the proposed rules improve actual reading.

## Proposed commands and integration

Introduce three PowerShell entry points with shared configuration and pinned tools:

| Proposed command | Responsibility |
| --- | --- |
| `format.ps1` | Apply the selected formatter to Java source and test files. |
| `style-check.ps1` | Check formatting, Checkstyle rules, and PMD rules without modifying source files. |
| `verify.ps1` | Run style checks, then the existing scene/engine verification chain. |

Keep compilation available independently for quick iteration. Use `verify.ps1` as
the documented completion gate for agent changes and as the CI entry point when CI
is added. It should invoke `scene-check.ps1`, which already invokes engine checks,
without running the same suites twice. Document remaining coverage gaps and continue
to require relevant tests for the changed behavior.

The existing `engine-check.ps1` could call the shared style check as a smaller first
integration. The preferred final arrangement is the explicit top-level wrapper so
engine-specific checks do not own repository-wide style policy.

Checks must return nonzero on violations or tool failures, identify the affected
file and rule, and preserve detailed reports under `out/`. Missing tools and parser
errors must never be treated as successful verification. Use repository-relative
paths and the existing JDK selection convention; any WSL wrapper should invoke the
same configuration rather than define a second policy.

## Automatic formatting

Use [google-java-format](https://github.com/google/google-java-format) as the initial
candidate, with its four-space AOSP option to stay closer to existing indentation.
The tool provides command-line apply and check modes and IntelliJ integration.
Its layout algorithm is deliberately opinionated, so review representative output
before selecting the version and style permanently.

Pin the formatter version and options. Verify that the selected release and runtime
handle this repository's Java 23 preview syntax before reformatting broadly. Keep
the editor and command-line versions aligned. The check command must detect drift;
only the explicit formatting command should rewrite files.

Apply any initial bulk formatting in a separate, behavior-preserving commit.
Keep structural refactoring out of that commit so reviewers can distinguish layout
changes from logic changes. Reapplying the formatter must produce no further changes.

## Mechanical readability rules

Use a focused [Checkstyle configuration](https://checkstyle.org/checks.html), initially
covering required braces for control flow, one statement per line, one variable per
declaration, and explicit imports instead of wildcard imports. Let the formatter
own whitespace and wrapping; avoid competing layout rules.

Use a focused [PMD configuration](https://pmd.github.io/pmd/pmd_rules_java_design.html)
for cognitive complexity, deeply nested control flow, oversized methods, and excessive
parameter counts. Cognitive complexity estimates how difficult a method's branches
and nesting are to follow. Treat these measurements as prompts to improve the code,
not as complete definitions of good design.

Select thresholds after inspecting reports on representative code. Avoid enabling
every bundled rule or extracting trivial methods merely to satisfy a number. A method
should describe a coherent step; a class should have an understandable responsibility.
Verify parser support for the actual source syntax in both tools.

## Human readability expectations

After adoption, record a concise readability contract in `AGENTS.md` and link this
plan. The proposed contract is:

- Use names that explain the role of a value. Conventional mathematical names such
  as `x`, `y`, or `u` are appropriate when their meaning is clear in the local context.
- Separate validation, calculation, and state publication into recognizable steps.
  Prefer named intermediate values when an expression combines several concepts.
- Prefer straightforward control flow to nested ternaries or compressed branches.
- Extract methods around meaningful operations and keep related state and behavior
  together. Avoid adding layers that only move complexity between files.
- Explain units, coordinate spaces, ownership, thread/lock requirements, and numerical
  assumptions where they affect correct use. Comments should explain reasons and
  invariants rather than narrate obvious syntax.
- Explain unusual performance-driven choices and link measurements or relevant
  benchmarks. Preserve allocation-free tracing and publication/lease guarantees.
- Keep changes reviewable. Do not mix unrelated cleanup into a functional change.

Review must still assess whether names, boundaries, and explanations help a human
understand the implementation. A green static-analysis report cannot make that judgment.
Review pilot output with the maintainer before declaring the readability policy settled.

## Architectural rules and test failures

Retain the existing engine import boundary and independent consumer compilation.
If architectural rules grow, consider
[ArchUnit](https://www.archunit.org/) for executable dependency and layering checks.
That is a later option; the initial readability gate does not require another library.

Make the test harness communicate failure through a nonzero process exit status
while preserving useful per-test diagnostics. Validate both passing and deliberately
failing suites through the actual command-line entry points. Keep the existing log
checks until the corrected failure behavior has been demonstrated.

## Gradual adoption and exceptions

Inventory existing structural violations before making them blocking. Introduce a
reviewable baseline for accepted existing findings and block new findings or increases
in an existing method's measured violation. Remove resolved entries as work proceeds.
Define stable matching by rule and code location or symbol so a line shift alone does
not appear to create a new issue. This baseline comparison is implementation work;
it must not be assumed to be a built-in capability of every selected tool.

Avoid excluding entire engine or test directories. Exceptions must be narrowly scoped
to a rule and code element, explain why the readable alternative is unsuitable, and
include performance evidence when performance is the reason. Do not silently expand
the baseline or disable rules to make verification pass. Review policy/configuration
changes explicitly alongside the code they affect.

Adopt formatting separately from the structural baseline. Prefer a dedicated initial
formatting commit followed by whole-repository format checks. If concurrent work makes
that disruptive, temporarily enforce formatting on changed files and record how and
when the remaining files will be brought under the same policy.

## Rollout and acceptance

1. **Pilot the tools.** Pin candidate versions, run them on representative source and
   test files, verify Java 23 compatibility, and review formatting and findings. Choose
   the final style and initial thresholds from that evidence.
2. **Add the shared checks.** Implement the commands, configuration, reports, and
   documented tool setup. Prove that clean files pass and representative formatting,
   rule, parser, and missing-tool failures fail with useful diagnostics.
3. **Establish adoption.** Separate bulk formatting from refactoring, record the
   structural baseline, and demonstrate that new violations fail while accepted old
   findings remain visible. Validate that resolved baseline entries are removed.
4. **Connect verification.** Add the top-level gate, correct test failure propagation,
   and document its use in `AGENTS.md`. Exercise the existing verification chain.
   When CI is introduced, run the same command and configure it as a required merge
   check; local hooks or editor settings alone are insufficient enforcement.
5. **Improve difficult code.** Refactor targeted problem areas in separate changes.
   Run relevant behavioral tests; measure performance when changes affect tracing or
   allocation patterns. Have the maintainer assess whether the result is easier to read.

Completion requires deterministic formatting, useful and reproducible rule reports,
reliable failure exit codes, a documented completion command, and a reviewable policy
for existing findings and exceptions. Passing checks must not conceal skipped suites
or imply full-project coverage that the scripts do not provide.

## Practices informing the proposal

[Spotless](https://github.com/diffplug/spotless) demonstrates separate formatter apply
and check tasks, build integration, and gradual adoption against a Git baseline.
It is a useful model if the project later adopts Maven or Gradle; direct command-line
tools fit the current scripts without requiring that migration. Checkstyle and PMD
provide distinct convention and design checks, while ArchUnit addresses architecture.
These mechanisms support the proposed separation of formatting, rules, behavioral
verification, and human review; they do not establish that tooling alone solves
maintainability.

## Scope of this proposal

The initial implementation selects the versions and starter thresholds documented
above. It does not migrate the build system, introduce broad bug-finder tooling,
rewrite the renderer, or configure CI enforcement. Structural cleanup proceeds in
targeted follow-up changes, preserving behavior and measured performance.
