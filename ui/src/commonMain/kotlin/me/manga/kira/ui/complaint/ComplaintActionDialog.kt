package me.manga.kira.ui.complaint

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.presentation.complaint.ActionDialogMode
import me.manga.kira.presentation.complaint.ComplaintIntent
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.back
import me.manga.kira.ui.generated.resources.cancel
import me.manga.kira.ui.generated.resources.character_count
import me.manga.kira.ui.generated.resources.close
import me.manga.kira.ui.generated.resources.complaint_actions_title
import me.manga.kira.ui.generated.resources.complaint_body
import me.manga.kira.ui.generated.resources.complaint_editing
import me.manga.kira.ui.generated.resources.complaint_id_label
import me.manga.kira.ui.generated.resources.complaint_no_id
import me.manga.kira.ui.generated.resources.complaint_save
import me.manga.kira.ui.generated.resources.complaint_send
import me.manga.kira.ui.generated.resources.complaint_warning
import me.manga.kira.ui.generated.resources.delete
import me.manga.kira.ui.generated.resources.delete_complaint
import me.manga.kira.ui.generated.resources.delete_forever
import me.manga.kira.ui.generated.resources.delete_warning_message
import me.manga.kira.ui.generated.resources.edit
import me.manga.kira.ui.generated.resources.edit_complaint
import me.manga.kira.ui.generated.resources.edit_placeholder
import me.manga.kira.ui.generated.resources.np_reply_to_complaint_id
import me.manga.kira.ui.generated.resources.reply
import me.manga.kira.ui.generated.resources.reply_placeholder
import me.manga.kira.ui.generated.resources.reply_to_complaint
import me.manga.kira.ui.generated.resources.subject
import me.manga.kira.ui.generated.resources.your_reply
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * Rework Feedback Manager action dialog — the user-side Reply / Edit / Delete sub-screens that
 * open after tapping a row in [ComplaintScreen].
 *
 * Phase 7.x.complaint.actions rework. Visual parity with legacy
 * `composeApp/.../ComplaintActionDialog.kt` (4 sub-panels: Menu / Reply / Edit / Delete +
 * ComplaintPreviewCard), but driven by the MVI state-machine
 * ([me.manga.kira.presentation.complaint.ComplaintState.actionDialogMode]) rather than the
 * legacy's local `var currentAction by remember { mutableStateOf(DialogAction.NONE) }`. That
 * shift is the whole point of the rework — single source of truth in the VM, the composable is
 * a pure projection.
 *
 * **Stateless except for form input**:
 *  - `replyText`, `editedSubject`, `editedText` use [rememberSaveable] (transient typed-input
 *    state — survives config changes but isn't part of the VM contract; the VM only sees the
 *    final value via `OnSubmitReply` / `OnSubmitEdit`).
 *  - Submit-button loading state comes from [me.manga.kira.presentation.complaint.ComplaintState.isSubmittingAction]
 *    — legacy's local `var isLoading by remember { mutableStateOf(false) }` would split the
 *    source of truth (the VM also tracks it for the in-flight guard), so the rework drops the
 *    local flag.
 *
 * **Per-mode branching** mirrors the legacy's `when (currentAction)` switch on `DialogAction`:
 *  - [ActionDialogMode.MENU] → [ActionSelectionContent] (Reply / Edit / Delete affordances).
 *  - [ActionDialogMode.REPLY] → [ReplyContent] (textarea + Send).
 *  - [ActionDialogMode.EDIT] → [EditContent] (subject + body + Save).
 *  - [ActionDialogMode.DELETE] → [DeleteConfirmationContent] (warning + Delete forever).
 *  - [ActionDialogMode.NONE] → composable not mounted (the [ComplaintScreen] precondition
 *    skips mounting when mode is NONE).
 *
 * **Status-gated affordances** (legacy lines 206-241): Edit and Delete buttons in the Menu are
 * hidden when `complaint.status == ComplaintStatus.PINNED` — PINNED records are admin-pinned
 * FAQ entries that the user must not mutate. Reply is always available (reply creates a fresh
 * record threaded back to the parent via `metadata.replyto`; PINNED parents accept replies as
 * a user-feedback channel).
 *
 * **Validation parity** (legacy):
 *  - Reply: non-blank, length ≤ 500. Same caps as legacy `ReplyContent` (line 254).
 *  - Edit: non-blank subject AND non-blank body, body length ≤ 1000. Same caps as legacy
 *    `EditContent` (line 395).
 *  - Delete: no input — confirmation-only gate.
 *
 * **Affordance icons** (GAP-CMP-U7 — restored to match native):
 *  - The screen-level affordances on [ComplaintScreen] (back-nav, search leading, search-clear
 *    trailing, no-matches placeholder) use inline [ImageVector] paths in [ComplaintIcons].
 *  - The dialog's three action buttons now carry leading Material glyphs matching native
 *    `ComplaintActionDialog.kt` (Reply → `Icons.AutoMirrored.Filled.Reply`, Edit →
 *    `Icons.Default.Edit`, Delete → `Icons.Default.Delete`), and the Edit info-card / Delete
 *    warning-card carry their `Icons.Default.Info` / `Icons.Default.Warning` leading glyphs.
 *    `:ui` carries `compose.materialIconsExtended`, so the real Material vectors are used
 *    directly (the earlier icon-free posture is superseded — native shows these glyphs).
 *  - The intra-dialog Back (mode switch) and the Close affordance remain labelled
 *    [TextButton]s rather than bare IconButtons; the screen-level route-pop Back uses the
 *    [ComplaintIcons.ComplaintArrowBack] glyph.
 *  - Labels resolve through `stringResource(Res.string.*)` against the `:ui` compose-resources
 *    catalog (UP-3 localization lift), reusing the legacy complaint keys (close, reply, edit,
 *    delete, reply_to_complaint, edit_complaint, delete_complaint, subject, complaint_body,
 *    delete_forever, etc.) so the hand-authored Arabic translations apply verbatim.
 *
 * **Why a single composable with internal branching, not 4 sibling composables**: the legacy
 * pattern is one big `Dialog { Card { when (mode) { ... } } }` block — keeps the dialog's
 * `RoundedCornerShape`+elevation outer chrome consistent across modes and lets the inner
 * branch swap content without re-mounting the [Dialog] (no flash). Same structure here.
 *
 * **Dispatched intents** (in dispatch order through the user flow):
 *  1. Tap an affordance in Menu → [ComplaintIntent.OnSelectAction] (REPLY / EDIT / DELETE).
 *  2. Tap Back in any sub-mode → [ComplaintIntent.OnSelectAction] (MENU).
 *  3. Tap Close/dismiss/outside → [ComplaintIntent.OnDismissActionDialog].
 *  4. Tap Send (Reply) → [ComplaintIntent.OnSubmitReply].
 *  5. Tap Save (Edit) → [ComplaintIntent.OnSubmitEdit].
 *  6. Tap Delete forever (Delete) → [ComplaintIntent.OnConfirmDelete].
 *
 * **SRP**: one rule — "render the dialog for the active complaint at the active sub-mode".
 * No persistence (VM owns); no business logic (use cases own); no derivation.
 *
 * **DIP**: depends only on `:domain` ([ComplaintSummary] / [ComplaintStatus]) and
 * `:presentation` ([ActionDialogMode] / [ComplaintIntent]). No `:data` / no `:shared` reach.
 *
 * **No `Any`, no `!!`, no `lateinit`**: all state is primitive / `String` / sealed-interface
 * variants. Banned features absent.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster34.staleKdocSweep.cascade,
 * Task #490, 2026-05-28): seven stale citations appear in this file's
 * class-level KDoc above, all referencing the deleted legacy `:
 * composeApp/.../ComplaintActionDialog.kt`:
 *  - Lines 45-46 ("Visual parity with legacy `composeApp/.../
 *    ComplaintActionDialog.kt` (4 sub-panels: Menu / Reply / Edit /
 *    Delete + ComplaintPreviewCard)").
 *  - Lines 49-50 ("the legacy's local `var currentAction by remember
 *    { mutableStateOf(DialogAction.NONE) }`").
 *  - Lines 58-60 ("legacy's local `var isLoading by remember {
 *    mutableStateOf(false) }` would split the source of truth").
 *  - Line 62 ("the legacy's `when (currentAction)` switch on
 *    `DialogAction`").
 *  - Line 70 ("(legacy lines 206-241): Edit and Delete buttons in the
 *    Menu are hidden when `complaint.status == ComplaintStatus.
 *    PINNED`").
 *  - Lines 77-79 ("Same caps as legacy `ReplyContent` (line 254)" +
 *    "Same caps as legacy `EditContent` (line 395)").
 *  - Lines 89-95 ("The legacy uses `Icons.AutoMirrored.Filled.Reply /
 *    Edit / Delete` on the buttons and `Icons.Default.Info / Warning`
 *    on the info cards; the rework drops them" + "Legacy `IconButton(
 *    ... Icons.AutoMirrored.Filled.ArrowBack ...)` → labelled
 *    [TextButton] 'Back'" + "Legacy `IconButton(... Icons.Default.
 *    Close ...)` → labelled [TextButton] 'Close'").
 *  - Lines 99-100 ("the legacy pattern is one big `Dialog { Card {
 *    when (mode) { ... } } }` block").
 *  All classified as STALE-SYMBOL-REFERENCE — Phase 9.x.complaint.
 *  legacyui.retire (§355) DELETED the legacy `:composeApp/.../
 *  ComplaintActionDialog.kt` along with its 4 sibling helpers as part
 *  of the 5-file orphan-retire chain. A recursive search of the
 *  legacy complaint folder for a `ComplaintActionDialog.kt` with the
 *  cited 4-sub-panel layout / `currentAction`+`isLoading` locals /
 *  `when (currentAction)` switch / line-206-241 status-gate / line-
 *  254 ReplyContent / line-395 EditContent / `Icons.AutoMirrored.
 *  Filled.Reply/Edit/Delete` icon usage / `Icons.Default.Close`+
 *  `ArrowBack` IconButton patterns / `Dialog { Card { when (mode) }
 *  } }` chrome shell returns NO MATCHES. HOWEVER — this rework `:ui`
 *  `ComplaintActionDialog` (same filename, different package: `me.
 *  manga.yamiapk.ui.complaint.ComplaintActionDialog`) is LIVE as the
 *  canonical user-side Complaint-action dialog backed by
 *  [me.manga.kira.presentation.complaint.ComplaintState] +
 *  [me.manga.kira.presentation.complaint.ComplaintViewModel] +
 *  [ComplaintIntent] + [me.manga.kira.presentation.complaint.
 *  ComplaintEffect]; all seven architectural rationales STAND on
 *  their own merits past the §355 fulfilled landing as LIVE rework
 *  realizations: (a) the 4-sub-panel layout (Menu / Reply / Edit /
 *  Delete) is preserved via [ActionDialogMode] sealed-interface
 *  branching in this composable; (b) the local-`currentAction`+
 *  `isLoading` posture is INVERTED — both pieces of state lift to the
 *  VM ([ComplaintState.actionDialogMode] + [ComplaintState.
 *  isSubmittingAction]) per the MVI single-source-of-truth contract
 *  (this inversion IS the rework's point); (c) the `when
 *  (currentAction)` switch is preserved structurally as `when (mode)`
 *  on [ActionDialogMode]; (d) the status-gate on PINNED records (Edit
 *  / Delete hidden) is preserved verbatim; (e) the validation caps
 *  (Reply ≤ 500, Edit body ≤ 1000) are preserved verbatim; (f) the
 *  icon-free posture is the rework's deliberate `:ui` build-graph
 *  choice (no `compose.materialIconsExtended` dep) — text-only
 *  affordances substitute for the legacy's `Icons.AutoMirrored.
 *  Filled.*` + `Icons.Default.*` glyphs; (g) the `Dialog { Card {
 *  when (mode) }}` chrome-shell structure is preserved verbatim.
 *  Original §253-era prose preserved verbatim per the audit-trail-
 *  preservation convention — the citations are historical record of
 *  the design lineage including all seven parity rationales that were
 *  subsequently fulfilled (legacy complaint chain retired) across
 *  §355.
 */
@Composable
internal fun ComplaintActionDialog(
    complaint: ComplaintSummary,
    mode: ActionDialogMode,
    isSubmitting: Boolean,
    onIntent: (ComplaintIntent) -> Unit,
) {
    Dialog(onDismissRequest = {
        if (!isSubmitting) onIntent(ComplaintIntent.OnDismissActionDialog)
    }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            when (mode) {
                ActionDialogMode.NONE -> Unit
                ActionDialogMode.MENU -> ActionSelectionContent(
                    complaint = complaint,
                    isSubmitting = isSubmitting,
                    onIntent = onIntent,
                )
                ActionDialogMode.REPLY -> ReplyContent(
                    complaint = complaint,
                    isSubmitting = isSubmitting,
                    onIntent = onIntent,
                )
                ActionDialogMode.EDIT -> EditContent(
                    complaint = complaint,
                    isSubmitting = isSubmitting,
                    onIntent = onIntent,
                )
                ActionDialogMode.DELETE -> DeleteConfirmationContent(
                    complaint = complaint,
                    isSubmitting = isSubmitting,
                    onIntent = onIntent,
                )
            }
        }
    }
}

@Composable
private fun ActionSelectionContent(
    complaint: ComplaintSummary,
    isSubmitting: Boolean,
    onIntent: (ComplaintIntent) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.complaint_actions_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            TextButton(
                onClick = { onIntent(ComplaintIntent.OnDismissActionDialog) },
                enabled = !isSubmitting,
            ) {
                Text(stringResource(Res.string.close))
            }
        }

        ComplaintPreviewCard(complaint = complaint)

        Column(
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            // GAP-CMP-U7 — native `ComplaintActionDialog.kt:130-182` carries a leading glyph on
            // each action button (Reply→AutoMirrored.Reply, Edit→Edit, Delete→Delete), 20.dp icon
            // + 12.dp gap + label. material-icons-extended is available in `:ui`, so use the real
            // Material vectors rather than the prior icon-free text buttons.
            ElevatedButton(
                onClick = { onIntent(ComplaintIntent.OnSelectAction(ActionDialogMode.REPLY)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSubmitting,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(Res.string.reply), style = MaterialTheme.typography.bodyLarge)
            }

            if (complaint.status != ComplaintStatus.PINNED) {
                OutlinedButton(
                    onClick = { onIntent(ComplaintIntent.OnSelectAction(ActionDialogMode.EDIT)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSubmitting,
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(Res.string.edit), style = MaterialTheme.typography.bodyLarge)
                }

                OutlinedButton(
                    onClick = { onIntent(ComplaintIntent.OnSelectAction(ActionDialogMode.DELETE)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(Res.string.delete), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun ReplyContent(
    complaint: ComplaintSummary,
    isSubmitting: Boolean,
    onIntent: (ComplaintIntent) -> Unit,
) {
    val spacing = LocalSpacing.current
    val maxChars = 500
    var replyText by rememberSaveable(complaint.id) { mutableStateOf("") }

    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.reply_to_complaint),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onIntent(ComplaintIntent.OnSelectAction(ActionDialogMode.MENU)) },
                enabled = !isSubmitting,
            ) {
                Text(stringResource(Res.string.back))
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    // GAP-CMP-U-REPLYID — native `ComplaintActionDialog.kt:239` uses
                    // `reply_to_complaint_id` ("Reply to Complaint ID: %s"); the prior KMP wording
                    // ("Replying to %s") diverged. Reconciled to native's "Reply to Complaint ID:"
                    // copy. The no-id fallback is RETAINED on purpose — pinned-FAQ entries carry an
                    // empty id (native passes the raw id with no fallback; the rework keeps the
                    // "(no id)" placeholder so the empty-id case reads coherently).
                    text = stringResource(
                        Res.string.np_reply_to_complaint_id,
                        complaint.id.ifEmpty { stringResource(Res.string.complaint_no_id) },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = complaint.subject,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = complaint.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        OutlinedTextField(
            value = replyText,
            onValueChange = { if (it.length <= maxChars) replyText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.your_reply)) },
            placeholder = { Text(stringResource(Res.string.reply_placeholder)) },
            minLines = 3,
            maxLines = 6,
            shape = RoundedCornerShape(12.dp),
            enabled = !isSubmitting,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                // GAP-CMP-U-COUNTER — native `ComplaintActionDialog.kt:281` routes the counter
                // through `stringResource(R.string.character_count, length, maxChars)`
                // ("%1$d/%2$d") so digit/format localization is preserved; matched here.
                text = stringResource(Res.string.character_count, replyText.length, maxChars),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            OutlinedButton(
                onClick = { onIntent(ComplaintIntent.OnSelectAction(ActionDialogMode.MENU)) },
                modifier = Modifier.weight(1f),
                enabled = !isSubmitting,
            ) {
                Text(stringResource(Res.string.cancel))
            }

            Button(
                onClick = {
                    val trimmed = replyText.trim()
                    if (trimmed.isNotBlank()) {
                        onIntent(ComplaintIntent.OnSubmitReply(trimmed))
                    }
                },
                modifier = Modifier.weight(1f),
                // The submit guard above trims before checking; gate `enabled` on the same trimmed
                // value so the button's enabled state matches the guard exactly (a reply that is
                // only whitespace stays disabled and never dispatches).
                enabled = !isSubmitting && replyText.trim().isNotBlank(),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(Res.string.complaint_send), color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun EditContent(
    complaint: ComplaintSummary,
    isSubmitting: Boolean,
    onIntent: (ComplaintIntent) -> Unit,
) {
    val spacing = LocalSpacing.current
    val maxChars = 1000
    var editedSubject by rememberSaveable(complaint.id) { mutableStateOf(complaint.subject) }
    var editedBody by rememberSaveable(complaint.id) { mutableStateOf(complaint.body) }
    // GAP-CMP-U-EDIT — native EditComplaintDialog (StatusChangeDialog.kt:124-126/278) gates Save on
    // `hasChanges` (the edited subject/body must differ from the original) so an unchanged complaint
    // can't be re-submitted, avoiding a redundant write. Matches the admin-side EditContent guard.
    val hasChanges = editedSubject != complaint.subject || editedBody != complaint.body

    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.edit_complaint),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onIntent(ComplaintIntent.OnSelectAction(ActionDialogMode.MENU)) },
                enabled = !isSubmitting,
            ) {
                Text(stringResource(Res.string.back))
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            // GAP-CMP-U7 — native `ComplaintActionDialog.kt:383-398` prefixes the edit info-card
            // with an Info glyph (onPrimaryContainer tint) + 12.dp gap before the text.
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(
                        Res.string.complaint_editing,
                        complaint.id.ifEmpty { stringResource(Res.string.complaint_no_id) },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        OutlinedTextField(
            value = editedSubject,
            onValueChange = { editedSubject = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.subject)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            enabled = !isSubmitting,
        )

        OutlinedTextField(
            value = editedBody,
            onValueChange = { if (it.length <= maxChars) editedBody = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.complaint_body)) },
            placeholder = { Text(stringResource(Res.string.edit_placeholder)) },
            minLines = 4,
            maxLines = 8,
            shape = RoundedCornerShape(12.dp),
            isError = editedBody.length > maxChars,
            enabled = !isSubmitting,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                // GAP-CMP-U-COUNTER — native `ComplaintActionDialog.kt:430` routes the counter
                // through `stringResource(R.string.character_count, length, maxChars)`
                // ("%1$d/%2$d") so digit/format localization is preserved; matched here.
                text = stringResource(Res.string.character_count, editedBody.length, maxChars),
                style = MaterialTheme.typography.bodySmall,
                color = if (editedBody.length > maxChars) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            OutlinedButton(
                onClick = { onIntent(ComplaintIntent.OnSelectAction(ActionDialogMode.MENU)) },
                modifier = Modifier.weight(1f),
                enabled = !isSubmitting,
            ) {
                Text(stringResource(Res.string.cancel))
            }

            Button(
                onClick = {
                    val trimmedSubject = editedSubject.trim()
                    val trimmedBody = editedBody.trim()
                    if (hasChanges &&
                        trimmedSubject.isNotBlank() &&
                        trimmedBody.isNotBlank() &&
                        trimmedBody.length <= maxChars
                    ) {
                        onIntent(ComplaintIntent.OnSubmitEdit(trimmedSubject, trimmedBody))
                    }
                },
                modifier = Modifier.weight(1f),
                // GAP-CMP-U-EDIT — Save disabled unless the value actually changed (hasChanges),
                // matching native EditComplaintDialog's gate (StatusChangeDialog.kt:278). Native
                // gates on the TRIMMED subject/body (`subject.trim().isNotEmpty() &&
                // body.trim().isNotEmpty()`); gate on the trimmed value here too so the button's
                // enabled state matches the submit guard above (whitespace-only stays disabled).
                enabled = !isSubmitting &&
                    hasChanges &&
                    editedSubject.trim().isNotBlank() &&
                    editedBody.trim().isNotBlank() &&
                    editedBody.trim().length <= maxChars,
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(Res.string.complaint_save), color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirmationContent(
    complaint: ComplaintSummary,
    isSubmitting: Boolean,
    onIntent: (ComplaintIntent) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.delete_complaint),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onIntent(ComplaintIntent.OnSelectAction(ActionDialogMode.MENU)) },
                enabled = !isSubmitting,
            ) {
                Text(stringResource(Res.string.back))
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            // GAP-CMP-U7 — native `ComplaintActionDialog.kt:522-545` prefixes the delete warning-
            // card with a Warning glyph (onErrorContainer tint) + 12.dp gap before the title/body
            // Column.
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(Res.string.complaint_warning),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = stringResource(Res.string.delete_warning_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        ComplaintPreviewCard(complaint = complaint)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            OutlinedButton(
                onClick = { onIntent(ComplaintIntent.OnSelectAction(ActionDialogMode.MENU)) },
                modifier = Modifier.weight(1f),
                enabled = !isSubmitting,
            ) {
                Text(stringResource(Res.string.cancel))
            }

            Button(
                onClick = { onIntent(ComplaintIntent.OnConfirmDelete) },
                modifier = Modifier.weight(1f),
                enabled = !isSubmitting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.delete_forever),
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComplaintPreviewCard(complaint: ComplaintSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = complaint.subject.ifEmpty { complaint.type.displayName() },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = complaint.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // GAP-CMP-U8 — native `ComplaintActionDialog.kt:630-642` ComplaintPreviewCard uses
                // the vivid getColorWithContrast palette (r8) rather than the list card's M3
                // StatusChip. Port via ComplaintStatusBadge so the dialog badge matches native.
                ComplaintStatusBadge(status = complaint.status)
            }

            // GAP-CMP-02 — closure-reason card in the dialog preview, matching the user row.
            val reason = complaint.reason
            if (reason != null &&
                (complaint.status == ComplaintStatus.CLOSED || complaint.status == ComplaintStatus.PINNED)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                ClosureReasonCard(reason = reason)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(
                    Res.string.complaint_id_label,
                    complaint.id.ifEmpty { stringResource(Res.string.complaint_no_id) },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
