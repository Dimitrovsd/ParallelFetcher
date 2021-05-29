package fetcher.model

import java.util.concurrent.CopyOnWriteArrayList
import org.asynchttpclient.ListenableFuture
import org.asynchttpclient.Request
import org.asynchttpclient.Response

class RequestData(
    val id: Long,
    val request: Request,
) {
    val callsInFly = CopyOnWriteArrayList<ListenableFuture<Response>>()
    var response: String? = null
}
