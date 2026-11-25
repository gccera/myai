package com.gccera.myai.data.catalog

import kotlinx.serialization.Serializable

@Serializable
enum class ModelType {
    CHAT,
    /** Coding LLMs (Code tab). */
    CODE,
    IMAGE,
    /** Small helper models: speech-to-text, embeddings, upscaling, background removal. */
    TOOL,
}

/** What a TOOL model does. */
object ToolKind {
    const val SPEECH = "speech"
    const val EMBEDDING = "embedding"
    const val UPSCALE_2X = "upscale2x"
    const val UPSCALE_4X = "upscale4x"
    const val BACKGROUND = "background"
}

@Serializable
data class ModelLicense(
    /** Key of a bundled licence text in assets/licenses/<id>.txt (if available). */
    val id: String,
    val name: String,
    val url: String,
    val commercialUse: Boolean = true,
    /** Notice that the licence requires us to display (e.g. "Built with Llama"). */
    val attribution: String? = null,
)

@Serializable
data class ModelFile(
    /** Relative path inside the model folder; sub-folders are preserved (ONNX external data needs this). */
    val path: String,
    val url: String,
    val sizeBytes: Long,
    /** Lower-case hex SHA-256. Null = not verified (only allowed for user-added custom models). */
    val sha256: String? = null,
)

/** Settings for Stable-Diffusion-style ONNX pipelines. */
@Serializable
data class ImageModelConfig(
    /** "SD1" (CLIP ViT-L, 768-d) or "SD2" (OpenCLIP ViT-H, 1024-d; e.g. SD 2.1 / SD-Turbo). Informational. */
    val family: String = "SD1",
    val defaultSize: Int = 512,
    val defaultSteps: Int = 20,
    val defaultGuidance: Float = 7.5f,
    /** Turbo/distilled models run without classifier-free guidance, so negative prompts do nothing. */
    val supportsNegativePrompt: Boolean = true,
    /** "leading" | "trailing" | "linspace" (diffusers' timestep_spacing). */
    val timestepSpacing: String = "leading",
    val stepsOffset: Int = 1,
    /** "epsilon" or "v_prediction". */
    val predictionType: String = "epsilon",
    val vaeScalingFactor: Float = 0.18215f,
    val maxSteps: Int = 50,
)

@Serializable
data class ModelSpec(
    val id: String,
    val type: ModelType,
    val name: String,
    val description: String = "",
    val params: String = "",
    val minRamGb: Int = 0,
    val recommendedRamGb: Int = 0,
    /** KV-cache / context size passed to the LLM engine. */
    val contextTokens: Int = 2048,
    /** Gated on Hugging Face: user must accept the licence on the website and add a token. */
    val requiresHfToken: Boolean = false,
    val license: ModelLicense,
    val files: List<ModelFile>,
    val image: ImageModelConfig? = null,
    val custom: Boolean = false,
    /** LLM accepts images (LiteRT-LM vision backend), e.g. Gemma 4 E2B. */
    val vision: Boolean = false,
    /** For TOOL models: one of [ToolKind]. */
    val tool: String? = null,
    /**
     * Built-in chat template to use instead of the one inside the model file ("chatml"). Needed for
     * conversions whose embedded template can't handle LiteRT-LM's structured message content.
     */
    val chatTemplate: String? = null,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /** The file the chat engine opens (LiteRT-LM models are a single .litertlm file). */
    val primaryFile: ModelFile get() = files.first()
}

@Serializable
data class CatalogFile(val version: Int = 1, val models: List<ModelSpec>)
