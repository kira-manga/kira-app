package me.manga.kira.data.backup

import me.manga.kira.data.backup.model.BackupFile
import me.manga.kira.data.backup.model.BackupHistoryItem
import me.manga.kira.data.backup.model.BackupManga
import me.manga.kira.data.identity.AcceptedCatalogToken
import me.manga.kira.data.identity.WorkAliasPolicy
import me.manga.kira.data.identity.WorkOwnerResolution
import me.manga.kira.data.identity.WorkOwnerResolver
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.mapper.savedIdentity
import me.manga.kira.data.repository.progress.ProgressOwnerSession
import me.manga.kira.data.repository.progress.ResolvedProgressChapter
import me.manga.kira.data.repository.progress.ResolvedProgressWork
import me.manga.kira.data.repository.progress.identifies
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.model.identity.WorkLocator

internal class BackupOwnershipException(reason: String) : IllegalStateException(reason)

internal fun requireBackup(condition: Boolean, reason: String) {
    if (!condition) throw BackupOwnershipException(reason)
}

internal data class BackupImportBatch(val token: AcceptedCatalogToken, val groups: List<BackupImportGroup>)

internal data class BackupImportGroup(
    val work: WorkLocator,
    val manga: BackupManga? = null,
    val history: BackupHistoryItem? = null,
    val admittedHistory: HistoryItemD? = null,
)

internal data class BackupLocalGroup(
    val manga: SavedMangaEntity?,
    val chapters: List<SavedChapterEntity>,
    val history: HistoryItemD?,
)

/** Discovery is only a capture hint. A fresh plan, including the full family scan, owns each write. */
internal data class BackupImportPlan(
    val group: BackupImportGroup,
    val work: ResolvedProgressWork,
    val local: BackupLocalGroup,
    val chapters: Map<ChapterLocator, ResolvedProgressChapter>,
) {
    fun requireSameOwners(observed: BackupImportPlan) {
        requireBackup(local.manga?.id == observed.local.manga?.id, "backup_work_changed")
        requireBackup(local.history?.id == observed.local.history?.id, "backup_history_changed")
        requireBackup(chapters.keys == observed.chapters.keys, "backup_capture_changed")
        chapters.forEach { (raw, resolved) ->
            requireBackup(resolved.owner == observed.chapters.getValue(raw).owner, "backup_chapter_changed")
        }
    }
}

/** Complete incoming families are rejected before an early group can manufacture persisted proof. */
internal fun planBackupImport(
    document: BackupFile,
    policy: WorkAliasPolicy,
    admittedHistory: List<HistoryItemD> = document.history.map { it.toEntity(resolvedMangaId = null) },
): BackupImportBatch {
    requireBackup(admittedHistory.size == document.history.size, "backup_history_admission")
    val groups = mutableListOf<BackupImportGroup>()
    val packedEntries = mutableSetOf<String>()
    document.mangas.forEach { manga ->
        val work = WorkLocator(manga.api, manga.url)
        requireBackupWork(work)
        requireBackup(groups.none { policy.identifies(it.work, work) }, "backup_duplicate_work")
        validateIncomingChapters(manga, policy, packedEntries)
        groups += BackupImportGroup(work, manga)
    }
    document.history.zip(admittedHistory).forEach { (item, admitted) -> groups.addHistory(item, admitted, policy) }
    val rawWorks = document.mangas.map { WorkLocator(it.api, it.url) } +
        document.history.map { WorkLocator(it.api, it.mangaUrl) }
    requireDistinctApis(rawWorks, "backup_cross_api_work")
    return BackupImportBatch(policy.token, groups)
}

private fun MutableList<BackupImportGroup>.addHistory(
    history: BackupHistoryItem,
    admitted: HistoryItemD,
    policy: WorkAliasPolicy,
) {
    val work = WorkLocator(history.api, history.mangaUrl)
    requireBackupWork(work)
    requireBackup(history.lastReadPage >= 0 && history.totalPages >= 0, "backup_history_page")
    requireBackup(
        admitted.api == history.api && admitted.mangaUrl == history.mangaUrl && admitted.chapterUrl == history.chapterUrl,
        "backup_history_admission",
    )
    val matches = withIndex().filter { policy.identifies(it.value.work, work) }
    requireBackup(matches.size <= 1, "backup_duplicate_work")
    val match = matches.singleOrNull()
    if (match == null) {
        add(BackupImportGroup(work, history = history, admittedHistory = admitted))
    } else {
        requireBackup(match.value.history == null, "backup_duplicate_history")
        this[match.index] = match.value.copy(history = history, admittedHistory = admitted)
    }
}

private fun validateIncomingChapters(
    manga: BackupManga,
    policy: WorkAliasPolicy,
    packedEntries: MutableSet<String>,
) {
    val seen = mutableListOf<WorkLocator>()
    manga.chapters.forEach { chapter ->
        val locator = WorkLocator(manga.api, chapter.url)
        requireBackupWork(locator)
        requireBackup(seen.none { policy.identifies(it, locator) }, "backup_duplicate_chapter")
        requireBackup(chapter.resumePage == null || chapter.resumePage >= 0, "backup_negative_page")
        chapter.downloadEntry?.let { entry ->
            requireBackup(entry.isNotBlank() && packedEntries.add(entry), "backup_duplicate_packed_entry")
        }
        seen += locator
    }
}

internal fun requireBackupWork(work: WorkLocator) {
    requireBackup(work.api.isNotBlank() && work.url.isNotBlank(), "backup_invalid_identity")
}

internal suspend fun ProgressOwnerSession.backupPlanner() = BackupImportPlanner(
    this,
    storage.savedCatalog.getAllSavedManga(),
    storage.savedCatalog.getAllHistoryOnce(),
)

/** All reads belong to the owning Room writer; nothing here selects by title or first URL hit. */
internal class BackupImportPlanner(
    private val session: ProgressOwnerSession,
    private val saved: List<SavedMangaEntity>,
    private val history: List<HistoryItemD>,
) {
    private val policy = session.policy

    fun savedWork(request: WorkLocator): SavedMangaEntity? {
        requireBackupWork(request)
        val exact = saved.filter { it.url == request.url }
        requireBackup(exact.size <= 1, "backup_exact_work_conflict")
        val resolved = WorkOwnerResolver(policy).resolve(
            request,
            exact.singleOrNull()?.savedIdentity(),
            saved.filter { it.api == request.api }.map { it.savedIdentity() },
        )
        return when (resolved) {
            is WorkOwnerResolution.Missing -> null
            is WorkOwnerResolution.Found -> saved.single { it.id == resolved.owner.id }
            is WorkOwnerResolution.Conflict -> throw BackupOwnershipException("backup_work_conflict")
        }
    }

    suspend fun plan(group: BackupImportGroup): BackupImportPlan {
        val manga = savedWork(group.work)
        val work = session.resolveWork(group.work)
        requireBackup(manga?.id == work.saved?.id, "backup_work_conflict")
        val local = BackupLocalGroup(
            manga,
            manga?.let { session.storage.savedCatalog.getChaptersForManga(it.id) }.orEmpty(),
            localHistory(group, work, manga),
        )
        val rawWorks = setOfNotNull(
            group.work,
            work.destination,
            work.anchor?.let { WorkLocator(it.api, it.workUrl) },
            group.history?.let { WorkLocator(it.api, it.mangaUrl) },
            local.history?.let { WorkLocator(it.api, it.mangaUrl) },
        )
        rawWorks.forEach { raw ->
            requireBackup(savedWork(raw)?.id == manga?.id, "backup_work_conflict")
            session.resolveWork(raw)
        }
        val chapters = rawChapters(group, work, local).associateWith { session.resolveChapter(it) }
        validateNewChapters(group, chapters, local.history)
        return BackupImportPlan(group, work, local, chapters)
    }

    fun historyForScope(rows: List<SavedMangaEntity>): List<HistoryItemD> = history.filter { item ->
        rows.any { row ->
            val request = row.savedIdentity().locator
            item.mangaId == row.id || item.mangaUrl == row.url ||
                policy.identifies(request, WorkLocator(item.api.ifBlank { row.api }, item.mangaUrl))
        }
    }

    private fun localHistory(
        group: BackupImportGroup,
        work: ResolvedProgressWork,
        manga: SavedMangaEntity?,
    ): HistoryItemD? {
        val requests = listOfNotNull(
            group.work,
            work.destination,
            group.history?.let { WorkLocator(it.api, it.mangaUrl) },
        )
        val matches = history.filter { item ->
            val same = requests.any { policy.identifies(it, WorkLocator(item.api, item.mangaUrl)) }
            val blank = item.api.isBlank() && requests.any {
                policy.identifies(it, WorkLocator(it.api, item.mangaUrl))
            }
            val crossApi = item.api != group.work.api && requests.any { it.url == item.mangaUrl }
            val retained = manga != null && item.mangaId == manga.id
            requireBackup(!blank && !crossApi && (!retained || same), "backup_history_owner_conflict")
            same
        }
        requireBackup(matches.size <= 1, "backup_history_family_conflict")
        return matches.singleOrNull()?.also {
            requireBackup(it.mangaId == 0L || it.mangaId == manga?.id, "backup_history_id_conflict")
        }
    }

    private suspend fun rawChapters(
        group: BackupImportGroup,
        work: ResolvedProgressWork,
        local: BackupLocalGroup,
    ): Set<ChapterLocator> {
        val raw = linkedSetOf<ChapterLocator>()
        group.manga?.chapters?.forEach { raw += ChapterLocator(group.work, it.url) }
        local.chapters.forEach { raw += ChapterLocator(work.destination, it.url) }
        work.anchor?.let { anchor ->
            session.storage.progress.chaptersForWork(anchor.workId).forEach {
                raw += ChapterLocator(WorkLocator(anchor.api, anchor.workUrl), it.chapterUrl)
            }
        }
        group.history?.takeIf { it.chapterUrl.isNotBlank() }?.let {
            raw += ChapterLocator(WorkLocator(it.api, it.mangaUrl), it.chapterUrl)
        }
        local.history?.takeIf { it.chapterUrl.isNotBlank() }?.let {
            raw += ChapterLocator(WorkLocator(it.api, it.mangaUrl), it.chapterUrl)
        }
        raw.forEach { requireBackup(it.chapterUrl.isNotBlank(), "backup_invalid_chapter") }
        return raw
    }

    private fun validateNewChapters(
        group: BackupImportGroup,
        chapters: Map<ChapterLocator, ResolvedProgressChapter>,
        localHistory: HistoryItemD?,
    ) {
        group.manga?.chapters?.forEach { chapter ->
            val resolved = chapters.getValue(ChapterLocator(group.work, chapter.url))
            if (resolved.saved == null) requireNewChapter(resolved.destination)
        }
        group.history?.takeIf { it.chapterUrl.isNotBlank() }?.let { item ->
            val raw = ChapterLocator(WorkLocator(item.api, item.mangaUrl), item.chapterUrl)
            val resolved = chapters.getValue(raw)
            if (resolved.saved == null && resolved.anchor == null && localHistory?.chapterUrl != item.chapterUrl) {
                requireNewChapter(resolved.destination)
            }
        }
    }

    private fun requireNewChapter(chapter: ChapterLocator) {
        requireBackup(
            policy.unownedRequestRejection(WorkLocator(chapter.work.api, chapter.chapterUrl)) == null,
            "backup_unowned_chapter_rejected",
        )
    }
}

internal data class BackupOwnedMerge(
    val manga: SavedMangaEntity?,
    val mangaWasNew: Boolean,
    val chapters: Map<ChapterLocator, SavedChapterEntity>,
    val chaptersAdded: Int,
    val historyMerged: Int,
) {
    val chaptersMerged: Int get() = chapters.size - chaptersAdded
}

internal data class BackupOwnedExport(
    val manga: SavedMangaEntity?,
    val chapters: List<BackupExportChapter>,
    val history: HistoryItemD?,
)

internal data class BackupExportChapter(val row: SavedChapterEntity, val pageIndex: Int?)

internal fun requireSeparateBackupTargets(plans: List<BackupImportPlan>) {
    val targets = plans.flatMap { listOf(it.group.work, it.work.destination) }
    requireDistinctApis(targets, "backup_cross_api_target")
}

private fun requireDistinctApis(works: List<WorkLocator>, reason: String) {
    val collisions = works.groupBy { it.url }.values.any { family -> family.map { it.api }.toSet().size > 1 }
    requireBackup(!collisions, reason)
}
