package me.manga.yamiapk.presentation.features.about.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

private fun openLink(
    context: Context,
    appUri: Uri,
    webUri: Uri
) {
    val pm = context.packageManager

    // 1) Try app-specific URI first
    val appIntent = Intent(Intent.ACTION_VIEW, appUri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        if (appIntent.resolveActivity(pm) != null) {
            context.startActivity(appIntent)
            return
        }
    } catch (e: ActivityNotFoundException) {
        // fall through to web fallback
    } catch (t: Throwable) {
        // unexpected - fall through safely
    }

    // 2) Try generic web intent with chooser
    val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        if (webIntent.resolveActivity(pm) != null) {
            val chooser = Intent.createChooser(webIntent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            return
        }
    } catch (e: ActivityNotFoundException) {
        // fall through to Custom Tabs attempt
    } catch (t: Throwable) {
        // fall through
    }

    // 3) Try Custom Tabs (if browser supports it)
    try {
        val customTabsIntent = CustomTabsIntent.Builder().build()
        if (context is Activity) {
            customTabsIntent.launchUrl(context, webUri)
        } else {
            // launch from non-activity context
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(customTabsIntent.intent.setData(webUri))
        }
        return
    } catch (t: Throwable) {
        // ignore and fall through to final fallback
    }

    // Final fallback: notify user (you may add an in-app WebView activity as a better fallback)
    Toast.makeText(context, "No app available to open this link.", Toast.LENGTH_SHORT).show()
}
fun openTwitter(context: Context, username: String) {
    val appUri = "twitter://user?screen_name=$username".toUri()
    val webUri = "https://twitter.com/$username".toUri()
    openLink(context, appUri, webUri)
}

/** Opens a Facebook page or profile by id or vanity name. */
fun openFacebook(context: Context, pageIdOrName: String) {
    // “fb://facewebmodal/f?href=” works for both pages & profiles
    val appUri = "fb://facewebmodal/f?href=https://www.facebook.com/$pageIdOrName".toUri()
    val webUri = "https://www.facebook.com/$pageIdOrName".toUri()
    openLink(context, appUri, webUri)
}

/** Opens an Instagram profile by username. */
fun openInstagram(context: Context, username: String) {
    val appUri = "instagram://user?username=$username".toUri()
    val webUri = "https://www.instagram.com/$username".toUri()
    openLink(context, appUri, webUri)
}

/** Opens a GitHub profile or repo by path (e.g. “owner” or “owner/repo”). */
fun openGitHub(context: Context, path: String) {
    // GitHub Android app supports the “github://” scheme
    val appUri = "github://$path".toUri()
    val webUri = "https://github.com/$path".toUri()
    openLink(context, appUri, webUri)
}

/** Opens a Discord invite link or server by code (e.g. “abc123”) */
fun openDiscordInvite(context: Context, inviteCode: String) {
    val appUri = "discord://discordapp.com/invite/$inviteCode".toUri()
    val webUri = "https://discord.gg/$inviteCode".toUri()
    openLink(context, appUri, webUri)
}


fun openBrowser(context: Context, url: String) {
    // GitHub Android app supports the “github://” scheme
    val appUri = url.toUri()
    val webUri = url.toUri()
    openLink(context, appUri, webUri)
}
/** Sends a WhatsApp message to a phone number with prefilled text. */
fun sendWhatsAppMessage(context: Context, rawNumber: String, message: String) {
    val pm = context.packageManager

    // Normalize number for E.164-ish (simple Egypt special-case from your original code)
    val phoneNumber = when {
        rawNumber.startsWith("0") -> "20" + rawNumber.trimStart('0')
        else -> rawNumber
    }

    val encodedMessage = Uri.encode(message)

    // 1) Try WhatsApp app using whatsapp:// scheme
    val appUri = Uri.parse("whatsapp://send?phone=$phoneNumber&text=$encodedMessage")
    val appIntent = Intent(Intent.ACTION_VIEW, appUri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Try official package first
        setPackage("com.whatsapp")
    }

    try {
        // if com.whatsapp can handle it -> open
        if (appIntent.resolveActivity(pm) != null) {
            context.startActivity(appIntent)
            return
        }

        // maybe user has WhatsApp Business
        val businessIntent = Intent(Intent.ACTION_VIEW, appUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.whatsapp.w4b")
        }
        if (businessIntent.resolveActivity(pm) != null) {
            context.startActivity(businessIntent)
            return
        }

        // 2) Fallback to browser with api.whatsapp.com (works if no app)
        val webUri = "https://api.whatsapp.com/send?phone=$phoneNumber&text=$encodedMessage".toUri()
        val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // use chooser when possible
        if (webIntent.resolveActivity(pm) != null) {
            context.startActivity(Intent.createChooser(webIntent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }

    } catch (e: ActivityNotFoundException) {
        // fall through to final fallback
    } catch (t: Throwable) {
        // log if you want, but fall through
    }

    // Final fallback: notify user (or open an in-app WebView)
    Toast.makeText(context, "No app available to open WhatsApp link.", Toast.LENGTH_SHORT).show()
}
