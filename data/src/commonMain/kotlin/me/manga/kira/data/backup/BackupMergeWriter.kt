package me.manga.kira.data.backup

import me.manga.kira.data.backup.model.BackupChapter
import me.manga.kira.data.backup.model.BackupFile
import me.manga.kira.data.identity.AcceptedCatalogToken
import me.manga.kira.data.local.dao.BackupChapterUpdate
import me.manga.kira.data.local.dao.BackupMangaUpdate
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.ReaderLegacyCleanupEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.mapper.savedIdentity
import me.manga.kira.data.repository.progress.CapturedLegacyProgress
import me.manga.kira.data.repository.progress.LegacyProgressReconciler
import me.manga.kira.data.repository.progress.LegacyProgressSettings
import me.manga.kira.data.repository.progress.LegacyProgressTransfer
import me.manga.kira.data.repository.progress.ProgressOwnerSession
import me.manga.kira.data.repository.progress.ProgressOwnerTransactions
import me.manga.kira.data.repository.progress.identifies
import me.manga.kira.domain.model.backup.BackupScope
import me.manga.kira.domain.model.backup.isValid
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.model.identity.WorkLocator

/**
 * Backup's owned Room boundary. Composition supplies the same real owner writer and Settings gate
 * as Reader, after readiness. Failures escape the whole writer before the repository maps them.
 * No Reader session/handle is fabricated, and legacy cleanup follows only committed receipts.
 */
class BackupMergeWriter(
    private val owners: ProgressOwnerTransactions,
    private val settings: LegacyProgressSettings,
) {
    private val reconciler = LegacyProgressReconciler(owners, settings)

    internal suspend fun batch(
        document: BackupFile,
        admittedHistory: List<HistoryItemD> = document.history.map { it.toEntity(resolvedMangaId = null) },
    ): BackupImportBatch = owners.write {
        val batch = planBackupImport(document, policy, admittedHistory)
        val planner = backupPlanner()
        requireSeparateBackupTargets(batch.groups.map { planner.plan(it) })
        batch
    }

    internal suspend fun export(
        scope: BackupScope,
        shouldStop: () -> Boolean,
    ): List<BackupOwnedExport>? {
        requireBackup(scope.isValid, "backup_scope")
        val (token, observed) = owners.write { exportPlans(scope) }
        val result = mutableListOf<BackupOwnedExport>()
        for (plan in observed) {
            if (shouldStop()) return null
            result += withPrepared(token, plan) { current ->
                val chapters = current.local.chapters.map { row ->
                    BackupExportChapter(row, readPosition(ChapterLocator(current.work.destination, row.url)))
                }
                BackupOwnedExport(current.local.manga, chapters, current.local.history)
            }
        }
        return result
    }

    private suspend fun ProgressOwnerSession.exportPlans(
        scope: BackupScope,
    ): Pair<AcceptedCatalogToken, List<BackupImportPlan>> {
        val planner = backupPlanner()
        val rows = when (scope) {
            BackupScope.FullLibrary -> storage.savedCatalog.getAllSavedManga()
            is BackupScope.Mangas -> scope.keys.map {
                planner.savedWork(it.work) ?: throw BackupOwnershipException("backup_scope_owner_missing")
            }
            BackupScope.Invalid -> throw BackupOwnershipException("backup_scope")
        }
        requireBackup(rows.map { it.id }.toSet().size == rows.size, "backup_duplicate_selection")
        val history = if (scope is BackupScope.FullLibrary) {
            storage.savedCatalog.getAllHistoryOnce()
        } else {
            planner.historyForScope(rows)
        }
        val document = BackupFile(
            mangas = rows.map { it.toBackup(emptyList()) },
            history = history.map { it.toBackup() },
        )
        val batch = planBackupImport(document, policy, history)
        val plans = batch.groups.map { planner.plan(it) }
        requireSeparateBackupTargets(plans)
        return policy.token to plans
    }

    internal suspend fun importGroup(batch: BackupImportBatch, group: BackupImportGroup): BackupOwnedMerge {
        requireBackup(group in batch.groups, "backup_unknown_group")
        val observed = owners.write {
            requireBackup(policy.token == batch.token, "backup_selection_changed")
            backupPlanner().plan(group)
        }
        return withPrepared(batch.token, observed) { merge(it) }
    }

    private suspend fun <T> withPrepared(
        token: AcceptedCatalogToken,
        observed: BackupImportPlan,
        block: suspend ProgressOwnerSession.(BackupImportPlan) -> T,
    ): T {
        // Only actual incoming/stored raw URLs are captured. Never synthesize old-host keys.
        val captured = observed.chapters.keys.associateWith { settings.capture(it.chapterUrl) }
        val (result, receipts) = owners.write {
            requireBackup(policy.token == token, "backup_selection_changed")
            val current = backupPlanner().plan(observed.group)
            current.requireSameOwners(observed)
            requireLegacyOwnerBeforeInsert(current, captured)
            val receipts = transferLegacy(current, captured)
            block(current) to receipts
        }
        receipts.forEach { reconciler.finish(it) }
        return result
    }

    private suspend fun ProgressOwnerSession.requireLegacyOwnerBeforeInsert(
        plan: BackupImportPlan,
        captured: Map<ChapterLocator, CapturedLegacyProgress?>,
    ) {
        val additions = plan.group.manga?.chapters.orEmpty().map {
            plan.chapters.getValue(ChapterLocator(plan.group.work, it.url))
        }.filter { it.saved == null }
        for ((raw, value) in captured) {
            if (value == null) continue
            val gainsOwner = additions.any {
                policy.identifies(
                    WorkLocator(raw.work.api, raw.chapterUrl),
                    WorkLocator(it.destination.work.api, it.destination.chapterUrl),
                )
            }
            if (!gainsOwner) continue
            // Even native data cannot license an unknown legacy payload. Otherwise this insert
            // could make a later prepare manufacture saved ownership and destructively clean it.
            requireBackup(storage.cleanup.find(value.key, value.payload) != null, "backup_legacy_owner_unproven")
        }
    }

    private suspend fun ProgressOwnerSession.transferLegacy(
        plan: BackupImportPlan,
        captured: Map<ChapterLocator, CapturedLegacyProgress?>,
    ): List<ReaderLegacyCleanupEntity> = captured.mapNotNull { (raw, value) ->
        // New imported rows must never manufacture a legacy owner. The transfer also proves
        // uniqueness against ALL persisted saved works/children before copying or receipting.
        if (value == null || plan.chapters.getValue(raw).owner?.chapterId == null) {
            null
        } else {
            LegacyProgressTransfer(this).commit(raw, value).receipt
        }
    }.distinct()

    private suspend fun ProgressOwnerSession.merge(plan: BackupImportPlan): BackupOwnedMerge {
        val manga = mergeManga(plan)
        val chapters = linkedMapOf<ChapterLocator, SavedChapterEntity>()
        var added = 0
        plan.group.manga?.chapters?.forEach { incoming ->
            val raw = ChapterLocator(plan.group.work, incoming.url)
            val before = plan.chapters.getValue(raw)
            val row = mergeChapter(checkNotNull(manga), incoming, before.saved, before.destination.chapterUrl)
            chapters[raw] = row
            if (before.saved == null) added++
            restorePage(raw, incoming, before.saved)
        }
        mergeHistory(plan, manga)
        return BackupOwnedMerge(
            manga,
            plan.group.manga != null && plan.local.manga == null,
            chapters,
            added,
            if (plan.group.history == null) 0 else 1,
        )
    }

    private suspend fun ProgressOwnerSession.mergeManga(plan: BackupImportPlan): SavedMangaEntity? {
        val incoming = plan.group.manga?.toEntity() ?: return plan.local.manga
        val local = plan.local.manga
        if (local == null) {
            val row = incoming.copy(url = plan.work.destination.url)
            val id = storage.savedCatalog.insertMangaRow(row)
            requireBackup(id > 0, "backup_manga_insert_conflict")
            return row.copy(id = id)
        }
        val merged = BackupMergePolicy.mergeManga(local, incoming)
        val count = storage.savedCatalog.updateMangaEngagement(
            BackupMangaUpdate(
                local.id,
                merged.isLiked,
                merged.isWatchingNow,
                merged.lastOpenTimestamp,
                merged.savedTimestamp,
            ),
        )
        requireBackup(count == 1, "backup_manga_update_conflict")
        return merged
    }

    private suspend fun ProgressOwnerSession.mergeChapter(
        manga: SavedMangaEntity,
        incoming: BackupChapter,
        local: SavedChapterEntity?,
        destination: String,
    ): SavedChapterEntity {
        val draft = incoming.toEntity().copy(mangaId = manga.id, url = destination)
        if (local == null) {
            val id = storage.savedCatalog.insertChapterRow(draft)
            requireBackup(id > 0, "backup_chapter_insert_conflict")
            return draft.copy(id = id)
        }
        val merged = BackupMergePolicy.mergeChapter(local, draft)
        val count = storage.savedCatalog.updateChapterReading(
            BackupChapterUpdate(local.id, merged.isRead, merged.isBookmarked, merged.lastReadDate),
        )
        requireBackup(count == 1, "backup_chapter_update_conflict")
        return merged
    }

    private suspend fun ProgressOwnerSession.restorePage(
        raw: ChapterLocator,
        incoming: BackupChapter,
        localBefore: SavedChapterEntity?,
    ) {
        val page = incoming.resumePage ?: return
        val resolved = resolveChapter(raw)
        val current = anchors.read(resolved)
        val cleared = current?.pageIndex == null &&
            ((resolved.work.anchor?.workGeneration ?: 0L) != 0L || (resolved.anchor?.chapterGeneration ?: 0L) != 0L)
        if (cleared || (localBefore == null && current?.pageIndex != null)) return
        val eligible = BackupMergePolicy.shouldRestoreResumePage(
            localBefore == null,
            incoming.lastReadDate,
            localBefore?.lastReadDate ?: 0L,
            current?.pageIndex,
        )
        if (!eligible) return
        // Explicit import may ensure missing anchors, but never resets epochs or reacquires on CAS failure.
        val snapshot = anchors.ensure(resolved)
        requireBackup(storage.progress.savePosition(snapshot, page), "backup_progress_write_conflict")
    }

    private suspend fun ProgressOwnerSession.mergeHistory(plan: BackupImportPlan, manga: SavedMangaEntity?) {
        val item = plan.group.history ?: return
        val raw = ChapterLocator(WorkLocator(item.api, item.mangaUrl), item.chapterUrl)
        val chapterUrl = if (item.chapterUrl.isBlank()) item.chapterUrl else resolveChapter(raw).destination.chapterUrl
        // Preserve the timestamp converted during whole-archive admission, even if the timezone
        // changes after an earlier group commits. Only receiver-local identity is assigned here.
        val incoming = checkNotNull(plan.group.admittedHistory).copy(
            mangaId = manga?.id ?: 0L,
            mangaUrl = plan.work.destination.url,
            chapterUrl = chapterUrl,
        )
        val local = plan.local.history
        if (local == null) {
            requireBackup(storage.savedCatalog.insertHistoryRow(incoming) > 0, "backup_history_insert_conflict")
        } else if (BackupMergePolicy.shouldReplaceHistory(local, incoming)) {
            val count = storage.savedCatalog.updateHistoryPosition(
                local.id,
                incoming.chapterUrl,
                incoming.chapterTitle,
                incoming.lastReadDate,
                incoming.lastReadPage,
                incoming.totalPages,
            )
            requireBackup(count == 1, "backup_history_update_conflict")
        }
    }

    /** Revalidate the scoped saved owner before the separate artifact publisher claims custody. */
    internal suspend fun checkDownloadOwner(
        manga: SavedMangaEntity,
        chapter: SavedChapterEntity,
    ) = owners.write {
        val raw = ChapterLocator(manga.savedIdentity().locator, chapter.url)
        val current = backupPlanner().plan(BackupImportGroup(raw.work))
        requireBackup(current.local.manga?.id == manga.id, "backup_download_work_changed")
        val resolved = resolveChapter(raw)
        requireBackup(
            resolved.saved?.id == chapter.id && chapter.mangaId == manga.id,
            "backup_download_chapter_changed",
        )
    }
}
