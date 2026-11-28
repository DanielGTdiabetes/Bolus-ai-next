package org.bolusai.next.mfp

import org.bolusai.mfp.MfpDate

/**
 * The only addresses the MyFitnessPal link may reach (ADR 0032, "Sesión y red"). The app has the Internet permission
 * for this link alone, and this is where it is limited by code: every request of the session goes through
 * [mainFrameAllowed] or [resourceAllowed] before it leaves the phone.
 *
 * Nothing here points at Legacy, the NAS, Render or the mini PC, and `verify.ps1` keeps it so.
 */
internal object MfpHosts {
    const val DIARY_HOST = "www.myfitnesspal.com"
    private const val DOMAIN = "myfitnesspal.com"

    /**
     * Cloudflare's challenge, which MyFitnessPal's sign-in page embeds. Observed on the Pixel on 2026-11-28: with it
     * blocked the sign-in fails with a captcha verification error. Allowed only as something a page loads, never as
     * the page shown. The other hosts that page asked for (analytics, telemetry, consent manager) stay blocked.
     */
    const val CHALLENGE_HOST = "challenges.cloudflare.com"

    /** The page the Legacy adapter reads (ADR 0032, "Adaptador del mini PC"): a read-only GET of one day. */
    fun diaryUrl(date: MfpDate): String = "https://$DIARY_HOST/food/diary?date=${date.text}"

    /** The diary without a day: MyFitnessPal shows its own current day. Only for viewing, never for a reading. */
    const val DIARY_PAGE = "https://$DIARY_HOST/food/diary"

    /** The page shown in the session view: HTTPS and exactly the diary host. */
    fun mainFrameAllowed(url: String): Boolean = host(url) == DIARY_HOST

    /** What a page may load: HTTPS from MyFitnessPal's own domain or [CHALLENGE_HOST], or content that needs no connection. */
    fun resourceAllowed(url: String): Boolean {
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme == "data" || scheme == "blob" || scheme == "about") return true
        val host = host(url) ?: return false
        return host == DOMAIN || host.endsWith(".$DOMAIN") || host == CHALLENGE_HOST
    }

    /**
     * Lowercase host of an HTTPS address, or `null` when it is not HTTPS or its authority carries user information or
     * a port. A backslash ends the authority, as browsers read it, so it cannot hide another host.
     */
    fun host(url: String): String? {
        val scheme = "https://"
        if (!url.startsWith(scheme, ignoreCase = true)) return null
        val authority = url.substring(scheme.length).takeWhile { it != '/' && it != '?' && it != '#' && it != '\\' }
        if (authority.isEmpty() || authority.any { it == '@' || it == ':' || it.isWhitespace() }) return null
        return authority.lowercase()
    }

    /** Host of any address, only to tell the user what was blocked. Never used to allow anything. */
    fun blockedLabel(url: String): String =
        url.substringAfter("://", url).takeWhile { it != '/' && it != '?' && it != '#' && it != '\\' }
            .substringAfterLast('@').lowercase().take(100).ifEmpty { "desconocido" }
}
