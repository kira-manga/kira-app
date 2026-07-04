package me.manga.yamiapk.core.states

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed class State<out T> {
    data class Success<T>(val data :T): State<T>()
    data object Loading: State<Nothing>()

    fun toData():T? = if (this is Success) data else null


    data class Error(val code: Int? , val message: String): State<Nothing>() {
        companion object {
            /** Factory that looks up a friendly message for you */
            fun fromCode(code: Int): Error =
                Error(code, httpStatusMessage(code))
            fun fromException(t: Throwable): Error =
                when (t) {


                    is UnknownHostException ->
                        Error(0, "Cannot reach server—please check your internet connection.")
                    is SocketTimeoutException ->
                        Error(0, "The request timed out—please try again later.")
                    is ConnectException ->
                        Error(0, "Unable to connect to the server.")
                    else ->
                        Error(0, "An unexpected error occurred: ${t.localizedMessage}")
                }
            private fun httpStatusMessage(code: Int): String =
                when (code) {
                    in 400..499 -> when (code) {
                        400 -> "Bad Request"
                        401 -> "Unauthorized"
                        403 -> "Forbidden Click On Help To Solve The Problem "
                        404 -> "Not Found"
                        408 -> "Request Timeout"
                        else -> "Client Error $code"
                    }
                    in 500..599 -> when (code) {
                        500 -> "Internal Server Error"
                        502 -> "Bad Gateway"
                        503 -> "Service Unavailable"
                        504 -> "Gateway Timeout"
                        else -> "Server Error $code"
                    }
                    else -> "Unexpected HTTP status $code"
                }
        }
    }

}



