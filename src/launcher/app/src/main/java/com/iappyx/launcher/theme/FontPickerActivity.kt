/*
 * MIT License - Copyright (c) 2026 iappyx
 *
 * Font browser. A WebView renders every family in its ACTUAL typeface:
 *  - bundled + already-downloaded fonts → local files (served via the
 *    shouldInterceptRequest hook → ThemeFonts.openFontStream), works offline.
 *  - not-yet-downloaded catalog fonts → live specimen from the Google Fonts
 *    CSS API (woff2 over the network), so you preview before downloading.
 * Tap a row → if not local, download the .ttf from GitHub (FontDownloader),
 * then return the chosen `--iappyx-font` stack via result extra "stack".
 * Downloaded fonts show a ✕ to delete the cached file.
 */
package com.iappyx.launcher.theme

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.iappyx.launcher.R
import com.iappyx.launcher.SettingsScaffold
import com.iappyx.launcher.widget.showThemed

class FontPickerActivity : AppCompatActivity() {

    private data class Row(val name: String, val stack: String, val entry: FontCatalog.Entry?)

    private lateinit var web: WebView
    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_font_picker)
        SettingsScaffold.attach(this, getString(R.string.font_picker_title))

        rows = buildList {
            for (f in ThemeFonts.ALL) add(Row(f.display, ThemeFonts.cssStack(f), null))
            for (e in FontCatalog.all(this@FontPickerActivity)) add(Row(e.family, FontCatalog.cssStack(e), e))
        }

        web = findViewById(R.id.font_web)
        web.setBackgroundColor(Color.parseColor("#0d0d13"))
        web.settings.javaScriptEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host == "widget.local" && url.path?.startsWith("/__themefont/") == true) {
                    val file = url.lastPathSegment ?: return null
                    val stream = ThemeFonts.openFontStream(this@FontPickerActivity, file) ?: return null
                    return WebResourceResponse("font/ttf", null, stream)
                }
                return null
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        reload()

        findViewById<EditText>(R.id.font_search).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = (s?.toString() ?: "").replace("'", "")
                web.evaluateJavascript("filter('${q.lowercase()}')", null)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun reload() {
        web.loadDataWithBaseURL("https://widget.local/", buildHtml(), "text/html", "UTF-8", null)
    }

    private fun buildHtml(): String {
        val localFaces = ThemeFonts.fontFaceCss(this) // bundled + downloaded @font-face
        val notDownloaded = FontCatalog.all(this).filter { !FontCatalog.isDownloaded(this, it) }
        val googleLink = if (notDownloaded.isNotEmpty()) {
            val fams = notDownloaded.joinToString("&") { "family=" + it.family.replace(" ", "+") }
            "<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?$fams&display=swap\">"
        } else ""
        val rowsHtml = rows.joinToString("") { r ->
            val have = ThemeFonts.resolveTypeface(this, r.stack) != null
            val removable = r.entry != null && FontCatalog.isDownloaded(this, r.entry)
            val st = if (have) "<span class=\"st have\">✓</span>" else "<span class=\"st\">↓</span>"
            val x = if (removable) "<span class=\"x\" onclick=\"rm('${r.name}',event)\">✕</span>" else ""
            "<div class=\"row\" data-fam=\"${r.name.lowercase()}\" onclick=\"pk('${r.name}')\">" +
                "<span class=\"name\" style=\"font-family:'${r.name}'\">${r.name}</span>$x$st</div>"
        }
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            $googleLink
            <style>$localFaces
            *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
            body{margin:0;padding:8px 0 32px;background:#0d0d13;color:#fff;font-family:-apple-system,Roboto,sans-serif}
            .row{display:flex;align-items:center;gap:10px;margin:8px 16px;padding:14px 16px;border-radius:14px;background:#16161f;cursor:pointer}
            .name{flex:1;font-size:21px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
            .x{color:#ff6b6b;font-size:15px;padding:6px}
            .st{color:#8b93a7;font-size:15px} .st.have{color:#46d39a}
            .hidden{display:none}
            </style></head><body><div id="list">$rowsHtml</div>
            <script>
            function pk(f){Android.pick(f)}
            function rm(f,e){e.stopPropagation();Android.remove(f)}
            function filter(q){document.querySelectorAll('.row').forEach(function(r){
              r.classList.toggle('hidden', q && r.dataset.fam.indexOf(q)<0)})}
            </script></body></html>""".trimIndent()
    }

    private inner class Bridge {
        @JavascriptInterface fun pick(family: String) { runOnUiThread { onPick(family) } }
        @JavascriptInterface fun remove(family: String) { runOnUiThread { onRemove(family) } }
    }

    private fun onPick(family: String) {
        val row = rows.firstOrNull { it.name == family } ?: return
        val available = ThemeFonts.resolveTypeface(this, row.stack) != null
        if (available || row.entry == null) {
            finishWith(row.stack)
        } else {
            Toast.makeText(this, "Downloading $family…", Toast.LENGTH_SHORT).show()
            FontDownloader.ensure(this, row.entry) { ok, err ->
                if (ok) finishWith(row.stack)
                else Toast.makeText(this, "Couldn't download $family${err?.let { " ($it)" } ?: ""}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onRemove(family: String) {
        val e = rows.firstOrNull { it.name == family }?.entry ?: return
        AlertDialog.Builder(this)
            .setTitle("Remove $family?")
            .setMessage("Deletes the downloaded font. You can re-download it anytime.")
            .setPositiveButton("Remove") { _, _ ->
                FontDownloader.delete(this, e)
                reload()
            }
            .setNegativeButton("Cancel", null)
            .showThemed()
    }

    private fun finishWith(stack: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra("stack", stack))
        finish()
    }
}
