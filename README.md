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
- Sends a Cronitor heartbeat every minute while the app is active

## Cronitor heartbeat

Cronitor configuration lives in the project root `app.properties` file, which is ignored by git.

The app auto-provisions or updates a heartbeat monitor by sending a request every 60 seconds to:

- monitor key: `cat-alarm-app`
- environment: `production`

To change those values locally, edit `app.properties`.

## Main files

- `app/src/main/java/com/ton/catalarm/MainActivity.kt`
- `app/src/main/java/com/ton/catalarm/CatDetector.kt`
- `app/src/main/java/com/ton/catalarm/BitmapUtils.kt`
- `app/src/main/java/com/ton/catalarm/YuvFrameConverter.kt`

## ThingClips / Tuya SDK

The app currently targets the newer ThingClips aggregate SDK line from the Tuya commercial Maven repository:

- `com.thingclips.smart:thingsmart:7.5.6`

The local drop-in location for vendor-provided Tuya native libraries is still present:

- `app/src/main/jniLibs/README.md`
- `app/src/main/jniLibs/arm64-v8a/`
- `app/src/main/jniLibs/armeabi-v7a/`

If you receive a startup error mentioning `libthing_security.so`, `libthing_security_algorithm.so`, `libmbedcrypto.so`, `libmbedtls.so`, or `libmbedx509.so`, place the vendor-provided `.so` files into those ABI folders and rebuild the app.

To list homes or pair devices, the app needs your Tuya/ThingClips credentials:

- `THING_SMART_COUNTRY_CODE`
- `THING_SMART_USERNAME`
- `THING_SMART_PASSWORD`

Start from the checked-in example file `app.properties.example`.


If Tuya logs `SING_VALIDATE_FALED` or `Permission Verification Failed`, the usual cause is one of these:

- the configured Android package name does not match the Tuya/ThingClips app configuration
- the configured SHA certificate fingerprint does not match the signing certificate registered in the Tuya/ThingClips console
- the configured `THING_SMART_KEY` / `THING_SMART_SECRET` pair does not match the same Tuya/ThingClips mobile app project

## Notes

- Detection quality depends entirely on the model you place in assets.
- If the model is missing or invalid, the app still opens and shows camera preview, but detection will stay disabled.
- The MP3 path is selected using Android's Storage Access Framework, which is the correct way to support user-selectable local files on recent Android versions.
- The project currently targets `com.thingclips.smart:thingsmart:7.5.6` from `https://maven-other.tuya.com/repository/maven-commercial-releases/`.
- The Tuya/ThingClips BLE bridge in `app/src/main/java/com/ton/catalarm/TuyaBleBridge.kt` now uses the direct ThingClips APIs exposed by the `7.5.6` SDK line.
- The `7.5.6` aggregate POM still references `thingsmart-asynclib`, which declares `thingsmart-modularCampAnno:1.0.0-SNAPSHOT`; the app excludes that broken transitive module in `app/build.gradle`.
- The commercial `7.5.6` aggregate AAR still exposes `libthing_security.so`, but in the repositories tested for this project no published companion artifact provided `libthing_security_algorithm.so`, `libmbedcrypto.so`, `libmbedtls.so`, or `libmbedx509.so`.
