package com.deplens.go

import com.intellij.openapi.diagnostic.Logger
import okhttp3.*
import okhttp3.internal.platform.Platform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

data class GithubRepoInfo(
    val stars: Int,
    val updatedDate: String,
)

@Serializable
data class GithubApiResponse(
    val stargazers_count: Int,
    val pushed_at: String,
)

object GithubRepoInfoService {
    private val LOG = Logger.getInstance("GithubRepoInfoService")
    private val json = Json { ignoreUnknownKeys = true }

    // 核心修复：完整的SSL上下文配置 + 代理强制适配
    private val okHttpClient: OkHttpClient by lazy {
        // 1. 信任所有SSL证书（彻底解决SSL握手问题）
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(null, trustAllCerts, SecureRandom())
        val sslSocketFactory = sslContext.socketFactory

        // 2. 日志拦截器
        val loggingInterceptor = Interceptor { chain ->
            val request = chain.request()
            LOG.info("[OkHttp] 发起请求: ${request.method} ${request.url}")
            val startTime = System.nanoTime()
            val response = try {
                chain.proceed(request)
            } catch (e: Exception) {
                LOG.error("[OkHttp] 请求异常: ${e.message}", e)
                throw e
            }
            val endTime = System.nanoTime()
            val duration = (endTime - startTime) / 1e6
            LOG.info("[OkHttp] 响应完成: ${response.code} ${request.url} 耗时: ${duration}ms")
            response
        }

        // 3. 构建客户端（强制代理 + 忽略SSL + 兼容配置）
        OkHttpClient.Builder()
            // 超时配置（延长到60秒，解决网络慢的问题）
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // 强制指定代理（替换为你的代理端口，如7890/7891）
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7890)))
            // 关键：禁用HTTP/2，强制使用HTTP/1.1（解决Mac代理下的EOF问题）
            .protocols(listOf(Protocol.HTTP_1_1))
            // SSL配置
            .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            // 连接池配置（避免连接复用导致的EOF）
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            // 禁用重试（避免重复请求）
            .retryOnConnectionFailure(false)
            // 添加日志
            .addInterceptor(loggingInterceptor)
            .build()
    }

    // 缓存相关
    private data class CachedRepoInfo(
        val info: GithubRepoInfo,
        val cacheTime: Long
    )
    private val cache = ConcurrentHashMap<String, CachedRepoInfo>()
    private val requests = ConcurrentHashMap<String, Call>()
    private val inFlightFutures = ConcurrentHashMap<String, CompletableFuture<GithubRepoInfo>>()
    private const val CACHE_EXPIRE_TIME = 10_000L

    init {
        LOG.info("=====================================")
        LOG.info("===== GithubRepoInfoService 启动 =====")
        LOG.info("=====================================")
        LOG.info("代理配置: 127.0.0.1:7890 | 强制HTTP/1.1 | 禁用HTTP/2")
    }

    fun getCacheKey(owner: String, repo: String): String = "$owner/$repo"

    fun getRepoInfo(owner: String, repo: String): GithubRepoInfo? {
        val cacheKey = getCacheKey(owner, repo)
        LOG.info("[缓存操作] 获取缓存: $cacheKey, 当前缓存数: ${cache.size}")

        val cached = cache[cacheKey] ?: run {
            LOG.info("[缓存操作] 缓存未命中: $cacheKey")
            return null
        }

        return if (System.currentTimeMillis() - cached.cacheTime < CACHE_EXPIRE_TIME) {
            LOG.info("[缓存操作] 缓存命中: $cacheKey, stars: ${cached.info.stars}")
            cached.info
        } else {
            LOG.info("[缓存操作] 缓存过期: $cacheKey, 已移除")
            cache.remove(cacheKey)
            null
        }
    }

    fun fetchRepoInfo(owner: String, repo: String): GithubRepoInfo? {
        val cacheKey = getCacheKey(owner, repo)
        LOG.info("[请求处理] 开始处理请求: $cacheKey")

        // 检查重复请求
        val existingCall = requests[cacheKey]
        if (existingCall != null && !existingCall.isCanceled() && !existingCall.isExecuted()) {
            LOG.info("[请求处理] 已有请求在处理: $cacheKey")
            return null
        }

        // 构建请求（添加更多兼容头）
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo")
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
            .header("Connection", "close") // 禁用长连接，解决EOF问题
            .header("Accept-Encoding", "gzip")
            .build()

        val call = okHttpClient.newCall(request)
        requests[cacheKey] = call
        LOG.info("[请求处理] 创建新请求: $cacheKey, URL: ${request.url}")

        return try {
            LOG.info("[请求处理] 执行请求: $cacheKey")
            val response = call.execute()

            if (!response.isSuccessful) {
                val errorMsg = "请求失败[${response.code}]"
                LOG.error("[请求处理] $errorMsg: $cacheKey")
                return GithubRepoInfo(-1, "加载失败")
            }

            val responseBody = response.body?.string() ?: run {
                LOG.error("[请求处理] 无响应内容: $cacheKey")
                return GithubRepoInfo(-1, "加载失败")
            }
            LOG.info("[请求处理] 响应内容长度: ${responseBody.length}字节: $cacheKey")

            val apiResponse = json.decodeFromString<GithubApiResponse>(responseBody)
            val instant = Instant.parse(apiResponse.pushed_at)
            val formattedDate = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneId.systemDefault())
                .format(instant)

            val repoInfo = GithubRepoInfo(
                stars = apiResponse.stargazers_count,
                updatedDate = formattedDate
            )
            cache[cacheKey] = CachedRepoInfo(repoInfo, System.currentTimeMillis())
            LOG.info("[请求处理] 请求成功: $cacheKey, stars: ${repoInfo.stars}, 更新时间: ${repoInfo.updatedDate}")

            repoInfo
        } catch (e: Exception) {
            LOG.error("[请求处理] 请求异常: $cacheKey", e)
            return GithubRepoInfo(-1, "加载失败")
        } finally {
            requests.remove(cacheKey)
            LOG.info("[请求处理] 清理请求记录: $cacheKey")
        }
    }

    fun cancelAllRequests() {
        LOG.info("[服务管理] 开始取消所有请求")
        LOG.info("[服务管理] 当前待处理请求数: ${requests.size}")
        requests.values.forEach { call ->
            if (!call.isCanceled() && !call.isExecuted()) {
                call.cancel()
                LOG.info("[服务管理] 取消请求: ${call.request().url}")
            }
        }
        requests.clear()
        LOG.info("[服务管理] 所有请求已取消")
    }

    fun shutdown() {
        LOG.info("[服务管理] 开始关闭服务")
        cancelAllRequests()
        clearCache()
        LOG.info("[服务管理] 服务已关闭")
    }

    fun clearCache() {
        LOG.info("[服务管理] 开始清空缓存, 清空前大小: ${cache.size}")
        cache.clear()
        LOG.info("[服务管理] 缓存已清空, 清空后大小: ${cache.size}")
    }
}