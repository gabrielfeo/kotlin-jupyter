package org.jetbrains.kotlinx.jupyter.messaging

import kotlinx.serialization.json.Json
import org.jetbrains.kotlinx.jupyter.api.Code
import org.jetbrains.kotlinx.jupyter.api.KernelLoggerFactory
import org.jetbrains.kotlinx.jupyter.api.KotlinKernelVersion.Companion.toMaybeUnspecifiedString
import org.jetbrains.kotlinx.jupyter.api.getLogger
import org.jetbrains.kotlinx.jupyter.api.libraries.RawMessage
import org.jetbrains.kotlinx.jupyter.commands.runCommand
import org.jetbrains.kotlinx.jupyter.common.looksLikeReplCommand
import org.jetbrains.kotlinx.jupyter.config.KernelStreams
import org.jetbrains.kotlinx.jupyter.config.currentKernelVersion
import org.jetbrains.kotlinx.jupyter.config.currentKotlinVersion
import org.jetbrains.kotlinx.jupyter.config.notebookLanguageInfo
import org.jetbrains.kotlinx.jupyter.exceptions.ReplException
import org.jetbrains.kotlinx.jupyter.execution.ExecutionResult
import org.jetbrains.kotlinx.jupyter.execution.JupyterExecutor
import org.jetbrains.kotlinx.jupyter.messaging.comms.CommManagerInternal
import org.jetbrains.kotlinx.jupyter.protocol.CapturingOutputStream
import org.jetbrains.kotlinx.jupyter.protocol.DisabledStdinInputStream
import org.jetbrains.kotlinx.jupyter.protocol.PROTOCOL_VERSION
import org.jetbrains.kotlinx.jupyter.protocol.StdinInputStream
import org.jetbrains.kotlinx.jupyter.repl.EvalRequestData
import org.jetbrains.kotlinx.jupyter.repl.ReplForJupyter
import org.jetbrains.kotlinx.jupyter.repl.result.EvalResultEx
import org.jetbrains.kotlinx.jupyter.util.EMPTY
import org.jetbrains.kotlinx.jupyter.util.systemErrStream
import org.jetbrains.kotlinx.jupyter.util.systemInStream
import org.jetbrains.kotlinx.jupyter.util.systemOutStream
import org.jetbrains.kotlinx.jupyter.util.withSubstitutedStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

@Suppress("MemberVisibilityCanBePrivate")
open class IdeCompatibleMessageRequestProcessor(
    rawIncomingMessage: RawMessage,
    messageFactoryProvider: MessageFactoryProvider,
    final override val socketManager: JupyterBaseSockets,
    protected val commManager: CommManagerInternal,
    protected val executor: JupyterExecutor,
    protected val executionCount: AtomicLong,
    loggerFactory: KernelLoggerFactory,
    protected val repl: ReplForJupyter,
) : AbstractMessageRequestProcessor(rawIncomingMessage),
    JupyterCommunicationFacility {
    private val logger = loggerFactory.getLogger(this::class)

    final override val messageFactory =
        run {
            messageFactoryProvider.update(rawIncomingMessage)
            messageFactoryProvider.provide()!!
        }

    protected val stdinIn: InputStream = StdinInputStream(socketManager.stdin, messageFactory)

    override fun processUnknownShellMessage(content: MessageContent) {
        socketManager.shell.sendMessage(
            messageFactory.makeReplyMessage(MessageType.NONE),
        )
    }

    override fun processUnknownControlMessage(content: MessageContent) {
    }

    override fun processUnknownStdinMessage(content: MessageContent) {
    }

    override fun processIsCompleteRequest(content: IsCompleteRequest) {
        socketManager.shell.sendMessage(
            messageFactory.makeReplyMessage(MessageType.IS_COMPLETE_REPLY, content = IsCompleteReply("complete")),
        )
    }

    override fun processListErrorsRequest(content: ListErrorsRequest) {
        executor.launchJob {
            repl.listErrors(content.code) { result ->
                sendWrapped(messageFactory.makeReplyMessage(MessageType.LIST_ERRORS_REPLY, content = result.message))
            }
        }
    }

    override fun processCompleteRequest(content: CompleteRequest) {
        executor.launchJob {
            repl.complete(content.code, content.cursorPos) { result ->
                sendWrapped(messageFactory.makeReplyMessage(MessageType.COMPLETE_REPLY, content = result.message))
            }
        }
    }

    override fun processCommMsg(content: CommMsg) {
        executor.runExecution("Execution of comm_msg request for ${content.commId}") {
            commManager.processCommMessage(incomingMessage, content)
        }
    }

    override fun processCommClose(content: CommClose) {
        executor.runExecution("Execution of comm_close request for ${content.commId}") {
            commManager.processCommClose(incomingMessage, content)
        }
    }

    override fun processCommOpen(content: CommOpen) {
        executor.runExecution("Execution of comm_open request for ${content.commId} of target ${content.targetName}") {
            commManager.processCommOpen(incomingMessage, content)
                ?: throw ReplException("Cannot open comm for ${content.commId} of target ${content.targetName}")
        }
    }

    override fun processCommInfoRequest(content: CommInfoRequest) {
        val comms = commManager.getComms(content.targetName)
        val replyMap = comms.associate { comm -> comm.id to Comm(comm.target) }
        sendWrapped(messageFactory.makeReplyMessage(MessageType.COMM_INFO_REPLY, content = CommInfoReply(replyMap)))
    }

    override fun processExecuteRequest(content: ExecuteRequest) {
        val count =
            executionCount.getAndUpdate {
                if (content.storeHistory) it + 1 else it
            }
        val startedTime = ISO8601DateNow

        doWrappedInBusyIdle {
            val code = content.code
            socketManager.iopub.sendMessage(
                messageFactory.makeReplyMessage(
                    MessageType.EXECUTE_INPUT,
                    content = ExecutionInputReply(code, count),
                ),
            )
            val response: JupyterResponse =
                if (looksLikeReplCommand(code)) {
                    runCommand(code, repl)
                } else {
                    evalWithIO(content.allowStdin) {
                        runExecution("Execution of code '${code.presentableForThreadName()}'") {
                            repl.evalEx(
                                EvalRequestData(
                                    code,
                                    count.toInt(),
                                    content.storeHistory,
                                    content.silent,
                                ),
                            )
                        }
                    }
                }

            sendResponse(response, count, startedTime)
        }
    }

    override fun processConnectRequest(content: ConnectRequest) {
        sendWrapped(
            messageFactory.makeReplyMessage(
                MessageType.CONNECT_REPLY,
                content =
                    ConnectReply(
                        Json.EMPTY,
                    ),
            ),
        )
    }

    override fun processHistoryRequest(content: HistoryRequest) {
        sendWrapped(
            messageFactory.makeReplyMessage(
                MessageType.HISTORY_REPLY,
                content = HistoryReply(listOf()), // not implemented
            ),
        )
    }

    override fun processKernelInfoRequest(content: KernelInfoRequest) {
        sendWrapped(
            messageFactory.makeReplyMessage(
                MessageType.KERNEL_INFO_REPLY,
                content =
                    KernelInfoReply(
                        PROTOCOL_VERSION,
                        "Kotlin",
                        currentKernelVersion.toMaybeUnspecifiedString(),
                        "Kotlin kernel v. ${currentKernelVersion.toMaybeUnspecifiedString()}, Kotlin v. $currentKotlinVersion",
                        notebookLanguageInfo,
                        listOf(),
                    ),
            ),
        )
    }

    override fun processShutdownRequest(content: ShutdownRequest) {
        repl.evalOnShutdown()
        socketManager.control.sendMessage(
            messageFactory.makeReplyMessage(MessageType.SHUTDOWN_REPLY, content = incomingMessage.content),
        )
        // exitProcess would kill the entire process that embedded the kernel
        // Instead the controlThread will be interrupted,
        // which will then interrupt the mainThread and make kernelServer return
        if (repl.isEmbedded) {
            logger.info("Interrupting controlThread to trigger kernel shutdown")
            throw InterruptedException()
        } else {
            exitProcess(0)
        }
    }

    override fun processInterruptRequest(content: InterruptRequest) {
        executor.interruptExecutions()
        socketManager.control.sendMessage(
            messageFactory.makeReplyMessage(MessageType.INTERRUPT_REPLY, content = incomingMessage.content),
        )
    }

    override fun processInputReply(content: InputReply) {
    }

    protected open fun runExecution(
        executionName: String,
        execution: () -> EvalResultEx,
    ): JupyterResponse {
        return when (
            val res =
                executor.runExecution(
                    executionName,
                    repl.currentClassLoader,
                    execution,
                )
        ) {
            is ExecutionResult.Success -> {
                try {
                    when (val replResult = res.result) {
                        is EvalResultEx.Success -> {
                            OkJupyterResponse(replResult.displayValue, replResult.metadata)
                        }
                        is EvalResultEx.Error -> {
                            replResult.error.toErrorJupyterResponse(replResult.metadata)
                        }
                        is EvalResultEx.RenderedError -> {
                            OkJupyterResponse(replResult.displayError, replResult.metadata)
                        }
                        is EvalResultEx.Interrupted -> {
                            AbortJupyterResponse(EXECUTION_INTERRUPTED_MESSAGE, replResult.metadata)
                        }
                    }
                } catch (e: Throwable) {
                    AbortJupyterResponse("error:  Unable to convert result to a string: $e")
                }
            }
            is ExecutionResult.Failure -> {
                res.throwable.toErrorJupyterResponse()
            }
            ExecutionResult.Interrupted -> {
                AbortJupyterResponse(EXECUTION_INTERRUPTED_MESSAGE)
            }
        }
    }

    private val replOutputConfig get() = repl.options.outputConfig

    private fun getCapturingStream(
        stream: PrintStream?,
        outType: JupyterOutType,
        captureOutput: Boolean,
    ): PrintStream {
        return CapturingOutputStream(
            stream,
            replOutputConfig,
            captureOutput,
        ) { text ->
            repl.notebook.currentCell?.appendStreamOutput(text)
            this.sendOut(outType, text)
        }.asPrintStream()
    }

    private fun OutputStream.asPrintStream() = PrintStream(this, false, "UTF-8")

    private fun <T> withForkedOut(body: () -> T): T {
        return withSubstitutedStream(
            ::systemOutStream,
            newStreamFactory = { out: PrintStream -> getCapturingStream(out, JupyterOutType.STDOUT, replOutputConfig.captureOutput) },
        ) { forkedOut ->
            KernelStreams.withOutStream(forkedOut, body)
        }
    }

    private fun <T> withForkedErr(body: () -> T): T {
        return withSubstitutedStream(
            ::systemErrStream,
            newStreamFactory = { err: PrintStream -> getCapturingStream(err, JupyterOutType.STDERR, false) },
        ) {
            val userErr = getCapturingStream(null, JupyterOutType.STDERR, true)
            userErr.use {
                KernelStreams.withErrStream(userErr, body)
            }
        }
    }

    private fun <T> withForkedIn(
        allowStdIn: Boolean,
        body: () -> T,
    ): T {
        return withSubstitutedStream(
            ::systemInStream,
            newStreamFactory = { if (allowStdIn) stdinIn else DisabledStdinInputStream },
        ) {
            body()
        }
    }

    protected open fun <T> evalWithIO(
        allowStdIn: Boolean,
        body: () -> T,
    ): T {
        repl.notebook.beginEvalSession()
        return withForkedOut {
            withForkedErr {
                withForkedIn(allowStdIn, body)
            }
        }
    }

    private fun Code.presentableForThreadName(): String {
        val newName = substringBefore('\n').take(20)
        return if (newName.length < length) {
            "$newName..."
        } else {
            this
        }
    }
}
