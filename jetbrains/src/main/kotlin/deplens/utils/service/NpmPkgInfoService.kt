package deplens.utils.service

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import java.util.concurrent.TimeUnit
import deplens.common.ResultWrapper

@Serializable
data class NpmPackageInfo(
    val name: String,
    val weeklyDownloads: Int,
    val githubUrl: String?,
    override val fetchedAt: Long = System.currentTimeMillis()
) : CachedEntry

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

object NpmPkgInfoService : AbstractCachedRequestService<NpmPackageInfo>() {

    override val logger: Logger = Logger.getInstance(NpmPkgInfoService::class.java)
    override val cacheFileName: String = "deplens/npm_package_cache.json"
    override val dataSerializer = NpmPackageInfo.serializer()

    private val json = Json { ignoreUnknownKeys = true }
    override val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    init {
        initCache()
    }

    fun getCacheKey(packageName: String): String = packageName

    fun getPackageInfo(packageName: String): ResultWrapper<NpmPackageInfo> = getCachedInfo(packageName)

    fun fetchPackageInfo(packageName: String, onFinish: (() -> Unit)? = null) {
        fetchByKey(packageName, onFinish)
    }

    override fun createRequestCall(key: String): Call {
        val request = Request.Builder()
            .url("https://registry.npmjs.org/$key")
            .header("Accept", "application/json")
            .build()
        return httpClient.newCall(request)
    }

    override fun parseResponseBody(key: String, body: String): NpmPackageInfo? {
        val registryInfo = json.decodeFromString<NpmRegistryResponse>(body)

        val downloadsRequest = Request.Builder()
            .url("https://api.npmjs.org/downloads/point/last-week/$key")
            .build()
        val downloadsBody = httpClient.newCall(downloadsRequest).execute().use { downloadsResponse ->
            if (!downloadsResponse.isSuccessful) {
                logger.warn("NPM downloads API failed: ${downloadsResponse.code}")
                return null
            }
            downloadsResponse.body?.string() ?: return null
        }
        val downloadsInfo = json.decodeFromString<NpmDownloadsResponse>(downloadsBody)

        val githubUrl = registryInfo.repository?.url?.removePrefix("git+")?.removeSuffix(".git")
        val packageInfo = NpmPackageInfo(
            name = registryInfo.name,
            weeklyDownloads = downloadsInfo.downloads,
            githubUrl = githubUrl
        )
        logger.info("[请求成功] $key, 下载量: ${packageInfo.weeklyDownloads}, github: ${packageInfo.githubUrl}")
        return packageInfo
    }

    fun shutdown() {
        shutdownInternal()
    }
}
