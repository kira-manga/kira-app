package me.manga.kira.ui.complaint.admin

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.presentation.complaint.admin.AdminActionDialogMode
import me.manga.kira.presentation.complaint.admin.AdminComplaintIntent
import me.manga.kira.ui.complaint.ClosureReasonType
import me.manga.kira.ui.complaint.ComplaintStatusChip
import me.manga.kira.ui.complaint.displayName
import me.manga.kira.ui.complaint.displayText
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.add_reason
import me.manga.kira.ui.generated.resources.admin_actions
import me.manga.kira.ui.generated.resources.admincomplaint_change_status
import me.manga.kira.ui.generated.resources.admincomplaint_closure_reason
import me.manga.kira.ui.generated.resources.admincomplaint_closure_reason_placeholder
import me.manga.kira.ui.generated.resources.admincomplaint_current_status
import me.manga.kira.ui.generated.resources.admincomplaint_delete_complaint_title
import me.manga.kira.ui.generated.resources.admincomplaint_delete_warning
import me.manga.kira.ui.generated.resources.admincomplaint_edit_complaint_title
import me.manga.kira.ui.generated.resources.admincomplaint_editing_complaint
import me.manga.kira.ui.generated.resources.admincomplaint_no_id
import me.manga.kira.ui.generated.resources.admincomplaint_preview_user
import me.manga.kira.ui.generated.resources.admincomplaint_reason_label
import me.manga.kira.ui.generated.resources.admincomplaint_save
import me.manga.kira.ui.generated.resources.add_closure_reason
import me.manga.kira.ui.generated.resources.back
import me.manga.kira.ui.generated.resources.bk_complaint_type_label
import me.manga.kira.ui.generated.resources.bk_complaint_type_selector_cd
import me.manga.kira.ui.generated.resources.cancel
import me.manga.kira.ui.generated.resources.change_status
import me.manga.kira.ui.generated.resources.character_count
import me.manga.kira.ui.generated.resources.close
import me.manga.kira.ui.generated.resources.complaint_body
import me.manga.kira.ui.generated.resources.delete
import me.manga.kira.ui.generated.resources.delete_forever
import me.manga.kira.ui.generated.resources.edit
import me.manga.kira.ui.generated.resources.edit_placeholder
import me.manga.kira.ui.generated.resources.np_admin_current_status_label
import me.manga.kira.ui.generated.resources.np_admin_status_label
import me.manga.kira.ui.generated.resources.np_closure_reason_details
import me.manga.kira.ui.generated.resources.np_confirm
import me.manga.kira.ui.generated.resources.np_reason_type
import me.manga.kira.ui.generated.resources.np_select_reason_type
import me.manga.kira.ui.generated.resources.subject
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * Rework admin Complaint action dialog — the status-change / closure-reason / delete sub-screens
 * that open after tapping a row in [AdminComplaintScreen].
 *
 * Phase 7.x.complaint.admin.actions rework. Visual parity with the legacy admin's dialogs
 * (legacy `composeApp/.../admin/complaint/StatusChangeDialog.kt`, `ClosureReasonDialog.kt`,
 * `DeleteConfirmationDialog.kt`, and the Edit dialog — all ported below).
 * Driven by the MVI state-machine
 * ([me.manga.kira.presentation.complaint.admin.AdminComplaintState.actionDialogMode])
 * rather than the legacy's local `var` mode flags.
 *
 * **Mirrors user-side [me.manga.kira.ui.complaint.ComplaintActionDialog]'s posture**:
 *  - Single `Dialog(...)` wrapper with a `when (mode)` branch over the 4 admin modes.
 *  - Internal sub-mode content composables for MENU / STATUS_CHANGE / CLOSURE_REASON / DELETE_CONFIRM.
 *  - `rememberSaveable(complaint.id)` for form input — the input survives config changes but
 *    isn't part of the VM contract; the VM only sees the final value via `OnSubmit*`.
 *  - Submit-button loading state comes from
 *    [me.manga.kira.presentation.complaint.admin.AdminComplaintState.isSubmittingAction]
 *    (single source of truth).
 *
 * **Per-mode branching**:
 *  - [AdminActionDialogMode.MENU] → [ActionSelectionContent] (3 admin actions).
 *  - [AdminActionDialogMode.STATUS_CHANGE] → [StatusChangeContent] (radio list of ALL statuses; the
 *    Confirm button is disabled while the selection equals the current status).
 *  - [AdminActionDialogMode.CLOSURE_REASON] → [ClosureReasonContent] (textarea + Add).
 *  - [AdminActionDialogMode.DELETE_CONFIRM] → [DeleteConfirmationContent] (warning + Delete forever).
 *  - [AdminActionDialogMode.EDIT] → [EditContent] (type + subject + body + Save).
 *  - [AdminActionDialogMode.NONE] → composable not mounted (the [AdminComplaintScreen]
 *    precondition skips mounting when mode is NONE).
 *
 * **Status-gated affordances** (matches legacy):
 *  - The status-change list shows every [ComplaintStatus]; the Confirm button is disabled while
 *    the selected status equals the current one, so no-op transitions can't be submitted
 *    (GAP-CMP-25). Matches legacy `StatusChangeDialog` line 50 + 84.
 *  - The closure-reason action is always available — legacy `AdminComplaintScreen.kt:557`
 *    shows the "Add reason" affordance regardless of current status. The repository's auto-
 *    CLOSE logic only fires when current status is OPEN/IN_PROGRESS; other statuses keep
 *    their current status with the new reason metadata.
 *  - Delete is always available — admin override.
 *
 * **Intent dispatch**:
 *  1. Tap a top-level action in MENU → [AdminComplaintIntent.OnSelectAction] (transition to mode).
 *  2. Tap Back in any sub-mode → [AdminComplaintIntent.OnSelectAction] (back to MENU).
 *  3. Tap Close/dismiss/outside → [AdminComplaintIntent.OnDismissActionDialog].
 *  4. Tap a status in STATUS_CHANGE → [AdminComplaintIntent.OnSubmitStatusChange].
 *  5. Tap Add in CLOSURE_REASON → [AdminComplaintIntent.OnSubmitClosureReason].
 *  6. Tap Delete forever in DELETE_CONFIRM → [AdminComplaintIntent.OnConfirmDelete].
 *
 * **Edit affordance** (Phase 7.x.complaint.admin.edit): admin can mutate the type + subject +
 * body of any user's complaint. Mirrors user-side `EditContent` (same field shape, same ≤ 1000
 * char body cap) plus a Type dropdown that the user-side lacks (native parity — the native
 * `EditComplaintDialog` Type selector). One repo-level distinction — admin edit PRESERVES the
 * legacy `metadata` field (closure-reason audit trail). The MENU "Edit" affordance is placed
 * between "Change status" and "Add closure reason" (status-action ordering). No PINNED gate
 * (unlike user-side — admin has override authority, same as Delete).
 *
 * **Closure-reason validation**:
 *  - Non-blank reason. The Add button is disabled when blank — this matches native
 *    `ClosureReasonDialog` (`StatusChangeDialog.kt:448`), whose Add button is gated on
 *    `reasonText.trim().isNotEmpty()`.
 *  - The reason length is additionally capped at 500 chars with a `length/max` counter. This is
 *    an intentional KMP tightening, NOT native parity — native's `ClosureReasonDialog` reason
 *    field (`StatusChangeDialog.kt:409-422`) is `onValueChange = { reasonText = it }` with no
 *    length guard and no counter. The cap guards against unbounded closure-reason metadata.
 *
 * **SRP**: one rule — "render the admin dialog for the active complaint at the active
 * sub-mode". No persistence (VM owns); no business logic (use cases own).
 *
 * **DIP**: depends only on `:domain` ([ComplaintSummary] / [ComplaintStatus]) and
 * `:presentation` ([AdminActionDialogMode] / [AdminComplaintIntent]). No `:data` / no
 * `:shared` reach.
 *
 * **No `Any`, no `!!`, no `lateinit`**: all state is primitive / `String` / sealed-interface /
 * enum variants. Banned features absent.
 *
 * **Audit-trail postscript** (Phase 9.x.admincomplaint.staleKdocSweep.cascade,
 * Task #455, 2026-05-28): three stale line-anchored citations into the
 * §366-retired legacy admin Complaint chain appear in per-section KDocs
 * above:
 *  - Line 73 cites "legacy `StatusChangeDialog` line 50 + 84" for the
 *    "exclude current status from the list" parity.
 *  - Line 74 cites "legacy `AdminComplaintScreen.kt:557`" for the
 *    "Add reason" affordance availability-rule parity.
 *  - Line 95 cites "legacy `ClosureReasonDialog.kt:73-83`" for the
 *    non-blank + 500-char closure-reason validation parity.
 * The legacy `composeApp/.../admin/complaint/AdminComplaintScreen.kt`
 * and `composeApp/.../admin/complaint/StatusChangeDialog.kt` were
 * retired together in Phase 9.x.admincomplaint.retire (§366, commit
 * `48a5c2b`); verified by a filesystem check returning zero hits for
 * both paths. The `ClosureReasonDialog.kt` filename also returns zero
 * hits (the legacy closure-reason flow was inline in the §366-retired
 * legacy AdminComplaintScreen rather than a standalone file). The
 * status-exclusion list, the always-available "Add reason" affordance,
 * the always-available Delete override, and the non-blank
 * closure-reason validation rules all stand on their own merits —
 * documented inline above and independent of which legacy file
 * originally carried the parity precedent. CORRECTION (P3 validation
 * audit, 2026-06-01): the "500-char closure-reason validation parity"
 * phrasing in the line-95 citation and the original prose was INACCURATE
 * — native's `ClosureReasonDialog` reason field carries NO length cap
 * and NO counter; only the non-blank Add gate is shared. The 500-char
 * cap is an intentional KMP tightening, now documented as such in the
 * Closure-reason validation section above. Phase 9.x.admincomplaint.swap
 * (§365) flipped the route to this rework dialog pre-retire, closing
 * the swap-then-retire loop. Original §253-era prose preserved
 * verbatim per the audit-trail-preservation convention — the citations
 * are historical record of the design lineage; the admin action
 * dialog continues to drive admin mutations correctly through the
 * legacy retire.
 */
@Composable
internal fun AdminComplaintActionDialog(
    complaint: ComplaintSummary,
    mode: AdminActionDialogMode,
    isSubmitting: Boolean,
    onIntent: (AdminComplaintIntent) -> Unit,
) {
    Dialog(onDismissRequest = {
        if (!isSubmitting) onIntent(AdminComplaintIntent.OnDismissActionDialog)
    }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            when (mode) {
                AdminActionDialogMode.NONE -> Unit
                AdminActionDialogMode.MENU -> ActionSelectionContent(
                    complaint = complaint,
                    isSubmitting = isSubmitting,
                    onIntent = onIntent,
                )
                AdminActionDialogMode.STATUS_CHANGE -> StatusChangeContent(
                    complaint = complaint,
                    isSubmitting = isSubmitting,
                    onIntent = onIntent,
                )
                AdminActionDialogMode.CLOSURE_REASON -> ClosureReasonContent(
                    complaint = complaint,
                    isSubmitting = isSubmitting,
                    onIntent = onIntent,
                )
                AdminActionDialogMode.DELETE_CONFIRM -> DeleteConfirmationContent(
                    complaint = complaint,
                    isSubmitting = isSubmitting,
                    onIntent = onIntent,
                )
                AdminActionDialogMode.EDIT -> EditContent(
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
    onIntent: (AdminComplaintIntent) -> Unit,
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
                text = stringResource(Res.string.admin_actions),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            TextButton(
                onClick = { onIntent(AdminComplaintIntent.OnDismissActionDialog) },
                enabled = !isSubmitting,
            ) {
                Text(stringResource(Res.string.close))
            }
        }

        AdminComplaintPreviewCard(complaint = complaint)

        Column(
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            ElevatedButton(
                onClick = { onIntent(AdminComplaintIntent.OnSelectAction(AdminActionDialogMode.STATUS_CHANGE)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSubmitting,
            ) {
                Text(stringResource(Res.string.change_status), style = MaterialTheme.typography.bodyLarge)
            }

            OutlinedButton(
                onClick = { onIntent(AdminComplaintIntent.OnSelectAction(AdminActionDialogMode.EDIT)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSubmitting,
            ) {
                Text(stringResource(Res.string.edit), style = MaterialTheme.typography.bodyLarge)
            }

            OutlinedButton(
                onClick = { onIntent(AdminComplaintIntent.OnSelectAction(AdminActionDialogMode.CLOSURE_REASON)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSubmitting,
            ) {
                Text(stringResource(Res.string.add_closure_reason), style = MaterialTheme.typography.bodyLarge)
            }

            OutlinedButton(
                onClick = { onIntent(AdminComplaintIntent.OnSelectAction(AdminActionDialogMode.DELETE_CONFIRM)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSubmitting,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(Res.string.delete), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun StatusChangeContent(
    complaint: ComplaintSummary,
    isSubmitting: Boolean,
    onIntent: (AdminComplaintIntent) -> Unit,
) {
    val spacing = LocalSpacing.current
    // GAP-CMP-25 — radio-select-then-Confirm flow mirroring native `StatusChangeDialog.kt:45-92`
    // (a RadioButton + StatusChip row per status, Confirm enabled only when the selection differs
    // from the current status). Replaces the prior button-per-status immediate-apply layout.
    var selectedStatus by rememberSaveable(complaint.id) { mutableStateOf(complaint.status) }
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
                text = stringResource(Res.string.admincomplaint_change_status),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onIntent(AdminComplaintIntent.OnSelectAction(AdminActionDialogMode.MENU)) },
                enabled = !isSubmitting,
            ) {
                Text(stringResource(Res.string.back))
            }
        }

        AdminComplaintPreviewCard(complaint = complaint)

        Text(
            text = stringResource(Res.string.admincomplaint_current_status, complaint.status.displayName()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            ComplaintStatus.entries.forEach { candidate ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = candidate == selectedStatus,
                            enabled = !isSubmitting,
                            onClick = { selectedStatus = candidate },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    RadioButton(
                        selected = candidate == selectedStatus,
                        onClick = { selectedStatus = candidate },
                        enabled = !isSubmitting,
                    )
                    ComplaintStatusChip(status = candidate)
                }
            }
        }

        Button(
            onClick = { onIntent(AdminComplaintIntent.OnSubmitStatusChange(selectedStatus)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSubmitting && selectedStatus != complaint.status,
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text(stringResource(Res.string.np_confirm))
            }
        }
    }
}

@Composable
private fun ClosureReasonContent(
    complaint: ComplaintSummary,
    isSubmitting: Boolean,
    onIntent: (AdminComplaintIntent) -> Unit,
) {
    val spacing = LocalSpacing.current
    val maxChars = 500
    // GAP-CMP-23 — pre-fill the existing reason + type from the complaint (mirrors native
    // `ClosureReasonDialog`'s LaunchedEffect). The stored reason carries a `"${key}: "` prefix
    // for non-OTHER types; strip it for the editable field so re-submitting doesn't double-prefix
    // (native left the prefix in, which double-prefixed on edit — fixed here without behaviour loss
    // since `fromString` still resolves the same type from the stripped text + the type chip).
    val existingType = ClosureReasonType.fromString(complaint.reason)
    val existingReasonStripped = stripReasonTypePrefix(complaint.reason, existingType)
    var reasonText by rememberSaveable(complaint.id) { mutableStateOf(existingReasonStripped) }
    var selectedType by rememberSaveable(complaint.id) { mutableStateOf(existingType) }
    var showTypeDropdown by rememberSaveable(complaint.id) { mutableStateOf(false) }

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
                text = stringResource(Res.string.admincomplaint_closure_reason),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onIntent(AdminComplaintIntent.OnSelectAction(AdminActionDialogMode.MENU)) },
                enabled = !isSubmitting,
            ) {
                Text(stringResource(Res.string.back))
            }
        }

        AdminComplaintPreviewCard(complaint = complaint)

        // GAP-CMP-A-CLOSURE — native `StatusChangeDialog.kt:336-345` (ClosureReasonDialog) shows a
        // "Current status:" label + StatusChip row above the reason-type selector. Ported here for
        // parity (status was previously surfaced only in the STATUS_CHANGE dialog).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(Res.string.np_admin_current_status_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ComplaintStatusChip(status = complaint.status)
        }

        // GAP-CMP-23 — reason-type selector (DropdownMenu over ClosureReasonType.entries),
        // matching native `ClosureReasonDialog`.
        Text(
            text = stringResource(Res.string.np_reason_type),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = selectedType.displayText(),
                onValueChange = { },
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                enabled = !isSubmitting,
                trailingIcon = {
                    IconButton(
                        onClick = { showTypeDropdown = !showTypeDropdown },
                        enabled = !isSubmitting,
                    ) {
                        Icon(
                            imageVector = if (showTypeDropdown) {
                                Icons.Default.ArrowDropUp
                            } else {
                                Icons.Default.ArrowDropDown
                            },
                            contentDescription = stringResource(Res.string.np_select_reason_type),
                        )
                    }
                },
            )
            DropdownMenu(
                expanded = showTypeDropdown,
                onDismissRequest = { showTypeDropdown = false },
            ) {
                ClosureReasonType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.displayText()) },
                        onClick = {
                            selectedType = type
                            showTypeDropdown = false
                        },
                        leadingIcon = {
                            if (selectedType == type) {
                                Icon(Icons.Default.Check, contentDescription = null)
                            }
                        },
                    )
                }
            }
        }

        Text(
            text = stringResource(Res.string.np_closure_reason_details),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = reasonText,
            onValueChange = { if (it.length <= maxChars) reasonText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.admincomplaint_reason_label)) },
            placeholder = { Text(stringResource(Res.string.admincomplaint_closure_reason_placeholder)) },
            minLines = 3,
            maxLines = 6,
            enabled = !isSubmitting,
        )

        Text(
            // Counter routed through the `character_count` ("%1$d/%2$d") resource so digit/format
            // localization is preserved (same posture as the user-side complaint counter). The
            // counter itself is a KMP-only addition — native's ClosureReasonDialog has no counter.
            text = stringResource(Res.string.character_count, reasonText.length, maxChars),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        val canSubmit = reasonText.isNotBlank() && !isSubmitting
        Button(
            onClick = {
                // GAP-CMP-23 — build `"${type.key}: ${reason}"` for non-OTHER types (matches
                // native StatusChengeDialog.kt:440-444). The `:data` AddClosureReason impl then
                // writes reason/reasonAddedBy/reasonAddedAt metadata + auto-sets CLOSED from
                // OPEN/IN_PROGRESS (already implemented).
                val trimmed = reasonText.trim()
                val finalReason = if (selectedType != ClosureReasonType.OTHER) {
                    "${selectedType.key}: $trimmed"
                } else {
                    trimmed
                }
                onIntent(AdminComplaintIntent.OnSubmitClosureReason(finalReason))
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = canSubmit,
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text(stringResource(Res.string.add_reason))
            }
        }
    }
}

/**
 * Strips a leading `"${type.key}: "` prefix from a stored closure reason so the admin dialog's
 * editable text field shows the raw human reason while the dropdown shows the parsed type
 * (GAP-CMP-23). No-op for [ClosureReasonType.OTHER] (no prefix stored) and for reasons that don't
 * actually carry the prefix.
 */
private fun stripReasonTypePrefix(reason: String?, type: ClosureReasonType): String {
    if (reason.isNullOrBlank()) return ""
    if (type == ClosureReasonType.OTHER) return reason
    val colon = reason.indexOf(':')
    return if (colon in 0 until reason.length - 1) {
        reason.substring(colon + 1).trim()
    } else {
        reason
    }
}

@Composable
private fun EditContent(
    complaint: ComplaintSummary,
    isSubmitting: Boolean,
    onIntent: (AdminComplaintIntent) -> Unit,
) {
    val spacing = LocalSpacing.current
    val maxChars = 1000
    var selectedType by rememberSaveable(complaint.id) { mutableStateOf(complaint.type) }
    var editedSubject by rememberSaveable(complaint.id) { mutableStateOf(complaint.subject) }
    var editedBody by rememberSaveable(complaint.id) { mutableStateOf(complaint.body) }
    var showTypeDropdown by rememberSaveable(complaint.id) { mutableStateOf(false) }
    // GAP-CMP-A2 — native `StatusChangeDialog.kt:124-126/278` gates Save on `hasChanges` (the
    // edited value must differ from the original) so an unchanged complaint can't be re-submitted.
    // Native includes the Type dropdown selection (`selectedType != complaint.type`) in its gate.
    val hasChanges = selectedType != complaint.type ||
        editedSubject != complaint.subject ||
        editedBody != complaint.body

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
                text = stringResource(Res.string.admincomplaint_edit_complaint_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onIntent(AdminComplaintIntent.OnSelectAction(AdminActionDialogMode.MENU)) },
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
            Text(
                text = stringResource(
                    Res.string.admincomplaint_editing_complaint,
                    complaint.id.ifEmpty { stringResource(Res.string.admincomplaint_no_id) },
                ),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        // COMPLAINT-BK-A — native `EditComplaintDialog` (StatusChangeDialog.kt:156-205) exposes a
        // Type dropdown (read-only OutlinedTextField + DropdownMenu over ComplaintType.entries) so
        // the admin can re-categorize the complaint. The selection feeds the hasChanges gate above
        // and the OnSubmitEdit intent below.
        Text(
            text = stringResource(Res.string.bk_complaint_type_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = selectedType.displayName(),
                onValueChange = { },
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                shape = RoundedCornerShape(12.dp),
                enabled = !isSubmitting,
                trailingIcon = {
                    IconButton(
                        onClick = { showTypeDropdown = !showTypeDropdown },
                        enabled = !isSubmitting,
                    ) {
                        Icon(
                            imageVector = if (showTypeDropdown) {
                                Icons.Default.ArrowDropUp
                            } else {
                                Icons.Default.ArrowDropDown
                            },
                            contentDescription = stringResource(Res.string.bk_complaint_type_selector_cd),
                        )
                    }
                },
            )
            DropdownMenu(
                expanded = showTypeDropdown,
                onDismissRequest = { showTypeDropdown = false },
            ) {
                ComplaintType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.displayName()) },
                        onClick = {
                            selectedType = type
                            showTypeDropdown = false
                        },
                        leadingIcon = {
                            if (selectedType == type) {
                                Icon(Icons.Default.Check, contentDescription = null)
                            }
                        },
                    )
                }
            }
        }

        // GAP-CMP-A2 — native `StatusChangeDialog.kt:216-228` subject field uses sentence-case
        // auto-capitalization + a Next IME action.
        OutlinedTextField(
            value = editedSubject,
            onValueChange = { editedSubject = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.subject)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next,
            ),
            enabled = !isSubmitting,
        )

        // GAP-CMP-A2 — native body field uses sentence-case auto-capitalization. The 1000-char
        // cap + counter + minLines/maxLines editor sizing is an intentional rework improvement
        // over native's fixed 120.dp/maxLines-5 uncapped field; preserved.
        OutlinedTextField(
            value = editedBody,
            onValueChange = { if (it.length <= maxChars) editedBody = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.complaint_body)) },
            placeholder = { Text(stringResource(Res.string.edit_placeholder)) },
            minLines = 4,
            maxLines = 8,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
            ),
            isError = editedBody.length > maxChars,
            enabled = !isSubmitting,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                // Counter routed through the `character_count` ("%1$d/%2$d") resource so digit/
                // format localization is preserved (same posture as the user-side complaint
                // counter). The cap + counter is a KMP-only addition — native's admin edit field
                // has no counter (see EditContent KDoc above).
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
                onClick = { onIntent(AdminComplaintIntent.OnSelectAction(AdminActionDialogMode.MENU)) },
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
                        onIntent(AdminComplaintIntent.OnSubmitEdit(selectedType, trimmedSubject, trimmedBody))
                    }
                },
                modifier = Modifier.weight(1f),
                // GAP-CMP-A2 — Save disabled unless the value actually changed (hasChanges),
                // matching native's gate. Native EditComplaintDialog gates on the TRIMMED
                // subject/body (`subject.trim().isNotEmpty() && body.trim().isNotEmpty()`,
                // StatusChangeDialog.kt:278); gate on the trimmed value here too so the button's
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
                    Text(stringResource(Res.string.admincomplaint_save), color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirmationContent(
    complaint: ComplaintSummary,
    isSubmitting: Boolean,
    onIntent: (AdminComplaintIntent) -> Unit,
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
            // GAP-CMP-A-DEL — native `StatusChangeDialog.kt:466-471` (DeleteConfirmationDialog)
            // leads with an error-tinted Warning icon next to the title. Ported to the title row.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(Res.string.admincomplaint_delete_complaint_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = { onIntent(AdminComplaintIntent.OnSelectAction(AdminActionDialogMode.MENU)) },
                enabled = !isSubmitting,
            ) {
                Text(stringResource(Res.string.back))
            }
        }

        AdminComplaintPreviewCard(complaint = complaint)

        // GAP-CMP-A-DEL — native's delete details card surfaces the complaint's current status as a
        // "Status:" label + StatusChip (`StatusChangeDialog.kt:516-525`). The KMP preview card omits
        // it, so render the status row here for parity.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(Res.string.np_admin_status_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ComplaintStatusChip(status = complaint.status)
        }

        Text(
            text = stringResource(Res.string.admincomplaint_delete_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = { onIntent(AdminComplaintIntent.OnConfirmDelete) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSubmitting,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Text(stringResource(Res.string.delete_forever))
            }
        }
    }
}

@Composable
private fun AdminComplaintPreviewCard(complaint: ComplaintSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(Res.string.admincomplaint_preview_user, complaint.userId.take(12)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.height(8.dp))
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
    }
}
