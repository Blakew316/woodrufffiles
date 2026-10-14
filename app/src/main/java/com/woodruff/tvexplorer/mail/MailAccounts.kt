package com.woodruff.tvexplorer.mail

import android.content.Context
import com.woodruff.tvexplorer.settings.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Known providers. All use IMAP over SSL with an app-specific password. */
enum class MailProvider(val label: String, val host: String, val port: Int, val passwordHelp: String) {
    ICLOUD("iCloud Mail", "imap.mail.me.com", 993,
        "Create an app-specific password at account.apple.com → Sign-In and Security → App-Specific Passwords."),
    GMAIL("Gmail", "imap.gmail.com", 993,
        "Turn on 2-Step Verification, then create an app password at myaccount.google.com/apppasswords."),
    YAHOO("Yahoo Mail", "imap.mail.yahoo.com", 993,
        "Create an app password in Yahoo Account Security → Generate app password."),
    OUTLOOK("Outlook / Hotmail", "outlook.office365.com", 993,
        "Microsoft has switched most personal accounts off password sign-in for IMAP; this may be refused."),
    CUSTOM("Other (IMAP)", "", 993, "Use your mail provider's IMAP server, port 993 (SSL)."),
}

data class MailAccount(
    val id: String,
    val provider: MailProvider,
    val email: String,
    val host: String,
    val port: Int,
    val password: String,
) {
    val label: String get() = "${provider.label} · $email"
}

/** Mail accounts, stored encrypted (passwords included) in [SecretStore]. */
object MailAccounts {
    private const val KEY = "mail.accounts"

    fun list(c: Context): List<MailAccount> {
        val raw = SecretStore.get(c).get(KEY) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            MailAccount(
                o.optString("id"),
                runCatching { MailProvider.valueOf(o.optString("provider")) }.getOrDefault(MailProvider.CUSTOM),
                o.optString("email"), o.optString("host"), o.optInt("port", 993), o.optString("password"),
            )
        }
    }

    fun add(c: Context, provider: MailProvider, email: String, password: String, host: String? = null, port: Int? = null): MailAccount {
        val a = MailAccount(
            UUID.randomUUID().toString(), provider, email.trim(),
            host?.trim()?.ifEmpty { null } ?: provider.host, port ?: provider.port,
            // App passwords are often shown with spaces ("abcd efgh …"); IMAP wants them without.
            password.replace(" ", ""),
        )
        require(a.email.contains('@')) { "Enter the full email address" }
        require(a.host.isNotBlank()) { "Enter the IMAP server" }
        require(a.password.isNotBlank()) { "Enter the app-specific password" }
        save(c, list(c).filter { it.email != a.email || it.host != a.host } + a)
        return a
    }

    fun remove(c: Context, id: String) = save(c, list(c).filter { it.id != id })

    private fun save(c: Context, items: List<MailAccount>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("id", it.id).put("provider", it.provider.name).put("email", it.email)
                .put("host", it.host).put("port", it.port).put("password", it.password))
        }
        SecretStore.get(c).put(KEY, arr.toString())
    }
}
