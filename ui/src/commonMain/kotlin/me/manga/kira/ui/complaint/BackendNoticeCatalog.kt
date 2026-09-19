package me.manga.kira.ui.complaint

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.backend_notice_content_policy_body
import me.manga.kira.ui.generated.resources.backend_notice_content_policy_subject
import me.manga.kira.ui.generated.resources.backend_notice_source_requirements_body
import me.manga.kira.ui.generated.resources.backend_notice_source_requirements_subject
import me.manga.kira.ui.generated.resources.unknown
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Finite app-owned copy. Definition versions are not backend row concurrency versions. */
internal enum class BackendNoticeDefinition(
    val key: String,
    val definitionVersion: Int,
    val subject: StringResource,
    val body: StringResource,
) {
    CONTENT_POLICY(
        "complaints.notice.content-policy",
        INITIAL_NOTICE_DEFINITION_VERSION,
        Res.string.backend_notice_content_policy_subject,
        Res.string.backend_notice_content_policy_body,
    ),
    SOURCE_REQUIREMENTS(
        "complaints.notice.source-requirements",
        INITIAL_NOTICE_DEFINITION_VERSION,
        Res.string.backend_notice_source_requirements_subject,
        Res.string.backend_notice_source_requirements_body,
    ),
}

internal fun backendNoticeDefinition(key: String): BackendNoticeDefinition? =
    BackendNoticeDefinition.entries.firstOrNull { it.key == key }

/**
 * Exact-key display eligibility for the finite in-app notice catalog. No aliases or normalization.
 * Recognition does not authorize a backend mutation or prove that a notice was seeded or activated.
 */
fun isKnownBackendNoticeKey(key: String): Boolean = backendNoticeDefinition(key) != null

/**
 * Read-only localized notice copy; unknown keys render only the generic localized placeholder.
 * Never displays the raw key or supplies an action, enrollment or legacy fallback.
 */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
fun BackendNoticeText(
    noticeKey: String,
    modifier: Modifier = Modifier,
) {
    val definition = backendNoticeDefinition(noticeKey)
    if (definition == null) {
        Text(stringResource(Res.string.unknown), modifier = modifier)
        return
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)) {
        Text(stringResource(definition.subject), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(definition.body), style = MaterialTheme.typography.bodyMedium)
    }
}

private const val INITIAL_NOTICE_DEFINITION_VERSION = 1
