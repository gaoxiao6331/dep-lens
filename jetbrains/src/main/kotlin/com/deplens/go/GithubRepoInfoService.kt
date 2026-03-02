package com.deplens.go

import com.intellij.openapi.components.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Service
object GithubRepoInfoService {
    private data class Cached(val info: RepoInfo, val ts: Long)
    data class RepoInfo(val stars: Int, val updatedDate: String)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val cache = ConcurrentHashMap<String, Cached>()
    private const val ttlMillis: Long = 10 * 60 * 1000

    fun getRepoInfo(owner: String, repo: String): RepoInfo {
        val key = "$owner/$repo"
        val now = System.currentTimeMillis()
        cache[key]?.let { c ->
            if (now - c.ts < ttlMillis) return c.info
        }
        val info = fetchRepoInfo(key)
        cache[key] = Cached(info, now)
        return info
    }

    private fun fetchRepoInfo(fullName: String): RepoInfo {
        return runCatching {
            val url = URI.create("https://api.github.com/repos/$fullName")
            val req = HttpRequest.newBuilder(url)
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) {
                return RepoInfo(0, "—")
            }
            val body = resp.body()
            val stars = STAR_REGEX.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val updated = UPDATED_REGEX.find(body)?.groupValues?.get(1)?.take(10) ?: "—"
            RepoInfo(stars, updated)
        }.getOrElse {
            RepoInfo(0, "—")
        }
    }

    private val STAR_REGEX = Regex("\"stargazers_count\"\\s*:\\s*(\\d+)")
    private val UPDATED_REGEX = Regex("\"updated_at\"\\s*:\\s*\"([^\"]+)\"")
}
