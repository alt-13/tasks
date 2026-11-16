package org.tasks.http

import io.ktor.client.engine.darwin.DarwinHttpRequestException

// Same as translated(): every Darwin request failure counts as a connection error.
internal actual fun Throwable.isUnreachable(): Boolean = this is DarwinHttpRequestException
