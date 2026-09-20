package me.manga.kira.data.remote.complaint

/** Closed native receive policies; callers cannot supply an arbitrary cap or accounting callback. */
internal sealed interface ComplaintReceiveBudget {
    val receivedBytes: Int
    val remainingBytes: Int

    fun accept(byteCount: ULong): Boolean

    fun isComplete(): Boolean
}
