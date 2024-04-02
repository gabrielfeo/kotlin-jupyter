package org.jetbrains.kotlinx.jupyter.libraries

import org.jetbrains.kotlinx.jupyter.api.KernelLoggerFactory
import org.jetbrains.kotlinx.jupyter.api.libraries.LibraryResource
import org.jetbrains.kotlinx.jupyter.api.libraries.ResourceType
import org.jetbrains.kotlinx.jupyter.common.HttpClient
import java.util.EnumMap

class LibraryResourcesProcessorImpl(
    loggerFactory: KernelLoggerFactory,
    httpClient: HttpClient,
) : LibraryResourcesProcessor {
    private val processorMap = EnumMap<ResourceType, LibraryResourcesProcessor>(ResourceType::class.java)

    init {
        processorMap[ResourceType.JS] = JsLibraryResourcesProcessor(loggerFactory, httpClient)
        processorMap[ResourceType.CSS] = CssLibraryResourcesProcessor(httpClient)
    }

    override fun wrapLibrary(
        resource: LibraryResource,
        classLoader: ClassLoader,
    ): String {
        return processorMap.getValue(resource.type).wrapLibrary(resource, classLoader)
    }
}
