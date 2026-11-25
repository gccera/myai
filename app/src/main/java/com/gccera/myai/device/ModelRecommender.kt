package com.gccera.myai.device

import com.gccera.myai.data.catalog.ModelSpec
import com.gccera.myai.data.catalog.ModelType

enum class Fit { RECOMMENDED, MAY_BE_SLOW, TOO_LARGE }

data class Recommendation(
    val chat: ModelSpec?,
    val image: ModelSpec?,
    val notes: List<String>,
)

/**
 * Picks models by the device's RAM class. Rules:
 *  - TOO_LARGE   if RAM class < model.minRamGb (likely to be killed by the OS),
 *  - MAY_BE_SLOW if below model.recommendedRamGb,
 *  - RECOMMENDED otherwise.
 * The recommended chat model is the most capable one that is RECOMMENDED (largest download).
 */
object ModelRecommender {

    fun fit(spec: ModelSpec, device: DeviceInfo): Fit {
        val ram = device.ramClassGb
        return when {
            ram < spec.minRamGb -> Fit.TOO_LARGE
            ram < spec.recommendedRamGb -> Fit.MAY_BE_SLOW
            else -> Fit.RECOMMENDED
        }
    }

    fun recommend(models: List<ModelSpec>, device: DeviceInfo): Recommendation {
        val notes = mutableListOf<String>()
        fun best(type: ModelType): ModelSpec? {
            val ofType = models.filter { it.type == type && !it.custom }
            return ofType.filter { fit(it, device) == Fit.RECOMMENDED }.maxByOrNull { it.totalBytes }
                ?: ofType.filter { fit(it, device) == Fit.MAY_BE_SLOW }.minByOrNull { it.totalBytes }
        }
        val chat = best(ModelType.CHAT)
        val image = best(ModelType.IMAGE)

        if (!device.supportsChatEngine) notes += "This phone's CPU (32-bit only) isn't supported by the on-device chat engine."
        if (device.isLowRamDevice) notes += "Android marks this as a low-RAM device; only the smallest models are suitable."
        if (image == null) notes += "Image generation needs at least 6 GB of RAM; it is not recommended on this phone."
        else if (fit(image, device) == Fit.MAY_BE_SLOW) notes += "Image generation will work but may be slow or fail if other apps are open."
        val gb = 1024L * 1024 * 1024
        if (device.freeStorageBytes < 2 * gb) notes += "Less than 2 GB of free storage: free up space before downloading models."
        return Recommendation(chat, image, notes)
    }
}
