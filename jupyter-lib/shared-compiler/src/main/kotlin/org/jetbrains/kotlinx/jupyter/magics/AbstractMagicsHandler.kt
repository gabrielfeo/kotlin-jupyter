package org.jetbrains.kotlinx.jupyter.magics

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.types.choice
import org.jetbrains.kotlinx.jupyter.api.libraries.LibraryDefinitionProducer
import org.jetbrains.kotlinx.jupyter.common.ReplLineMagic

abstract class AbstractMagicsHandler : MagicsHandler {
    protected var arg: String? = null
    protected var tryIgnoreErrors: Boolean = false
    protected var parseOnly: Boolean = false

    protected fun argumentsList() = arg?.trim()?.takeIf { it.isNotEmpty() }?.split(" ") ?: emptyList()
    protected fun handleSingleOptionalFlag(action: (Boolean?) -> Unit) {
        object : CliktCommand() {
            val arg by nullableFlag()
            override fun run() {
                action(arg)
            }
        }.parse(argumentsList())
    }

    protected val newLibraries: MutableList<LibraryDefinitionProducer> = mutableListOf()

    private val callbackMap: Map<ReplLineMagic, () -> Unit> = mapOf(
        ReplLineMagic.USE to ::handleUse,
        ReplLineMagic.TRACK_CLASSPATH to ::handleTrackClasspath,
        ReplLineMagic.TRACK_EXECUTION to ::handleTrackExecution,
        ReplLineMagic.DUMP_CLASSES_FOR_SPARK to ::handleDumpClassesForSpark,
        ReplLineMagic.USE_LATEST_DESCRIPTORS to ::handleUseLatestDescriptors,
        ReplLineMagic.OUTPUT to ::handleOutput,
        ReplLineMagic.LOG_LEVEL to ::handleLogLevel,
        ReplLineMagic.LOG_HANDLER to ::handleLogHandler,
    )

    override fun handle(magic: ReplLineMagic, arg: String?, tryIgnoreErrors: Boolean, parseOnly: Boolean) {
        val callback = callbackMap[magic] ?: throw UnhandledMagicException(magic, this)

        this.arg = arg
        this.tryIgnoreErrors = tryIgnoreErrors
        this.parseOnly = parseOnly

        callback()
    }

    override fun getLibraries(): List<LibraryDefinitionProducer> {
        val librariesCopy = newLibraries.toList()
        newLibraries.clear()
        return librariesCopy
    }

    open fun handleUse() {}
    open fun handleTrackClasspath() {}
    open fun handleTrackExecution() {}
    open fun handleDumpClassesForSpark() {}
    open fun handleUseLatestDescriptors() {}
    open fun handleOutput() {}
    open fun handleLogLevel() {}
    open fun handleLogHandler() {}

    companion object {
        fun CliktCommand.nullableFlag() = argument().choice(mapOf("on" to true, "off" to false)).optional()
    }
}
