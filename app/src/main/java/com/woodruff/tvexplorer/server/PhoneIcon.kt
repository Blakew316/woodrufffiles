package com.woodruff.tvexplorer.server

import android.content.Context
import com.woodruff.tvexplorer.tvs.TvLinks
import org.json.JSONObject
import java.security.MessageDigest

/**
 * What a phone calls this TV's web app on its Home Screen: the TV's own name (Settings → TVs &
 * phones → This TV's name, e.g. "Living Room"), so each TV's icon says which TV it opens. A TV that
 * hasn't been given a name keeps the app's name. Only the icon's label changes: its logo, the app
 * inside it, and the name of Woodruff on the TV all stay "Woodruff Family".
 *
 * iOS takes the label from the page's apple-mobile-web-app-title, Android from the manifest, both
 * when the icon is added (an icon added before keeps its label until it's added again).
 */
object PhoneIcon {
    const val APP_NAME = "Woodruff Family"

    fun label(c: Context): String = TvLinks.givenName(c) ?: APP_NAME

    /** The web app's page for this TV: its pairing key and Home Screen label filled in. */
    fun render(template: String, token: String, label: String): String = template
        .replace("__TVX_KEY__", token)
        .replace("__TVX_HOME_NAME__", escapeHtml(label))
        .replace("__TVX_HOME_TAG__", tag(label))

    /** The Home Screen app's manifest ([template], from assets) named [label]. */
    fun manifest(template: String, label: String): String =
        JSONObject(template).put("name", label).put("short_name", label).toString()

    /**
     * A short stand-in for [label] in the manifest's address: phones keep the manifest a week, so a
     * renamed TV gives them a new address to fetch rather than the old name.
     */
    fun tag(label: String): String =
        MessageDigest.getInstance("SHA-256").digest(label.toByteArray()).take(4).joinToString("") { "%02x".format(it) }

    /** [s] made safe inside an HTML attribute. */
    fun escapeHtml(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }
}
