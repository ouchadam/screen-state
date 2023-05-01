package app.dapk.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.*

class Database {

    private data class MutableString(var content: String = "0")

    fun observe(): Flow<List<String>> {
        return flow {
            val content = (0..3).map { MutableString() }
            repeat(4) {
                content[it].setValue(it + 1)
                emit(content.map { it.content })
                delay(100)
            }
        }
    }

    private fun MutableString.setValue(index: Int) {
        content = index.toString()
    }
}
