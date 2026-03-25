package deplens.utils.resolver

import deplens.utils.service.RepoKey
import java.io.File

object MavenRepoResolver {

    fun repoKeyFromGroupArtifact(groupId: String?, artifactId: String?, version: String? = null): RepoKey? {
        if (groupId.isNullOrBlank() || artifactId.isNullOrBlank()) return null

        val resolvedVersion = resolveLocalVersion(groupId, artifactId, version) ?: return null
        return resolveRepoFromPom(groupId, artifactId, resolvedVersion, 0)
    }

    fun repoKeyFromResolvedPath(path: String?): RepoKey? {
        if (path.isNullOrBlank()) return null

        val marker = "/.m2/repository/"
        val idx = path.indexOf(marker)
        if (idx < 0) return null

        val after = path.substring(idx + marker.length)
        val jarPart = after.substringBefore('!')
        val parts = jarPart.split('/').filter { it.isNotBlank() }
        if (parts.size < 4) return null

        val artifactId = parts[parts.size - 3]
        val version = parts[parts.size - 2]
        val groupParts = parts.subList(0, parts.size - 3)
        val groupId = groupParts.joinToString(".")
        if (groupId.isBlank() || artifactId.isBlank() || version.isBlank()) return null

        return repoKeyFromGroupArtifact(groupId, artifactId, version)
    }

    private fun resolveLocalVersion(groupId: String, artifactId: String, version: String?): String? {
        val cleaned = version?.trim()?.takeIf { it.isNotBlank() && !it.contains('$') }
        if (cleaned != null) return cleaned

        val metaFile = localMetadataFile(groupId, artifactId) ?: return null
        if (!metaFile.exists()) return null

        val meta = runCatching { metaFile.readText() }.getOrNull() ?: return null

        val release = Regex("<release>([^<]+)</release>").find(meta)?.groupValues?.get(1)?.trim()
        if (!release.isNullOrBlank()) return release

        val latest = Regex("<latest>([^<]+)</latest>").find(meta)?.groupValues?.get(1)?.trim()
        if (!latest.isNullOrBlank()) return latest

        val versions = Regex("<version>([^<]+)</version>")
            .findAll(meta)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
            .filter { it.isNotBlank() }
            .toList()
        return versions.lastOrNull()
    }

    private fun localPomFile(groupId: String, artifactId: String, version: String): File? {
        val base = localRepoBase() ?: return null
        val groupPath = groupId.replace('.', File.separatorChar)
        return File(base, "$groupPath/$artifactId/$version/$artifactId-$version.pom")
    }

    private fun localMetadataFile(groupId: String, artifactId: String): File? {
        val base = localRepoBase() ?: return null
        val groupPath = groupId.replace('.', File.separatorChar)
        return File(base, "$groupPath/$artifactId/maven-metadata-local.xml")
            .takeIf { it.exists() }
            ?: File(base, "$groupPath/$artifactId/maven-metadata.xml")
    }

    private fun localRepoBase(): File? {
        val home = System.getProperty("user.home") ?: return null
        return File(home, ".m2/repository")
    }

    private fun resolveRepoFromPom(groupId: String, artifactId: String, version: String, depth: Int): RepoKey? {
        if (depth > 6) return null

        val pom = localPomFile(groupId, artifactId, version) ?: return null
        if (!pom.exists()) return null

        val content = runCatching { pom.readText() }.getOrNull() ?: return null
        val direct = extractGithubRepoKey(content)
        if (direct != null) return direct

        val parent = extractParentCoords(content) ?: return null
        if (parent.groupId.contains('$') || parent.artifactId.contains('$') || parent.version.contains('$')) return null

        return resolveRepoFromPom(parent.groupId, parent.artifactId, parent.version, depth + 1)
    }

    private fun extractGithubRepoKey(text: String): RepoKey? {
        val scmBlock = Regex("<scm>.*?</scm>", RegexOption.DOT_MATCHES_ALL)
            .find(text)
            ?.value

        if (scmBlock != null) {
            val scmMatch = extractGithubRepoKeyFromText(scmBlock)
            if (scmMatch != null) return scmMatch
        }

        val url = Regex("<url>([^<]+)</url>").find(text)?.groupValues?.get(1)?.trim()
        if (!url.isNullOrBlank()) {
            val urlMatch = extractGithubRepoKeyFromText(url)
            if (urlMatch != null) return urlMatch
        }

        return extractGithubRepoKeyFromText(text)
    }

    private fun extractGithubRepoKeyFromText(text: String): RepoKey? {
        val match = Regex("github\\.com[:/]+([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)")
            .find(text) ?: return null

        val owner = match.groupValues[1]
        val repo = match.groupValues[2].removeSuffix(".git")
        if (owner.isBlank() || repo.isBlank()) return null
        return RepoKey(owner, repo)
    }

    private data class ParentCoords(val groupId: String, val artifactId: String, val version: String)

    private fun extractParentCoords(text: String): ParentCoords? {
        val parentBlock = Regex("<parent>.*?</parent>", RegexOption.DOT_MATCHES_ALL)
            .find(text)
            ?.value
            ?: return null

        val groupId = Regex("<groupId>([^<]+)</groupId>").find(parentBlock)?.groupValues?.get(1)?.trim()
        val artifactId = Regex("<artifactId>([^<]+)</artifactId>").find(parentBlock)?.groupValues?.get(1)?.trim()
        val version = Regex("<version>([^<]+)</version>").find(parentBlock)?.groupValues?.get(1)?.trim()

        if (groupId.isNullOrBlank() || artifactId.isNullOrBlank() || version.isNullOrBlank()) return null
        return ParentCoords(groupId, artifactId, version)
    }
}
