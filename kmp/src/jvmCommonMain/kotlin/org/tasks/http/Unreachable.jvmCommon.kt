package org.tasks.http

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal actual fun Throwable.isUnreachable(): Boolean =
    this is SocketTimeoutException || this is ConnectException || this is UnknownHostException
