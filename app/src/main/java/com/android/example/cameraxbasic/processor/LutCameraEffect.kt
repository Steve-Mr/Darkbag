package com.android.example.cameraxbasic.processor

import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceProcessor
import androidx.core.util.Consumer
import java.util.concurrent.Executor

class LutCameraEffect(
    targets: Int,
    executor: Executor,
    surfaceProcessor: SurfaceProcessor,
    errorListener: Consumer<Throwable>
) : CameraEffect(targets, executor, surfaceProcessor, errorListener)
