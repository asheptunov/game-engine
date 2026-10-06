# Engine/editor dispatch state

Scope: E1–E5. E6 deferred. Plan: [EngineImplementationPlan.md](EngineImplementationPlan.md).

| id | title | deps | gui-qa | status | lane | commit | notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | Engine extraction and independent consumer | — | no | ready | — | — | E1/E2 |
| B | Scene graph, persistence, meshes and queries | A | no | blocked | — | — | E3/E4 |
| C | Playable scene editor | B | yes | blocked | — | — | E5 |

Admission: C: has ~83 GiB free; reserve 8 GiB and budget 6 GiB per active lane.
Use one writing lane at a time because nodes depend on preceding APIs. Independent
verification happens before integration. Preserve existing uncommitted `Goals.md` edits.
