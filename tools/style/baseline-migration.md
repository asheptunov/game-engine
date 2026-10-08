# Baseline migration after the editor merge

The readability work was restored on 2026-10-08 after merge commit
`36f7bf3` introduced scene authoring, mesh tools, and input changes. The preserved
stash is `9be398bbbb77108d7c6952d60f125301547b320c`, based on `b92e929`.

This migration accepts findings already present in the merged main branch as
existing debt under the original gradual-adoption policy. It does not change any
rule, threshold, formatter version, or failure policy. Future new or increased
findings still fail verification.

The original 1380 finding groups became 2012: 680 identities were added and 48
removed. Seven retained identities had higher counts or metrics, and one decreased.
All 687 new or increased groups occur in paths changed by the merge.

| Current rule | Finding groups |
| --- | --- |
| Required braces | 1363 |
| Multiple variable declarations | 336 |
| Wildcard imports | 174 |
| Cognitive complexity | 91 |
| Parameter lists | 22 |
| NCSS size | 24 |
| Nested if statements | 2 |

To distinguish merge debt from restoration regressions, an independent source
snapshot was extracted from `36f7bf3` and formatted with the restored pinned tool.
The five prior compatibility/harness files were unchanged by the merge; their
preserved edits were overlaid on that reference. Every Java file in the restored
tree then matched the reference byte for byte. The five files are `src/Main.java`,
`src/engine/CameraProjection.java`, `src/rendering/PixelRaster.java`,
`tst/Workbench.java`, and `tst/harness/SuiteRunner.java`.

The current report replaces stale allowances and records only the findings of
that verified merged source tree. This is a one-time adoption migration, not an
automatic baseline reset mechanism. New exceptions still require narrow review;
ordinary cleanup uses `style-check.ps1 -PruneBaseline`.
