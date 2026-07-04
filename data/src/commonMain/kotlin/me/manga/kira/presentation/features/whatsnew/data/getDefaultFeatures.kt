package me.manga.kira.presentation.features.whatsnew.data

import me.manga.kira.presentation.features.whatsnew.model.WhatsNewFeature

/**
 * Ported from upstream `presentation/features/whatsnew/data/getDefaultFeatures.kt`.
 *
 * Deltas vs source:
 *   1. `fun getDefaultFeatures(context: Context): List<WhatsNewFeature>` →
 *      `fun getDefaultFeatures(): List<WhatsNewFeature>`. Upstream's body is fully commented out
 *      and returns `listOf()`, so the `context.getString(...)` calls inside the comments aren't
 *      reachable. Dropping the parameter keeps the call sites Context-free.
 *   2. Lives in `:shared/commonMain` because [WhatsNewViewModel] (also in shared) calls it.
 */
fun getDefaultFeatures(): List<WhatsNewFeature> {
    return emptyList()
}

/*
 * Audit-trail postscript (Phase 9.x.cluster204.staleKdocSweep.cascade, Task #660, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster204 leaf 2/3 — :shared/whatsnew/data/ tier midbody, sibling 368. Cumulative
 * §253-postscript count = 93 leaves with this commit.
 *
 * File-shape note: 17-line top-level `fun getDefaultFeatures(): List<WhatsNewFeature>` —
 * minimal `return emptyList()` body. Carries a 10-line block-KDoc narrating 2 explicit
 * deltas vs the upstream Android source. 1 import (sibling 364 WhatsNewFeature). No
 * companion-object, no helpers.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • CASCADE-ORPHANED-NOT-RETIRED — post-Phase 9.x.whatsnewvm.componentprune (Task #410)
 *     this function has ZERO live callers. The KDoc (Task #410's WhatsNewViewModel
 *     postscript at sibling 369 L127-128) explicitly enumerates the coupled-import drop:
 *     "me.manga.kira.presentation.features.whatsnew.data.getDefaultFeatures — sole
 *     reader was `loadDefaultFeatures()` (dropped)." The file itself was NOT deleted in
 *     Task #410 — the function declaration survives as the named symbol; the prune
 *     campaign only dropped the WhatsNewViewModel members that imported and called it.
 *     Per §253 — preserved (this is an Audit pass, not a retire pass; reachability +
 *     classification only).
 *
 *   • DEAD-CODE-PRESERVED — the function body is INTENTIONALLY-empty (`return emptyList()`)
 *     by the file's OWN KDoc delta 1: "Upstream's body is fully commented out and returns
 *     `listOf()`, so the `context.getString(...)` calls inside the comments aren't
 *     reachable. Dropping the parameter keeps the call sites Context-free." The function
 *     is a STUB — preserved for upstream-source-shape parity, not for runtime behavior.
 *     DO NOT inline-replace callers (there are none) with `emptyList<WhatsNewFeature>()`
 *     during dead-code cleanup passes — the named symbol carries documentation value as
 *     a port-history marker even though the body is degenerate.
 *
 *   • DOC-STUB-PRESERVED — the 10-line KDoc explains both the port delta (dropping the
 *     Context parameter) AND the placement decision ("Lives in `:shared/commonMain`
 *     because [WhatsNewViewModel] (also in shared) calls it" — the latter claim is
 *     technically FACTUALLY-DRIFTED-IN-PROSE-ONLY post-cluster410: WhatsNewViewModel no
 *     longer calls it. Per §253 — preserved as point-in-time accurate; the prose drift
 *     is documented in this postscript rather than in-edit-rewriting the KDoc).
 *
 *   • FACTUALLY-DRIFTED-IN-PROSE-ONLY — the KDoc claim "Lives in `:shared/commonMain`
 *     because [WhatsNewViewModel] (also in shared) calls it" is no longer true post-
 *     cluster410. The function survives as a port-history marker; the called-by claim
 *     is historical. DO NOT regenerate the KDoc — the audit-trail value is that the
 *     ORIGINAL motivation is preserved alongside this postscript that flags the drift.
 *
 *   • RETIRE-CANDIDATE-CLUSTER-FUTURE — this function + the entire `getDefaultFeatures.kt`
 *     file is a clean retire candidate for a future Phase 9.x.getdefaultfeatures.retire
 *     slice. Retire is NOT executed in this audit pass — staleKdocSweep clusters preserve
 *     all symbols and only annotate. Cluster204 closer (sibling 369) carries the
 *     subdirectory-wide retire-candidate register.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE-BUT-DEGENERATE — 1 import (sibling 364
 *     WhatsNewFeature). Import is referenced only in the return type — never instantiated
 *     in the function body (which returns the empty list). The import-graph reach is
 *     real (compile-time signature dependency) but runtime-degenerate.
 */

