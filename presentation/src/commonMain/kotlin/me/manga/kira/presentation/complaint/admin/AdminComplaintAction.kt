package me.manga.kira.presentation.complaint.admin

/**
 * The semantic admin complaint action whose success snackbar an
 * [AdminComplaintEffect.ShowActionSuccess] announces. Carrying the action (not its English copy)
 * keeps i18n text out of effects per the MVI contract — `:ui` maps each value to a localized
 * `stringResource`.
 */
enum class AdminComplaintAction {
    STATUS_UPDATED,
    CLOSURE_REASON_ADDED,
    DELETED,
    UPDATED,
    BODY_COPIED,
}
