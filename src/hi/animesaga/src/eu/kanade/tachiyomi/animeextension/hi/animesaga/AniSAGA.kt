package eu.kanade.tachiyomi.animeextension.hi.animesaga

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.chillxextractor.ChillxExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Response
import org.jsoup.nodes.Element

class AniSAGA : DooPlay(
    "hi",
    "AniSAGA",
    "https://anisaga.org",
) {
    val supportedGenres = listOf(
        "action-adventure",
        "comedy",
        "drama",
        "family",
        "romance",
        "sci-fi-fantasy",
    )

    private val videoHost = "https://plyrxcdn.site/"

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.top-imdb-list > div.top-imdb-item"

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div#episodes div.item.episode"

    // ============================== Filters ===============================
    fun genreListPath() = "genre"

    // ============================== Video Links ===========================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val playerUrls = document.select("ul#playeroptionsul li:not([id*=trailer])")
            .mapNotNull { runCatching { getPlayerUrl(it) }.getOrNull() }

        return playerUrls.flatMap { url ->
            runCatching {
                getPlayerVideos(url)
            }.getOrElse { e ->
                logError("Failed to extract videos from $url: ${e.message}")
                emptyList()
            }
        }.distinctBy { it.url }
    }

    private val chillxExtractor by lazy { ChillxExtractor(client, headers) }

    private fun getPlayerVideos(url: String): List<Video> {
        return when {
            videoHost in url -> chillxExtractor.videoFromUrl(url, baseUrl, prefix = "AniSAGA - ")
            else -> emptyList()
        }
    }

    private fun getPlayerUrl(player: Element): String {
        val post = player.attr("data-post").takeIf { it.isNotEmpty() } ?: return ""
        val nume = player.attr("data-nume").takeIf { it.isNotEmpty() } ?: return ""
        val type = player.attr("data-type").takeIf { it.isNotEmpty() } ?: return ""

        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", post)
            .add("nume", nume)
            .add("type", type)
            .build()

        val ajaxHeaders = Headers.Builder()
            .addAll(headers)
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Referer", baseUrl)
            .add("Origin", baseUrl)
            .build()

        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, body))
            .execute()
            .use { response ->
                val responseBody = response.body.string()
                responseBody
                    .substringAfter("\"embed_url\":\"")
                    .substringBefore("\",")
                    .replace("\\", "")
                    .takeIf { it.isNotEmpty() && videoHost in it }
                    ?: ""
            }
    }

    // ============================== Utilities =============================
    private fun logError(message: String) {
        // Replace with actual logging mechanism if available
        println("AniSAGA Error: $message")
    }

    // ============================== Paths =============================
    fun latestUpdatesPath() = "trending"
    fun animeListPath() = "tvshows"
    fun movieListPath() = "movies"
}
