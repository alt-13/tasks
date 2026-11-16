package org.tasks.http

/** Timeouts and connect or DNS failures: the server couldn't be reached. */
internal expect fun Throwable.isUnreachable(): Boolean
