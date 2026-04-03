package deplens.utils.service

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import deplens.common.RETRY_DELAY_MILLIS
import deplens.common.Result
import deplens.common.ResultWrapper
import deplens.utils.RequestManager
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

interface CachedEntry {
    val fetchedAt: Long
}

abstract class AbstractCachedRequestService<T : CachedEntry> {

    protected abstract val logger: Logger
    protected abstract val cacheFileName: String
    protected abstract val dataSerializer: KSerializer<T>

    protected open val cacheTtlMillis: Long = 3L * 24 * 60 * 60 * 1000

    protected open val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val requestManager = RequestManager()
    private val cache = ConcurrentHashMap<String, T>()
    private val runningRequests = ConcurrentHashMap<String, Call>()

    private val cacheFile: File by lazy {
        File(PathManager.getSystemPath(), cacheFileName).apply {
            parentFile.mkdirs()
        }
    }

    protected abstract fun createRequestCall(key: String): Call?
    protected abstract fun parseResponseBody(key: String, body: String): T?

    protected fun initCache() {
        loadCacheFromDisk()
    }

    protected fun getCachedInfo(key: String): ResultWrapper<T> {
        val info = cache[key]
        if (info != null) {
            val now = System.currentTimeMillis()
            if (now - info.fetchedAt > cacheTtlMillis) {
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

        return if (isFailure(key)) {
            ResultWrapper(Result.FAILURE, null)
        } else {
            ResultWrapper(Result.NONE, null)
        }
    }

    protected fun fetchByKey(key: String, onFinish: (() -> Unit)? = null) {
        if (!requestManager.shouldRequest(key)) {
            logger.debug("should not request: $key")
            onFinish?.invoke()
            return
        }

        val call = createRequestCall(key)
        if (call == null) {
            onFinish?.invoke()
            return
        }

        while (true) {
            val existing = runningRequests.putIfAbsent(key, call)
            if (existing == null) {
                break
            }
            if (!existing.isCanceled()) {
                logger.debug("Request already running: $key")
                return
            }
            runningRequests.remove(key, existing)
        }

        var shouldNotify = false
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    logger.warn("Request failed: $key, code=${response.code}")
                    return
                }

                val body = response.body?.string() ?: return
                val data = parseResponseBody(key, body) ?: return
                // Only successful parse/write updates cache; failures never overwrite existing cache entry.
                cache[key] = data
                saveCacheToDiskAsync()
                shouldNotify = true
            }
        } catch (e: Exception) {
            logger.warn("Request error: $key", e)
        } finally {
            val hasData = cache.containsKey(key)
            if (!hasData) {
                // Request failed and no cache was produced this round; mark failure for retry/backoff.
                requestManager.updateFailed(key)
                val canRetry = requestManager.shouldRequest(key)
                if (canRetry) {
                    AppExecutorUtil.getAppScheduledExecutorService().schedule(
                        { fetchByKey(key, onFinish) },
                        RETRY_DELAY_MILLIS,
                        TimeUnit.MILLISECONDS
                    )
                } else {
                    shouldNotify = true
                }
            }

            runningRequests.remove(key, call)
            if (shouldNotify) {
                onFinish?.invoke()
            }
        }
    }

    protected fun retryByKey(key: String, onFinish: (() -> Unit)? = null) {
        // Explicit retry should bypass previous failure quota and try immediately.
        requestManager.clearFailure(key)
        fetchByKey(key, onFinish)
    }

    fun hasFailure(key: String): Boolean = requestManager.hasFailure(key)

    fun isRequestRunning(key: String): Boolean {
        val running = runningRequests[key]
        return running != null && !running.isCanceled()
    }

    protected fun isFailure(key: String): Boolean {
        return !cache.containsKey(key) && !requestManager.shouldRequest(key)
    }

    fun cancelAllRequests() {
        runningRequests.values.forEach { call ->
            if (!call.isCanceled()) {
                call.cancel()
            }
        }
        runningRequests.clear()
    }

    fun clearCache() {
        cache.clear()
        saveCacheToDiskAsync()
    }

    protected fun shutdownInternal() {
        cancelAllRequests()
        clearCache()
    }

    private fun loadCacheFromDisk() {
        try {
            if (!cacheFile.exists()) return
            val content = cacheFile.readText()
            if (content.isBlank()) return

            val map = json.decodeFromString(
                MapSerializer(String.serializer(), dataSerializer),
                content
            )

            val now = System.currentTimeMillis()
            var hasExpired = false
            for ((k, v) in map) {
                if (now - v.fetchedAt > cacheTtlMillis) {
                    hasExpired = true
                } else {
                    cache[k] = v
                }
            }
            if (hasExpired) {
                saveCacheToDiskAsync()
            }
        } catch (e: Exception) {
            logger.warn("Failed to load cache: $cacheFileName", e)
        }
    }

    private fun saveCacheToDiskAsync() {
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val content = json.encodeToString(
                    MapSerializer(String.serializer(), dataSerializer),
                    cache.toMap()
                )
                cacheFile.writeText(content)
            } catch (e: Exception) {
                logger.warn("Failed to save cache: $cacheFileName", e)
            }
        }
    }
}
