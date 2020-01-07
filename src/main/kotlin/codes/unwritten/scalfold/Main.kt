package codes.unwritten.scalfold

import ch.qos.logback.classic.Level
import codes.unwritten.common.CoroutineWebVerticle
import codes.unwritten.common.deploy
import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import com.xenomachina.argparser.mainBody
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.core.json.jackson.DatabindCodec
import io.vertx.core.logging.LoggerFactory.LOGGER_DELEGATE_FACTORY_CLASS_NAME
import io.vertx.core.logging.SLF4JLogDelegateFactory
import net.logstash.logback.argument.StructuredArguments.keyValue
import org.slf4j.LoggerFactory

class MainVerticle : CoroutineWebVerticle() {
    override suspend fun start() {
        super.start()

        val port = config.getInteger("http.port", 8080)
        server.requestHandler(router).listen(port)

        log.info("MainVerticle started listening on port $port.", keyValue("listening_port", port))
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

        vertx.deploy(MainVerticle(), config)

        log.info("Application started.")
    }
}
