package me.manga.kira.data.remote.complaint

import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.KtorNSURLSessionDelegate
import io.ktor.http.Url
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSURLSession
import me.manga.kira.data.remote.complaint.IosComplaintInstallationPolicy as InstallationPolicy

/**
 * Creates an owned, isolated iOS engine for one serialized session exchange at a time.
 * The fixed HTTPS target is validated before session allocation. No configured session,
 * delegate, credential store or trust override is accepted by this public factory.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
fun createIosComplaintSessionEngineOwner(sessionUrl: Url): ComplaintSessionEngineOwner? {
    val target = iosComplaintSessionTarget(sessionUrl) ?: return null
    return createIosComplaintInstallationEngineOwner(InstallationPolicy.Session(target))
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal fun createIosComplaintInstallationEngineOwner(policy: InstallationPolicy): ComplaintSessionEngineOwner {
    val ktor =
        KtorNSURLSessionDelegate { _, _, challenge, complete ->
            complete(
                iosComplaintSessionChallengeDisposition(challenge.protectionSpace.authenticationMethod),
                null,
            )
        }
    val guard = IosComplaintSessionReceiveGuard(policy, IosComplaintSessionKtorCallbacks(ktor))
    val session =
        NSURLSession.sessionWithConfiguration(
            iosComplaintSessionConfiguration(),
            IosComplaintSessionDelegate(guard, ktor),
            delegateQueue = null,
        )
    return ownIosSessionEngine(session, ktor, guard)
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private fun ownIosSessionEngine(
    session: NSURLSession,
    ktor: KtorNSURLSessionDelegate,
    guard: IosComplaintSessionReceiveGuard,
): ComplaintSessionEngineOwner {
    var transferred = false
    return try {
        val engine =
            Darwin.create {
                usePreconfiguredSession(session, ktor)
                configureRequest { guard.prepare(this) }
            }
        // The owner cancels engine call contexts and closes Ktor's task-creation gate first.
        OwnedComplaintSessionEngine(engine, { guard.close(session) }, session::invalidateAndCancel)
            .also { transferred = true }
    } finally {
        if (!transferred) {
            try {
                guard.close(session)
            } finally {
                session.invalidateAndCancel()
            }
        }
    }
}
