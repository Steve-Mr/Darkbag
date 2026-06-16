with open('app/src/main/java/top/maary/darkbag/fragments/CameraFragment.kt', 'r') as f:
    content = f.read()

content = content.replace("import top.maary.darkbag.processor.HdrPlusExportWorker\n", "")

replacement_single_work = r"""val request = top.maary.darkbag.processor.HdrPlusRequest(
                    requestId = java.util.UUID.randomUUID().toString(),
                    buffers = arrayOf(image.data),
                    width = image.width,
                    height = image.height,
                    orientation = image.combinedOrientation,
                    whiteLevel = whiteLevel,
                    blackLevelPattern = blackLevelPattern ?: intArrayOf(64,64,64,64),
                    lensShadingMap = lensShadingMapData,
                    lensShadingRows = lensShadingRows,
                    lensShadingCols = lensShadingCols,
                    useSensorColorMatrix = false,
                    whiteBalance = wb,
                    ccm = ccm,
                    ccmAlt = null,
                    exportMatrixAB = false,
                    cfaPattern = cfa,
                    targetLogIndex = targetLogIndex,
                    lutPath = nativeLutPath,
                    digitalGain = image.digitalGain,
                    zoomFactor = image.zoomRatio,
                    mirror = mirror,
                    metadata = captureMetadata,
                    isSingleFrame = true,
                    saveJpg = saveJpg,
                    saveRaw = saveRaw,
                    baseName = dngName,
                    fullResJpgPath = fullResJpgFile.absolutePath,
                    linearDngPath = linearDngPath,
                    zslTargetUriStr = image.zslTargetUriStr,
                    jpgFolderUri = jpgFolderUri,
                    rawFolderUri = rawFolderUri,
                    hfMetadata = image.halfFrameMetadata,
                    editConfig = editConfig
                )
                top.maary.darkbag.processor.HdrPlusRequestManager.enqueue(request)
                val serviceIntent = android.content.Intent(context, top.maary.darkbag.processor.HdrPlusProcessingService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }"""


replacement_burst_work = r"""val request = top.maary.darkbag.processor.HdrPlusRequest(
                        requestId = java.util.UUID.randomUUID().toString(),
                        buffers = buffers,
                        width = width,
                        height = height,
                        orientation = combinedOrientation,
                        whiteLevel = whiteLevel,
                        blackLevelPattern = blackLevelPattern ?: intArrayOf(64,64,64,64),
                        lensShadingMap = lensShadingMapData,
                        lensShadingRows = lensShadingRows,
                        lensShadingCols = lensShadingCols,
                        useSensorColorMatrix = useSensorColorMatrix,
                        whiteBalance = wb,
                        ccm = ccm,
                        ccmAlt = ccmAlt,
                        exportMatrixAB = exportMatrixAB,
                        cfaPattern = cfa,
                        targetLogIndex = targetLogIndex,
                        lutPath = nativeLutPath,
                        digitalGain = digitalGain,
                        zoomFactor = currentZoom,
                        mirror = mirror,
                        metadata = captureMetadata,
                        isSingleFrame = false,
                        saveJpg = saveJpg,
                        saveRaw = saveRaw,
                        baseName = dngName,
                        fullResJpgPath = fullResJpgFile.absolutePath,
                        linearDngPath = linearDngPath,
                        zslTargetUriStr = zslTargetUriTracker?.get(0),
                        jpgFolderUri = jpgFolderUri,
                        rawFolderUri = rawFolderUri,
                        hfMetadata = hfMetadata?.copy(digitalGain = digitalGain),
                        editConfig = null
                    )
                    top.maary.darkbag.processor.HdrPlusRequestManager.enqueue(request)
                    val serviceIntent = android.content.Intent(context, top.maary.darkbag.processor.HdrPlusProcessingService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }"""

start1 = 'val workData = androidx.work.Data.Builder()\n                    .putString("tempRawPath", tempRawFile.absolutePath)'
end1 = 'androidx.work.WorkManager.getInstance(context).enqueue(workRequest)'

idx1 = content.find(start1)
if idx1 != -1:
    idx_end = content.find(end1, idx1)
    if idx_end != -1:
        content = content[:idx1] + replacement_single_work + content[idx_end + len(end1):]

start2 = 'val workData = androidx.work.Data.Builder()\n                        .putString("tempRawPath", tempRawFile.absolutePath)'
end2 = 'androidx.work.WorkManager.getInstance(context).enqueue(workRequest)'
idx2 = content.find(start2)
if idx2 != -1:
    idx_end = content.find(end2, idx2)
    if idx_end != -1:
        content = content[:idx2] + replacement_burst_work + content[idx_end + len(end2):]

# Fix ret = 0
import re
pattern_burst_ret = r"""val ret = ColorProcessor\.processHdrPlus\(
\s*buffers,
\s*width, height,
\s*combinedOrientation,
\s*whiteLevel, blackLevelPattern,
\s*lensShadingMapData, lensShadingRows, lensShadingCols, useSensorColorMatrix,
\s*wb, ccm, ccmAlt, exportMatrixAB, cfa,
\s*targetLogIndex,
\s*nativeLutPath,
\s*null, // outputJpgPath \(fast preview disabled\)
\s*null, // outputDngPath \(finalize in background\)
\s*digitalGain,
\s*debugStats,
\s*null, // outputBitmap
\s*tempRawFile\.absolutePath,
\s*currentZoom,
\s*mirror,
\s*metadata = captureMetadata
\s*\)"""
content = re.sub(pattern_burst_ret, "val ret = 0", content, flags=re.DOTALL)

content = content.replace("val null = top.maary.darkbag.models.EditConfig", "val editConfig = top.maary.darkbag.models.EditConfig")
content = content.replace("arrayOf(image.data)", "arrayOf(image.data!!)")

with open('app/src/main/java/top/maary/darkbag/fragments/CameraFragment.kt', 'w') as f:
    f.write(content)
