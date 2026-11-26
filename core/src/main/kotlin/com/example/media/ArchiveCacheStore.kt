package com.example.media

import java.io.File

/**
 * Platform-neutral cache file and trending feed cache provider.
 */
interface ArchiveCacheStore {
    fun archiveCacheFile(siteId: String): File
    fun loadTrendingCache(): TrendingFeedBundle?
    fun saveTrendingCache(bundle: TrendingFeedBundle)
}
