package io.github.usernamealreadytakensht.trackstuff.data.remote

/**
 * Short, readable description of a failure for the screens ("offline", "invalid key"…) instead of raw
 * exception text such as "Unable to resolve host". Unknown errors keep their message.
 */
fun describeError(e: Throwable): String = when (e) {
    is retrofit2.HttpException -> when (e.code()) {
        401 -> "invalid key or sign-in expired"
        403 -> "access denied (key or plan)"
        404 -> "not found"
        408, 504 -> if (e.code() == 504 && e.message().contains("only-if-cached", ignoreCase = true)) "offline" else "timed out"
        420, 429 -> "rate limited, try again later"
        in 500..599 -> "service unavailable (HTTP ${e.code()})"
        else -> "HTTP ${e.code()}"
    }
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException -> "offline"
    is java.net.SocketTimeoutException -> "timed out"
    is javax.net.ssl.SSLException -> "secure connection failed"
    is java.io.InterruptedIOException -> "interrupted"
    is java.io.IOException -> if (e.message?.contains("unexpected end of stream", ignoreCase = true) == true) "connection dropped" else (e.message ?: "network error")
    is com.squareup.moshi.JsonDataException, is com.squareup.moshi.JsonEncodingException -> "unexpected answer from the service"
    else -> e.message ?: e.javaClass.simpleName
}
