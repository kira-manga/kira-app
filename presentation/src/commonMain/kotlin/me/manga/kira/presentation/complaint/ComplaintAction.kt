package me.manga.kira.presentation.complaint

/**
 * The semantic user-side complaint action whose success snackbar a [ComplaintEffect.ShowActionSuccess]
 * announces. Carrying the action (not its English copy) keeps i18n text out of effects per the MVI
 * contract — `:ui` maps each value to a localized `stringResource`.
 */
enum class ComplaintAction {
    REPLY_SENT,
    UPDATED,
    DELETED,
    BODY_COPIED,
}
