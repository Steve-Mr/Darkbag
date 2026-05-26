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
            val baseName = File(firstPath).nameWithoutExtension
            val playgroundDir = File(requireContext().filesDir, "playground_dngs")
            val potentialJpg = File(playgroundDir, "$baseName.jpg")
            val jpgUri = if (potentialJpg.exists()) Uri.fromFile(potentialJpg) else null

            val group = if (playgroundPaths.size == 1) {
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
                val layout = arguments?.getString("playground_hf_layout") ?: "SBS"
                val mergedBaseName = baseName + "_merged"
                val potentialMergedJpg = File(playgroundDir, "$mergedBaseName.jpg")
                val mergedJpgUri = if (potentialMergedJpg.exists()) Uri.fromFile(potentialMergedJpg) else null

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
                    }
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

        // We override the Save Menu to include an explicit "Export to JPG" option,
        // while retaining the "Share as TIFF" option which acts as the intermediate output requested.
        binding.btnSaveMenu.setOnClickListener {
            binding.btnSaveMenu.isCheckable = true
            binding.btnSaveMenu.isChecked = true
            val popup = PopupMenu(requireContext(), it)

            // Add native "Save as new file" which saves into sandbox
            popup.menu.add(0, 1001, 0, "Save as new file").apply {
                setIcon(R.drawable.ic_save_as)
            }
            // Add original "Share as TIFF" intermediate
            popup.menu.add(0, 1002, 0, getString(R.string.share_as_tiff)).apply {
                setIcon(R.drawable.ic_photo)
            }
            // Add custom "Export to JPG"
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
                    1002 -> performShareAsTiff() // Keeps TIFF capability
                    1003 -> exportToJpg() // Exports standard JPG to MediaStore
                }
                true
            }
            popup.setOnDismissListener { binding.btnSaveMenu.isChecked = false }
            popup.show()
        }
    }

    override fun saveEdit(isReplacement: Boolean) {
        val config = currentEditConfig ?: return
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        val dngUri1 = currentGroup.dngUri ?: currentGroup.dngUri1
        val dngUri2 = currentGroup.dngUri2

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
                        val context = requireContext()
                        val logIndex = top.maary.darkbag.fragments.SettingsFragment.LOG_CURVES.indexOf(config.log)
                        val lutPath = if (config.lut != null && config.lut != "None") {
                            File(lutManager.lutDir, config.lut).absolutePath
                        } else null

                        fun processFull(bytes: ByteArray?, uri: Uri, index: Int): android.graphics.Bitmap? {
                            val finalBytes = bytes ?: run {
                                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                    java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                                }
                            } ?: return null
                            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeByteArray(finalBytes, 0, finalBytes.size, options)
                            val orientation = try {
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    androidx.exifinterface.media.ExifInterface(input).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                                } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                            } catch (e: Exception) { androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL }

                            val rotDegrees = when(orientation) {
                                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                                else -> 0
                            }
                            val fullW = if (rotDegrees == 90 || rotDegrees == 270) options.outHeight else options.outWidth
                            val fullH = if (rotDegrees == 90 || rotDegrees == 270) options.outWidth else options.outHeight
                            val bmpW = (fullW / config.zoomFactor).toInt()
                            val bmpH = (fullH / config.zoomFactor).toInt()
                            val previewBitmap = android.graphics.Bitmap.createBitmap(bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888)

                            val basicAdj = top.maary.darkbag.models.BasicAdjustments(
                                config.exposure, config.contrast, config.saturation, config.highlights, config.shadows, config.whites, config.blacks
                            )
                            val adj = if (currentGroup.isHalfFrame()) config.adjustments?.get(index) ?: top.maary.darkbag.models.BasicAdjustments() else basicAdj

                            val meta = repository.getCaptureMetadata(uri)
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
                                digitalGain = 1.0f,
                                outputJpgPath = null,
                                outputTiffPath = null,
                                useGpu = false,
                                orientation = rotDegrees,
                                mirror = false,
                                outputBitmap = previewBitmap,
                                downsampleFactor = 1,
                                zoomFactor = config.zoomFactor,
                                metadata = meta
                            )
                            return previewBitmap
                        }

                        val finalBitmap = if (!currentGroup.isHalfFrame()) {
                            val primaryUri = dngUri1 ?: dngUri2 ?: return@withContext null
                            val primaryBytes = if (dngUri1 != null) sourceDngBytes else sourceDngBytes2
                            processFull(primaryBytes, primaryUri, 0)
                        } else {
                            val f1 = dngUri1?.let { processFull(sourceDngBytes, it, 0) }
                            val f2 = dngUri2?.let { processFull(sourceDngBytes2, it, 1) }

                            val b1 = if (config.isSwapped) f2 else f1
                            val b2 = if (config.isSwapped) f1 else f2

                            if (b1 != null || b2 != null) {
                                val isSBS = currentGroup.hfLayout != "TB"
                                val gap = top.maary.darkbag.utils.HalfFrameUtils.calculateGap(maxOf(b1?.width ?: 0, b1?.height ?: 0)).toFloat()
                                val w1 = b1?.width ?: b2?.width ?: 0
                                val h1 = b1?.height ?: b2?.height ?: 0
                                val w2 = b2?.width ?: w1
                                val h2 = b2?.height ?: h1
                                val resW = if (isSBS) (w1 + gap + w2).toInt() else maxOf(w1, w2)
                                val resH = if (isSBS) maxOf(h1, h2) else (h1 + gap + h2).toInt()

                                val composite = android.graphics.Bitmap.createBitmap(resW, resH, android.graphics.Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(composite)
                                canvas.drawColor(android.graphics.Color.BLACK)
                                b1?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                                b2?.let {
                                    if (isSBS) canvas.drawBitmap(it, w1 + gap, 0f, null)
                                    else canvas.drawBitmap(it, 0f, h1 + gap, null)
                                }
                                val finalComposite = top.maary.darkbag.utils.HalfFrameUtils.addEffects(
                                    composite,
                                    config.showTimestamp,
                                    config.flareType >= 0,
                                    currentGroup.hfLayout ?: "SBS",
                                    time1 = currentGroup.captureTime,
                                    time2 = currentGroup.captureTime,
                                    flareType = config.flareType
                                )
                                if (finalComposite != composite) {
                                    composite.recycle()
                                }
                                f1?.recycle()
                                f2?.recycle()
                                finalComposite
                            } else null
                        }

                        finalBitmap?.let { bitmap ->
                            newBaseName = if (isReplacement) currentGroup.baseName else "${currentGroup.baseName}_edited_${System.currentTimeMillis()}"

                            val playgroundDir = File(context.filesDir, "playground_dngs")
                            if (!playgroundDir.exists()) playgroundDir.mkdirs()

                            val targetFile = File(playgroundDir, "${newBaseName}.jpg")

                            java.io.FileOutputStream(targetFile).use { out ->
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                            }

                            finalJpgUri = Uri.fromFile(targetFile)

                            val captureMetadata = (currentGroup.jpgUri ?: currentGroup.dngUri ?: currentGroup.dngUri1)?.let { repository.getCaptureMetadata(it) }

                            top.maary.darkbag.utils.ImageSaver.writeMetadataToExif(context, finalJpgUri!!, config, captureMetadata)

                            if (!isReplacement && !currentGroup.isHalfFrame() && dngUri1 != null) {
                                val newDngFile = File(playgroundDir, "${newBaseName}.dng")
                                context.contentResolver.openInputStream(dngUri1)?.use { input ->
                                    java.io.FileOutputStream(newDngFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            } else if (!isReplacement && currentGroup.isHalfFrame()) {
                                dngUri1?.let { uri ->
                                    val newDngFile1 = File(playgroundDir, "${newBaseName}_1.dng")
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        java.io.FileOutputStream(newDngFile1).use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                }
                                dngUri2?.let { uri ->
                                    val newDngFile2 = File(playgroundDir, "${newBaseName}_2.dng")
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        java.io.FileOutputStream(newDngFile2).use { output ->
                                            input.copyTo(output)
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
                        currentList[currentIndex] = currentGroup.copy(jpgUri = finalJpgUri, baseName = newBaseName)
                        adapter.updateGroups(currentList.toList())
                    } else {
                        val newDngUri1 = if (!currentGroup.isHalfFrame()) Uri.fromFile(File(File(requireContext().filesDir, "playground_dngs"), "${newBaseName}.dng")) else Uri.fromFile(File(File(requireContext().filesDir, "playground_dngs"), "${newBaseName}_1.dng"))
                        val newDngUri2 = if (currentGroup.isHalfFrame()) Uri.fromFile(File(File(requireContext().filesDir, "playground_dngs"), "${newBaseName}_2.dng")) else null

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
                requireActivity().onBackPressedDispatcher.onBackPressed()
            } else {
                adapter.updateGroups(currentList.toList())
                updateControlsVisibility()
            }
        }
    }

    private fun exportToJpg() {
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)

        if (isEditingAdjustments) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Export to JPG")
                .setMessage("Save adjustments and export to JPG?")
                .setPositiveButton("Save & Export") { _, _ ->
                    saveEdit(isReplacement = true)
                    processAndExportJpg()
                }
                .setNegativeButton("Export Without Saving") { _, _ ->
                    processAndExportJpg()
                }
                .setNeutralButton(top.maary.darkbag.R.string.cancel, null)
                .show()
        } else {
            processAndExportJpg()
        }
    }

    private fun processAndExportJpg() {
        val currentGroup = adapter.getGroup(binding.imagePager.currentItem)
        binding.initialLoadingIndicator.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val context = requireContext()
                    val jpgUri = currentGroup.jpgUri
                    if (jpgUri != null && jpgUri.scheme == "file") {
                        val file = File(jpgUri.path!!)
                        if (file.exists()) {
                            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                            if (bitmap != null) {
                                ImageSaver.saveProcessedImage(
                                    context = context,
                                    inputBitmap = bitmap,
                                    bmpPath = null,
                                    rotationDegrees = 0,
                                    zoomFactor = 1.0f,
                                    baseName = currentGroup.baseName,
                                    linearDngPath = null,
                                    saveJpg = true,
                                    saveRaw = false
                                )
                                bitmap.recycle()
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(requireContext(), "Exported JPG to Pictures", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("PlaygroundViewer", "Failed to export JPG", e)
            } finally {
                binding.initialLoadingIndicator.visibility = View.GONE
            }
        }
    }
}
