package codes.unwritten.common

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.ext.web.client.sendAwait
import io.vertx.kotlin.ext.web.client.sendBufferAwait
import io.vertx.kotlin.ext.web.client.sendJsonObjectAwait
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Represent a user principal
 */
interface UserPrincipal

/**
 * User without explicit identity
 */
class Anonymous : UserPrincipal

/**
 * Represent a user principal with name
 */
interface UserPrincipalWithName : UserPrincipal {
    val name: String
}

/**
 * Represent a user principal with user name
 */
interface UserPrincipalWithUserName : UserPrincipal {
    val username: String
}

/**
 * Provide auth function
 */
interface AuthProvider {
    /**
     * Init the auth provider
     */
    suspend fun init() {}

    /**
     * Authenticate a request
     * @param context the routing context which contains the request
     * @return a user principal if auth succeeded
     */
    suspend fun auth(context: RoutingContext): UserPrincipal
}

/**
 * Dummy Auth provider, does nothing and always succeeds with an anonymous user principal returned
 */
class NoAuth : AuthProvider {
    override suspend fun auth(context: RoutingContext) = Anonymous()
}

/**
 * Basic Auth User Principal
 * @param username User name extracted from HTTP Basic Auth header
 * @param password Password extracted from HTTP Basic Auth header
 */
class BasicAuthUserPrincipal(
    override val username: String = "",
    val password: String = ""
) : UserPrincipalWithUserName

/**
 * HTTP Basic Auth provider
 */
class BasicAuth : AuthProvider {
    interface UserPassStore {
        suspend fun verifyUser(user: BasicAuthUserPrincipal): Boolean
    }

    val users: MutableMap<String, String> = mutableMapOf()

    var userPassStore: UserPassStore = object : UserPassStore {
        override suspend fun verifyUser(user: BasicAuthUserPrincipal): Boolean {
            return users[user.username] == user.password
        }
    }

    @ExperimentalStdlibApi
    override suspend fun auth(context: RoutingContext): UserPrincipal {
        try {
            val authHeader = context.request().getHeader("authorization") ?: throw ForbiddenException()
            val (schema, value) = authHeader.split(Regex("\\s+"), 2)

            if (schema.compareTo("basic", ignoreCase = true) != 0) {
                throw ForbiddenException()
            }
            val decoded = Base64.getDecoder().decode(value).decodeToString()
            val (username, password) = decoded.split(':', limit = 2)
            val user = BasicAuthUserPrincipal(username, password)
            if (!userPassStore.verifyUser(user)) throw ForbiddenException()
            return user
        } catch (e: IllegalArgumentException) {
            throw ForbiddenException()
        }
    }
}

enum class AADUserPrincipalType {
    USER,
    APP
}

/**
 * AzureAD User Principal, can be an AAD user or an AAP Application
 * @param type principal type
 * @param name name of the AAD User, empty if it's an application principal
 * @param username user name of the AAD User, empty if it's an application principal
 * @param appId AAD Application id, empty if it's an user principal
 * @param issuedAt Time of the token was issued
 * @param notBefore the token is not valid before this time
 * @param expiration the token is expired after this time
 */
class AADUserPrincipal(
    val type: AADUserPrincipalType,
    override val name: String,
    override val username: String,
    val appId: String,
    val issuedAt: Instant = Instant.EPOCH,
    val notBefore: Instant = Instant.EPOCH,
    val expiration: Instant = Instant.EPOCH
) : UserPrincipalWithName, UserPrincipalWithUserName

/**
 * AAD Auth Provider
 */
open class AADAuth : AuthProvider {
    @Transient
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Transient
    protected lateinit var vertx: Vertx
    protected val client by lazy { WebClient.create(vertx) }

    /**
     * AAD authority
     */
    val authority: String = "https://login.microsoftonline.com/common"

    /**
     * Audience
     */
    val audience: String = ""

    /**
     * AAD Tenant name or UUID, i.e. "contoso.com"
     */
    val tenantId: String = ""

    /**
     * AAD AppId
     */
    val appId: String = ""

    /**
     * AAD App Security
     */
    val secret: String = ""

    /**
     * AppId in this list is authorized
     */
    val appIdAllowedList: List<String> = listOf()

    @Transient
    private lateinit var keys: Map<String, RSAPublicKey>

    @Transient
    protected val tokenCache: LoadingCache<Int, Deferred<String>> = CacheBuilder.newBuilder()
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .build(object : CacheLoader<Int, Deferred<String>>() {
            override fun load(key: Int): Deferred<String> {
                log.info("Acquiring token...")
                return CoroutineScope(vertx.dispatcher()).async {
                    log.debug("Requesting $authority/$tenantId/oauth2/v2.0/token")
                    val resp = client.postAbs("$authority/$tenantId/oauth2/v2.0/token")
                        .putHeader("content-type", "application/x-www-form-urlencoded")
                        .sendBufferAwait(
                            Buffer.buffer(
                                "client_id=$appId\n" +
                                        "&scope=https%3A%2F%2Fgraph.microsoft.com%2F.default\n" +
                                        "&client_secret=$secret\n" +
                                        "&grant_type=client_credentials"
                            )
                        )
                    val body = resp.bodyAsJsonObject()
                    body.getString("access_token", "")
                }
            }
        })

    override suspend fun init() {
        val openidCfg =
            client.getAbs("https://login.microsoftonline.com/$tenantId/v2.0/.well-known/openid-configuration")
                .sendAwait().bodyAsJsonObject()
        val jwksUri = openidCfg.getString("jwks_uri", "")
        if (jwksUri.isNotBlank()) {
            val body = client.getAbs(jwksUri).sendAwait().bodyAsJsonObject()
            keys = body.getJsonArray("keys", JsonArray())
                .map { it as JsonObject }
                .map {
                    val buffer = org.apache.commons.codec.binary.Base64.decodeBase64(
                        it.getJsonArray("x5c").getString(0).toByteArray()
                    )
                    val certStream = ByteArrayInputStream(buffer)
                    val cert = CertificateFactory.getInstance("X.509").generateCertificate(certStream)
                    val key = cert.publicKey as RSAPublicKey
                    it.getString("kid") to key
                }
                .toMap()
        } else {
            keys = mapOf()
        }
    }

    override suspend fun auth(context: RoutingContext): UserPrincipal {
        val authHeader = context.request().getHeader("authorization")
        if (authHeader == null || authHeader.isBlank()) {
            throw ForbiddenException()
        }

        val (type, token) = authHeader.split(' ')
        if (type.toLowerCase() != "bearer") {
            throw ForbiddenException()
        }

        try {
            val header = JsonObject(
                String(org.apache.commons.codec.binary.Base64.decodeBase64(token.split('.')[0].toByteArray()))
            )
            val kid = header.getString("kid")
            val pubKey = keys[kid]
            try {
                val algorithm = Algorithm.RSA256(pubKey, null)
                val verifier = JWT.require(algorithm)
                    .build()
                val jwt = verifier.verify(token)
                log.debug("JWT $jwt verified.")
            } catch (e: JWTVerificationException) {
                //Invalid signature/claims
                throw ForbiddenException(e.message ?: "Invalid token")
            }

            val decoded = JsonObject(
                String(org.apache.commons.codec.binary.Base64.decodeBase64(token.split('.')[1].toByteArray()))
            )
            val preferredUsername = decoded.getString("preferred_username", "")
            val aud = decoded.getString("aud", "")

            // Check audience
            if (audience.isNotBlank()) {
                // Use audience
                if (audience != aud) {
                    throw ForbiddenException("The token is not for this audience.")
                }
            } else {
                // Empty audience, fallback to appId
                if (aud != appId) {
                    throw ForbiddenException("The token is not for this audience.")
                }
            }

            // Check expiration
            val issuedAt = Instant.ofEpochSecond(decoded.getLong("iat", 0))
            val notBefore = Instant.ofEpochSecond(decoded.getLong("nbf", 0))
            val expiration = Instant.ofEpochSecond(decoded.getLong("exp", 0))
            val now = Instant.now()
            if (now < notBefore || now > expiration) {
                throw ForbiddenException("Token expired.")
            }

            // Check if there is a username
            if (preferredUsername.isNullOrBlank()) {
                val appId = decoded.getString("appid", "")

                // Check if there is an appId
                if (appId.isBlank()) {
                    throw ForbiddenException()
                } else {
                    // It's a AADApp principal
                    if (appId.toLowerCase() in appIdAllowedList.map { it.toLowerCase() })
                        return AADUserPrincipal(
                            type = AADUserPrincipalType.APP,
                            name = "",
                            username = "",
                            appId = appId,
                            issuedAt = issuedAt,
                            notBefore = notBefore,
                            expiration = expiration
                        )
                }
            }

            // AAD User principal
            return AADUserPrincipal(
                type = AADUserPrincipalType.USER,
                name = decoded.getString("name", ""),
                username = preferredUsername,
                appId = "",
                issuedAt = issuedAt,
                notBefore = notBefore,
                expiration = expiration
            )
        } catch (e: Throwable) {
            log.warn(e.message)
            throw ForbiddenException()
        }
    }
}

/**
 * AAD Auth Provider with security group constraints, this provider only works with user, not for AAD app
 */
class AADSecurityGroupAuth : AADAuth() {
    @Transient
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    /**
     * Allowed security groups, only user in these group can be authorized
     */
    val securityGroups: List<String> = listOf()

    @Transient
    private lateinit var groups: JsonArray

    val groupCache: LoadingCache<String, Deferred<List<String>>> = CacheBuilder.newBuilder()
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .build(object : CacheLoader<String, Deferred<List<String>>>() {
            override fun load(key: String): Deferred<List<String>> {
                log.info("Checking user groups...")
                return CoroutineScope(vertx.dispatcher()).async {
                    val myToken = tokenCache[0].await()
                    val resp = client.postAbs("https://graph.microsoft.com/v1.0/users/$key/checkMemberGroups")
                        .putHeader("Authorization", "Bearer $myToken")
                        .sendJsonObjectAwait(
                            jsonObjectOf(
                                "groupIds" to groups
                            )
                        )
                    val body = resp.bodyAsJsonObject()
                    body.getJsonArray("value").map { it.toString() }.toList()
                }
            }
        })

    override suspend fun init() {
        super.init()
        groups = JsonArray(securityGroups.map {
            //https://graph.microsoft.com/v1.0/groups?$filter=displayName+eq+'XXX'&$select=id
            val resp = client.getAbs("https://graph.microsoft.com/v1.0/groups")
                .putHeader("authorization", tokenCache[0].await())
                .addQueryParam("\$filter", "displayName eq '$it'")
                .addQueryParam("\$select", "id")
                .sendAwait()
            val b = resp.bodyAsJsonObject()
            if (!b.getJsonArray("value").isEmpty) {
                b.getJsonArray("value").getJsonObject(0).getString("id")
            } else ""
        }.filter { it.isNotBlank() })
    }

    override suspend fun auth(context: RoutingContext): UserPrincipal {
        val userPrincipal = super.auth(context) as AADUserPrincipal

        // Security group is for users only
        if (userPrincipal.type != AADUserPrincipalType.USER) {
            throw ForbiddenException()
        }

        // Check is the user is in the security group
        val groups = groupCache[userPrincipal.username].await()
        if (groups.isEmpty()) {
            throw ForbiddenException()
        }

        return userPrincipal
    }
}