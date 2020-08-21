@file:Suppress("unused")

package codes.unwritten.common

import com.fasterxml.jackson.core.type.TypeReference
import io.vertx.core.*
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerResponse
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.core.json.jackson.DatabindCodec
import io.vertx.core.json.jackson.JacksonCodec
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.HttpRequest
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.kotlin.core.deployVerticleAwait
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.coroutines.toChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.logstash.logback.argument.StructuredArgument
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import sun.misc.Unsafe
import kotlin.reflect.*
import kotlin.reflect.full.isSupertypeOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.jvm.isAccessible

/**
 * Convert any object into JSON string
 */
fun Any?.stringify(): String = Json.encode(this)

/**
 * State a coroutine in a CoroutineVerticle
 */
fun CoroutineVerticle.coro(block: suspend CoroutineScope.() -> Unit) = launch(vertx.dispatcher(), block = block)

/**
 * Map request body to T
 */
inline fun <reified T> RoutingContext.body(): T = bodyAsJson.mapTo(T::class.java)

inline fun <reified T> JsonObject.mapAs(): T = mapTo(T::class.java)

/**
 * Map message body to T
 */
inline fun <reified T> Message<String>.bodyAs(): T = JacksonCodec.decodeValue(body(), object : TypeReference<T>() {})

/**
 * Start a loop to receive and process every message in a channel in a type-safe manner
 */
suspend inline fun <C : CoroutineVerticle, reified T, R> C.forEachMessage(
    channel: String,
    crossinline f: suspend C.(T) -> R
) {
    coro {
        val log = LoggerFactory.getLogger(this.javaClass)
        val stream = vertx.eventBus().consumer<String>(channel).toChannel(vertx)
        for (msg in stream) {
            val req = Json.decodeValue(msg.body(), T::class.java)
            try {
                msg.reply(f(req)?.stringify())
            } catch (e: Throwable) {
                log.error(
                    "Failed to process request, error message is ${e::class.simpleName}('${e.message})'.",
                    kv("type", e::class.simpleName),
                    kv("error", e.message),
                    kv("stack", e.stackTrace)
                )
                msg.fail(1, e.message)
            }
        }
    }
}

/**
 * Type-safe message sending/replying
 */
suspend inline fun <T, reified R> Vertx.send(channel: String, t: T): R {
    return awaitResult<Message<String>> { eventBus().request(channel, t?.stringify(), it) }.bodyAs()
}

fun HttpServerResponse.notFound(msg: String = "Not found") {
    setStatusCode(404).end(msg)
}

fun HttpServerResponse.badRequest(msg: String = "Bad request") {
    setStatusCode(400).end(msg)
}

fun HttpServerResponse.internalError(msg: String = "Internal Server Error") {
    setStatusCode(500).end(msg)
}

fun HttpServerResponse.endWith(o: Any) {
    putHeader("content-type", "application/json; charset=utf-8").end(Json.encodePrettily(o))
}

inline fun <reified T> HttpRequest<Buffer>.bodyCodec(): HttpRequest<T> {
    return `as`(BodyCodec.json(T::class.java))
}

inline fun <reified T : Verticle> Vertx.deploy(v: T, config: JsonObject = JsonObject()) {
    val log = LoggerFactory.getLogger(this.javaClass)
    try {
        log.info("Deploying ${T::class.java.simpleName}...")
        deployVerticle(v, DeploymentOptions().setConfig(config))
        log.info("${T::class.java.simpleName} deployed.")
    } catch (e: Throwable) {
        log.error(
            "Failed to deploy ${T::class.java.simpleName}, error is ${e::class.simpleName}(${e.message}).",
            kv("type", e::class.simpleName),
            kv("error", e.message)
        )
        throw e
    }
}

suspend inline fun <reified T : Verticle> Vertx.deployAwait(v: T, config: JsonObject = JsonObject()) {
    val log = LoggerFactory.getLogger(this.javaClass)
    try {
        log.info("Deploying ${T::class.java.simpleName}...")
        deployVerticleAwait(v, DeploymentOptions().setConfig(config))
        log.info("${T::class.java.simpleName} deployed.")
    } catch (e: Throwable) {
        log.error(
            "Failed to deploy ${T::class.java.simpleName}, error is ${e::class.simpleName}(${e.message}).",
            kv("type", e::class.simpleName),
            kv("error", e.message)
        )
        throw e
    }
}

inline fun <reified T : Verticle> Vertx.deploy(config: JsonObject) =
    deploy(DatabindCodec.mapper().convertValue(config, object : TypeReference<T>() {}), config)

open class StructuralException(message: String, val args: Array<out StructuredArgument>) : Throwable(message)
open class VertxWebException(message: String, val statusCode: Int = 500, vararg args: StructuredArgument) :
    StructuralException(message, args)

class BadRequestException(message: String = "Bad Request", vararg args: StructuredArgument) :
    VertxWebException(message, 400, *args)

class UnauthorizedException(
    message: String = "Unauthorized",
    val schema: String,
    val realm: String,
    vararg args: StructuredArgument
) :
    VertxWebException(message, 401, *args)

class ForbiddenException(message: String = "Forbidden", vararg args: StructuredArgument) :
    VertxWebException(message, 403, *args)

class NotFoundException(message: String = "Not Found", vararg args: StructuredArgument) :
    VertxWebException(message, 404, *args)

class InternalErrorException(message: String = "Internal Server Error", vararg args: StructuredArgument) :
    VertxWebException(message, 500, *args)

fun <T> CoroutineScope.promiseHandler(h: suspend () -> T): Handler<Promise<T>> {
    val log = LoggerFactory.getLogger(this.javaClass)
    return Handler {
        launch(coroutineContext) {
            try {
                it.complete(h())
            } catch (e: StructuralException) {
                log.warn(e.message, *(e.args), kv("stack", e.stackTrace))
                it.fail(e)
            } catch (e: Throwable) {
                log.warn(e.message, kv("stack", e.stackTrace))
                it.fail(e)
            }
        }
    }
}

open class CoroutineWebVerticle : CoroutineVerticle() {
    @Transient
    val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Transient
    lateinit var server: HttpServer

    val router: Router by lazy {
        Router.router(vertx)
    }

    override suspend fun start() {
        log.info("Starting ${this.javaClass.name}...")

        server = vertx.createHttpServer()
        router.route()
            .handler(LogstashLoggerHandler())
            .handler(BodyHandler.create())
            .handler(StaticHandler.create().setDefaultContentEncoding("UTF-8"))
            .handler(
                CorsHandler.create("*")
                    .allowedHeaders(
                        setOf(
                            "x-requested-with",
                            "Access-Control-Allow-Origin",
                            "origin",
                            "Content-Type",
                            "authorization",
                            "accept"
                        )
                    )
                    .allowedMethods(
                        setOf(
                            HttpMethod.GET,
                            HttpMethod.POST,
                            HttpMethod.PUT,
                            HttpMethod.DELETE,
                            HttpMethod.PATCH
                        )
                    )
            )
        router.get("/healthz").coroHandler {
            if (healthCheck())
                "OK"
            else
                throw InternalErrorException("Health check failed")
        }
    }

    fun Route.coroHandler(handler: suspend (RoutingContext) -> Any) {
        handler {
            launch(context.dispatcher()) {
                try {
                    it.response().endWith(handler(it))
                } catch (e: VertxWebException) {
                    it.response().setStatusCode(e.statusCode).setStatusMessage(e.message).end()
                } catch (e: Throwable) {
                    it.response().internalError(e.message ?: "Internal error")
                }
            }
        }
    }

    open suspend fun healthCheck(): Boolean = true
}

interface SuspendCloseable {
    suspend fun close()
}

suspend inline fun <T : SuspendCloseable, R> T.use(block: (T) -> R): R {
    var exception: Throwable? = null
    try {
        return block(this)
    } catch (e: Throwable) {
        exception = e
        throw e
    } finally {
        when (exception) {
            null -> close()
            else -> try {
                close()
            } catch (e: Throwable) {
                // ignored here
                e.printStackTrace()
            }
        }
    }
}

inline fun <reified T : Annotation> KClass<*>.getAnnotation(ann: KClass<T>): T? {
    return annotations.firstOrNull {
        it is T
    } as? T
}

inline fun <reified T : Annotation> KParameter.getAnnotation(ann: KClass<T>): T? {
    return annotations.firstOrNull {
        it is T
    } as? T
}

inline fun <reified T : Annotation> KFunction<*>.getAnnotation(ann: KClass<T>): T? {
    return annotations.firstOrNull {
        it is T
    } as? T
}

fun setProperty(instance: Any, prop: KMutableProperty1<Any, Any?>, value: Any?) {
    prop.isAccessible = true
    prop.set(instance, value)
}

fun injectProperty(target: Any, obj: Any, name: String = "") {
    // Inject property
    target::class.memberProperties
        .filterIsInstance<KMutableProperty<*>>()
        .firstOrNull {
            it.returnType.isSupertypeOf(obj::class.starProjectedType) && (name.isBlank() || it.name == name)
        }?.let {
            @Suppress("UNCHECKED_CAST")
            setProperty(target, it as KMutableProperty1<Any, Any?>, obj)
        }
}
