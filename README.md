# CameraXbasic

CameraXbasic aims to demonstrate how to use CameraX APIs written in Kotlin.

## Features
- **Manual Camera Controls**: ISO, Shutter Speed, Focus.
- **Log Curves**: Support for F-Log2, S-Log3, Canon Log, etc.
- **LUT Support**: Import and apply 3D LUTs (.cube) to previews and captured images.
- **RAW Processing**: Native processing using LibRaw.
- **HDR+ Support**: Burst mode computational photography.

## HDR+ Usage

To use the HDR+ feature:
1. Ensure your device supports RAW capture (CameraX/Camera2 API Level Full/Level 3).
2. Tap the **HDR+** toggle in the camera interface to enable it (icon turns blue).
3. Press the shutter button. The app will capture a burst of 3 RAW frames.
4. The frames are aligned and merged using the [timothybrooks/hdr-plus](https://github.com/timothybrooks/hdr-plus) algorithm (implemented in Halide) to reduce noise and increase dynamic range.
5. The result is saved as a new RAW (DNG) file, along with optional TIFF/JPG outputs.

## Build

To build the app directly from the command line, run:
```sh
./gradlew assembleDebug
```

## Test

Unit testing and instrumented device testing share the same code. To test the app using Robolectric, no device required, run:
```sh
./gradlew test
```

To run the same tests in an Android device connected via ADB, run:
```sh
./gradlew connectedAndroidTest
```

Alternatively, test running configurations can be added to Android Studio for convenience (and a nice UI). To do that:
1. Go to: `Run` > `Edit Configurations` > `Add New Configuration`.
1. For Robolectric select `Android JUnit`, for connected device select `Android Instrumented Tests`.
1. Select `app` module and `com.android.example.cameraxbasic.MainInstrumentedTest` class.
1. Optional: Give the run configuration a name, like `test robolectric` or `test device`

## Acknowledgments

*   **[LibRaw](https://www.libraw.org/)**: For RAW image decoding and processing.
*   **[timothybrooks/hdr-plus](https://github.com/timothybrooks/hdr-plus)**: For the HDR+ alignment and merging algorithm implementation.
*   **[Halide](https://halide-lang.org/)**: For high-performance image processing kernels used in the HDR+ pipeline.
