package io.github.jaypetez.ideaforge.ui.onboarding

import io.github.jaypetez.ideaforge.providers.ClientFactory
import io.github.jaypetez.ideaforge.providers.ProviderHttpClients
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CopyOnWriteArrayList

/** The production checker and adapters, with only their HTTP responses intercepted. */
internal class CatalogueCheck(private val reply: (Request) -> String) : AutoCloseable {
    val requests = CopyOnWriteArrayList<Request>()
    private val clients = CopyOnWriteArrayList<OkHttpClient>()
    val idle: Boolean get() = clients.all { it.dispatcher.runningCallsCount() == 0 }

    val checker = ProviderCheck.withClients(ClientFactory { policy ->
        ProviderHttpClients.create(policy).newBuilder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(reply(request).toResponseBody()).build()
        }.build().also { clients += it }
    })

    override fun close() {
        clients.forEach {
            it.dispatcher.cancelAll()
            it.dispatcher.executorService.shutdown()
            it.connectionPool.evictAll()
        }
    }
}
