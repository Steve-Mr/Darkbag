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
                        newBaseName = if (isReplacement) currentGroup.baseName else "${currentGroup.baseName}_edited_${System.currentTimeMillis()}"
                        val playgroundDir = File(appContext.filesDir, "playground_dngs")
                        if (!playgroundDir.exists()) playgroundDir.mkdirs()
                        val targetFile = File(playgroundDir, "${newBaseName}.jpg")
                        val captureMetadata = (currentGroup.jpgUri ?: currentGroup.dngUri ?: currentGroup.dngUri1)?.let { repository.getCaptureMetadata(it) }

                        val ccm = floatArrayOf(1.884f, -0.669f, -0.215f, -0.428f, 1.439f, -0.011f, 0.057f, -0.575f, 1.518f)
                        val wb = floatArrayOf(1f, 1f, 1f, 1f)

                        if (currentGroup.isHalfFrame()) {
                            val t1 = if (config.isSwapped) (currentGroup.dngUri2?.let { repository.getCaptureMetadata(it)?.dateTimeOriginal } ?: currentGroup.captureTime) else (dngUri1?.let { repository.getCaptureMetadata(it)?.dateTimeOriginal } ?: currentGroup.captureTime)
                            val t2 = if (config.isSwapped) (dngUri1?.let { repository.getCaptureMetadata(it)?.dateTimeOriginal } ?: currentGroup.captureTime) else (currentGroup.dngUri2?.let { repository.getCaptureMetadata(it)?.dateTimeOriginal } ?: currentGroup.captureTime)
                            val temp1 = File(appContext.cacheDir, "temp1.jpg")
                            val temp2 = File(appContext.cacheDir, "temp2.jpg")
                            val dng1 = if (config.isSwapped) currentGroup.dngUri2 else dngUri1
                            val dng2 = if (config.isSwapped) dngUri1 else currentGroup.dngUri2
                            val c1 = if (config.isSwapped) 1 else 0
                            val c2 = if (config.isSwapped) 0 else 1
                            val logIndex = top.maary.darkbag.fragments.SettingsFragment.LOG_CURVES.indexOf(config.log)
                            val lutPath = if (config.lut != null && config.lut != "None") java.io.File(lutManager.lutDir, config.lut).absolutePath else null

                            if (dng1 != null) {
                                val p = dngUriToPath(dng1)
                                top.maary.darkbag.processor.ColorProcessor.exportHdrPlus(p, 0, 0, 0, config.digitalGain, logIndex, lutPath, config.adjustments?.get(c1)?.exposure ?: 0f, config.adjustments?.get(c1)?.contrast ?: 0f, config.adjustments?.get(c1)?.saturation ?: 0f, config.adjustments?.get(c1)?.highlights ?: 0f, config.adjustments?.get(c1)?.shadows ?: 0f, config.adjustments?.get(c1)?.whites ?: 0f, config.adjustments?.get(c1)?.blacks ?: 0f, temp1.absolutePath, null, ccm, wb, config.zoomFactor, false, captureMetadata ?: top.maary.darkbag.models.CaptureMetadata())
                                if (dng1.scheme != "file") File(p).delete()
                            }
                            if (dng2 != null) {
                                val p = dngUriToPath(dng2)
                                top.maary.darkbag.processor.ColorProcessor.exportHdrPlus(p, 0, 0, 0, config.digitalGain, logIndex, lutPath, config.adjustments?.get(c2)?.exposure ?: 0f, config.adjustments?.get(c2)?.contrast ?: 0f, config.adjustments?.get(c2)?.saturation ?: 0f, config.adjustments?.get(c2)?.highlights ?: 0f, config.adjustments?.get(c2)?.shadows ?: 0f, config.adjustments?.get(c2)?.whites ?: 0f, config.adjustments?.get(c2)?.blacks ?: 0f, temp2.absolutePath, null, ccm, wb, config.zoomFactor, false, captureMetadata ?: top.maary.darkbag.models.CaptureMetadata())
                                if (dng2.scheme != "file") File(p).delete()
                            }
                            val stitchedBitmap = top.maary.darkbag.utils.HalfFrameUtils.stitchImages(temp1.absolutePath, temp2.absolutePath, currentGroup.hfLayout ?: "SBS", false)
                            temp1.delete()
                            temp2.delete()
                            if (stitchedBitmap != null) {
                                val finalBitmap = top.maary.darkbag.utils.HalfFrameUtils.addEffects(stitchedBitmap, config.showTimestamp, config.flareType >= 0, currentGroup.hfLayout ?: "SBS", t1, t2, config.flareType)
                                java.io.FileOutputStream(targetFile).use { out ->
                                    finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                                }
                                if (finalBitmap != stitchedBitmap) stitchedBitmap.recycle()
                                finalBitmap.recycle()
                            }
                        } else {
                            dngUri1?.let { uri ->
                                val p = dngUriToPath(uri)
                                top.maary.darkbag.processor.ColorProcessor.exportHdrPlus(
                                    tempRawPath = p, width = 0, height = 0, orientation = 0,
                                    digitalGain = config.digitalGain, targetLog = top.maary.darkbag.fragments.SettingsFragment.LOG_CURVES.indexOf(config.log),
                                    lutPath = if (config.lut != null && config.lut != "None") java.io.File(lutManager.lutDir, config.lut).absolutePath else null,
                                    exposure = config.exposure, contrast = config.contrast, saturation = config.saturation,
                                    highlights = config.highlights, shadows = config.shadows, whites = config.whites, blacks = config.blacks,
                                    jpgPath = targetFile.absolutePath, dngPath = null,
                                    ccm = ccm, whiteBalance = wb, zoomFactor = config.zoomFactor, mirror = false,
                                    metadata = captureMetadata ?: top.maary.darkbag.models.CaptureMetadata()
                                )
                                if (uri.scheme != "file") File(p).delete()
                            }
                        }

                        finalJpgUri = Uri.fromFile(targetFile)

                        top.maary.darkbag.utils.ImageSaver.writeMetadataToExif(appContext, finalJpgUri!!, config, captureMetadata)

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
                                                    if (oldFile.nameWithoutExtension.endsWith("_1") || oldFile.nameWithoutExtension.endsWith("_2")) {
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
                                                    if (oldFile.nameWithoutExtension.endsWith("_1") || oldFile.nameWithoutExtension.endsWith("_2")) {
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

                        currentList[currentIndex] = currentGroup.copy(jpgUri = finalJpgUri, baseName = newBaseName, dngUri1 = newDngUri1, dngUri2 = newDngUri2)
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
                            hfLayout = currentGroup.hfLayout,
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


    private fun dngUriToPath(uri: Uri): String {
        return if (uri.scheme == "file") {
            uri.path ?: ""
        } else {
            val file = java.io.File(requireContext().cacheDir, "temp_dng_${System.currentTimeMillis()}.dng")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file.absolutePath
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

        lifecycleScope.launch {
            try {
                ensureDngBytesLoaded()
                withContext(Dispatchers.IO) {
                    val captureMetadata = (currentGroup.jpgUri ?: currentGroup.dngUri ?: currentGroup.dngUri1)?.let { repository.getCaptureMetadata(it) }
                    val dngUri1 = currentGroup.dngUri ?: currentGroup.dngUri1
                    val ccm = floatArrayOf(1.884f, -0.669f, -0.215f, -0.428f, 1.439f, -0.011f, 0.057f, -0.575f, 1.518f)
                    val wb = floatArrayOf(1f, 1f, 1f, 1f)
                    val logIndex = top.maary.darkbag.fragments.SettingsFragment.LOG_CURVES.indexOf(config.log)
                    val lutPath = if (config.lut != null && config.lut != "None") java.io.File(lutManager.lutDir, config.lut).absolutePath else null

                    if (currentGroup.isHalfFrame()) {
                        val t1 = if (config.isSwapped) (currentGroup.dngUri2?.let { repository.getCaptureMetadata(it)?.dateTimeOriginal } ?: currentGroup.captureTime) else (dngUri1?.let { repository.getCaptureMetadata(it)?.dateTimeOriginal } ?: currentGroup.captureTime)
                        val t2 = if (config.isSwapped) (dngUri1?.let { repository.getCaptureMetadata(it)?.dateTimeOriginal } ?: currentGroup.captureTime) else (currentGroup.dngUri2?.let { repository.getCaptureMetadata(it)?.dateTimeOriginal } ?: currentGroup.captureTime)
                        val temp1 = File(appContext.cacheDir, "temp1.jpg")
                        val temp2 = File(appContext.cacheDir, "temp2.jpg")
                        val dng1 = if (config.isSwapped) currentGroup.dngUri2 else dngUri1
                        val dng2 = if (config.isSwapped) dngUri1 else currentGroup.dngUri2
                        val c1 = if (config.isSwapped) 1 else 0
                        val c2 = if (config.isSwapped) 0 else 1
                        if (dng1 != null) {
                            val p = dngUriToPath(dng1)
                            top.maary.darkbag.processor.ColorProcessor.exportHdrPlus(p, 0, 0, 0, config.digitalGain, logIndex, lutPath, config.adjustments?.get(c1)?.exposure ?: 0f, config.adjustments?.get(c1)?.contrast ?: 0f, config.adjustments?.get(c1)?.saturation ?: 0f, config.adjustments?.get(c1)?.highlights ?: 0f, config.adjustments?.get(c1)?.shadows ?: 0f, config.adjustments?.get(c1)?.whites ?: 0f, config.adjustments?.get(c1)?.blacks ?: 0f, temp1.absolutePath, null, ccm, wb, config.zoomFactor, false, captureMetadata ?: top.maary.darkbag.models.CaptureMetadata())
                            if (dng1.scheme != "file") File(p).delete()
                        }
                        if (dng2 != null) {
                            val p = dngUriToPath(dng2)
                            top.maary.darkbag.processor.ColorProcessor.exportHdrPlus(p, 0, 0, 0, config.digitalGain, logIndex, lutPath, config.adjustments?.get(c2)?.exposure ?: 0f, config.adjustments?.get(c2)?.contrast ?: 0f, config.adjustments?.get(c2)?.saturation ?: 0f, config.adjustments?.get(c2)?.highlights ?: 0f, config.adjustments?.get(c2)?.shadows ?: 0f, config.adjustments?.get(c2)?.whites ?: 0f, config.adjustments?.get(c2)?.blacks ?: 0f, temp2.absolutePath, null, ccm, wb, config.zoomFactor, false, captureMetadata ?: top.maary.darkbag.models.CaptureMetadata())
                            if (dng2.scheme != "file") File(p).delete()
                        }
                        val stitchedBitmap = top.maary.darkbag.utils.HalfFrameUtils.stitchImages(temp1.absolutePath, temp2.absolutePath, currentGroup.hfLayout ?: "SBS", false)
                        temp1.delete()
                        temp2.delete()
                        if (stitchedBitmap != null) {
                            val finalBitmap = top.maary.darkbag.utils.HalfFrameUtils.addEffects(stitchedBitmap, config.showTimestamp, config.flareType >= 0, currentGroup.hfLayout ?: "SBS", t1, t2, config.flareType)
                            ImageSaver.saveProcessedImage(context = appContext, inputBitmap = finalBitmap, bmpPath = null, rotationDegrees = 0, zoomFactor = 1.0f, baseName = currentGroup.baseName, linearDngPath = null, saveJpg = true, saveRaw = false, editConfig = config, isAlreadyStitched = currentGroup.isHalfFrame(), captureMetadata = captureMetadata)
                            if (finalBitmap != stitchedBitmap) stitchedBitmap.recycle()
                            finalBitmap.recycle()
                        }
                    } else {
                        dngUri1?.let { uri ->
                            val targetFile = File(appContext.cacheDir, "export_temp.jpg")
                            val p = dngUriToPath(uri)
                            top.maary.darkbag.processor.ColorProcessor.exportHdrPlus(
                                tempRawPath = p, width = 0, height = 0, orientation = 0,
                                digitalGain = config.digitalGain, targetLog = logIndex,
                                lutPath = lutPath,
                                exposure = config.exposure, contrast = config.contrast, saturation = config.saturation,
                                highlights = config.highlights, shadows = config.shadows, whites = config.whites, blacks = config.blacks,
                                jpgPath = targetFile.absolutePath, dngPath = null,
                                ccm = ccm, whiteBalance = wb, zoomFactor = config.zoomFactor, mirror = false,
                                metadata = captureMetadata ?: top.maary.darkbag.models.CaptureMetadata()
                            )
                            if (uri.scheme != "file") File(p).delete()
                            ImageSaver.saveProcessedImage(context = appContext, inputBitmap = null, bmpPath = targetFile.absolutePath, rotationDegrees = 0, zoomFactor = 1.0f, baseName = currentGroup.baseName, linearDngPath = null, saveJpg = true, saveRaw = false, editConfig = config, isAlreadyStitched = currentGroup.isHalfFrame(), captureMetadata = captureMetadata)
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(appContext, "Exported JPG to Pictures", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("PlaygroundViewer", "Failed to export JPG", e)
            } finally {
                binding.initialLoadingIndicator.visibility = View.GONE
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