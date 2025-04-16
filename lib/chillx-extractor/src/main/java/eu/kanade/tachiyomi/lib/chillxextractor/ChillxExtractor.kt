package eu.kanade.tachiyomi.lib.chillxextractor

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class ChillxExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val json: Json by injectLazy()
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    companion object {
        private val REGEX_MASTER_JS = Regex("""\s*=\s*'([^']+)'""")
        private val REGEX_SOURCES = Regex("""sources:\s*\[\s*\{\s*"file"\s*:\s*"([^"]+)""")
        private val REGEX_FILE = Regex("""file\s*:\s*"([^"]+)"""")
        private val REGEX_SOURCE = Regex("""source\s*=\s*"([^"]+)"""")
        private val REGEX_SUBS = Regex("""\{"file"\s*:\s*"([^"]+)","label"\s*:\s*"([^"]+)","kind"\s*:\s*"captions"(?:,"default"\s*:\s*\w+)?\}""")
        private val KEY_SOURCES = listOf(
            "https://raw.githubusercontent.com/Rowdy-Avocado/multi-keys/keys/index.html",
            "https://raw.githubusercontent.com/backup-keys/chillx/main/keys.json",
        )
    }

    fun videoFromUrl(url: String, referer: String, prefix: String = "Chillx - "): List<Video> {
        try {
            val newHeaders = headers.newBuilder()
                .set("Referer", referer.trimEnd('/') + "/")
                .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .set("Accept-Language", "en-US,en;q=0.5")
                .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36")
                .build()

            val body = client.newCall(GET(url, newHeaders)).execute().body.string()

            val master = REGEX_MASTER_JS.find(body)?.groupValues?.get(1)
                ?: throw ErrorLoadingException("Master JS not found")

            val aesJson = json.decodeFromString<CryptoInfo>(master)
            val key = fetchKey() ?: throw ErrorLoadingException("Unable to fetch decryption key")
            val decryptedScript = CryptoAES.decryptWithSalt(aesJson.ciphertext, aesJson.salt, key)
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\'", "'")

            val masterUrl = REGEX_SOURCES.find(decryptedScript)?.groupValues?.get(1)
                ?: REGEX_FILE.find(decryptedScript)?.groupValues?.get(1)
                ?: REGEX_SOURCE.find(decryptedScript)?.groupValues?.get(1)
                ?: throw ErrorLoadingException("Video source not found")

            val subtitleList = mutableListOf<Track>()
            REGEX_SUBS.findAll(decryptedScript).forEach {
                try {
                    val subUrl = it.groupValues[1]
                    val subLabel = decodeUnicodeEscape(it.groupValues[2])
                    Log.d("ChillxExtractor", "Found subtitle: $subUrl, Label: $subLabel")
                    subtitleList.add(Track(subUrl, subLabel))
                } catch (e: Exception) {
                    Log.e("ChillxExtractor", "Error parsing subtitle: ${e.message}")
                }
            }

            return playlistUtils.extractFromHls(
                playlistUrl = masterUrl,
                referer = url,
                videoNameGen = { "$prefix$it" },
                subtitleList = subtitleList,
            )
        } catch (e: Exception) {
            Log.e("ChillxExtractor", "Extraction failed: ${e.message}")
            throw ErrorLoadingException("Failed to extract video: ${e.message}")
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun fetchKey(): String? {
        for (source in KEY_SOURCES) {
            try {
                val response = client.newCall(GET(source)).execute()
                val keysData = response.parseAs<KeysData>()
                return keysData.keys.firstOrNull()
            } catch (e: Exception) {
                Log.e("ChillxExtractor", "Failed to fetch key from $source: ${e.message}")
                continue
            }
        }
        return null
    }

    private fun decodeUnicodeEscape(input: String): String {
        return try {
            val regex = Regex("\\\\u([0-9a-fA-F]{4})")
            regex.replace(input) {
                it.groupValues[1].toInt(16).toChar().toString()
            }
        } catch (e: Exception) {
            Log.e("ChillxExtractor", "Error decoding Unicode: ${e.message}")
            input
        }
    }

    @Serializable
    data class CryptoInfo(
        @SerialName("ct") val ciphertext: String,
        @SerialName("s") val salt: String,
    )

    @Serializable
    data class KeysData(
        @SerialName("chillx") val keys: List<String>
    )
}

class ErrorLoadingException(message: String) : Exception(message)
