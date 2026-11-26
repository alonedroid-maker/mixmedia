package com.example.media

/**
 * ============================================================================
 *  Built-in Movie & Series Source Sites Catalog (فهرست سایت‌های منبع فیلم و سریال)
 * ============================================================================
 *
 * You can edit, add, or remove built-in websites right here in this file,
 * commit/push to GitHub, and build a new release APK via GitHub Actions.
 *
 * برای ویرایش، افزودن یا حذف سایت‌های پیش‌فرض جستجو، فقط کافیست این فایل را در گیت‌هاب
 * ویرایش کنید و با ورک‌فلو گیت‌هاب نسخه جدید را بسازید.
 *
 * Fields (فیلدها):
 * - id: Unique short English ID (شناسه یکتای انگلیسی، مثلا "my_site")
 * - name: Display name shown in the app (نام نمایشی در برنامه)
 * - baseUrl: Homepage URL of the site (آدرس صفحه اصلی سایت)
 * - searchUrlTemplate: Search URL containing `{query}` (الگوی لینک جستجو با `{query}`)
 * - isArchive: Set true ONLY for single-page HTML archive lists (فقط برای آرشیوهای تک‌صفحه)
 * - enabled: Default switch state (فعال بودن پیش‌فرض: true یا false)
 * - requiresDomesticIpHint: Show Iranian-IP hint on HTTP 503/403 (نیاز به آی‌پی ایران)
 */
object SourceSitesCatalog {

    val BUILT_IN_SITES: List<SourceSiteConfig> = listOf(
        SourceSiteConfig(
            id = "serialblog",
            name = "SerialBlog (سریال بلاگ)",
            baseUrl = "https://serialblog1.top",
            searchUrlTemplate = "https://serialblog1.top/?s={query}",
            isArchive = false,
            enabled = true,
            isCustom = false,
        ),
        SourceSiteConfig(
            id = "donyayeserial",
            name = "DonyayeSerial (دنیای سریال)",
            baseUrl = "https://donyayeserial-new1.top",
            searchUrlTemplate = "https://donyayeserial-new1.top/?s={query}",
            isArchive = false,
            enabled = true,
            isCustom = false,
        ),
        SourceSiteConfig(
            id = "myf2ms",
            name = "MyF2Ms",
            baseUrl = "https://www.myf2ms.top",
            searchUrlTemplate = "https://www.myf2ms.top/?s={query}",
            isArchive = false,
            enabled = true,
            isCustom = false,
        ),
        SourceSiteConfig(
            id = "donyayeserial_archive_all",
            name = "DonyayeSerial Archive 26k+",
            baseUrl = "https://dls2.aparatchi-dlcenter.top/DonyayeSerial/donyaye_serial_all_archive.html",
            searchUrlTemplate = "https://dls2.aparatchi-dlcenter.top/DonyayeSerial/donyaye_serial_all_archive.html",
            isArchive = true,
            enabled = true,
            isCustom = false,
            requiresDomesticIpHint = true,
        ),
        SourceSiteConfig(
            id = "donyayeserial_archive_10k",
            name = "DonyayeSerial Archive 10k",
            baseUrl = "https://dls6.aparatchi-dlcenter.top/DonyayeSerial/10_thous.html",
            searchUrlTemplate = "https://dls6.aparatchi-dlcenter.top/DonyayeSerial/10_thous.html",
            isArchive = true,
            enabled = true,
            isCustom = false,
            requiresDomesticIpHint = true,
        ),
        SourceSiteConfig(
            id = "archive_org",
            name = "Internet Archive",
            baseUrl = "https://archive.org",
            searchUrlTemplate = "https://archive.org/search?query={query}",
            isArchive = false,
            enabled = true,
            isCustom = false,
        ),
    )
}
