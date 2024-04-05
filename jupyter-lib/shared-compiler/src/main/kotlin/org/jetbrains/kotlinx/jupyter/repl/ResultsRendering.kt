package org.jetbrains.kotlinx.jupyter.repl

import org.jetbrains.kotlinx.jupyter.api.DisplayResult
import org.jetbrains.kotlinx.jupyter.api.InMemoryMimeTypedResult
import org.jetbrains.kotlinx.jupyter.api.MimeTypedResult
import org.jetbrains.kotlinx.jupyter.api.Renderable
import org.jetbrains.kotlinx.jupyter.api.libraries.ExecutionHost
import org.jetbrains.kotlinx.jupyter.api.textResult
import org.jetbrains.kotlinx.jupyter.repl.notebook.MutableNotebook

fun renderValue(
    notebook: MutableNotebook,
    executor: ExecutionHost,
    value: Any?,
    id: String?,
): DisplayResult? {
    val inMemoryReplResultsHolder = notebook.sharedReplContext!!.inMemoryReplResultsHolder
    val renderedValue = notebook.renderersProcessor.renderValue(executor, value)
    val rendered =
        if (renderedValue is InMemoryMimeTypedResult) {
            // Strip out in-memory values and put them into the InMemoryReplResultsHolder
            val inMemoryValue = renderedValue.inMemoryOutput.result
            val displayId =
                if (id != null) {
                    inMemoryReplResultsHolder.setReplResult(id, inMemoryValue)
                    id
                } else {
                    inMemoryReplResultsHolder.addReplResult(inMemoryValue)
                }
            MimeTypedResult(mimeData = renderedValue.fallbackResult + Pair(renderedValue.inMemoryOutput.mimeType, displayId))
        } else {
            renderedValue
        }
    return notebook.postRender(rendered)
}

fun MutableNotebook.postRender(value: Any?): DisplayResult? {
    fun renderAsText(obj: Any?): String = textRenderersProcessor.renderPreventingRecursion(obj)
    return when (value) {
        null -> textResult(renderAsText(null))
        is DisplayResult -> value
        is Renderable -> value.render(this)
        is Unit -> null
        else -> textResult(renderAsText(value))
    }
}
