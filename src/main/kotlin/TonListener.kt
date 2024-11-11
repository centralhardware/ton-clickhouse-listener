import com.clickhouse.jdbc.ClickHouseDataSource
import kotliquery.sessionOf
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.chunked
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

val dataSource: DataSource =
    try {
        ClickHouseDataSource(System.getenv("CLICKHOUSE_URL"))
    } catch (e: SQLException) {
        throw RuntimeException(e)
    }

val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun save(data: List<Pair<LocalDateTime, String>>) = sessionOf(dataSource).use { session ->
    val parsedData = data.mapNotNull {
        try {
            val jsonElement = json.parseToJsonElement(it.second)
            val accountId = jsonElement.jsonObject["account_id"]?.jsonPrimitive?.content
            val lt = jsonElement.jsonObject["lt"]?.jsonPrimitive?.longOrNull
            val txHash = jsonElement.jsonObject["tx_hash"]?.jsonPrimitive?.content

            listOf(it.first, accountId, lt, txHash)
        } catch (e: Exception) {
            println("Ошибка при парсинге JSON: ${e.message}")
            null
        }
    }

    if (parsedData.isNotEmpty()) {
        session.batchPreparedStatement(
            "INSERT INTO ton.transactions (date_time, account_id, lt, tx_hash) VALUES (?, ?, ?, ?)",
            parsedData
        )
    }
}

fun sseFlow(client: OkHttpClient, request: Request): Flow<Pair<LocalDateTime, String>> = callbackFlow {
    val eventSource = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            println("Подключено к SSE")
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            trySend(Pair(LocalDateTime.now(), data)).isSuccess
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
        .header("Authorization", "Bearer ${System.getenv("TONAPI_TOKEN")}")
        .build()

    val flow = sseFlow(client, request)

    flow.onEach(::println)
        .buffer()
        .chunked(System.getenv("CHUNK_SIZE").toInt())
        .collect(::save)
}