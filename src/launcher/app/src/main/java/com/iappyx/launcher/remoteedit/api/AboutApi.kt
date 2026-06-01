/*
 * MIT License - Copyright (c) 2026 iappyx
 * REMOTE EDIT FEATURE — about endpoint. Surfaces build / version
 * metadata so the web Settings tab can show the same About info the
 * on-device Settings activity does.
 */
package com.iappyx.launcher.remoteedit.api

import android.content.Context
import android.os.Build
import com.iappyx.launcher.BuildConfig
import com.iappyx.launcher.R
import com.iappyx.launcher.about.Acknowledgements
import com.iappyx.launcher.about.LicenseKind
import com.iappyx.launcher.about.LicenseTexts
import com.iappyx.launcher.remoteedit.server.JsonResponse
import com.iappyx.launcher.remoteedit.server.MicroHttpServer
import org.json.JSONArray
import org.json.JSONObject

class AboutApi(private val context: Context) {

    fun get(ex: MicroHttpServer.Exchange) {
        val resp = JSONObject().apply {
            // Build / device facts.
            put("appName", context.getString(R.string.app_name))
            put("version", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("buildType", BuildConfig.BUILD_TYPE)
            put("package", BuildConfig.APPLICATION_ID)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")

            // Marketing / human-readable info, sourced from the same
            // strings.xml entries the on-device About activity uses.
            put("tagline", context.getString(R.string.about_tagline))
            put("footer", context.getString(R.string.about_footer))

            // License (the launcher itself — MIT).
            put("license", JSONObject().apply {
                put("label", context.getString(R.string.about_license_label))
                put("value", context.getString(R.string.about_license_value))
                put("text", LicenseTexts.MIT.trim())
            })

            // Source-code URL (sans scheme — same form the on-device row
            // shows; the web client should prefix `https://`).
            put("sourceUrl", context.getString(R.string.about_source_url))

            // Support / donate link (full URL, ready to open).
            put("support", JSONObject().apply {
                put("label", context.getString(R.string.about_support_label))
                put("value", context.getString(R.string.about_support_value))
                put("url", context.getString(R.string.about_support_url))
            })

            // Privacy block.
            put("privacy", JSONObject().apply {
                put("title", context.getString(R.string.about_privacy_title))
                put("body", context.getString(R.string.about_privacy_body))
            })

            // Acknowledgements list — same data the on-device
            // AcknowledgementsActivity walks. The web doesn't need to
            // ship every license text inline; it includes the LICENSE
            // KIND per entry so a click can lazy-fetch the body from
            // /api/about/license/{kind}.
            val acks = JSONArray()
            for (a in Acknowledgements.ALL) {
                acks.put(JSONObject().apply {
                    put("name", a.name)
                    put("description", a.description)
                    put("copyrightLine", a.copyrightLine)
                    put("licenseKind", a.licenseKind.name.lowercase())
                })
            }
            put("acknowledgements", acks)
        }
        JsonResponse.ok(ex, resp)
    }

    /** GET /api/about/license/{kind} — returns the full canonical
     *  license text for [kind]. Kept as a separate endpoint so the
     *  main /about response stays lean; the web client fetches a
     *  license body only when the user opens that license. */
    fun license(ex: MicroHttpServer.Exchange, kindStr: String) {
        val text = when (kindStr.lowercase()) {
            "mit" -> LicenseTexts.MIT
            "apache2" -> LicenseTexts.APACHE2
            "bsd2" -> LicenseTexts.BSD2
            "bsd3" -> LicenseTexts.BSD3
            "lgpl21" -> LicenseTexts.LGPL21_SUMMARY
            else -> return JsonResponse.error(ex, 404, "unknown license: $kindStr")
        }
        JsonResponse.ok(ex, JSONObject().apply {
            put("kind", kindStr.lowercase())
            put("text", text.trim())
        })
    }
}
