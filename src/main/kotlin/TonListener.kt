import com.clickhouse.jdbc.ClickHouseDataSource
import kotliquery.queryOf
import kotliquery.sessionOf
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.chunked
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.sql.SQLException
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

val dataSource: DataSource =
    try {
        ClickHouseDataSource(System.getenv("CLICKHOUSE_URL"))
    } catch (e: SQLException) {
        throw RuntimeException(e)
    }

fun save(data: List<String>) = sessionOf(dataSource).use { session ->
    session.batchPreparedStatement(
        """
            INSERT INTO ton.transactions (account_id, lt, tx_hash)
            SELECT
                JSONExtractString(json_data, 'account_id') AS account_id,
                JSONExtractUInt(json_data, 'lt') AS lt,
                JSONExtractString(json_data, 'tx_hash') AS tx_hash
            FROM (
                SELECT
                    :data AS json_data
                );
            """, data.map { listOf(it) }
    )
}

fun sseFlow(client: OkHttpClient, request: Request): Flow<String> = callbackFlow {
    val eventSource = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            println("Подключено к SSE")
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            trySend(data).isSuccess
        }

        override fun onClosed(eventSource: EventSource) {
            println("Соединение закрыто")
            close() // Закрываем поток при закрытии соединения
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            println("Ошибка при подключении: ${t?.message}")
            close(t) // Закрываем поток при ошибке
        }
    })

    awaitClose { eventSource.cancel() } // Закрываем EventSource при завершении callbackFlow
}

fun main() = runBlocking {
    val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Отключение тайм-аута для SSE
        .build()

    val request = Request.Builder()
        .url("https://tonapi.io/v2/sse/accounts/transactions?accounts=ALL")
        .header("Authorization", "Bearer AEUPUUAS7GEOMOIAAAACCYEQ2ENGQZNA6PA336W5XCQSZDUPGWVVATWPHMW7TPO7X4BHN4Y")
        .build()

    val flow = sseFlow(client, request)

    flow.onEach(::println)
        .buffer()
        .chunked(System.getenv("CHUNK_SIZE").toInt())
        .collect(::save)
}
