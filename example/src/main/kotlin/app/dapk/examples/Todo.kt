package app.dapk.examples

import app.dapk.examples.TodoState.*
import app.dapk.annotation.State
import app.dapk.annotation.StateActions
import app.dapk.state.createReducer

@State(actions = [TodoActions::class])
data class TodoState(
    val todo: List<Todo>,
    val completed: List<Todo>,
) {
    @StateActions interface TodoActions {
        fun add(content: String)
        fun markCompleted(id: String)
        fun delete(id: String)
        fun edit(id: String, content: String)
    }
}

data class Todo(val id: String, val content: String)

val todoReducer = createReducer(initialState = TodoState(todo = emptyList(), completed = emptyList())) {
    add { todoState, payload ->
        val newTodo = Todo(id = (todoState.todo.size + 1).toString(), payload.content)
        update { todo(todoState.todo + newTodo) }
    }

    markCompleted { todoState, payload ->
        update {
            todo(todoState.todo.filterNot { it.id == payload.id })
            completed(todoState.completed + todoState.todo.filter { it.id == payload.id })
        }
    }

    delete { todoState, payload ->
        update {
            todo(todoState.todo.filterNot { it.id == payload.id })
            completed(todoState.completed.filterNot { it.id == payload.id })
        }
    }

    edit { todoState, edit ->
        update {
            todo(todoState.todo.replace(edit.id, edit.content))
            completed(todoState.completed.replace(edit.id, edit.content))
        }
    }
}

private fun List<Todo>.replace(id: String, content: String): List<Todo> {
    return this.map { if (it.id == id) it.copy(content = content) else it }
}
