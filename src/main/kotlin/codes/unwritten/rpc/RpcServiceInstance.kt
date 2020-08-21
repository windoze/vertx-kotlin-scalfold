package codes.unwritten.rpc

import codes.unwritten.common.injectProperty
import io.vertx.core.Vertx
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.memberFunctions

internal interface RpcServiceInstance {
    val impl: Any

    suspend fun startInstance(vertx: Vertx) {
        // Inject vertx member
        injectProperty(impl, vertx, "vertx")
        // Invoke start
        impl::class.memberFunctions.firstOrNull {
            it.name == "start"
        }?.callSuspend(impl)
    }


    suspend fun processRequest(request: RpcRequest): RpcResponse

    companion object {
        fun <T : Any> instance(impl: T): RpcServiceInstance {
            return object : RpcServiceInstance {
                override val impl: Any
                    get() = impl

                override suspend fun processRequest(request: RpcRequest): RpcResponse {
                    val ret = impl::class.members.first {
                        // TODO: Check signature to support overloading
                        it.name == request.method
                    }.callSuspend(impl, *(request.args.toTypedArray()))
                    return RpcResponse(ret)
                }
            }
        }
    }
}