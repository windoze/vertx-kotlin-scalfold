package codes.unwritten.common

import io.vertx.core.http.HttpMethod
import kotlin.reflect.KClass

annotation class Request(val method: HttpMethod, val path: String)
annotation class PathParam(val name: String)
annotation class QueryParam(val name: String)
annotation class FromBody()
annotation class Auth(val providerType: KClass<out AuthProvider>, val configKey: String = "")