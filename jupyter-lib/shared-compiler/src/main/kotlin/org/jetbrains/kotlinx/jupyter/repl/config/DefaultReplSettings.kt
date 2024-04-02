package org.jetbrains.kotlinx.jupyter.repl.config

import org.jetbrains.kotlinx.jupyter.api.KernelLoggerFactory
import org.jetbrains.kotlinx.jupyter.config.DefaultKernelLoggerFactory
import org.jetbrains.kotlinx.jupyter.config.defaultRuntimeProperties
import org.jetbrains.kotlinx.jupyter.repl.ReplConfig
import org.jetbrains.kotlinx.jupyter.repl.ReplRuntimeProperties
import org.jetbrains.kotlinx.jupyter.startup.KernelConfig

data class DefaultReplSettings(
    val kernelConfig: KernelConfig,
    val replConfig: ReplConfig,
    val loggerFactory: KernelLoggerFactory = DefaultKernelLoggerFactory,
    val runtimeProperties: ReplRuntimeProperties = defaultRuntimeProperties,
    val scriptReceivers: List<Any> = emptyList(),
)
