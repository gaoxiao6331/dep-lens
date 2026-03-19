package deplens.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import deplens.common.RETRY_DELAY_MILLIS
import deplens.common.Result
import deplens.common.ResultWrapper

@Serializable
data class NpmPackageInfo(
    val name: String,
    val weeklyDownloads: Int,
    val githubUrl: String?,
    val fetchedAt: Long = System.currentTimeMillis()
)

@Serializable
data class NpmRegistryResponse(
    val name: String,
    val repository: RepositoryInfo?
)

@Serializable
data class RepositoryInfo(
    val type: String? = null,
    val url: String? = null
)

@Serializable
data class NpmDownloadsResponse(
    val downloads: Int,
    val start: String? = null,
    val end: String? = null,
    val packageName: String? = null
)

// TODO: 继承抽象类
object NpmPkgInfoService {

    private val LOG = Logger.getInstance(NpmPkgInfoService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val cacheFile: File by lazy {
        File(com.intellij.openapi.application.PathManager.getSystemPath(), "deplens/npm_package_cache.json").apply {
            parentFile.mkdirs()
        }
    }

    private val cache = ConcurrentHashMap<String, NpmPackageInfo>()
    private val runningRequests = ConcurrentHashMap<String, Call>()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

    init {
        loadCacheFromDisk()
    }

    private fun loadCacheFromDisk() {
        try {
            if (cacheFile.exists()) {
                val content = cacheFile.readText()
                if (content.isNotBlank()) {
                    val map = json.decodeFromString<Map<String, NpmPackageInfo>>(content)
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

                    if (hasExpired) saveCacheToDiskAsync()
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to load npm package cache", e)
        }
    }

    private fun saveCacheToDiskAsync() {
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val content = json.encodeToString(cache.toMap())
                cacheFile.writeText(content)
            } catch (e: Exception) {
                LOG.warn("Failed to save npm package cache", e)
            }
        }
    }

    fun getCacheKey(packageName: String): String = packageName

    fun isFailure(packageName: String): Boolean = false

    fun getPackageInfo(packageName: String): ResultWrapper<NpmPackageInfo> {
        val info = cache[packageName]
        if (info != null) {
            val now = System.currentTimeMillis()
            val threeDaysMillis = 3 * 24 * 60 * 60 * 1000L
            if (now - info.fetchedAt > threeDaysMillis) {
                cache.remove(packageName)
                saveCacheToDiskAsync()
            } else {
                return ResultWrapper(Result.SUCCESS, info)
            }
        }
        return if (isFailure(packageName)) ResultWrapper(Result.FAILURE, null) else ResultWrapper(Result.NONE, null)
    }

    fun fetchPackageInfo(packageName: String, onFinish: (() -> Unit)? = null) {
        val existing = runningRequests[packageName]
        if (existing != null && !existing.isCanceled()) {
            LOG.debug("Request already running: $packageName")
            return
        }

        // 1. 获取 npm registry info
        val registryRequest = Request.Builder()
            .url("https://registry.npmjs.org/$packageName")
            .header("Accept", "application/json")
            .build()

        val call = httpClient.newCall(registryRequest)
        runningRequests[packageName] = call

        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                LOG.warn("NPM registry API failed: ${response.code}")
                return
            }

            val body = response.body?.string() ?: return
            val registryInfo = json.decodeFromString<NpmRegistryResponse>(body)

            // 2. 获取 npm 下载量（过去 7 天）
            val downloadsRequest = Request.Builder()
                .url("https://api.npmjs.org/downloads/point/last-week/$packageName")
                .build()

            val downloadsResponse = httpClient.newCall(downloadsRequest).execute()
            val downloadsBody = downloadsResponse.body?.string() ?: return
            val downloadsInfo = json.decodeFromString<NpmDownloadsResponse>(downloadsBody)

            // git+https://github.com/Microsoft/vscode-extension-vscode.git
            val githubUrl = registryInfo.repository?.url?.removePrefix("git+")?.removeSuffix(".git")

            val packageInfo = NpmPackageInfo(
                name = registryInfo.name,
                weeklyDownloads = downloadsInfo.downloads,
                githubUrl = githubUrl
            )

            cache[packageName] = packageInfo
            saveCacheToDiskAsync()

            LOG.info("[请求成功] $packageName, 下载量: ${packageInfo.weeklyDownloads}, github: ${packageInfo.githubUrl}")

            onFinish?.invoke()

        } catch (e: Exception) {
            LOG.warn("NPM request error: $packageName", e)

            // 3 秒后重试
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                { fetchPackageInfo(packageName) },
                RETRY_DELAY_MILLIS,
                TimeUnit.MILLISECONDS
            )
        } finally {
            runningRequests.remove(packageName)
        }
    }

    fun cancelAllRequests() {
        runningRequests.values.forEach { if (!it.isCanceled()) it.cancel() }
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