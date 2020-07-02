package codes.unwritten.common

import io.vertx.core.http.HttpMethod
import kotlin.reflect.KClass

/**
 * The function is request handler
 * @param method The HTTP method this function can handle
 * @param method The path of this function to handle
 */
@Target(AnnotationTarget.FUNCTION)
annotation class Request(val method: HttpMethod, val path: String)

/**
 * The param is taken from path
 * @param name the name of the path parameter
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class PathParam(val name: String)

/**
 * The param is taken from query string
 * @param name the name of the query parameter
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class QueryParam(val name: String)

/**
 * The param is taken from body as JSON
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class FromBody()

/**
 * The AuthProvider to be used for this class or function
 * @param providerType the class of the AuthProvider
 * @param configKey the configKey to be used to create the auth provider,
 *                  the config is taken from the WebVerticle instance which hosts the controller
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Auth(val providerType: KClass<out AuthProvider>, val configKey: String = "")