/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.about

/**
 * One row in the acknowledgements list. Grouped by copyright holder + license
 * (not by Maven coordinate) so the screen stays readable — the 24+ AndroidX
 * packages collapse to one entry, the four ML Kit modules to one entry, etc.
 *
 * @param licenseKind one of the keys in [LicenseTexts]: "mit", "apache2",
 *                    "bsd2", "bsd3", "lgpl21".
 */
data class Acknowledgement(
    val name: String,
    val description: String,
    val copyrightLine: String,
    val licenseKind: LicenseKind,
)

enum class LicenseKind { APACHE2, BSD2, BSD3, LGPL21, MIT, OFL }

/**
 * The launcher's third-party dependency inventory. One entry per unique
 * (copyright holder × license) pair, not per Maven artifact — over-listing
 * by artifact reads as noise without adding meaning. New deps go HERE so
 * the about screen reflects reality on the next build.
 */
object Acknowledgements {
    val ALL: List<Acknowledgement> = listOf(
        Acknowledgement(
            name = "AndroidX libraries",
            description = "Core, AppCompat, RecyclerView, ViewPager2, Camera, Media3, Lifecycle, Security-Crypto, Biometric, Webkit, Palette, Dynamic Animation, ConstraintLayout, GridLayout, ExifInterface, Car App.",
            copyrightLine = "Copyright (c) The Android Open Source Project",
            licenseKind = LicenseKind.APACHE2,
        ),
        Acknowledgement(
            name = "Material Components for Android",
            description = "Material Design components used throughout the launcher's UI.",
            copyrightLine = "Copyright (c) Google LLC",
            licenseKind = LicenseKind.APACHE2,
        ),
        Acknowledgement(
            name = "ML Kit",
            description = "On-device barcode scanning, text recognition, image labeling, and selfie segmentation. Used by widget bridges that the launcher exposes to generated widgets.",
            copyrightLine = "Copyright (c) Google LLC",
            licenseKind = LicenseKind.APACHE2,
        ),
        Acknowledgement(
            name = "Firebase Cloud Messaging",
            description = "Optional push-notification delivery surface for widgets that opt in.",
            copyrightLine = "Copyright (c) Google LLC",
            licenseKind = LicenseKind.APACHE2,
        ),
        Acknowledgement(
            name = "Google Play Services Location",
            description = "Geofencing primitives behind profile geofence triggers.",
            copyrightLine = "Copyright (c) Google LLC",
            licenseKind = LicenseKind.APACHE2,
        ),
        Acknowledgement(
            name = "OkHttp",
            description = "HTTP client used by the AI service, the Showcase fetcher, and the widget HTTP bridge.",
            copyrightLine = "Copyright (c) Square, Inc.",
            licenseKind = LicenseKind.APACHE2,
        ),
        Acknowledgement(
            name = "ZXing core",
            description = "QR code encoding for the QR transfer flow.",
            copyrightLine = "Copyright (c) ZXing authors",
            licenseKind = LicenseKind.APACHE2,
        ),
        Acknowledgement(
            name = "Nordic Semiconductor BLE library",
            description = "Bluetooth Low Energy scaffolding used by the widget BLE bridge.",
            copyrightLine = "Copyright (c) Nordic Semiconductor ASA",
            licenseKind = LicenseKind.BSD3,
        ),
        Acknowledgement(
            name = "JSch (mwiede fork)",
            description = "SSH client used by the widget SSH bridge.",
            copyrightLine = "Copyright (c) ymnk and contributors; fork (c) Michael Wiedemann",
            licenseKind = LicenseKind.BSD3,
        ),
        Acknowledgement(
            name = "jCIFS-NG",
            description = "SMB/CIFS client used by the widget SMB bridge. See license text for your relink rights.",
            copyrightLine = "Copyright (c) The jCIFS-NG authors",
            licenseKind = LicenseKind.LGPL21,
        ),
        Acknowledgement(
            name = "Leaflet",
            description = "Map library used by the geofence picker (bundled in assets/leaflet.js + leaflet.css).",
            copyrightLine = "Copyright (c) 2010-2024 Volodymyr Agafonkin and contributors",
            licenseKind = LicenseKind.BSD2,
        ),
        Acknowledgement(
            name = "Theme fonts",
            description = "Optional typefaces selectable in the theme editor: Inter, Poppins, Nunito, Space Grotesk, Lora, and JetBrains Mono (bundled in assets/fonts/).",
            copyrightLine = "Copyright (c) The Inter, Poppins, Nunito, Space Grotesk, Lora, and JetBrains Mono Project Authors",
            licenseKind = LicenseKind.OFL,
        ),
    )
}
