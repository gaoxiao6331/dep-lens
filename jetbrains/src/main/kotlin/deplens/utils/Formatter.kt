package deplens.utils

object Formatter {
    fun formatGithubStar(stars: Int): String {
        return when {
            stars >= 1000 -> "${String.format("%.1f", stars / 1000.0).toDouble()}k"
            else -> stars.toString()
        }
    }
}