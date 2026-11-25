package com.gccera.myai.data.catalog

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The configurable model list = bundled `assets/model_catalog.json` + models the user added
 * in-app (stored in `files/custom_models.json`). Nothing is fetched from the network.
 */
@Singleton
class ModelCatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val customFile = File(context.filesDir, "custom_models.json")

    private val bundled: List<ModelSpec> by lazy {
        context.assets.open("model_catalog.json").bufferedReader().use {
            json.decodeFromString(CatalogFile.serializer(), it.readText()).models
        }
    }

    private val _models = MutableStateFlow(bundled + readCustom())
    val models: StateFlow<List<ModelSpec>> = _models.asStateFlow()

    fun find(id: String?): ModelSpec? = id?.let { i -> _models.value.firstOrNull { it.id == i } }

    suspend fun addCustom(spec: ModelSpec) = withContext(Dispatchers.IO) {
        require(_models.value.none { it.id == spec.id }) { "A model with id '${spec.id}' already exists" }
        val custom = readCustom() + spec.copy(custom = true)
        writeCustom(custom)
        _models.update { bundled + custom }
    }

    suspend fun removeCustom(id: String) = withContext(Dispatchers.IO) {
        val custom = readCustom().filterNot { it.id == id }
        writeCustom(custom)
        _models.update { bundled + custom }
    }

    private fun readCustom(): List<ModelSpec> = runCatching {
        if (!customFile.exists()) emptyList()
        else json.decodeFromString(CatalogFile.serializer(), customFile.readText()).models
    }.getOrDefault(emptyList())

    private fun writeCustom(list: List<ModelSpec>) {
        customFile.writeText(json.encodeToString(CatalogFile.serializer(), CatalogFile(models = list)))
    }

    suspend fun clearCustom() = withContext(Dispatchers.IO) {
        customFile.delete()
        _models.update { bundled }
    }
}
