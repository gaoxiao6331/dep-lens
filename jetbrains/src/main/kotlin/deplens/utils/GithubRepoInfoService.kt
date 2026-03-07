package deplens.utils

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.proxy.CommonProxy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import java.net.Proxy
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class GithubRepoInfo(
    val stars: Int,
    val updatedDate: String,
)

@Serializable
data class GithubApiResponse(
    val stargazers_count: Int,
    val pushed_at: String,
)

data class RepoKey(val owner: String, val repo: String) {
    override fun toString(): String = "$owner/$repo"
}

object GithubRepoInfoService {

    private val LOG = Logger.getInstance(GithubRepoInfoService::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ===== 缓存 =====

    private data class CachedRepoInfo(
        val info: GithubRepoInfo,
        val cacheTime: Long
    )

    private val cache = ConcurrentHashMap<String, CachedRepoInfo>()
    private const val CACHE_EXPIRE_TIME = 10 * 60 * 1000L // 10分钟

    // ===== 请求管理 =====

    private val runningRequests = ConcurrentHashMap<String, Call>()

    private val dateFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd")
        .withZone(ZoneId.systemDefault())

    fun getCacheKey(owner: String, repo: String): String {
        return "$owner/$repo"
    }

    fun getRepoInfo(owner: String, repo: String): GithubRepoInfo? {
        val key = getCacheKey(owner, repo)
        val cached = cache[key] ?: return null

        return if (System.currentTimeMillis() - cached.cacheTime < CACHE_EXPIRE_TIME) {
            cached.info
        } else {
            cache.remove(key)
            null
        }
    }

    fun fetchRepoInfo(owner: String, repo: String): GithubRepoInfo? {

        val key = getCacheKey(owner, repo)

        val existing = runningRequests[key]
        if (existing != null && !existing.isCanceled()) {
            LOG.debug("Request already running: $key")
            return null
        }

        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "DepLens")
            .build()

        val client = httpClient.newBuilder()
            .proxy(resolveProxy())
            .build()

        val call = client.newCall(request)

        runningRequests[key] = call

        return try {

            val response = call.execute()

            if (!response.isSuccessful) {
                LOG.warn("GitHub API failed: ${response.code}")
                return GithubRepoInfo(-1, "加载失败")
            }

            val body = response.body?.string() ?: return GithubRepoInfo(-1, "加载失败")

            val apiResponse = json.decodeFromString<GithubApiResponse>(body)

            val instant = Instant.parse(apiResponse.pushed_at)

            val repoInfo = GithubRepoInfo(
                stars = apiResponse.stargazers_count,
                updatedDate = dateFormatter.format(instant)
            )

            LOG.info("[请求成功] $key, 星数: ${repoInfo.stars}, 更新日期: ${repoInfo.updatedDate}")

            cache[key] = CachedRepoInfo(repoInfo, System.currentTimeMillis())

            repoInfo

        } catch (e: Exception) {

            LOG.warn("GitHub request error: $key", e)
            GithubRepoInfo(-1, "加载失败")

        } finally {

            runningRequests.remove(key)

        }
    }

    private fun resolveProxy(): Proxy? {
        return try {
            val uri = URI("https://api.github.com")
            val proxies = CommonProxy.getInstance().select(uri)
            proxies.firstOrNull()
        } catch (e: Exception) {
            LOG.warn("Proxy resolve failed", e)
            null
        }
    }

    fun cancelAllRequests() {

        runningRequests.values.forEach {
            if (!it.isCanceled()) {
                it.cancel()
            }
        }

        runningRequests.clear()
    }

    fun clearCache() {
        cache.clear()
    }

    fun shutdown() {
        cancelAllRequests()
        clearCache()
    }
}
