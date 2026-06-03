package top.maary.darkbag.fragments

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.R
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.utils.ImageSaver
import java.io.File
import androidx.appcompat.widget.PopupMenu

class PlaygroundViewerFragment : ImageViewerFragment() {

    override fun loadImages(targetUri: String?, forceRefresh: Boolean) {
        binding.initialLoadingIndicator.visibility = View.VISIBLE
        binding.imagePager.visibility = View.INVISIBLE

        val playgroundPaths = arguments?.getStringArray("playground_dng_paths")
        if (playgroundPaths != null && playgroundPaths.isNotEmpty()) {
            val firstPath = playgroundPaths[0]
            val playgroundDir = File(requireContext().filesDir, "playground_dngs")
            val group = if (playgroundPaths.size == 1) {
                val baseName = File(firstPath).nameWithoutExtension
                val potentialJpg = File(playgroundDir, "$baseName.jpg")
                val jpgUri = if (potentialJpg.exists()) Uri.fromFile(potentialJpg) else null

                val path = playgroundPaths[0]
                val uri = Uri.fromFile(File(path))
                ImageGroup(
                    baseName = baseName,
                    dngUri = uri,
                    jpgUri = jpgUri,
                    captureTime = System.currentTimeMillis(),
                    lastModified = System.currentTimeMillis()
                )
            } else {
                val path1 = playgroundPaths[0]
                val path2 = playgroundPaths[1]
                val name1 = File(path1).nameWithoutExtension

                // If it's an existing group being opened, name1 will likely end in _1.
                // We should derive the group base name by stripping _1 if present, otherwise
                // it's a new merge so we append _merged.
                val mergedBaseName = if (name1.endsWith("_1")) {
                    name1.removeSuffix("_1")
                } else {
                    name1 + "_merged"
                }

                val potentialMergedJpg = File(playgroundDir, "$mergedBaseName.jpg")
                val mergedJpgUri = if (potentialMergedJpg.exists()) Uri.fromFile(potentialMergedJpg) else null

                // For an existing composite with a saved JPG, we want to rely on the EXIF for layout.
                // But for a new merge, we take the layout from arguments.
                val layout = if (mergedJpgUri == null) {
                    arguments?.getString("playground_hf_layout") ?: "SBS"
                } else {
                    "SBS" // Fallback, will be overridden by loadMetadata
                }

                ImageGroup(
                    baseName = mergedBaseName,
                    dngUri1 = Uri.fromFile(File(path1)),
                    dngUri2 = Uri.fromFile(File(path2)),
                    jpgUri = mergedJpgUri,
                    hfLayout = layout,
                    captureTime = System.currentTimeMillis(),
                    lastModified = System.currentTimeMillis()
                )
            }
            val groups = listOf(group)

            if (binding.imagePager.adapter == null) {
                adapter = ImageViewerAdapter(groups, lifecycleScope, requireContext()).apply {
                    onImageTapped = { toggleUi() }
                    onZoomChanged = { isZoomed -> if (isZoomed) hideUi() else showUi() }
                    onLongPressStarted = { handleLongPressStarted(it) }
                    onLongPressEnded = { handleLongPressEnded(it) }
                    setFormatSwitcherPersistentHidden(isAdjusted)
                    onCurrentListChanged = { previousList, currentList ->
                        val currentIndex = binding.imagePager.currentItem
                        if (currentIndex in currentList.indices) {
                            val currentGroup = currentList[currentIndex]
                            val prevGroup = previousList.getOrNull(currentIndex)

                            if (prevGroup != null && !prevGroup.metadataLoaded && currentGroup.metadataLoaded) {
                                if (currentGroup.editConfig != null) {
                                    prepareEditConfig(currentGroup)
                                }
                            }
                            updateControlsVisibility()
                        }
                    }
                }
                binding.imagePager.adapter = adapter
                binding.imagePager.registerOnPageChangeCallback(pageChangeCallback)
                binding.imagePager.isUserInputEnabled = !isAdjusted
                setupActionButtons()

                val initialGroup = groups[0]
                if (!initialGroup.metadataLoaded) {
                    lifecycleScope.launch {
                        val updatedGroup = repository.loadMetadata(initialGroup)
                        adapter.updateGroups(listOf(updatedGroup))

                        // Mark as adjusted directly if we are displaying a newly merged playground group
                        if (groups.size == 1 && playgroundPaths.size == 2 && updatedGroup.jpgUri == null) {
                            isAdjusted = true
                            adapter.setFormatSwitcherPersistentHidden(true)
                            updateControlsVisibility()
                        }
                    }
                } else if (groups.size == 1 && playgroundPaths.size == 2 && initialGroup.jpgUri == null) {
                    isAdjusted = true
                    adapter.setFormatSwitcherPersistentHidden(true)
                    updateControlsVisibility()
                }

                binding.initialLoadingIndicator.visibility = View.GONE
                binding.imagePager.visibility = View.VISIBLE
                updateControlsVisibility()
            } else {
                adapter.updateGroups(groups)
                binding.initialLoadingIndicator.visibility = View.GONE
                binding.imagePager.visibility = View.VISIBLE
            }
        }
    }

    override fun setupActionButtons() {
        super.setupActionButtons()

        binding.btnSaveMenu.setOnClickListener {
            binding.btnSaveMenu.isCheckable = true
            binding.btnSaveMenu.isChecked = true
            val popup = PopupMenu(requireContext(), it)

            popup.menu.add(0, 1001, 0, "Save as new file").apply {
                setIcon(R.drawable.ic_save_as)
            }
            popup.menu.add(0, 1002, 0, getString(R.string.share_as_tiff)).apply {
                setIcon(R.drawable.ic_photo)
            }
            popup.menu.add(0, 1003, 0, "Export to JPG (Pictures)").apply {
                setIcon(R.drawable.ic_save)
            }

            try {
                val fieldPopup = PopupMenu::class.java.getDeclaredField("mPopup")
                fieldPopup.isAccessible = true
                val mPopup = fieldPopup.get(popup)
                mPopup.javaClass.getDeclaredMethod("setForceShowIcon", Boolean::class.java).invoke(mPopup, true)
            } catch (e: Exception) {
                Log.e("PlaygroundViewer", "Error forcing menu icons", e)
            }

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1001 -> saveEdit(isReplacement = false)
                    1002 -> performShareAsTiff()
                    1003 -> exportToJpg()
                }
                true
            }
            popup.setOnDismissListener { binding.btnSaveMenu.isChecked = false }
            popup.show()
        }
    }

    override fun saveEdit(isReplacement: Boolean) {
        val context = context ?: return
        val appContext = context.applicationContext
        val config = currentEditConfig ?: return
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val dngUri1 = currentGroup.dngUri ?: currentGroup.dngUri1

        val finalConfig = config.copy(hfLayout = config.hfLayout ?: currentGroup.hfLayout)

        previewJob?.cancel()
        binding.initialLoadingIndicator.visibility = View.VISIBLE
        binding.interactionBlocker?.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                ensureDngBytesLoaded()
                var finalJpgUri: Uri? = null
                var newBaseName: String = currentGroup.baseName

                withContext(Dispatchers.IO) {
                    try {
                        val finalBitmap = generateProcessedBitmap(finalConfig, currentGroup)

                        finalBitmap?.let { bitmap ->
                            newBaseName = if (isReplacement) currentGroup.baseName else "${currentGroup.baseName}_edited_${System.currentTimeMillis()}"

                            val playgroundDir = File(appContext.filesDir, "playground_dngs")
                            if (!playgroundDir.exists()) playgroundDir.mkdirs()

                            val targetFile = File(playgroundDir, "${newBaseName}.jpg")

                            java.io.FileOutputStream(targetFile).use { out ->
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                            }

                            finalJpgUri = Uri.fromFile(targetFile)

                            val captureMetadata = (currentGroup.jpgUri ?: currentGroup.dngUri ?: currentGroup.dngUri1)?.let { repository.getCaptureMetadata(it) }

                            top.maary.darkbag.utils.ImageSaver.writeMetadataToExif(appContext, finalJpgUri!!, finalConfig, captureMetadata)

                            if (!isReplacement && !currentGroup.isHalfFrame() && dngUri1 != null) {
                                val newDngFile = File(playgroundDir, "${newBaseName}.dng")
                                appContext.contentResolver.openInputStream(dngUri1)?.use { input ->
                                    java.io.FileOutputStream(newDngFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            } else if (!isReplacement && currentGroup.isHalfFrame()) {
                                dngUri1?.let { uri ->
                                    val newDngFile1 = File(playgroundDir, "${newBaseName}_1.dng")
                                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                                        java.io.FileOutputStream(newDngFile1).use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                }
                                currentGroup.dngUri2?.let { uri ->
                                    val newDngFile2 = File(playgroundDir, "${newBaseName}_2.dng")
                                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                                        java.io.FileOutputStream(newDngFile2).use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                }
                            } else if (isReplacement && currentGroup.isHalfFrame()) {
                                // If replacing (normal save) a half-frame composite, ensure the original DNG files
                                // are renamed to match the merged base name with _1 and _2 suffixes.
                                // This ensures they are grouped correctly in the playground gallery.
                                val isFirstSave = currentGroup.jpgUri == null
                                if (isFirstSave) {
                                    currentGroup.dngUri1?.let { uri ->
                                        if (uri.scheme == "file") {
                                            uri.path?.let { path ->
                                                val oldFile = File(path)
                                                val newFile = File(playgroundDir, "${newBaseName}_1.dng")
                                                if (oldFile.exists() && oldFile.absolutePath != newFile.absolutePath) {
                                                    // If the old file is already part of a different merged group
                                                    // (i.e. it ends with _1 or _2), we should copy it instead of renaming it
                                                    // so we don't break the original group.
                                                    // Also, if the old file has an associated edited JPG, we must copy it
                                                    // to avoid breaking the existing edited image.
                                                    val hasAssociatedJpg = File(oldFile.parent, "${oldFile.nameWithoutExtension}.jpg").exists()
                                                    if (oldFile.nameWithoutExtension.endsWith("_1") || oldFile.nameWithoutExtension.endsWith("_2") || hasAssociatedJpg) {
                                                        oldFile.copyTo(newFile, overwrite = true)
                                                    } else {
                                                        oldFile.renameTo(newFile)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    currentGroup.dngUri2?.let { uri ->
                                        if (uri.scheme == "file") {
                                            uri.path?.let { path ->
                                                val oldFile = File(path)
                                                val newFile = File(playgroundDir, "${newBaseName}_2.dng")
                                                if (oldFile.exists() && oldFile.absolutePath != newFile.absolutePath) {
                                                    val hasAssociatedJpg = File(oldFile.parent, "${oldFile.nameWithoutExtension}.jpg").exists()
                                                    if (oldFile.nameWithoutExtension.endsWith("_1") || oldFile.nameWithoutExtension.endsWith("_2") || hasAssociatedJpg) {
                                                        oldFile.copyTo(newFile, overwrite = true)
                                                    } else {
                                                        oldFile.renameTo(newFile)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            bitmap.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e("PlaygroundViewer", "Failed to save edit", e)
                    }
                }

                resetAdjustments()

                if (finalJpgUri != null) {
                    val currentList = adapter.getGroups().toMutableList()
                    val currentIndex = binding.imagePager.currentItem

                    if (isReplacement) {
                        val playgroundDir = File(appContext.filesDir, "playground_dngs")
                        val dngFile1 = File(playgroundDir, "${newBaseName}_1.dng")
                        val dngFile2 = File(playgroundDir, "${newBaseName}_2.dng")

                        val newDngUri1 = if (currentGroup.isHalfFrame() && currentGroup.jpgUri == null && dngFile1.exists()) Uri.fromFile(dngFile1) else currentGroup.dngUri1
                        val newDngUri2 = if (currentGroup.isHalfFrame() && currentGroup.jpgUri == null && dngFile2.exists()) Uri.fromFile(dngFile2) else currentGroup.dngUri2

                        currentList[currentIndex] = currentGroup.copy(
                            jpgUri = finalJpgUri,
                            baseName = newBaseName,
                            dngUri1 = newDngUri1,
                            dngUri2 = newDngUri2,
                            hfLayout = finalConfig.hfLayout
                        )
                        adapter.updateGroups(currentList.toList())
                    } else {
                        val newDngUri1 = if (!currentGroup.isHalfFrame()) Uri.fromFile(File(File(appContext.filesDir, "playground_dngs"), "${newBaseName}.dng")) else Uri.fromFile(File(File(appContext.filesDir, "playground_dngs"), "${newBaseName}_1.dng"))
                        val newDngUri2 = if (currentGroup.isHalfFrame()) Uri.fromFile(File(File(appContext.filesDir, "playground_dngs"), "${newBaseName}_2.dng")) else null

                        val newGroup = ImageGroup(
                            baseName = newBaseName,
                            dngUri = if (!currentGroup.isHalfFrame()) newDngUri1 else null,
                            dngUri1 = if (currentGroup.isHalfFrame()) newDngUri1 else null,
                            dngUri2 = newDngUri2,
                            jpgUri = finalJpgUri,
                            hfLayout = finalConfig.hfLayout,
                            captureTime = currentGroup.captureTime,
                            lastModified = System.currentTimeMillis()
                        )

                        val updatedNewGroup = repository.loadMetadata(newGroup)
                        currentList.add(currentIndex + 1, updatedNewGroup)
                        adapter.updateGroups(currentList.toList())
                        binding.imagePager.setCurrentItem(currentIndex + 1, false)
                    }

                    updateControlsVisibility()
                }

            } finally {
                binding.initialLoadingIndicator.visibility = View.GONE
                binding.interactionBlocker?.visibility = View.GONE
            }
        }
    }

    override fun deleteImage(group: ImageGroup, deleteGroup: Boolean) {
        val filesToDelete = mutableListOf<File>()

        group.jpgUri?.let { if (it.scheme == "file") it.path?.let { p -> filesToDelete.add(File(p)) } }
        group.dngUri?.let { if (it.scheme == "file") it.path?.let { p -> filesToDelete.add(File(p)) } }
        group.dngUri1?.let { if (it.scheme == "file") it.path?.let { p -> filesToDelete.add(File(p)) } }
        group.dngUri2?.let { if (it.scheme == "file") it.path?.let { p -> filesToDelete.add(File(p)) } }

        var deletedCount = 0
        for (f in filesToDelete) {
            if (f.exists() && f.delete()) {
                deletedCount++
            }
        }

        if (deletedCount > 0) {
            val currentList = adapter.getGroups().toMutableList()
            currentList.removeAll { it.baseName == group.baseName }
            if (currentList.isEmpty()) {
                activity?.onBackPressedDispatcher?.onBackPressed()
            } else {
                adapter.updateGroups(currentList.toList())
                updateControlsVisibility()
            }
        }
    }

    private fun exportToJpg() {
        val context = context ?: return

        if (isEditingAdjustments) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle("Export to JPG")
                .setMessage("Save adjustments and export to JPG?")
                .setPositiveButton("Save & Export") { _, _ ->
                    // First save the file locally in Sandbox, then trigger export to gallery
                    saveEdit(isReplacement = true)
                    processAndExportJpg()
                }
                .setNegativeButton("Export Without Saving") { _, _ ->
                    // If they just want to export, decode the preview directly
                    // avoiding reading the saved jpg which might be stale or null
                    processAndExportJpg()
                }
                .setNeutralButton(top.maary.darkbag.R.string.cancel, null)
                .show()
        } else {
            processAndExportJpg()
        }
    }

    private fun processAndExportJpg() {
        val context = context ?: return
        val appContext = context.applicationContext
        val config = currentEditConfig ?: return
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        binding.initialLoadingIndicator.visibility = View.VISIBLE
        binding.interactionBlocker?.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                ensureDngBytesLoaded()
                withContext(Dispatchers.IO) {
                    val dngUri1 = currentGroup.dngUri ?: currentGroup.dngUri1
                    val dngUri2 = currentGroup.dngUri2
                    val logIndex = top.maary.darkbag.fragments.SettingsFragment.LOG_CURVES.indexOf(config.log)
                    val lutPath = if (config.lut != null && config.lut != "None") {
                        File(lutManager.lutDir, config.lut).absolutePath
                    } else null

                    // If it is a single frame, we can use processRaw to directly output a full-resolution JPEG to a temp file,
                    // bypassing Bitmap memory allocation entirely!
                    if (!currentGroup.isHalfFrame()) {
                        val primaryUri = dngUri1 ?: dngUri2 ?: return@withContext
                        val primaryBytes = if (dngUri1 != null) sourceDngBytes else sourceDngBytes2
                        val finalBytes = primaryBytes ?: run {
                            appContext.contentResolver.openFileDescriptor(primaryUri, "r")?.use { pfd ->
                                java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                            }
                        } ?: return@withContext

                        val orientation = try {
                            appContext.contentResolver.openInputStream(primaryUri)?.use { input ->
                                androidx.exifinterface.media.ExifInterface(input).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                            } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                        } catch (e: Exception) { androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL }

                        val rotDegrees = when(orientation) {
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }
                        val adj = top.maary.darkbag.models.BasicAdjustments(config.exposure, config.contrast, config.saturation, config.highlights, config.shadows, config.whites, config.blacks, config.digitalGain)
                        val meta = repository.getCaptureMetadata(primaryUri)

                        val tempJpgFile = java.io.File(appContext.cacheDir, "temp_export_${System.currentTimeMillis()}.jpg")

                        top.maary.darkbag.processor.ColorProcessor.processRaw(
                            dngData = finalBytes,
                            targetLog = logIndex,
                            lutPath = lutPath,
                            exposure = adj.exposure,
                            contrast = adj.contrast,
                            saturation = adj.saturation,
                            highlights = adj.highlights,
                            shadows = adj.shadows,
                            whites = adj.whites,
                            blacks = adj.blacks,
                            digitalGain = adj.digitalGain,
                            outputJpgPath = tempJpgFile.absolutePath,
                            useGpu = false,
                            orientation = rotDegrees,
                            mirror = false,
                            outputBitmap = null,
                            downsampleFactor = 1,
                            zoomFactor = config.zoomFactor,
                            metadata = meta
                        )

                        if (tempJpgFile.exists()) {
                            ImageSaver.saveProcessedImage(
                                context = appContext,
                                inputBitmap = null,
                                bmpPath = tempJpgFile.absolutePath,
                                rotationDegrees = 0,
                                zoomFactor = 1.0f,
                                baseName = currentGroup.baseName,
                                linearDngPath = null,
                                saveJpg = true,
                                saveRaw = false,
                                editConfig = config,
                                isAlreadyStitched = true,
                                captureMetadata = meta
                            )
                            tempJpgFile.delete()
                        }
                    } else {
                        // For half-frame, we MUST composite.
                        // To prevent OOM with two full-res raw files, we use a safe downsampleFactor (e.g. 2 or 4)
                        // by passing the calculated downsample factor to ColorProcessor to render a high-quality (but not 24MP) image
                        fun processFullSafe(bytes: ByteArray?, uri: Uri, index: Int): android.graphics.Bitmap? {
                            val finalBytes = bytes ?: run {
                                appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                    java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                                }
                            } ?: return null

                            val orientation = try {
                                appContext.contentResolver.openInputStream(uri)?.use { input ->
                                    androidx.exifinterface.media.ExifInterface(input).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                                } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                            } catch (e: Exception) { androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL }

                            val rotDegrees = when(orientation) {
                                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                                else -> 0
                            }

                            val adj = if (currentGroup.isHalfFrame()) config.adjustments?.get(index) ?: top.maary.darkbag.models.BasicAdjustments() else top.maary.darkbag.models.BasicAdjustments(config.exposure, config.contrast, config.saturation, config.highlights, config.shadows, config.whites, config.blacks, config.digitalGain)
                            val meta = repository.getCaptureMetadata(uri)

                            val tempJpgFile = java.io.File(appContext.cacheDir, "temp_export_${System.currentTimeMillis()}_$index.jpg")

                            top.maary.darkbag.processor.ColorProcessor.processRaw(
                                dngData = finalBytes,
                                targetLog = logIndex,
                                lutPath = lutPath,
                                exposure = adj.exposure,
                                contrast = adj.contrast,
                                saturation = adj.saturation,
                                highlights = adj.highlights,
                                shadows = adj.shadows,
                                whites = adj.whites,
                                blacks = adj.blacks,
                                digitalGain = adj.digitalGain,
                                outputJpgPath = tempJpgFile.absolutePath,
                                useGpu = false,
                                orientation = rotDegrees,
                                mirror = false,
                                outputBitmap = null,
                                downsampleFactor = 1,
                                zoomFactor = config.zoomFactor,
                                metadata = meta
                            )

                            if (!tempJpgFile.exists()) return null

                            val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                            }
                            val bitmap = android.graphics.BitmapFactory.decodeFile(tempJpgFile.absolutePath, decodeOpts)
                            tempJpgFile.delete()
                            return bitmap
                        }

                        val f1 = dngUri1?.let { processFullSafe(sourceDngBytes, it, 0) }
                        val f2 = dngUri2?.let { processFullSafe(sourceDngBytes2, it, 1) }

                        val b1 = if (config.isSwapped) f2 else f1
                        val b2 = if (config.isSwapped) f1 else f2

                        val finalCompositeBitmap: android.graphics.Bitmap? = if (b1 != null || b2 != null) {
                            val isSBS = currentGroup.hfLayout != "TB"

                            val refW = b1?.width ?: b2?.width ?: 0
                            val refH = b1?.height ?: b2?.height ?: 0

                            val oriented1 = b1?.let { top.maary.darkbag.utils.HalfFrameUtils.ensureOrientation(it, isSBS) }
                            val oriented2 = b2?.let { top.maary.darkbag.utils.HalfFrameUtils.ensureOrientation(it, isSBS) }

                            val w1 = oriented1?.width ?: refW
                            val h1 = oriented1?.height ?: refH
                            val w2 = oriented2?.width ?: refW
                            val h2 = oriented2?.height ?: refH

                            val tempB1 = oriented1 ?: android.graphics.Bitmap.createBitmap(w2, h2, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
                            val tempB2 = oriented2 ?: android.graphics.Bitmap.createBitmap(w1, h1, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }

                            var composite = top.maary.darkbag.utils.HalfFrameUtils.composeBitmaps(tempB1, tempB2, isSBS)

                            val economical = top.maary.darkbag.utils.HalfFrameManager(appContext).downsample
                            if (economical) {
                                val scale = 0.707f
                                val scaledW = (composite.width * scale).toInt().coerceAtLeast(1)
                                val scaledH = (composite.height * scale).toInt().coerceAtLeast(1)
                                val scaled = android.graphics.Bitmap.createScaledBitmap(composite, scaledW, scaledH, true)
                                if (scaled != composite) {
                                    composite.recycle()
                                    composite = scaled
                                }
                            }

                            if (oriented1 != b1) oriented1?.recycle()
                            if (oriented2 != b2) oriented2?.recycle()
                            if (tempB1 != oriented1) tempB1.recycle()
                            if (tempB2 != oriented2) tempB2.recycle()

                            val time1 = dngUri1?.let { repository.getCaptureMetadata(it)?.dateTimeOriginal } ?: currentGroup.captureTime
                            val time2 = dngUri2?.let { repository.getCaptureMetadata(it)?.dateTimeOriginal } ?: currentGroup.captureTime

                            val t1 = if (config.isSwapped) time2 else time1
                            val t2 = if (config.isSwapped) time1 else time2

                            val finalComposite = top.maary.darkbag.utils.HalfFrameUtils.addEffects(
                                composite,
                                config.showTimestamp,
                                config.flareType >= 0,
                                currentGroup.hfLayout ?: "SBS",
                                time1 = t1,
                                time2 = t2,
                                flareType = config.flareType
                            )
                            if (finalComposite != composite) {
                                composite.recycle()
                            }
                            f1?.recycle()
                            f2?.recycle()
                            finalComposite
                        } else null

                        finalCompositeBitmap?.let { bitmap ->
                            val captureMetadata = (currentGroup.jpgUri ?: currentGroup.dngUri ?: currentGroup.dngUri1)?.let { repository.getCaptureMetadata(it) }

                            ImageSaver.saveProcessedImage(
                                context = appContext,
                                inputBitmap = bitmap,
                                bmpPath = null,
                                rotationDegrees = 0,
                                zoomFactor = 1.0f,
                                baseName = currentGroup.baseName,
                                linearDngPath = null,
                                saveJpg = true,
                                saveRaw = false,
                                editConfig = config,
                                isAlreadyStitched = true,
                                captureMetadata = captureMetadata
                            )
                            bitmap.recycle()
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(appContext, "Exported JPG to Pictures", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("PlaygroundViewer", "Failed to export JPG", e)
            } finally {
                binding.initialLoadingIndicator.visibility = android.view.View.GONE
                binding.interactionBlocker?.visibility = android.view.View.GONE
            }
        }
    }

    override fun showDiscardChangesDialog() {
        val playgroundPaths = arguments?.getStringArray("playground_dng_paths")
        val groups = adapter.getGroups()

        // If we are in the special state where we just merged two images in playground and haven't saved
        if (groups.size == 1 && playgroundPaths != null && playgroundPaths.size == 2 && groups[0].jpgUri == null) {
            // Discarding a newly merged playground composite should just exit the viewer
            androidx.navigation.fragment.NavHostFragment.findNavController(this).navigateUp()
            return
        }

        super.showDiscardChangesDialog()
    }

}