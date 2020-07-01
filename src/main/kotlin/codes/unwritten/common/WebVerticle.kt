package codes.unwritten.common

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.jvmErasure

open class WebVerticle : CoroutineWebVerticle() {
    private val authProviders: MutableMap<KClass<out AuthProvider>, AuthProvider> =
        mutableMapOf(NoAuth::class to NoAuth())

    private fun setProperty(instance: Any, prop: KMutableProperty1<Any, Any?>, value: Any?) {
        prop.set(instance, value)
    }

    private suspend fun createAuthProvider(type: KClass<out AuthProvider>, configKey: String): AuthProvider {
        val auth = if (configKey.isNotBlank())
            config.getJsonObject(configKey).mapTo(type.javaObjectType)
        else
            type.createInstance()   // Use default constructor
        // Inject Vertx instance if needed
        auth::class.memberProperties
            .filterIsInstance<KMutableProperty<*>>()
            .firstOrNull {
                it.returnType.isSubtypeOf(Vertx::class.starProjectedType) && it.name == "vertx"
            }?.let {
                @Suppress("UNCHECKED_CAST")
                setProperty(auth, it as KMutableProperty1<Any, Any?>, vertx)
            }
        auth.init()
        return auth
    }

    override suspend fun start() {
        super.start()
        this::class.getAnnotation(Auth::class)?.let {
            authProviders.putIfAbsent(it.providerType, createAuthProvider(it.providerType, it.configKey))
        }
        scanHandlers(router)
    }

    private suspend fun scanHandlers(router: Router) {
        val klass = this::class
        klass.memberFunctions.filter {
            !it.returnType.isMarkedNullable
        }.forEach { func ->
            func.getAnnotation(Auth::class)?.let {
                authProviders.putIfAbsent(it.providerType, createAuthProvider(it.providerType, it.configKey))
            }
            func.getAnnotation(Request::class)?.let {
                router.route(it.method, it.path).coroHandler { context ->
                    invokeHandler(context, func)
                }
            }
        }
    }

    private fun castParam(input: String, param: KParameter): Any? {
        return mapper.convertValue(input, param.type.jvmErasure.java)
    }

    private fun castParam(input: List<String>, param: KParameter): Any? {
        return try {
            mapper.convertValue(input, param.type.jvmErasure.java)
        } catch (e: IllegalArgumentException) {
            try {
                mapper.convertValue(input.last(), param.type.jvmErasure.java)
            } catch (e: IllegalArgumentException) {
                throw e
            }
        }
    }

    private suspend fun invokeHandler(context: RoutingContext, func: KFunction<*>): Any {
        val authClass = (func.annotations.firstOrNull { it is Auth } as? Auth)?.providerType ?: NoAuth::class
        val authProvider = authProviders[authClass] ?: throw ForbiddenException()
        val userPrincipal = authProvider.auth(context)

        val paramMap: Map<KParameter, Any?> = func.parameters.mapNotNull { param ->
            (param.getAnnotation(PathParam::class)?.name?.let { name ->
                // Param is PathParam
                if (name.isBlank()) {
                    param to castParam(context.pathParam(param.name), param)
                } else {
                    param to castParam(context.pathParam(name), param)
                }
            }) ?: (param.getAnnotation(QueryParam::class)?.name?.let { name ->
                // Param is QueryParam
                if (name.isBlank()) {
                    param to castParam(context.queryParam(param.name), param)
                } else {
                    param to castParam(context.queryParam(name), param)
                }
            }) ?: (param.getAnnotation(FromBody::class)?.let {
                // Param is in body
                param to context.bodyAsJson.mapTo(param.type.jvmErasure.java)
            }) ?: if (param.kind == KParameter.Kind.INSTANCE) {
                // Param is this
                param to this
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
        return func.callSuspendBy(paramMap)!!
    }

    fun getAuthProvider(klass: KClass<out AuthProvider>): AuthProvider? {
        return authProviders[klass]
    }

    companion object {
        val mapper: ObjectMapper = ObjectMapper().registerModule(KotlinModule())
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
    }
}