package com.ton.catalarm

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector

class CatDetector(context: Context) {
    private val detector: ObjectDetector

    init {
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setMaxResults(10)
            .setScoreThreshold(MIN_SCORE)
            .build()

        detector = ObjectDetector.createFromFileAndOptions(
            context,
            MODEL_ASSET,
            options
        )
    }

    fun detect(bitmap: Bitmap): DetectionResult {
        val tensorImage = TensorImage.fromBitmap(bitmap)
        val detections = detector.detect(tensorImage)

        val labels = detections.flatMap { detection ->
            detection.categories.map { category ->
                "${category.label}:${"%.2f".format(category.score)}"
            }
        }

        val bestCat = detections
            .asSequence()
            .flatMap { detection -> detection.categories.asSequence() }
            .filter { it.label.equals("cat", ignoreCase = true) }
            .maxByOrNull { it.score }

        return DetectionResult(
            foundCat = bestCat != null,
            score = bestCat?.score ?: 0f,
            labels = labels
        )
    }

    companion object {
        const val MODEL_ASSET = "cat_detector.tflite"
        private const val MIN_SCORE = 0.45f
    }
}

data class DetectionResult(
    val foundCat: Boolean,
    val score: Float,
    val labels: List<String>
)
