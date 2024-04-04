package org.jetbrains.kotlinx.jupyter.repl.embedded

import kotlin.reflect.KClass

/**
 * Implementation that doesn't store anything. Should be used when the kernel isn't running
 * in embedded mode.
 */
object NoOpInMemoryReplResultsHolder : InMemoryReplResultsHolder {
    override fun <T : Any> getReplResult(id: String, type: KClass<T>): T? {
        /* Do nothing */
        return null
    }

    override fun addReplResult(result: Any?): String {
        /* Do nothing */
        return ""
    }

    override fun setReplResult(id: String, result: Any?) {
        /* Do nothing */
    }

    override fun removeReplResult(id: String): Boolean {
        return false
    }

    override val size: Int = 0
}
