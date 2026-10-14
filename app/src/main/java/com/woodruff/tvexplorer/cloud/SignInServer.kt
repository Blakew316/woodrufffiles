package com.woodruff.tvexplorer.cloud

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.ResponseException
import java.io.IOException

/**
 * Short-lived LAN server used only while connecting Google Drive:
 *  - `/setup?k=KEY`: a phone-friendly form for pasting the OAuth client ID and secret, so nobody
 *    types a 72-character ID with a remote.
 *  - `/oauth/callback`: where the GitHub Pages relay forwards Google's one-time code.
 * Every request must carry the per-session [key] (setup) or the current sign-in `state`
 * (callback), so other devices on the network can't change the setup or inject a code.
 */
class SignInServer(
    port: Int,
    val key: String,
    private val expectedState: () -> String?,
    private val onClient: (id: String, secret: String) -> Unit,
    private val onCode: (code: String) -> Unit,
    private val onError: (String) -> Unit,
) : NanoHTTPD(port) {

    override fun serve(s: IHTTPSession): Response = try {
        when {
            s.uri == "/setup" -> setup(s)
            s.uri == "/oauth/callback" -> callback(s)
            else -> page(Response.Status.NOT_FOUND, "Not found", "Scan the QR code on the TV again.")
        }
    } catch (e: Exception) {
        page(Response.Status.INTERNAL_ERROR, "Something went wrong", e.message ?: "Try again from the TV.")
    }

    private fun setup(s: IHTTPSession): Response {
        if (s.method == Method.POST) {
            val body = HashMap<String, String>()
            try { s.parseBody(body) } catch (e: ResponseException) { throw IOException(e.message) }
            if (s.parameters["k"]?.firstOrNull() != key) return page(Response.Status.FORBIDDEN, "Link expired", "Scan the QR code on the TV again.")
            val id = s.parameters["client_id"]?.firstOrNull().orEmpty().trim()
            val secret = s.parameters["client_secret"]?.firstOrNull().orEmpty().trim()
            if (!id.endsWith(".apps.googleusercontent.com") || secret.isEmpty()) {
                return form("That doesn't look right: the Client ID ends in .apps.googleusercontent.com, and the secret usually starts with GOCSPX-.")
            }
            onClient(id, secret)
            return page(Response.Status.OK, "Saved ✓", "Look at your TV: it now shows a second QR code to sign in to Google.")
        }
        if (s.parameters["k"]?.firstOrNull() != key) return page(Response.Status.FORBIDDEN, "Link expired", "Scan the QR code on the TV again.")
        return form(null)
    }

    private fun callback(s: IHTTPSession): Response {
        val state = s.parameters["state"]?.firstOrNull()
        val expected = expectedState()
        if (state == null || expected == null || state != expected) {
            return page(Response.Status.FORBIDDEN, "Sign-in expired", "Start again from the TV and scan the new QR code.")
        }
        s.parameters["error"]?.firstOrNull()?.let {
            onError(if (it == "access_denied") "Sign-in was cancelled on the phone." else "Google said: $it")
            return page(Response.Status.OK, "Sign-in cancelled", "You can try again from the TV.")
        }
        val code = s.parameters["code"]?.firstOrNull() ?: return page(Response.Status.BAD_REQUEST, "Missing code", "Try again from the TV.")
        onCode(code)
        return page(Response.Status.OK, "You're signed in ✓", "Look at your TV: Woodruff Pictures is loading your Google Drive.")
    }

    private fun form(error: String?): Response = html(
        Response.Status.OK,
        """
        <h1>Connect Google Drive</h1>
        <p>Paste the two values from your Google Cloud <b>Web application</b> OAuth client
        (APIs &amp; Services → Credentials).</p>
        ${error?.let { "<p class=err>${esc(it)}</p>" } ?: ""}
        <form method="post" action="/setup?k=$key">
          <label>Client ID<input name="client_id" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="1234…apps.googleusercontent.com"></label>
          <label>Client secret<input name="client_secret" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="GOCSPX-…"></label>
          <button>Save</button>
        </form>
        """,
    )

    private fun page(status: Response.Status, title: String, body: String) =
        html(status, "<h1>${esc(title)}</h1><p>${esc(body)}</p>")

    private fun html(status: Response.Status, inner: String) = newFixedLengthResponse(
        status, "text/html; charset=utf-8",
        """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Woodruff Pictures</title><style>
        body{margin:0;padding:24px;font:16px/1.5 system-ui,sans-serif;background:#080b10;color:#f2f4f7}
        h1{font-size:22px;margin:0 0 12px}h1:before{content:"▶ ";color:#e5484d}p{color:#93a1b4}
        label{display:block;margin:14px 0;color:#93a1b4;font-size:14px}
        input{display:block;width:100%;box-sizing:border-box;margin-top:6px;padding:12px;font:inherit;color:#f2f4f7;background:#121822;border:1px solid #2a3544;border-radius:10px}
        button{margin-top:8px;padding:12px 22px;font:inherit;font-weight:600;color:#ffffff;background:#e5484d;border:0;border-radius:10px}
        .err{color:#ff7b72}</style></head><body>$inner</body></html>""",
    )

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
