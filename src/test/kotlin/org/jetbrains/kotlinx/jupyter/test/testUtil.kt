package org.jetbrains.kotlinx.jupyter.test

import jupyter.kotlin.DependsOn
import jupyter.kotlin.JavaRuntime
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.kotlinx.jupyter.DisplayHandler
import org.jetbrains.kotlinx.jupyter.ReplRuntimeProperties
import org.jetbrains.kotlinx.jupyter.api.CodeCell
import org.jetbrains.kotlinx.jupyter.api.DisplayContainer
import org.jetbrains.kotlinx.jupyter.api.JREInfoProvider
import org.jetbrains.kotlinx.jupyter.api.KotlinKernelVersion
import org.jetbrains.kotlinx.jupyter.api.Notebook
import org.jetbrains.kotlinx.jupyter.api.ResultsAccessor
import org.jetbrains.kotlinx.jupyter.api.libraries.JupyterIntegration
import org.jetbrains.kotlinx.jupyter.api.libraries.LibraryDefinition
import org.jetbrains.kotlinx.jupyter.config.defaultRepositories
import org.jetbrains.kotlinx.jupyter.defaultRuntimeProperties
import org.jetbrains.kotlinx.jupyter.dependencies.ResolverConfig
import org.jetbrains.kotlinx.jupyter.libraries.LibrariesDir
import org.jetbrains.kotlinx.jupyter.libraries.LibraryDescriptorExt
import org.jetbrains.kotlinx.jupyter.libraries.LibraryDescriptorResolver
import org.jetbrains.kotlinx.jupyter.libraries.LibraryReference
import org.jetbrains.kotlinx.jupyter.libraries.LibraryResolutionInfo
import org.jetbrains.kotlinx.jupyter.libraries.LibraryResolver
import org.jetbrains.kotlinx.jupyter.libraries.parseLibraryDescriptors
import org.jetbrains.kotlinx.jupyter.log
import java.io.File
import kotlin.script.experimental.jvm.util.scriptCompilationClasspathFromContext

const val standardResolverBranch = "master"

val standardResolverRuntimeProperties = object : ReplRuntimeProperties by defaultRuntimeProperties {
    override val currentBranch: String
        get() = standardResolverBranch
}

val classpath = scriptCompilationClasspathFromContext(
    "lib",
    "api",
    "shared-compiler",
    "kotlin-stdlib",
    "kotlin-reflect",
    "kotlin-script-runtime",
    classLoader = DependsOn::class.java.classLoader
)

val testResolverConfig: ResolverConfig
    get() = ResolverConfig(
        defaultRepositories,
        getResolverFromNamesMap(parseLibraryDescriptors(readLibraries()))
    )

fun Collection<Pair<String, String>>.toLibraries(): LibraryResolver {
    val libJsons = map { it.first to Json.decodeFromString<JsonObject>(it.second) }.toMap()
    return getResolverFromNamesMap(parseLibraryDescriptors(libJsons))
}

@JvmName("toLibrariesStringLibraryDefinition")
fun Collection<Pair<String, LibraryDefinition>>.toLibraries() = getResolverFromNamesMap(toMap())

fun getResolverFromNamesMap(map: Map<String, LibraryDefinition>): LibraryResolver {
    return InMemoryLibraryResolver(null, map.mapKeys { entry -> LibraryReference(LibraryResolutionInfo.Default(), entry.key) })
}

fun readLibraries(basePath: String? = null): Map<String, JsonObject> {
    return File(basePath, LibrariesDir)
        .listFiles()?.filter { it.extension == LibraryDescriptorExt }
        ?.map {
            log.info("Loading '${it.nameWithoutExtension}' descriptor from '${it.canonicalPath}'")
            it.nameWithoutExtension to Json.decodeFromString<JsonObject>(it.readText())
        }
        .orEmpty()
        .toMap()
}

class InMemoryLibraryResolver(parent: LibraryResolver?, initialCache: Map<LibraryReference, LibraryDefinition>? = null) : LibraryDescriptorResolver(parent) {
    private val cache = hashMapOf<LibraryReference, LibraryDefinition>()

    init {
        initialCache?.forEach { (key, value) ->
            cache[key] = value
        }
    }

    override fun shouldResolve(reference: LibraryReference): Boolean {
        return reference.shouldBeCachedInMemory
    }

    override fun tryResolve(reference: LibraryReference): LibraryDefinition? {
        return cache[reference]
    }

    override fun save(reference: LibraryReference, definition: LibraryDefinition) {
        cache[reference] = definition
    }
}

class TestDisplayHandler(val list: MutableList<Any> = mutableListOf()) : DisplayHandler {
    override fun handleDisplay(value: Any) {
        list.add(value)
    }

    override fun handleUpdate(value: Any, id: String?) {
        // TODO: Implement correct updating
    }
}

class NotebookMock : Notebook<CodeCell> {
    override val cells: Map<Int, CodeCell>
        get() = emptyMap()
    override val results: ResultsAccessor
        get() = ResultsAccessor { cells[it] }
    override val displays: DisplayContainer
        get() = error("Not supposed to be called")

    override fun history(before: Int): CodeCell? {
        error("Not supposed to be called")
    }

    override val kernelVersion: KotlinKernelVersion
        get() = defaultRuntimeProperties.version!!
    override val jreInfo: JREInfoProvider
        get() = JavaRuntime
}

fun library(builder: JupyterIntegration.Builder.(Notebook<*>?) -> Unit): LibraryDefinition {
    val o = object : JupyterIntegration(builder) {}
    return o.getDefinitions(null).single()
}