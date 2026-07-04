package me.manga.yamiapk.presentation.features.complaint.data

import android.content.Context
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.features.complaint.model.Complaint
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintStatus
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintType
import java.util.Date

// Add two custom complaints to the top before displaying the list
val customTopComplaints = listOf(
    Complaint(
        id = "admin",
        userId = "0",
        type = ComplaintType.CUSTOM, // or any type you define
        subject = "Pinned: Please check this urgent issue",
        body = "This is a manually pinned complaint.",
        createdAt = Date(),
        status = ComplaintStatus.PINNED,
        metadata = mapOf(
            "osVersion" to "0",
            "pinned" to true,
            "reason" to "pinned :teesrasrgasdsfdgrgaergaergaergraeggerg"
        )
    ),
    Complaint(
        id = "admin",
        userId = "0",
        type = ComplaintType.CUSTOM,
        subject = "Pinned: Known bug in version 1.0.0Pinned: Known bug in version 1.0.0Pinned: Known bug in version 1.0.0Pinned: Known bug in version 1.0.0",
        body = "We're aware of this issue and working on a fix.We're aware of this issue and working on a fix.We're aware of this issue and working on a fix.",
        createdAt = Date(),
        status = ComplaintStatus.PINNED,
        metadata = mapOf(
            "osVersion" to "0",
            "pinned" to true,
            "reason" to "pinned :test 123456789:test 123456789:test 123456789:test 123456789:test 123456789:test 123456789:test 123456789:test 123456789:test 123456789:test 123456789"
        )
    )
)
fun getCustomTopComplaints(context: Context): List<Complaint> {
    return listOf(
        Complaint(
            id = context.getString(R.string.admin),
            userId = "0",
            type = ComplaintType.CUSTOM,
            subject = context.getString(R.string.content_removed_18_hentai),
            body = context.getString(R.string.references_to_adult_18_content_aren_t_allowed_here_so_we_ve_removed_them_to_keep_our_community_safe_thanks_for_understanding),
            createdAt = Date(),
            status = ComplaintStatus.PINNED,
            metadata = mapOf(
                "osVersion" to "0",
                "pinned" to true,
                // A human-readable reason for why it was removed:
                "reason" to context.getString(R.string.removed_18_hentai_reference_to_comply_with_community_guidelines)
            )
        ),
        Complaint(
            id = context.getString(R.string.admin),
            userId = "0",
            type = ComplaintType.CUSTOM,
            subject = context.getString(R.string.pinned_new_manga_site_requirements),
            body = context.getString(R.string.any_new_manga_site_must_offer_at_least_200_titles_have_no_bot_verification_steps_and_be_worth_the_setup_effort_adding_a_site_takes_significant_time_and_work),
            createdAt = Date(),
            status = ComplaintStatus.PINNED,
            metadata = mapOf(
                "osVersion" to "0",
                "pinned" to true,
                "reason" to "pinned : "+ context.getString(R.string.new_site_requires_200_mangas_no_bot_checks_to_justify_the_manual_setup_effort)
            )
        )

    )
}