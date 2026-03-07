package deplens.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.proxy.CommonProxy
import deplens.common.RETRY_DELAY_MILLIS
import deplens.common.Result
import deplens.common.ResultWrapper
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
    val stars: String,
    val originalStars: Int,
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

    private val requestManager = RequestManager()

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // TODO cache持久化
    private val cache = ConcurrentHashMap<String, GithubRepoInfo>()


    // ===== 请求管理 =====

    private val runningRequests = ConcurrentHashMap<String, Call>()

    private val dateFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd")
        .withZone(ZoneId.systemDefault())

    fun getCacheKey(owner: String, repo: String): String {
        return "$owner/$repo"
    }

    fun isFailure(key: String): Boolean {

        return !cache.containsKey(key) && !requestManager.shouldRequest(key)
    }

    fun getRepoInfo(owner: String, repo: String): ResultWrapper<GithubRepoInfo> {
        val key = getCacheKey(owner, repo)
        return when {
            cache.containsKey(key) -> ResultWrapper(Result.SUCCESS, cache[key])
            isFailure(key) -> ResultWrapper(Result.FAILURE, null)
            else -> ResultWrapper(Result.NONE, null)
        }
    }

    fun fetchRepoInfo(owner: String, repo: String): Unit {

        val key = getCacheKey(owner, repo)

        val existing = runningRequests[key]
        if (existing != null && !existing.isCanceled()) {
            LOG.debug("Request already running: $key")
            return
        }

        if (!requestManager.shouldRequest(key)) {
            LOG.debug("should not request: $key")
            return
        }

        // TOOD 使用界面配置
        val githubToken = System.getenv("GITHUB_TOKEN") ?: ""

        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 13_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36")
            .header("Authorization", "Bearer $githubToken")
            .build()

        val client = httpClient.newBuilder()
            .proxy(resolveProxy())
            .build()

        val call = client.newCall(request)

        runningRequests[key] = call

        try {

            val response = call.execute()

            if (!response.isSuccessful) {
                LOG.warn("GitHub API failed: ${response.code}")
            }

            val body = response.body?.string() ?: return

            val apiResponse = json.decodeFromString<GithubApiResponse>(body)

            val instant = Instant.parse(apiResponse.pushed_at)

            val repoInfo = GithubRepoInfo(
                stars = Formatter.formatGithubStar(apiResponse.stargazers_count),
                originalStars = apiResponse.stargazers_count,
                updatedDate = dateFormatter.format(instant)
            )

            LOG.info("[请求成功] $key, 星数: ${repoInfo.stars}, 更新日期: ${repoInfo.updatedDate}")

            cache[key] = repoInfo

        } catch (e: Exception) {

            LOG.warn("GitHub request error: $key", e)

            requestManager.updateFailed(key)

            // 3s后自动重试
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                {
                    fetchRepoInfo(owner, repo)
                },
                RETRY_DELAY_MILLIS,
                TimeUnit.MILLISECONDS
            )


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
