# Vendor native libraries for ThingClips BLE

The project currently uses `com.thingclips.smart:thingsmart:7.5.6` from the Tuya commercial Maven repository.

In the repositories tested for this project, the published `7.5.6` aggregate SDK exposes `libthing_security.so`, but no matching `7.5.6` companion artifact was found that provides the remaining native dependencies required at runtime.

The following ARM mbedtls libraries have already been copied into this project from the published `com.thingclips.smart:thingsmart-security-mbedtls:5.17.2` artifact:

- `libmbedcrypto.so`
- `libmbedtls.so`
- `libmbedx509.so`

The main remaining library you may still need to provide manually for both supported ABIs is:

- `app/src/main/jniLibs/arm64-v8a/libthing_security_algorithm.so`
- `app/src/main/jniLibs/armeabi-v7a/libthing_security_algorithm.so`

If your vendor package also provides replacement copies of `libthing_security.so`, place those here too:

- `app/src/main/jniLibs/arm64-v8a/libthing_security.so`
- `app/src/main/jniLibs/armeabi-v7a/libthing_security.so`

Notes:

- Test on a real ARM device or an ARM emulator.
- Android Gradle packages `.so` files found under `app/src/main/jniLibs/<abi>/`.
- After copying the files, do a clean rebuild and reinstall the app.

