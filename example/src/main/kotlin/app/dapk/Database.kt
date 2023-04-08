package app.dapk

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.util.UUID

class Database {

    private data class MutableString(var content: String = randomUUID())

    fun observe(): Flow<List<String>> {
        return flow {
            val content = (0..4).map { MutableString() }

            while (currentCoroutineContext().isActive) {
                content.random().randomise()
                emit(content.map { it.content })
                delay(3000)
            }
        }
    }

    private fun MutableString.randomise() {
        content = randomUUID()
    }
}

private fun randomUUID() = UUID.randomUUID().toString().substring(0, 4)
