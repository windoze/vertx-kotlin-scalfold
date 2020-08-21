package codes.unwritten.scalfold

import ch.qos.logback.classic.Level
import codes.unwritten.common.*
import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import com.xenomachina.argparser.mainBody
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.core.json.jackson.DatabindCodec
import io.vertx.core.logging.LoggerFactory.LOGGER_DELEGATE_FACTORY_CLASS_NAME
import io.vertx.core.logging.SLF4JLogDelegateFactory
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.ext.web.client.sendAwait
import org.slf4j.Logger
import org.slf4j.LoggerFactory

// Config key is used with the config from the WebVerticle which hosts this controller
@Auth(AADAuth::class, configKey = "aadauth")
class MainController {
    @Transient
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    // Called before start
    fun init() {
        log.info("MainController initialized.")
    }

    // Fallback to class auth
    @Request(method = HttpMethod.GET, path = "/")
    suspend fun index(): String {
        return "OK"
    }

    // Explicit auth, user info taken from injected AADUserPrincipal param
    @GET(path = "/hello")
    @Auth(AADAuth::class)
    suspend fun hello(user: UserPrincipalWithName): String {
        return "Hello, ${user.name}."
    }

    // Path param
    // Explicit no auth
    @GET("/hello1/:user")
    @Auth(NoAuth::class)
    suspend fun hello1(@PathParam("user") user: String): String {
        return "Hello1 $user"
    }

    // Query param
    @GET("/hello2")
    suspend fun hello2(@QueryParam("user") user: String): String {
        return "Hello2 $user"
    }

    data class Param(val user: List<String> = listOf())

    // Request body
    @POST("/hello3")
    @Auth(NoAuth::class)
    suspend fun hello3(@FromBody param: Param): String {
        return "Hello3 ${param.user.first()}"
    }

    // Type conversion
    @GET("/hello4/:user")
    suspend fun hello4(@PathParam("user") user: Int): String {
        return "Hello4 $user"
    }

    // Different auth type
    @Auth(BasicAuth::class, configKey = "auth")
    @GET("/hello5/:user")
    suspend fun hello5(user: Int, p: UserPrincipalWithUserName, ctx: RoutingContext): String {
        return "Hello5 ${p.username} $user"
    }
}

interface SomeService {
    suspend fun hello(user: String): String
}

class SomeServiceImpl : SomeService {
    override suspend fun hello(user: String): String {
        return "Hello, $user."
    }
}

class WorldController {
    // Will be injected before starting serving
    lateinit var vertx: Vertx

    // Must be lazy as the `vertx` will be injected after construction
    val client: WebClient by lazy {
        WebClient.create(vertx)
    }

    // URL is "/w/world/xxx"
    @GET("/world/:user")
    suspend fun world(@PathParam("user") user: String): String {
        return "World $user"
    }

    // 204 response
    @GET("/null")
    suspend fun nll(): String? {
        return null
    }

    // Handle the response directly, w/o return value
    @GET("/e")
    suspend fun g(context: RoutingContext) {
        val resp = client.getAbs("http://example.com/").sendAwait()
        resp.headers().forEach {
            context.response().putHeader(it.key, it.value)
        }
        context.response().end(resp.body())
    }
}

class AppArgs(parser: ArgParser) {
    val conf by parser.storing("-c", "--conf", help = "config file name").default("config.json")
    val debug by parser.flagging("-d", "--debug", help = "show all debug logs").default(false)
    val verbose by parser.flagging("-v", "--verbose", help = "show debug log for the service only").default(false)

    fun debugLog(switch: Boolean) {
        val rootLogger =
            LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        rootLogger.level = Level.toLevel(if (switch) "debug" else "info")
    }

    fun devLog(switch: Boolean) {
        val logger = LoggerFactory.getLogger(this.javaClass.`package`.name) as ch.qos.logback.classic.Logger
        logger.level = Level.toLevel(if (switch) "debug" else "info")
    }
}

fun main(args: Array<String>) = mainBody {
    ArgParser(args).parseInto(::AppArgs).run {
        DatabindCodec.mapper().setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)

        debugLog(debug)
        devLog(verbose)

        System.setProperty(LOGGER_DELEGATE_FACTORY_CLASS_NAME, SLF4JLogDelegateFactory::class.java.name)
        LoggerFactory.getLogger(LoggerFactory::class.java) // Required for Logback to work in Vertx

        val log = LoggerFactory.getLogger(this.javaClass.name)
        log.info("Starting application...")

        val vertx = Vertx.vertx()
        val config = try {
            JsonObject(vertx.fileSystem().readFileBlocking(conf))
        } catch (e: io.vertx.core.file.FileSystemException) {
            log.warn("Config file '$conf' not found.")
            JsonObject()
        }

        // Add 2 controllers, 2nd is mounted under `/w`
        vertx.deploy(
            WebVerticle()
                .addController(MainController())
                .addController(WorldController(), "/w"),
            config
        )

        log.info("Application started.")
    }
}
