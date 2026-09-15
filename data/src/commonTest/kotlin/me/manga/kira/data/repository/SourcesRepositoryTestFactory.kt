package me.manga.kira.data.repository

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository as LegacySourcesRepository

/** Uses the caller-owned scope for the legacy observers; no test creates an unowned application job. */
internal fun CoroutineScope.sourceSettingsRepository(
    dao: SourcesDao,
    registry: SourceRegistry,
): SourcesRepositoryImpl =
    SourcesRepositoryImpl(
        legacy =
            LegacySourcesRepository(
                sourcesDao = dao,
                repos = emptySet(),
                prefs = SharedPrefsHelper(MapSettings()),
                applicationScope = this,
            ),
        sourceRegistry = registry,
        dataStore = DataStoreHelper(MapSettings()),
        sourcesDao = dao,
    )
