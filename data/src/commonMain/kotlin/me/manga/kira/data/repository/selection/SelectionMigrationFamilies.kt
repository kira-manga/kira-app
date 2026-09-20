package me.manga.kira.data.repository.selection

import me.manga.kira.data.identity.SelectionAliasKey
import me.manga.kira.data.identity.SelectionUrlRewrites
import me.manga.kira.data.identity.SelectionWorkOwnerIndex
import me.manga.kira.data.identity.WorkOwnerResolution
import me.manga.kira.data.local.dao.SelectionSavedWork
import me.manga.kira.data.local.dao.SourceSelectionMigrationDao
import me.manga.kira.data.local.entity.ReaderWorkStateEntity
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.sources.contracts.SourceSelectionUnavailable

/** One proved family has at most one owner in each independent ID namespace. */
internal data class SelectionMigrationFamily(
    val requested: WorkLocator,
    val saved: SelectionSavedWork?,
    val reader: ReaderWorkStateEntity?,
    val destination: String,
)

/** All caches are private to this unchanged preflight snapshot; none may be used after applying. */
internal class SelectionMigrationFamilies(
    private val dao: SourceSelectionMigrationDao,
    private val rows: SelectionMigrationIndexes,
    private val rewrites: SelectionUrlRewrites,
    private val progress: SelectionPlanningProgress,
) {
    private val owners = mutableMapOf<String, SelectionWorkOwnerIndex>()
    private val readers = mutableMapOf<SelectionAliasKey, MutableList<ReaderWorkStateEntity>>()
    private val families = mutableMapOf<SelectionAliasKey, SelectionMigrationFamily>()
    private val savedFamilies = mutableMapOf<Long, SelectionMigrationFamily>()
    private val readerFamilies = mutableMapOf<Long, SelectionMigrationFamily>()
    private val global = mutableMapOf<String, SelectionSavedWork?>()
    private val savedTargets = mutableMapOf<String, SelectionSavedWork>()
    private val readerTargets = mutableMapOf<WorkLocator, ReaderWorkStateEntity>()
    private val claims = mutableListOf<Pair<SelectionMigrationFamily, String>>()

    suspend fun discover(): List<SelectionMigrationFamily> {
        prepareOwners()
        for ((request, key) in rows.requests) {
            progress.visit(SelectionVisit.RELATION)
            val family = families[key] ?: resolve(request, key).also { families[key] = it }
            checkOccupancy(family, request.url, rewrites.destination(request))
        }
        return orderedFamilies()
    }

    private suspend fun prepareOwners() {
        for ((api, reference) in rows.references) {
            progress.visit(SelectionVisit.OWNER)
            val index = SelectionWorkOwnerIndex.prepare(reference, rows.savedByApi[api].orEmpty(), rewrites, progress)
            index.rejectAffectedExclusions()
            owners[api] = index
            for (row in rows.readerByApi[api].orEmpty()) {
                progress.visit(SelectionVisit.OWNER)
                val read = rewrites.read(row.locator())
                if (read.affected) rewrites.key(row.locator())
                read.key?.let { readers.getOrPut(it) { mutableListOf() }.add(row) }
            }
        }
    }

    private suspend fun resolve(
        request: WorkLocator,
        key: SelectionAliasKey,
    ): SelectionMigrationFamily {
        val saved = savedOwner(request)
        val anchors = readers[key].orEmpty()
        requireSelection(anchors.size <= 1, "selection work-anchor alias conflict")
        val anchor = anchors.firstOrNull()
        requireSelection(saved != null || anchor != null, "affected work is unowned")
        val destination = rewrites.destination(saved?.locator() ?: checkNotNull(anchor).locator())
        anchor?.let {
            requireSelection(it.workId > 0, "invalid reader work identity")
            rewrites.checked(it.locator(), destination)
        }
        val family = SelectionMigrationFamily(request, saved, anchor, destination)
        reserve(family)
        saved?.let {
            savedFamilies[it.id] = family
            checkOccupancy(family, it.url, destination)
        }
        anchor?.let {
            readerFamilies[it.workId] = family
            checkOccupancy(family, it.workUrl, destination)
        }
        return family
    }

    private suspend fun savedOwner(request: WorkLocator): SelectionSavedWork? =
        when (val resolved = owners.getValue(request.api).resolve(request, globalAt(request.url)?.identity())) {
            is WorkOwnerResolution.Found -> rows.savedParent(resolved.owner.id)
            is WorkOwnerResolution.Missing -> null
            is WorkOwnerResolution.Conflict -> unavailable("selection saved-work ownership conflict")
        }

    /** Cache null explicitly. getOrPut on a nullable map would query every repeated empty target. */
    private suspend fun globalAt(url: String): SelectionSavedWork? {
        progress.visit(SelectionVisit.LOOKUP)
        if (!global.containsKey(url)) {
            progress.visit(SelectionVisit.GLOBAL_READ)
            global[url] = progress.around { dao.savedWorkAt(url) }
        }
        return global[url]
    }

    private fun reserve(family: SelectionMigrationFamily) {
        family.saved?.let { saved ->
            val prior = savedTargets.put(family.destination, saved)
            requireSelection(prior == null || prior == saved, "planned saved-work destinations collide")
        }
        family.reader?.let { reader ->
            val prior = readerTargets.put(WorkLocator(reader.api, family.destination), reader)
            requireSelection(prior == null || prior == reader, "planned reader-work destinations collide")
        }
    }

    /** Existing saved occupancy remains global even for different-API or soon-to-move owners. */
    suspend fun checkOccupancy(
        family: SelectionMigrationFamily,
        original: String,
        destination: String,
    ) {
        for (url in listOf(original, destination).distinct()) {
            progress.visit(SelectionVisit.RELATION)
            val occupied = globalAt(url)
            requireSelection(occupied == null || occupied == family.saved, "global saved-work URL is occupied")
            claims += family to url
        }
    }

    /** Only real saved-row targets create global ownership; reader/related spellings are claims. */
    suspend fun checkPlannedOccupancy() {
        for ((family, url) in claims) {
            progress.visit(SelectionVisit.RELATION)
            val planned = savedTargets[url]
            requireSelection(planned == null || planned == family.saved, "work locator has another planned saved owner")
        }
    }

    private suspend fun orderedFamilies(): List<SelectionMigrationFamily> {
        val ordered = mutableListOf<SelectionMigrationFamily>()
        // The complete DAO lists are ID-ordered; no repeated sorting or bucket enumeration is needed.
        for (row in rows.rows.works.saved) {
            progress.visit(SelectionVisit.ROW)
            savedFamilies[row.id]?.let { ordered += it }
        }
        for (row in rows.rows.works.reader) {
            progress.visit(SelectionVisit.ROW)
            readerFamilies[row.workId]?.takeIf { it.saved == null }?.let { ordered += it }
        }
        return ordered
    }
}

internal fun SelectionSavedWork.locator(): WorkLocator = WorkLocator(api, url)

internal fun SelectionSavedWork.identity(): SavedWorkIdentity = SavedWorkIdentity(id, locator())

internal fun ReaderWorkStateEntity.locator(): WorkLocator = WorkLocator(api, workUrl)

internal fun requireSelection(
    condition: Boolean,
    message: String,
) {
    if (!condition) unavailable(message)
}

internal fun unavailable(message: String): Nothing = throw SourceSelectionUnavailable(message)
