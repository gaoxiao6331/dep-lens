package com.deplens.go

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.ModalTaskOwner.project
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

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
    private val cache = ConcurrentHashMap<String, GithubRepoInfo>()
    private val requests = ConcurrentHashMap<String, Job>()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun getCacheKey(owner: String, repo: String): String {
        return "$owner/$repo"
    }

    fun getRepoInfo(owner: String, repo: String): GithubRepoInfo? {
        val cacheKey = getCacheKey(owner, repo)
        val cached = cache[cacheKey]
        return cached
    }

    suspend fun fetchRepoInfo(owner: String, repo: String): GithubRepoInfo? = withContext(Dispatchers.IO) {
        val url = URI("https://api.github.com/repos/$owner/$repo").toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        try {
            val inputStream = connection.inputStream
            val response = inputStream.bufferedReader().use { it.readText() }
            val apiResponse = json.decodeFromString<GithubApiResponse>(response)

            val instant = Instant.parse(apiResponse.pushed_at)
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
            val formattedDate = formatter.format(instant)

            val info = GithubRepoInfo(
                stars = apiResponse.stargazers_count,
                updatedDate = formattedDate,
            )

            cache[getCacheKey(owner, repo)] = info



        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            connection.disconnect()
        }

        getRepoInfo(owner, repo)
    }
}