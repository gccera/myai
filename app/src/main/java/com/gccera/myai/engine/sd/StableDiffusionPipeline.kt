package com.gccera.myai.engine.sd

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.gccera.myai.data.catalog.ImageModelConfig
import com.gccera.myai.data.catalog.ModelSpec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.Random
import java.util.concurrent.CancellationException

/** Generated pixels as packed ARGB (convert with Bitmap.createBitmap(argb, w, h, ARGB_8888)). */
class RgbImage(val width: Int, val height: Int, val argb: IntArray)

data class ImageGenParams(
    val prompt: String,
    val negativePrompt: String,
    val steps: Int,
    val guidance: Float,
    val seed: Long,
    val width: Int,
    val height: Int,
)

/**
 * Text-to-image with Stable Diffusion exported to ONNX (diffusers layout:
 * `text_encoder/`, `unet/`, `vae_decoder/`, `tokenizer/`), run on the CPU by ONNX Runtime Mobile.
 *
 * - fp16 and fp32 exports both work: tensor types are read from each model's inputs.
 * - SD 1.x (768-d CLIP) and SD 2.x / Turbo (1024-d OpenCLIP) work: dims come from the model.
 * - [lowMemory]: each network is loaded only for its phase and closed afterwards, so the peak is
 *   roughly the UNet alone (~1.7 GB for fp16 SD 1.5) instead of all three networks.
 * - [cancel] terminates even an in-flight UNet call via ORT RunOptions.
 */
class StableDiffusionPipeline(
    spec: ModelSpec,
    private val dir: File,
    private val lowMemory: Boolean,
) : AutoCloseable {

    private val cfg: ImageModelConfig = spec.image ?: ImageModelConfig()
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val tokenizer = ClipTokenizer.fromDirectory(File(dir, "tokenizer"))

    private var textEncoder: OrtSession? = null
    private var unet: OrtSession? = null
    private var vae: OrtSession? = null

    @Volatile private var cancelled = false
    @Volatile private var runOptions: OrtSession.RunOptions? = null

    init {
        // Fail fast if files are missing, and pre-load everything on high-RAM phones.
        listOf("text_encoder/model.onnx", "unet/model.onnx", "vae_decoder/model.onnx").forEach {
            require(File(dir, it).exists()) { "Missing $it — re-download the model." }
        }
        if (!lowMemory) { textEncoder(); unet(); vae() }
    }

    private fun options() = OrtSession.SessionOptions().apply {
        // Use the big cores; more threads than ~4-6 usually slows phones down (little cores, heat).
        setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 6))
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
        // Low-memory mode: don't let ORT's arena keep (and grow) multi-GB activation buffers between runs.
        setMemoryPatternOptimization(!lowMemory)
        setCPUArenaAllocator(!lowMemory)
    }

    // Created from a file path so ONNX "external data" (e.g. unet/weights.pb) resolves next to it.
    private fun load(rel: String) = env.createSession(File(dir, rel).path, options())
    private fun textEncoder() = textEncoder ?: load("text_encoder/model.onnx").also { textEncoder = it }
    private fun unet() = unet ?: load("unet/model.onnx").also { unet = it }
    private fun vae() = vae ?: load("vae_decoder/model.onnx").also { vae = it }

    fun cancel() {
        cancelled = true
        runCatching { runOptions?.setTerminate(true) }
    }

    /**
     * Blocking; call from a background dispatcher.
     * @param onProgress (completedSteps, totalSteps) — UNet steps; text encoding and VAE decoding are
     *   reported as step 0 and step == total respectively.
     */
    fun generate(p: ImageGenParams, onProgress: (Int, Int) -> Unit): RgbImage {
        cancelled = false
        val ro = OrtSession.RunOptions().also { runOptions = it }
        try {
            require(p.width % 64 == 0 && p.height % 64 == 0) { "Width and height must be multiples of 64" }
            val useCfg = cfg.supportsNegativePrompt && p.guidance > 1f
            onProgress(0, p.steps)

            // 1) Text → embeddings ([uncond, cond] when using classifier-free guidance).
            val te = textEncoder()
            val cond = encodeText(te, p.prompt, ro)
            val uncond = if (useCfg) encodeText(te, p.negativePrompt, ro) else null
            if (lowMemory) { te.close(); textEncoder = null }
            checkCancelled()
            val embDim = cond.size / 77
            // High-RAM: one batch-2 UNet call per step ([uncond, cond]).
            // Low-RAM: two batch-1 calls — same result, about half the peak activation memory
            // (at 512 px a batch-2 pass needs >6 GB on CPU).
            val batched = useCfg && !lowMemory

            // 2) Denoising loop.
            val scheduler = EulerAncestralScheduler(p.steps, cfg.timestepSpacing, cfg.stepsOffset, cfg.predictionType)
            val rng = Random(p.seed)
            val lh = p.height / 8; val lw = p.width / 8
            val latentSize = 4 * lh * lw
            val latents = FloatArray(latentSize) { (rng.nextGaussian() * scheduler.initNoiseSigma).toFloat() }
            val un = unet()
            val inNames = un.inputNames.toList()
            val sampleName = inNames.first { it.contains("sample") && !it.contains("hidden") }
            val timeName = inNames.first { it.contains("time") }
            val hiddenName = inNames.first { it.contains("hidden") }
            val timeInfo = un.inputInfo.getValue(timeName).info as TensorInfo

            /** One UNet evaluation for `batch` copies of the (scaled) latent. */
            fun runUnet(scaledLatent: FloatArray, batch: Int, t: Float, hidden: OnnxTensor): FloatArray {
                val timeLen = if (timeInfo.shape.isEmpty()) 0 else if (timeInfo.shape[0] == 1L) 1 else batch
                val timeShape = if (timeLen == 0) longArrayOf() else longArrayOf(timeLen.toLong())
                val sampleData = if (batch == 1) scaledLatent else FloatArray(latentSize * batch) { scaledLatent[it % latentSize] }
                return tensor(un, sampleName, sampleData, longArrayOf(batch.toLong(), 4, lh.toLong(), lw.toLong())).use { sample ->
                    tensor(un, timeName, FloatArray(maxOf(1, timeLen)) { t }, timeShape).use { time ->
                        un.run(mapOf(sampleName to sample, timeName to time, hiddenName to hidden), ro).use { out ->
                            readFloats(out[0] as OnnxTensor)
                        }
                    }
                }
            }

            val embShape1 = longArrayOf(1, 77, embDim.toLong())
            val hiddenTensors: List<OnnxTensor> = when {
                batched -> listOf(tensor(un, hiddenName, uncond!! + cond, longArrayOf(2, 77, embDim.toLong())))
                useCfg -> listOf(tensor(un, hiddenName, uncond!!, embShape1), tensor(un, hiddenName, cond, embShape1))
                else -> listOf(tensor(un, hiddenName, cond, embShape1))
            }
            try {
                for (i in 0 until p.steps) {
                    checkCancelled()
                    val scale = scheduler.inputScale(i)
                    val scaled = FloatArray(latentSize) { latents[it] * scale }
                    val t = scheduler.timesteps[i].toFloat()
                    val guided = when {
                        batched -> {
                            val out = runUnet(scaled, 2, t, hiddenTensors[0])
                            FloatArray(latentSize) { j -> val u = out[j]; u + p.guidance * (out[latentSize + j] - u) }
                        }
                        useCfg -> {
                            val u = runUnet(scaled, 1, t, hiddenTensors[0])
                            checkCancelled()
                            val c = runUnet(scaled, 1, t, hiddenTensors[1])
                            FloatArray(latentSize) { j -> u[j] + p.guidance * (c[j] - u[j]) }
                        }
                        else -> runUnet(scaled, 1, t, hiddenTensors[0])
                    }
                    scheduler.step(guided, i, latents) { rng.nextGaussian().toFloat() }
                    onProgress(i + 1, p.steps)
                }
            } finally {
                hiddenTensors.forEach { it.close() }
            }
            if (lowMemory) { un.close(); unet = null }
            checkCancelled()

            // 3) Latents → pixels.
            val v = vae()
            val inv = 1f / cfg.vaeScalingFactor
            val vaeIn = v.inputNames.first()
            val pixels = tensor(v, vaeIn, FloatArray(latentSize) { latents[it] * inv }, longArrayOf(1, 4, lh.toLong(), lw.toLong())).use { lat ->
                v.run(mapOf(vaeIn to lat), ro).use { out -> readFloats(out[0] as OnnxTensor) }
            }
            if (lowMemory) { v.close(); vae = null }
            return toImage(pixels, p.width, p.height)
        } finally {
            runOptions = null
            ro.close()
        }
    }

    private fun encodeText(session: OrtSession, text: String, ro: OrtSession.RunOptions): FloatArray {
        val ids = tokenizer.encodeForModel(text)
        val name = session.inputNames.first()
        val type = (session.inputInfo.getValue(name).info as TensorInfo).type
        val shape = longArrayOf(1, ids.size.toLong())
        val input = if (type == OnnxJavaType.INT64) {
            OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), shape)
        } else OnnxTensor.createTensor(env, IntBuffer.wrap(ids), shape)
        return input.use {
            session.run(mapOf(name to it), ro).use { out ->
                val hidden = out.get("last_hidden_state").orElse(out[0])
                readFloats(hidden as OnnxTensor)
            }
        }
    }

    /** Creates a float tensor in the element type the model input expects (fp16 or fp32). */
    private fun tensor(session: OrtSession, input: String, data: FloatArray, shape: LongArray): OnnxTensor {
        val type = (session.inputInfo.getValue(input).info as TensorInfo).type
        return if (type == OnnxJavaType.FLOAT16) {
            val buf = ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder())
            val sb = buf.asShortBuffer()
            for (f in data) sb.put(Fp16.fromFloat(f))
            OnnxTensor.createTensor(env, buf, shape, OnnxJavaType.FLOAT16)
        } else {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
        }
    }

    private fun readFloats(t: OnnxTensor): FloatArray = when (t.info.type) {
        OnnxJavaType.FLOAT16 -> {
            val sb = t.byteBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            FloatArray(sb.remaining()) { Fp16.toFloat(sb.get(it)) }
        }
        else -> {
            val fb = t.floatBuffer
            FloatArray(fb.remaining()).also { fb.get(it) }
        }
    }

    private fun toImage(chw: FloatArray, w: Int, h: Int): RgbImage {
        val plane = w * h
        val px = IntArray(plane) { i ->
            fun ch(c: Int) = ((chw[c * plane + i] / 2f + 0.5f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            (0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }
        return RgbImage(w, h, px)
    }

    private fun checkCancelled() { if (cancelled) throw CancellationException("Cancelled") }

    override fun close() {
        cancel()
        listOf(textEncoder, unet, vae).forEach { runCatching { it?.close() } }
        textEncoder = null; unet = null; vae = null
    }
}
