package codes.unwritten.common

import net.logstash.logback.argument.StructuredArguments

/**
 * Verticle to host controllers
 * It will inject `config` and `vertx` properties into controllers if these properties exist and have the correct types.
 * It will also inject `vertx` into AuthProviders used by controllers.
 * @param portKey the config key to get the listening port
 */
open class WebVerticle(portKey: String = "") : CoroutineWebVerticle() {
    private var portKey: String = ""
    private var listeningPort: Int = 0

    private val handlers: MutableList<RouteHandler> = mutableListOf()

    /**
     * Create WebVerticle listening on the port specified by `portKey`
     */
    init {
        this.portKey = portKey
        this.listeningPort = 0
    }

    /**
     * Create WebVerticle listening on `listeningPort`
     * @param listeningPort the port to listen
     */
    constructor(listeningPort: Int) : this() {
        this.portKey = ""
        this.listeningPort = listeningPort
    }

    /**
     * Add a controller object into this WebVerticle, and mount routes under `root`
     * @param root the path to mount the controller, default to the root
     */
    fun addController(controller: Any, root: String = ""): WebVerticle {
        handlers.add(RouteHandler(controller, root))
        return this
    }

    private fun getPort(): Int {
        if (listeningPort != 0) return listeningPort
        return if (portKey.isBlank()) {
            System.getenv("HTTP_PLATFORM_PORT")?.toInt() ?: config.getInteger("http.port", 8080)
        } else {
            System.getenv("HTTP_PLATFORM_PORT")?.toInt() ?: config.getInteger(portKey, 8080)
        }
    }

    override suspend fun start() {
        super.start()

        // Setup route handlers
        handlers.forEach { setupRouteHandler(it) }

        // Start listening
        getPort().let {
            server.requestHandler(router).listen(it)
            log.info(
                "MainVerticle started listening on port $it.", StructuredArguments.keyValue("listening_port", it)
            )
        }
    }

    private suspend fun setupRouteHandler(r: RouteHandler) {
        injectProperty(r, vertx, "vertx")
        injectProperty(r, router, "router")
        injectProperty(r, config, "config")
        r.init()
    }
}