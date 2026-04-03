package deplens.utils.service

import com.intellij.openapi.diagnostic.Logger
import deplens.common.ResultWrapper
import deplens.utils.Formatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import java.net.Proxy
import java.net.URI
import java.net.ProxySelector
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@Serializable
data class GithubRepoInfo(
    val stars: String,
    val originalStars: Int,
    val updatedDate: String,
    override val fetchedAt: Long = System.currentTimeMillis()
) : CachedEntry

@Serializable
data class GithubApiResponse(
    val stargazers_count: Int,
    val pushed_at: String,
)

data class RepoKey(val owner: String, val repo: String) {
    override fun toString(): String = "$owner/$repo"
}

object GithubRepoInfoService : AbstractCachedRequestService<GithubRepoInfo>() {

    override val logger: Logger = Logger.getInstance(GithubRepoInfoService::class.java)
    override val cacheFileName: String = "deplens/github_repo_cache.json"
    override val dataSerializer = GithubRepoInfo.serializer()

    private val json = Json {
        ignoreUnknownKeys = true
    }

    override val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    init {
        initCache()
    }

    private val dateFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd")
        .withZone(ZoneId.systemDefault())

    fun getCacheKey(owner: String, repo: String): String {
        return "$owner/$repo"
    }


    fun getRepoKey(path: String): RepoKey? {
        val raw = path.trim()
        if (raw.isBlank()) return null

        val cleaned = raw
            .removePrefix("git+")
            .removePrefix("git://")
            .removePrefix("ssh://")

        if (cleaned.startsWith("github:")) {
            val rest = cleaned.removePrefix("github:").trimStart('/')
            val parts = rest.split("/").filter { it.isNotBlank() }
            if (parts.size < 2) return null
            val owner = parts[0]
            val repo = normalizeRepoName(parts[1])
            if (owner.isBlank() || repo.isBlank()) return null
            return RepoKey(owner, repo)
        }

        val match = Regex("github\\.com[:/]+([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)")
            .find(cleaned)
            ?: return null

        val owner = match.groupValues[1]
        val repo = normalizeRepoName(match.groupValues[2])
        if (owner.isBlank() || repo.isBlank()) return null
        return RepoKey(owner, repo)
    }

    private fun normalizeRepoName(value: String): String {
        return value
            .substringBefore('#')
            .substringBefore('?')
            .removeSuffix(".git")
            .trim()
    }

    fun getRepoInfo(owner: String, repo: String): ResultWrapper<GithubRepoInfo> {
        val key = getCacheKey(owner, repo)
        return getCachedInfo(key)
    }

    fun fetchRepoInfo(owner: String, repo: String, onFinish: (() -> Unit)? = null): Unit {

        val key = getCacheKey(owner, repo)
        fetchByKey(key, onFinish)
    }

    fun retryRepoInfo(owner: String, repo: String, onFinish: (() -> Unit)? = null): Unit {
        // Explicit retry for UI action: bypass failure quota and trigger immediate fetch.
        val key = getCacheKey(owner, repo)
        retryByKey(key, onFinish)
    }

    override fun createRequestCall(key: String): Call? {
        val parts = key.split("/", limit = 2)
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            logger.warn("Invalid repo key: $key")
            return null
        }
        val owner = parts[0]
        val repo = parts[1]
        val githubToken = System.getenv("GITHUB_TOKEN") ?: ""
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 13_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36")
            .apply {
                if (githubToken.isNotBlank()) {
                    header("Authorization", "Bearer $githubToken")
                }
            }
            .build()

        val client = httpClient.newBuilder()
            .proxy(resolveProxy())
            .build()
        return client.newCall(request)
    }

    override fun parseResponseBody(key: String, body: String): GithubRepoInfo {
        val apiResponse = json.decodeFromString<GithubApiResponse>(body)
        val instant = Instant.parse(apiResponse.pushed_at)
        val repoInfo = GithubRepoInfo(
            stars = Formatter.formatGithubStar(apiResponse.stargazers_count),
            originalStars = apiResponse.stargazers_count,
            updatedDate = dateFormatter.format(instant)
        )
        logger.info("[请求成功] $key, 星数: ${repoInfo.stars}, 更新日期: ${repoInfo.updatedDate}")
        return repoInfo
    }

    private fun resolveProxy(): Proxy? {
        return try {
            val uri = URI("https://api.github.com")
            val proxies = ProxySelector.getDefault()?.select(uri).orEmpty()
            proxies.firstOrNull()
        } catch (e: Exception) {
            logger.warn("Proxy resolve failed", e)
            null
        }
    }

    fun shutdown() {
        shutdownInternal()
    }
}
