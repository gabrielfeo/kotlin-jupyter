package org.jetbrains.kotlinx.jupyter

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.kotlinx.jupyter.api.KernelLoggerFactory
import org.jetbrains.kotlinx.jupyter.api.arrayRenderer
import org.jetbrains.kotlinx.jupyter.api.bufferedImageRenderer
import org.jetbrains.kotlinx.jupyter.api.getLogger
 import org.jetbrains.kotlinx.jupyter.api.swingJFrameInMemoryRenderer
import org.jetbrains.kotlinx.jupyter.api.swingJComponentInMemoryRenderer
import org.jetbrains.kotlinx.jupyter.codegen.ResultsRenderersProcessor
import org.jetbrains.kotlinx.jupyter.common.LibraryDescriptorsManager
import org.jetbrains.kotlinx.jupyter.compiler.util.CodeInterval
import org.jetbrains.kotlinx.jupyter.compiler.util.SourceCodeImpl
import org.jetbrains.kotlinx.jupyter.config.catchAll
import org.jetbrains.kotlinx.jupyter.libraries.*
import org.jetbrains.kotlinx.jupyter.util.createCachedFun
import java.io.Closeable
import java.io.File
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.jvm.util.toSourceCodePosition

fun List<String>.joinToLines() = joinToString("\n")

fun generateDiagnostic(
    fromLine: Int,
    fromCol: Int,
    toLine: Int,
    toCol: Int,
    message: String,
    severity: String,
) = ScriptDiagnostic(
    ScriptDiagnostic.unspecifiedError,
    message,
    ScriptDiagnostic.Severity.valueOf(severity),
    null,
    SourceCode.Location(SourceCode.Position(fromLine, fromCol), SourceCode.Position(toLine, toCol)),
)

fun generateDiagnosticFromAbsolute(
    code: String,
    from: Int,
    to: Int,
    message: String,
    severity: ScriptDiagnostic.Severity,
): ScriptDiagnostic {
    val snippet = SourceCodeImpl(0, code)
    return ScriptDiagnostic(
        ScriptDiagnostic.unspecifiedError,
        message,
        severity,
        null,
        SourceCode.Location(from.toSourceCodePosition(snippet), to.toSourceCodePosition(snippet)),
    )
}

fun CodeInterval.diagnostic(
    code: String,
    message: String,
    severity: ScriptDiagnostic.Severity = ScriptDiagnostic.Severity.ERROR,
): ScriptDiagnostic {
    return generateDiagnosticFromAbsolute(code, from, to, message, severity)
}

fun generateDiagnosticFromAbsolute(
    code: String,
    from: Int,
    to: Int,
    message: String,
    severity: String,
): ScriptDiagnostic {
    return generateDiagnosticFromAbsolute(code, from, to, message, ScriptDiagnostic.Severity.valueOf(severity))
}

fun withPath(
    path: String?,
    diagnostics: List<ScriptDiagnostic>,
): List<ScriptDiagnostic> = diagnostics.map { it.copy(sourcePath = path) }

fun ResultsRenderersProcessor.registerDefaultRenderers() {
    register(bufferedImageRenderer)
    register(arrayRenderer)
    register(swingJFrameInMemoryRenderer)
    register(swingJComponentInMemoryRenderer)
}

/**
 * Stores info about where a variable Y was declared and info about what are they at the address X.
 * K: key, stands for a way of addressing variables, e.g. address.
 * V: value, from Variable, choose any suitable type for your variable reference.
 * Default: T=Int, V=String
 */
class VariablesUsagesPerCellWatcher<K : Any, V : Any> {
    val cellVariables = mutableMapOf<K, MutableSet<V>>()

    /**
     * Tells in which cell a variable was declared
     */
    private val variablesDeclarationInfo: MutableMap<V, K> = mutableMapOf()

    fun addDeclaration(
        address: K,
        variableRef: V,
    ) {
        ensureStorageCreation(address)

        // redeclaration of any type
        if (variablesDeclarationInfo.containsKey(variableRef)) {
            val oldCellId = variablesDeclarationInfo[variableRef]
            if (oldCellId != address) {
                cellVariables[oldCellId]?.remove(variableRef)
            }
        }
        variablesDeclarationInfo[variableRef] = address
        cellVariables[address]?.add(variableRef)
    }

    fun addUsage(
        address: K,
        variableRef: V,
    ) = cellVariables[address]?.add(variableRef)

    fun removeOldUsages(newAddress: K) {
        // remove known modifying usages in this cell
        cellVariables[newAddress]?.removeIf {
            variablesDeclarationInfo[it] != newAddress
        }
    }

    fun ensureStorageCreation(address: K) = cellVariables.putIfAbsent(address, mutableSetOf())
}

class HomeDirLibraryDescriptorsProvider(
    loggerFactory: KernelLoggerFactory,
    private val homeDir: File?,
    private val libraryDescriptorsManager: LibraryDescriptorsManager,
) : ResourceLibraryDescriptorsProvider(loggerFactory) {
    private val logger = loggerFactory.getLogger(this::class)

    override fun getDescriptors(): Map<String, LibraryDescriptor> {
        return if (homeDir == null) {
            super.getDescriptors()
        } else {
            libraryDescriptors(homeDir)
        }
    }

    override fun getDescriptorGlobalOptions(): LibraryDescriptorGlobalOptions {
        return if (homeDir == null) {
            super.getDescriptorGlobalOptions()
        } else {
            descriptorOptions(homeDir)
        }
    }

    val descriptorOptions =
        createCachedFun(calculateKey = { file: File -> file.absolutePath }) { homeDir: File ->
            val globalOptions =
                libraryDescriptorsManager
                    .homeLibrariesDir(homeDir)
                    .resolve(libraryDescriptorsManager.optionsFileName())
            if (globalOptions.exists()) {
                parseLibraryDescriptorGlobalOptions(globalOptions.readText())
            } else {
                DefaultLibraryDescriptorGlobalOptions
            }
        }

    val libraryDescriptors =
        createCachedFun(calculateKey = { file: File -> file.absolutePath }) { homeDir: File ->
            val libraryFiles =
                libraryDescriptorsManager
                    .homeLibrariesDir(homeDir)
                    .listFiles(libraryDescriptorsManager::isLibraryDescriptor)
                    .orEmpty()
            libraryFiles.toList().mapNotNull { file ->
                val libraryName = file.nameWithoutExtension
                logger.info("Parsing descriptor for library '$libraryName'")
                logger.catchAll(msg = "Parsing descriptor for library '$libraryName' failed") {
                    libraryName to parseLibraryDescriptor(file.readText())
                }
            }.toMap()
        }
}

class LibraryDescriptorsByResolutionProvider(
    private val delegate: LibraryDescriptorsProvider,
    private val libraryResolver: LibraryResolver,
    private val libraryReferenceParser: LibraryReferenceParser,
) : LibraryDescriptorsProvider by delegate {
    override fun getDescriptorForVersionsCompletion(fullName: String): LibraryDescriptor? {
        return super.getDescriptorForVersionsCompletion(fullName)
            ?: run {
                val reference = libraryReferenceParser.parseLibraryReference(fullName)
                val descriptorText = libraryResolver.resolve(reference, emptyList())?.originalDescriptorText ?: return@run null
                parseLibraryDescriptor(descriptorText)
            }
    }
}

fun JsonElement.resolvePath(path: List<String>): JsonElement? {
    var cur: JsonElement? = this
    for (fragment in path) {
        val sub = cur
        if (sub is JsonObject) {
            cur = sub[fragment]
        } else {
            return null
        }
    }

    return cur
}

data class MutablePair<T1, T2>(var first: T1, var second: T2)

fun Any.closeIfPossible() {
    if (this is Closeable) close()
}
