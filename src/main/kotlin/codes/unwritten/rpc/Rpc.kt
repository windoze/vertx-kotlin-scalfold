@file:Suppress("unused")

package codes.unwritten.rpc

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import io.vertx.core.AsyncResult
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.eventbus.Message
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// TODO: Register parameter and return type on the fly and disable this, for security concerns
private val kryo = Kryo().apply {
    isRegistrationRequired = false
}

@Suppress("ArrayInDataClass")
data class RpcRequest(
    val service: String = "",
    val method: String = "",
    val args: List<out Any?> = listOf()
) {
    @Synchronized
    fun toBytes(): ByteArray = ByteArrayOutputStream().use {
        val out = Output(it, 104857600)
        kryo.writeObject(out, this)
        out.flush()
        out.buffer
    }

    fun toBuffer(): Buffer = Buffer.buffer(toBytes())
}

fun ByteArray.toRpcRequest(): RpcRequest = ByteArrayInputStream(this).use {
    (kryo.readObject(Input(it), RpcRequest::class.java) as RpcRequest)
}

fun Buffer.toRpcRequest(): RpcRequest = bytes.toRpcRequest()

data class RpcResponse(val response: Any? = null) {
    fun toBytes(): ByteArray = ByteArrayOutputStream().use {
        val out = Output(it, 104857600)
        kryo.writeObject(out, this)
        out.flush()
        out.buffer
    }

    fun toBuffer(): Buffer = Buffer.buffer(toBytes())
}

fun ByteArray.toRpcResponse(): RpcResponse = ByteArrayInputStream(this).use {
    (kryo.readObject(Input(it), RpcResponse::class.java) as RpcResponse)
}

fun Buffer.toRpcResponse(): RpcResponse = bytes.toRpcResponse()

inline fun <reified T : Any> getProxyWithBlock(
    name: String,
    crossinline block: (RpcRequest, Continuation<Any?>) -> Unit
) =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args: Array<Any?> ->
        val lastArg = args.lastOrNull()
        if (lastArg is Continuation<*>) {
            // The last argument of a suspend function is the Continuation object
            @Suppress("UNCHECKED_CAST") val cont = lastArg as Continuation<Any?>
            val argsButLast = args.take(args.size - 1)
            // Call the block with the request and the continuation
            block(RpcRequest(name, method.name, argsButLast.toTypedArray().toList()), cont)
            // Suspend the coroutine to wait for the reply
            COROUTINE_SUSPENDED
        } else {
            // The function is not suspend
            null
        }
    } as T

/**
 * Dynamically create the service proxy object for the given interface
 * @param vertx Vertx instance
 * @param channel Name of the channel where RPC service listening
 * @param name Name of the service
 * @return RPC proxy object implements T
 */
inline fun <reified T : Any> getServiceProxy(
    vertx: Vertx,
    channel: String,
    name: String,
    deliveryOptions: DeliveryOptions = DeliveryOptions()
) =
    getProxyWithBlock(name) { req, cont ->
        vertx.eventBus()
            .request(channel, req.toBytes(), deliveryOptions, Handler<AsyncResult<Message<ByteArray>>> { event ->
                // Resume the suspended coroutine on reply
                if (event?.succeeded() == true) {
                    cont.resume(event.result().body().toRpcResponse().response)
                } else {
                    cont.resumeWithException(event?.cause() ?: Exception("Unknown error"))
                }
            })
    } as T
