package deplens.utils.resolver

object DartImportResolver {

    private val importPattern = Regex("""^\s*import\s+['"]([^'"]+)['"]""")

    fun getDepName(line: String): String? {
        return importPattern.find(line)?.groupValues?.getOrNull(1)
    }

    fun isLocalImport(dep: String): Boolean {
        return dep.startsWith(".") || dep.startsWith("/") || dep.startsWith("dart:")
    }

    fun isPackageImport(dep: String): Boolean {
        return dep.startsWith("package:")
    }

    fun getPkgName(dep: String): String {
        val normalized = dep.removePrefix("package:")
        return normalized.substringBefore("/")
    }
}
