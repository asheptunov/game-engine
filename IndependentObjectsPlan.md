# Independent editor objects (I1)

Implement in `C:/Users/andri/.codex/worktrees/scene-editor/RayTracingEngine`,
branch `codex/scene-editor`, starting at `bdec663`. Preserve unrelated readability
changes in the main checkout. This follow-up supersedes E7's shared-editing UX.

The user wants each object to have independent geometry and materials. Linking
objects is outside the editor's current scope. Immutable value storage and render
caches may still be reused internally; replacing one object's asset never edits
another object.

## Settled decisions

1. Every geometry-bearing editor node has distinct geometry and material asset
   identities. Starter scenes, creation, duplication and file loading uphold this.
   The generic engine may still represent explicitly shared assets.
2. `SceneEdit.duplicateSubtree` defaults to independent geometry and materials for
   every copied geometry-bearing node, including when source nodes share assets.
   Keep hierarchy, transforms and other components; publish as one transaction.
3. Normalize legacy aliases at editor ingress, on the existing I/O worker before
   EDT publication. Preserve node and element IDs, hierarchy, transforms and values.
   Choose the lowest node UUID to retain an original identity. Derive subsequent
   IDs deterministically from a versioned namespace, source asset and node IDs;
   reserve original IDs and resolve collisions with deterministic bounded salts.
   Repeated normalization is a no-op. Do not adopt a worker-owned SceneDocument.
4. Preserve unreferenced assets. Validate expanded persistence resource limits
   before publication, using centralized SceneFiles validation rather than copied
   limits. Reject oversized expansion atomically, retaining the old document,
   selection, history and file. Avoid unbounded serialized buffers for validation.
5. A successful load is clean with empty history, including legacy normalization.
   Saving uses the existing schema; generic engine file loading preserves sharing.
6. Remove editor sharing counts, Make unique controls and linking commands. Apply
   geometry/material changes to the selected object. Approximation remains optional
   and undoable, with wording describing only the selected object's shape.

## Delivery and acceptance

One implementation commit: `fix(editor): give objects independent geometry and materials`.

- [ ] New/starter objects and subtree duplicates own independent asset identities.
- [ ] Geometry and color edits on a duplicate leave originals/siblings unchanged;
      duplication and subsequent edits each undo/redo correctly.
- [ ] Loading old shared files yields deterministic independent assets and a clean
      baseline; collisions and oversized expansion are covered.
- [ ] Engine explicit sharing still round-trips unchanged outside the editor.
- [ ] UI, command help and application APIs contain no sharing/unique workflow.
- [ ] Relevant FAQ/API documentation reflects the changed duplication behavior.
- [ ] Full PS5.1 input/editor/transport gate passes; harness logs audited separately.
- [ ] Modified Java follows the new readability guidance. The editor branch lacks
      main's uncommitted style tools: record scoped formatter/check evidence without
      copying unrelated work or claiming main verification covers editor changes.
- [ ] Independent review and root verification clear; native editor QA remains separate.

Implementer owns product changes; root owns review, verification, specification and
state updates. Do not commit before final review approval. Do not open native windows.
