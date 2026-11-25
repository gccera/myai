package com.gccera.myai.engine

import com.gccera.myai.data.catalog.ModelSpec
import com.gccera.myai.data.models.ModelRepository

/** Thrown when a feature needs a helper model that isn't downloaded yet. */
class MissingModelException(val modelName: String) :
    Exception("Download \"$modelName\" first: Settings → Models → Tools.")

fun ModelRepository.requireTool(kind: String): ModelSpec =
    toolModel(kind) ?: throw MissingModelException(toolSpec(kind)?.name ?: kind)
