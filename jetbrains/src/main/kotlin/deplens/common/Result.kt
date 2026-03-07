package deplens.common

enum class Result {
    SUCCESS,
    FAILURE,
    PENDING,
    NONE,
}

data class ResultWrapper<T>(
    val result: Result,
    val data: T?
)