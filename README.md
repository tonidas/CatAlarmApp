# Cat Alarm Android App (TensorFlow Lite)

This Android Studio project runs fully locally on-device:
- CameraX for front/back camera preview and frame analysis
- TensorFlow Lite for offline object detection
- Local MP3 selection through the Android document picker
- Alarm playback when a cat is detected in the camera image

## Important model requirement

The code is already wired for **TensorFlow Lite full-body cat detection**, but this environment did not include a redistributable `.tflite` model binary.

Before building the app, place a TensorFlow Lite object detection model file at:

`app/src/main/assets/cat_detector.tflite`

The model must:
- be an **object detection** TFLite model
- expose labels through metadata
- include the label `cat`

A COCO-style object detector that labels cats as `cat` is suitable.

## Features

- 100% local processing, no external APIs
- Switch between front and back camera
- Choose MP3 file from device storage
- Persists selected camera and MP3 URI
- Cooldown to avoid repeated alarm spam

## Main files

- `app/src/main/java/com/example/catalarm/MainActivity.kt`
- `app/src/main/java/com/example/catalarm/CatDetector.kt`
- `app/src/main/java/com/example/catalarm/BitmapUtils.kt`
- `app/src/main/java/com/example/catalarm/YuvFrameConverter.kt`

## Notes

- Detection quality depends entirely on the model you place in assets.
- If the model is missing or invalid, the app still opens and shows camera preview, but detection will stay disabled.
- The MP3 path is selected using Android's Storage Access Framework, which is the correct way to support user-selectable local files on recent Android versions.
