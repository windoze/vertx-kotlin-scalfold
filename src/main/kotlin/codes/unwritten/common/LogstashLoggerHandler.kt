package codes.unwritten.common

import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpVersion
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.LoggerFormat
import io.vertx.ext.web.handler.LoggerHandler
import io.vertx.ext.web.handler.LoggerHandler.DEFAULT_FORMAT
import io.vertx.ext.web.impl.Utils
import net.logstash.logback.argument.StructuredArguments.keyValue
import org.slf4j.LoggerFactory

/**
 * Logstash access logging handler for Vert.X
 */
class LogstashLoggerHandler(private val immediate: Boolean = false, private val format: LoggerFormat = DEFAULT_FORMAT) :
    LoggerHandler {
    private val logger = LoggerFactory.getLogger(this.javaClass)

    private fun log(
        context: RoutingContext,
        timestamp: Long,
        remoteClient: String?,
        version: HttpVersion,
        method: HttpMethod,
        uri: String
    ) {
        val request = context.request()
        var contentLength: Long = 0
        if (immediate) {
            val obj = request.headers().get("content-length")
            if (obj != null) {
                contentLength = try {
                    java.lang.Long.parseLong(obj.toString())
                } catch (e: NumberFormatException) {
                    // ignore it and continue
                    0
                }

            }
        } else {
            contentLength = request.response().bytesWritten()
        }
        val versionFormatted = when (version) {
            HttpVersion.HTTP_1_0 -> "HTTP/1.0"
            HttpVersion.HTTP_1_1 -> "HTTP/1.1"
            HttpVersion.HTTP_2 -> "HTTP/2.0"
        }

        val headers = request.headers()
        val status = request.response().statusCode
        var message: String? = null
        // as per RFC1945 the header is referer but it is not mandatory some implementations use referrer
        var referrer: String? = if (headers.contains("referrer")) headers.get("referrer") else headers.get("referer")
        var userAgent: String? = request.headers().get("user-agent")
        referrer = referrer ?: ""
        userAgent = userAgent ?: ""

        when (format) {
            LoggerFormat.DEFAULT -> {
                message = String.format(
                    "%s - - [%s] \"%s %s %s\" %d %d \"%s\" \"%s\"",
                    remoteClient,
                    Utils.formatRFC1123DateTime(timestamp),
                    method,
                    uri,
                    versionFormatted,
                    status,
                    contentLength,
                    referrer,
                    userAgent
                )
            }
            LoggerFormat.SHORT -> message = String.format(
                "%s - %s %s %s %d %d - %d ms",
                remoteClient,
                method,
                uri,
                versionFormatted,
                status,
                contentLength,
                System.currentTimeMillis() - timestamp
            )
            LoggerFormat.TINY -> message = String.format(
                "%s %s %d %d - %d ms",
                method,
                uri,
                status,
                contentLength,
                System.currentTimeMillis() - timestamp
            )
        }
        doLog(
            status, message,
            keyValue("method", method),
            keyValue("protocol", versionFormatted),
            keyValue("remote_host", remoteClient),
            keyValue("requested_uri", uri),
            keyValue("status_code", status),
            keyValue("content_length", contentLength),
            keyValue("referrer", referrer),
            keyValue("user_agent", userAgent)
        )
    }

    private fun doLog(status: Int, message: String, vararg others: Any) {
        when {
            status >= 500 -> logger.error(message, *others)
            status >= 400 -> logger.warn(message, *others)
            else -> logger.info(message, *others)
        }
    }

    override fun handle(context: RoutingContext?) {
        if (context == null) return
        val timestamp = System.currentTimeMillis()
        val remoteClient = context.request().remoteAddress()?.host()
        val method = context.request().method()
        val uri = context.request().uri()
        val version = context.request().version()

        if (immediate) {
            log(context, timestamp, remoteClient, version, method, uri)
        } else {
            context.addBodyEndHandler { log(context, timestamp, remoteClient, version, method, uri) }
        }

        context.next()

    }
}