# Scene documents and spatial query verification

E3–E4 add the reusable scene-document layer without changing the existing transport
estimator. The public API and file format are documented in [EngineApi.md](../../EngineApi.md).

## Gate

Run from the repository root on Java 23:

```powershell
./scene-check.ps1 -OutputDirectory out/scene-check
```

The 2026-10-06 B gate completed with exit 0. `engine-check.ps1` passed its engine-only
dependency build, all-source compilation, seven extraction/transport suites, and original
headless consumer. The B extension passed:

- `SceneDocumentTest`: 6 transaction, hierarchy, revision and history tests.
- `ScenePersistenceTest`: 6 strict XML, round-trip, fresh-JVM render and failure-safety tests.
- `SpatialQueryTest`: 4 open/closed mesh, BVH/brute, identity/tie and camera-pick tests.
- `MeshAccelerationTest`: 8 tests.
- `DielectricPathTest`: 11 tests.
- `VolumePathTest`: 8 tests.
- `ParallelTraceTest`: 4 tests.

The script inspects harness logs because the harness can exit zero after a failed test.
Logs and generated evidence are under `out/scene-check/`. The independent
`SceneDocumentDemo` produced `document-demo.scene.xml` and `document-demo.png`, reloaded
the saved document, and picked original source face 500 before rendering. The persistence
suite separately launched a fresh JVM and matched the pre-save seeded raw-image SHA-256.

The known pre-existing Caps-on synthetic-console path is explicitly skipped by this gate,
as directed by the implementation handoff. B does not change the production console.

## Scope and limits

- Scene XML V1 embeds assets. External references are diagnosed and deferred to a later
  schema; there is no project-root resolution in V1.
- Mesh V1 stores positions, triangle indices and source-face IDs. Shading uses faceted
  geometric normals and one material asset per node. Editable polygon topology, UVs,
  vertex normals and material slots belong to later modelling work.
- Closed-solid validation checks indexing, degeneracy, manifold edge pairing, winding,
  connectivity and positive signed volume. As before, it does not detect self-intersection.
- Parent transforms use the documented restricted positive-TRS policy. Unsupported shear,
  reflection, singular composition and nonuniform camera world scale fail atomically.
- The existing playground remains on the E1 legacy session adapter. The headless document
  demo proves the public E3/E4 consumer; the second interactive document consumer and its
  end-to-end picking workflow are the E5 editor gate.
