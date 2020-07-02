package codes.unwritten.common

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.core.json.jackson.DatabindCodec
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.*
import kotlin.reflect.jvm.jvmErasure

class RouteHandler(private val controller: Any, private val root: String) : CoroutineScope {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    // Coroutine context from verticle
    override val coroutineContext: CoroutineContext by lazy { Vertx.currentContext().dispatcher() }

    // To be injected by WebVerticle
    private lateinit var vertx: Vertx
    private lateinit var router: Router
    private lateinit var config: JsonObject

    // Auth of the controller class
    private var authProvider: AuthProvider? = null

    // Auth of controller members
    private val authProviders: MutableMap<KClass<out AuthProvider>, AuthProvider> =
        mutableMapOf(NoAuth::class to NoAuth())

    private suspend fun initController() {
        injectProperty(controller, vertx, "vertx")
        injectProperty(controller, config, "config")
        // Call init function it existed
        controller::class.memberFunctions.firstOrNull { it.name == "init" }?.callSuspend(controller)
    }

    private suspend fun createAuthProvider(type: KClass<out AuthProvider>, configKey: String): AuthProvider {
        val auth = if (configKey.isNotBlank())
            config.getJsonObject(configKey).mapTo(type.javaObjectType)
        else
            type.createInstance()   // Use default constructor
        // Inject Vertx instance if needed
        injectProperty(auth, vertx, "vertx")
        auth.init()
        return auth
    }

    suspend fun init() {
        controller::class.getAnnotation(Auth::class)?.let {
            val provider = createAuthProvider(it.providerType, it.configKey)
            authProviders.putIfAbsent(it.providerType, provider)
            authProvider = provider
        }

        if (root.isBlank()) {
            // Mount to root
            scanHandlers(router)
        } else {
            // Mount to sub directory
            val subrouter = Router.router(vertx)
            scanHandlers(subrouter)
            router.mountSubRouter(root, subrouter)
        }

        initController()
    }

    private suspend fun scanHandlers(router: Router) {
        val klass = controller::class
        klass.memberFunctions.forEach { func ->
            func.getAnnotation(Auth::class)?.let {
                authProviders.putIfAbsent(it.providerType, createAuthProvider(it.providerType, it.configKey))
            }
            func.getAnnotation(Request::class)?.let {
                router.route(it.method, it.path).coroHandler { context ->
                    invokeHandler(context, func)
                }
            } ?: func.getAnnotation(GET::class)?.let {
                router.get(it.path).coroHandler { context ->
                    invokeHandler(context, func)
                }
            } ?: func.getAnnotation(PUT::class)?.let {
                router.put(it.path).coroHandler { context ->
                    invokeHandler(context, func)
                }
            } ?: func.getAnnotation(POST::class)?.let {
                router.post(it.path).coroHandler { context ->
                    invokeHandler(context, func)
                }
            } ?: func.getAnnotation(DELETE::class)?.let {
                router.delete(it.path).coroHandler { context ->
                    invokeHandler(context, func)
                }
            } ?: func.getAnnotation(PATCH::class)?.let {
                router.patch(it.path).coroHandler { context ->
                    invokeHandler(context, func)
                }
            }
        }
    }

    private fun castParam(input: String, param: KParameter): Any? {
        return DatabindCodec.mapper().convertValue(input, param.type.jvmErasure.java)
    }

    private fun castParam(input: List<String>, param: KParameter): Any? {
        return try {
            DatabindCodec.mapper().convertValue(input, param.type.jvmErasure.java)
        } catch (e: IllegalArgumentException) {
            try {
                DatabindCodec.mapper().convertValue(input.last(), param.type.jvmErasure.java)
            } catch (e: IllegalArgumentException) {
                throw e
            }
        }
    }

    private suspend fun invokeHandler(context: RoutingContext, func: KFunction<*>) {
        val authClass =
            (func.annotations.firstOrNull { it is Auth } as? Auth)?.providerType
                ?: if (authProvider == null) NoAuth::class else authProvider!!::class
        val authProvider = authProviders[authClass] ?: throw ForbiddenException()
        val userPrincipal = authProvider.auth(context)

        val paramMap: Map<KParameter, Any?> = func.parameters.mapNotNull { param ->
            (param.getAnnotation(PathParam::class)?.name?.let { name ->
                // Param is PathParam
                param to castParam(context.pathParam(if (name.isBlank()) param.name else name), param)
            }) ?: (param.getAnnotation(QueryParam::class)?.name?.let { name ->
                // Param is QueryParam
                param to castParam(context.queryParam(if (name.isBlank()) param.name else name), param)
            }) ?: (param.getAnnotation(FromBody::class)?.let {
                // Param is in body
                param to context.bodyAsJson.mapTo(param.type.jvmErasure.java)
            }) ?: if (param.kind == KParameter.Kind.INSTANCE) {
                // Param is this
                param to controller
            } else if (param.type.jvmErasure.isSubclassOf(UserPrincipal::class)) {
                // Param is the Credential
                param to userPrincipal
            } else if (param.type.jvmErasure.isSubclassOf(RoutingContext::class)) {
                // Param is the RoutingContext
                param to context
            } else if (context.pathParams().containsKey(param.name)) {
                // No annotation, search for path param first
                param to castParam(context.pathParam(param.name), param)
            } else if (!context.queryParams().contains(param.name)) {
                // Then query params
                param to castParam(context.queryParam(param.name), param)
            } else {
                if (param.isOptional) {
                    // Optional param
                    null
                } else {
                    // Unknown param
                    throw BadRequestException("Bad request")
                }
            }
        }.toMap()

        if (func.returnType == Unit::class.starProjectedType) {
            func.callSuspendBy(paramMap)
            if (!context.response().ended()) {
                log.warn("The response has not been ended by function '${func.name}'.")
                context.response().setStatusCode(204).end()
            }
        } else {
            val ret = func.callSuspendBy(paramMap)
            if (ret == null) {
                // 204 No content
                context.response().setStatusCode(204).end()
            } else {
                if (context.response().ended()) {
                    log.warn("The response has been ended by function '${func.name}', return value ignored.")
                } else {
                    context.response().endWith(ret)
                }
            }
        }
    }

    private fun Route.coroHandler(handler: suspend (RoutingContext) -> Any) {
        handler {
            launch(coroutineContext) {
                try {
                    handler(it)
                } catch (e: VertxWebException) {
                    it.response().setStatusCode(e.statusCode).setStatusMessage(e.message).end()
                } catch (e: Throwable) {
                    it.response().internalError(e.message ?: "Internal error")
                }
            }
        }
    }
}