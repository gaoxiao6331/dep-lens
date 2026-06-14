package deplens.utils.service

import com.intellij.openapi.diagnostic.Logger
import deplens.common.ResultWrapper
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class PubPackageInfo(
    val name: String,
    val githubUrl: String?,
    override val fetchedAt: Long = System.currentTimeMillis()
) : CachedEntry

@Serializable
data class PubPackageResponse(
    val name: String,
    val latest: PubLatestInfo? = null
)

@Serializable
data class PubLatestInfo(
    val pubspec: PubSpecInfo? = null
)

@Serializable
data class PubSpecInfo(
    val repository: String? = null,
    val homepage: String? = null,
    val issue_tracker: String? = null,
    val documentation: String? = null
)

object PubPkgInfoService : AbstractCachedRequestService<PubPackageInfo>() {

    override val logger: Logger = Logger.getInstance(PubPkgInfoService::class.java)
    override val cacheFileName: String = "deplens/pub_package_cache.json"
    override val dataSerializer = PubPackageInfo.serializer()

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

    fun getPackageInfo(packageName: String): ResultWrapper<PubPackageInfo> = getCachedInfo(packageName)

    fun fetchPackageInfo(packageName: String, onFinish: (() -> Unit)? = null) {
        fetchByKey(packageName, onFinish)
    }

    fun retryPackageInfo(packageName: String, onFinish: (() -> Unit)? = null) {
        retryByKey(packageName, onFinish)
    }

    override fun createRequestCall(key: String): Call {
        val request = Request.Builder()
            .url("https://pub.dev/api/packages/$key")
            .header("Accept", "application/json")
            .build()
        return httpClient.newCall(request)
    }

    override fun parseResponseBody(key: String, body: String): PubPackageInfo? {
        val packageInfo = json.decodeFromString<PubPackageResponse>(body)
        val pubspec = packageInfo.latest?.pubspec
        val githubUrl = listOf(
            pubspec?.repository,
            pubspec?.homepage,
            pubspec?.issue_tracker,
            pubspec?.documentation
        ).firstOrNull { it?.contains("github.com") == true }

        val result = PubPackageInfo(
            name = packageInfo.name,
            githubUrl = githubUrl
        )
        logger.info("[请求成功] $key, github: ${result.githubUrl}")
        return result
    }

    fun shutdown() {
        shutdownInternal()
    }
}
