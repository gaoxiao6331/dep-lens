package deplens.utils

import java.util.concurrent.ConcurrentHashMap

private val maxRetryCount = 3

private val failedExpireTime = 30 * 60 * 1000; // 30 分钟

class RequestManager {

    private val failed = ConcurrentHashMap<String, List<Long>>()

    fun shouldRequest(key: String): Boolean {

        val now = System.currentTimeMillis()

        val items = failed[key]
            ?.filter { expireTime ->
                expireTime > now
            }?.let { times ->
                failed[key] = times
                times
            }

        val cnt = items?.size ?: 0

        return cnt < maxRetryCount
    }

    fun updateFailed(key: String) {
        val now = System.currentTimeMillis()
        val expireTimes = (failed[key] ?: emptyList()) + (now + failedExpireTime)
        failed[key] = expireTimes
    }
}