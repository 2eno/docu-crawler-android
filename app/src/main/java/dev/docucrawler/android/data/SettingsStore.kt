package dev.docucrawler.android.data

import android.content.Context
import dev.docucrawler.android.crawler.CrawlConfig

/** Merkt sich die zuletzt verwendete Crawl-Konfiguration. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("crawl_settings", Context.MODE_PRIVATE)

    fun load(): CrawlConfig? {
        val url = prefs.getString("startUrl", null) ?: return null
        return CrawlConfig(
            startUrl = url,
            projectName = prefs.getString("projectName", "").orEmpty(),
            maxDepth = prefs.getInt("maxDepth", 3),
            delayMs = prefs.getLong("delayMs", 500),
            contentSelector = prefs.getString("selector", null),
            restrictToPathPrefix = prefs.getBoolean("restrictPrefix", true),
            maxPages = prefs.getInt("maxPages", 0),
            excludePatterns = prefs.getString("excludes", null)
                ?.split('\n')?.filter { it.isNotBlank() }
                ?: CrawlConfig.DEFAULT_EXCLUDES,
        )
    }

    fun save(config: CrawlConfig) {
        prefs.edit()
            .putString("startUrl", config.startUrl)
            .putString("projectName", config.projectName)
            .putInt("maxDepth", config.maxDepth)
            .putLong("delayMs", config.delayMs)
            .putString("selector", config.contentSelector)
            .putBoolean("restrictPrefix", config.restrictToPathPrefix)
            .putInt("maxPages", config.maxPages)
            .putString("excludes", config.excludePatterns.joinToString("\n"))
            .apply()
    }
}
