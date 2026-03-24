package deplens.utils.resolver

import deplens.utils.service.RepoKey

object MavenRepoResolver {

    private val groupPrefixes = listOf(
        "com.github.",
        "io.github."
    )

    fun repoKeyFromGroupArtifact(groupId: String?, artifactId: String?): RepoKey? {
        if (groupId.isNullOrBlank() || artifactId.isNullOrBlank()) return null

        for (prefix in groupPrefixes) {
            if (groupId.startsWith(prefix)) {
                val remainder = groupId.removePrefix(prefix)
                val owner = remainder.split('.').firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
                return RepoKey(owner, artifactId)
            }
        }

        return null
    }

    fun repoKeyFromImport(qualifiedName: String?): RepoKey? {
        if (qualifiedName.isNullOrBlank()) return null

        for (prefix in groupPrefixes) {
            if (qualifiedName.startsWith(prefix)) {
                val remainder = qualifiedName.removePrefix(prefix)
                val parts = remainder.split('.')
                if (parts.size < 2) return null
                val owner = parts[0]
                val repo = parts[1]
                if (owner.isBlank() || repo.isBlank()) return null
                return RepoKey(owner, repo)
            }
        }

        return null
    }
}