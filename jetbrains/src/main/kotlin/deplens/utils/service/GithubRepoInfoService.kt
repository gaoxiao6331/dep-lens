package deplens.utils.service

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.proxy.CommonProxy
import deplens.common.RETRY_DELAY_MILLIS
import deplens.common.Result
import deplens.common.ResultWrapper
import deplens.utils.Formatter
import deplens.utils.RequestManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import java.io.File
import java.net.Proxy
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.collections.iterator

@Serializable
data class GithubRepoInfo(
    val stars: String,
    val originalStars: Int,
    val updatedDate: String,
    val fetchedAt: Long = System.currentTimeMillis()
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

    private val cacheFile: File by lazy {
        File(PathManager.getSystemPath(), "deplens/github_repo_cache.json").apply {
            parentFile.mkdirs()
        }
    }

    private val cache = ConcurrentHashMap<String, GithubRepoInfo>()

    init {
        loadCacheFromDisk()
    }

    private fun loadCacheFromDisk() {
        try {
            if (cacheFile.exists()) {
                val content = cacheFile.readText()
                if (content.isNotBlank()) {
                    val map = json.decodeFromString<Map<String, GithubRepoInfo>>(content)
                    val now = System.currentTimeMillis()
                    val threeDaysMillis = 3 * 24 * 60 * 60 * 1000L
                    var hasExpired = false

                    for ((k, v) in map) {
                        if (now - v.fetchedAt > threeDaysMillis) {
                            hasExpired = true
                        } else {
                            cache[k] = v
                        }
                    }

                    if (hasExpired) {
                        saveCacheToDiskAsync()
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to load github repo cache", e)
        }
    }

    private fun saveCacheToDiskAsync() {
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val content = json.encodeToString(cache.toMap())
                cacheFile.writeText(content)
            } catch (e: Exception) {
                LOG.warn("Failed to save github repo cache", e)
            }
        }
    }

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
        val info = cache[key]
        if (info != null) {
            val now = System.currentTimeMillis()
            val threeDaysMillis = 3 * 24 * 60 * 60 * 1000L
            if (now - info.fetchedAt > threeDaysMillis) {
                cache.remove(key)
                saveCacheToDiskAsync()
            } else {
                return ResultWrapper(Result.SUCCESS, info)
            }
        }
        val running = runningRequests[key]
        if (running != null && !running.isCanceled()) {
            return ResultWrapper(Result.PENDING, null)
        }

        return when {
            isFailure(key) -> ResultWrapper(Result.FAILURE, null)
            else -> ResultWrapper(Result.NONE, null)
        }
    }

    fun fetchRepoInfo(owner: String, repo: String, onFinish: (() -> Unit)? = null): Unit {

        val key = getCacheKey(owner, repo)

        val existing = runningRequests[key]
        if (existing != null && !existing.isCanceled()) {
            LOG.debug("Request already running: $key")
            onFinish?.invoke()
            return
        }

        if (!requestManager.shouldRequest(key)) {
            LOG.debug("should not request: $key")
            onFinish?.invoke()
            return
        }

        // TOOD 使用界面配置
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

        val call = client.newCall(request)

        runningRequests[key] = call

        try {

            val response = call.execute()

            if (!response.isSuccessful) {
                LOG.warn("GitHub API failed: ${response.code}")
                return
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
            saveCacheToDiskAsync()
            onFinish?.invoke()

        } catch (e: Exception) {

            LOG.warn("GitHub request error: $key", e)


        } finally {

            if(!cache.containsKey(key)) {
                requestManager.updateFailed(key)

                // 3s后自动重试
                AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    {
                        fetchRepoInfo(owner, repo, onFinish)
                    },
                    RETRY_DELAY_MILLIS,
                    TimeUnit.MILLISECONDS
                )
            }

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
        saveCacheToDiskAsync()
    }

    fun shutdown() {
        cancelAllRequests()
        clearCache()
    }
}
