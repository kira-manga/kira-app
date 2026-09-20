package me.manga.kira.data.complaint.backend

import co.touchlab.kermit.Logger

/** At most these four fixed diagnostics per complete load. Never include a token/key/ID or prose. */
internal enum class ComplaintHistoryMismatch {
    STATUS,
    TYPE,
    KIND,
    NOTICE_KEY,
    ;

    fun report() {
        Logger.withTag("ComplaintHistory").w { "contract_mismatch=" + name }
    }
}
