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
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.ext.web.client.sendAwait
import io.vertx.kotlin.ext.web.client.sendBufferAwait
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

interface UserPrincipal

class Anonymous : UserPrincipal

interface AuthProvider {
    suspend fun auth(context: RoutingContext): UserPrincipal
    suspend fun init() {}
}

class NoAuth : AuthProvider {
    override suspend fun auth(context: RoutingContext) = Anonymous()
}

class BasicAuthUserPrincipal(val username: String = "", val password: String = "") : UserPrincipal

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
            val (username, password) = decoded.toString().split(':', limit = 2)
            val user = BasicAuthUserPrincipal(username, password)
            if (!userPassStore.verifyUser(user)) throw ForbiddenException()
            return user
        } catch (e: IllegalArgumentException) {
            throw ForbiddenException()
        }
    }
}

class AADUserPrincipal(
    val name: String,
    val preferredUsername: String,
    val issuedAt: Instant = Instant.EPOCH,
    val notBefore: Instant = Instant.EPOCH,
    val expiration: Instant = Instant.EPOCH
) : UserPrincipal

class AADAuth : AuthProvider {
    @Transient
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Transient
    private lateinit var vertx: Vertx
    private val client by lazy { WebClient.create(vertx) }

    val authority: String = "https://login.microsoftonline.com/common"
    val audience: String = ""
    val tenantId: String = ""
    val appId: String = ""
    val secret: String = ""

    @Transient
    private lateinit var keys: Map<String, RSAPublicKey>

    @Transient
    private val tokenCache: LoadingCache<Int, Deferred<String>> = CacheBuilder.newBuilder()
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
                    val key = cert.getPublicKey() as RSAPublicKey
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
                throw ForbiddenException()
            }

            return AADUserPrincipal(
                name = decoded.getString("name", ""),
                preferredUsername = preferredUsername,
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