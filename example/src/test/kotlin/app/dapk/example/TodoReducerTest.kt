package app.dapk.example

import app.dapk.examples.Todo
import app.dapk.examples.TodoState
import app.dapk.examples.todoReducer
import app.dapk.examples.todoState
import org.junit.*
import test.testReducer

private val A_TODO = Todo(id = "1", content = "first todo")
private val A_TODO_2 = Todo(id = "2", content = "second todo")

class TodoReducerTest {

    private val runReducerTest = testReducer { todoReducer }

    @Test
    fun `initial state`() = runReducerTest {
        assertInitialState(TodoState(todo = emptyList(), completed = emptyList()))
    }

    @Test
    fun `add todo`() = runReducerTest {
        reduce { todoState.add(A_TODO.content) }

        assertStateUpdate { it.copy(todo = listOf(A_TODO)) }
    }

    @Test
    fun `mark completed`() = runReducerTest {
        setState(TodoState(todo = listOf(A_TODO), completed = emptyList()))

        reduce { todoState.markCompleted(A_TODO.id) }

        assertStateUpdate { it.copy(todo = emptyList(), completed = listOf(A_TODO)) }
    }

    @Test
    fun `delete todo`() = runReducerTest {
        setState(TodoState(todo = listOf(A_TODO), completed = listOf(A_TODO_2)))

        reduce { todoState.delete(A_TODO.id) }

        assertStateUpdate { it.copy(todo = emptyList()) }
    }

    @Test
    fun `delete completed`() = runReducerTest {
        setState(TodoState(todo = listOf(A_TODO), completed = listOf(A_TODO_2)))

        reduce { todoState.delete(A_TODO_2.id) }

        assertStateUpdate { it.copy(completed = emptyList()) }
    }

    @Test
    fun `edit todo`() = runReducerTest {
        setState(TodoState(todo = listOf(A_TODO), completed = listOf(A_TODO_2)))

        reduce { todoState.edit(A_TODO.id, "edited") }

        assertStateUpdate { it.copy(todo = listOf(A_TODO.copy(content = "edited"))) }
    }

    @Test
    fun `edit completed`() = runReducerTest {
        setState(TodoState(todo = listOf(A_TODO), completed = listOf(A_TODO_2)))

        reduce { todoState.edit(A_TODO_2.id, "edited") }

        assertStateUpdate { it.copy(completed = listOf(A_TODO_2.copy(content = "edited"))) }
    }
}