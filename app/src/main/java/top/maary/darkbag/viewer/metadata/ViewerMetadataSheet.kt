package top.maary.darkbag.viewer.metadata

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.maary.darkbag.R
import top.maary.darkbag.databinding.BottomSheetImageDetailsBinding
import top.maary.darkbag.databinding.ItemDetailRowBinding
import top.maary.darkbag.models.ImageGroup
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

class ViewerMetadataSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetImageDetailsBinding? = null
    private val binding get() = _binding!!

    companion object {
        const val TAG = "ViewerMetadataSheet"
        private const val ARG_URI = "arg_uri"
        private const val ARG_GROUP = "arg_group"

        fun newInstance(uri: Uri, group: ImageGroup? = null): ViewerMetadataSheet {
            return ViewerMetadataSheet().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_URI, uri)
                    if (group != null) putParcelable(ARG_GROUP, group)
                }
            }
        }

        fun show(fragmentManager: FragmentManager, uri: Uri, group: ImageGroup? = null) {
            newInstance(uri, group).show(fragmentManager, TAG)
        }

        fun formatFileSize(size: Long): String {
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
            return String.format(Locale.US, "%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
        }

        fun queryContentResolver(context: Context, uri: Uri, columnName: String): String? {
            return try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(columnName)
                        if (index != -1) cursor.getString(index) else null
                    } else null
                }
            } catch (_: Exception) {
                null
            }
        }

        fun getFileName(context: Context, uri: Uri): String {
            return queryContentResolver(context, uri, OpenableColumns.DISPLAY_NAME)
                ?: uri.lastPathSegment ?: context.getString(R.string.details_unknown)
        }

        fun getFileSize(context: Context, uri: Uri): String {
            val sizeStr = queryContentResolver(context, uri, OpenableColumns.SIZE)
            return sizeStr?.toLongOrNull()?.let { formatFileSize(it) } ?: context.getString(R.string.details_unknown)
        }

        internal fun formatNonDefaultAdjustments(adjJson: JSONObject): String {
            val parts = mutableListOf<String>()
            val keys = adjJson.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = adjJson.optDouble(k, 0.0)
                if (abs(v) > 0.001) {
                    val sign = if (v > 0) "+" else ""
                    val label = k.replace("_", " ").replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
                    }
                    parts.add("$label $sign${String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')}")
                }
            }
            return parts.joinToString(" · ")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetImageDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.let { window ->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                navigationBars.bottom + resources.getDimensionPixelSize(R.dimen.margin_medium)
            )
            insets
        }

        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelable(ARG_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getParcelable(ARG_URI)
        }
        val group: ImageGroup? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelable(ARG_GROUP, ImageGroup::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getParcelable(ARG_GROUP)
        }

        if (uri == null) {
            dismiss()
            return
        }

        bindFormatBadges(group)
        loadMetadata(uri, group)
    }

    private fun bindFormatBadges(group: ImageGroup?) {
        val container = binding.layoutFormatBadges
        container.removeAllViews()
        if (group == null) return
        val ctx = requireContext()
        val badges = mutableListOf<String>()
        if (group.hasMasterRaw) badges.add("DNG")
        if (group.jpgUri != null || group.derivativeJpgUris.isNotEmpty()) badges.add("JPG")
        if (group.isMotionPhoto) badges.add(ctx.getString(R.string.details_badge_live))
        if (group.isHalfFrame()) badges.add(ctx.getString(R.string.details_badge_hf))
        if (group.isMultiCamera) badges.add(ctx.getString(R.string.details_badge_multicam))
        if (group.isRawVideo) badges.add(ctx.getString(R.string.details_badge_rawvid))
        if (group.isCinemaDng) badges.add(ctx.getString(R.string.details_badge_cdng))

        val density = resources.displayMetrics.density
        badges.forEachIndexed { index, text ->
            val tv = TextView(ctx).apply {
                this.text = text
                textSize = 10f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.bg_bento_badge_primary)
                setPadding((7f * density).toInt(), (2f * density).toInt(), (7f * density).toInt(), (2f * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) marginStart = (4f * density).toInt()
                }
            }
            container.addView(tv)
        }
    }

    private fun loadMetadata(uri: Uri, group: ImageGroup?) {
        val ctx = context ?: return
        val appContext = ctx.applicationContext

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val overviewDetails = mutableListOf<Pair<String, String>>()
            val techExtraDetails = mutableListOf<Pair<String, String>>()
            val configDetails = mutableListOf<Pair<String, String>>()
            val assetDetails = mutableListOf<Pair<String, String>>()

            var focalMetric = "--"
            var apertureMetric = "--"
            var shutterMetric = "--"
            var isoMetric = "--"
            var copyablePath: String? = null
            var copyableGps: String? = null

            try {
                appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)

                    // 1. Overview Info
                    overviewDetails.add(appContext.getString(R.string.details_file_name) to getFileName(appContext, uri))
                    overviewDetails.add(appContext.getString(R.string.details_file_size) to getFileSize(appContext, uri))

                    val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                    val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                    if (width > 0 && height > 0) {
                        val mp = String.format(Locale.US, "%.1f MP", width * height / 1000000f)
                        val ratio = group?.aspectRatio?.let { " · $it" } ?: ""
                        overviewDetails.add(appContext.getString(R.string.details_resolution) to "${width}×${height} ($mp$ratio)")
                    }

                    val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    dateStr?.let { overviewDetails.add(appContext.getString(R.string.details_date_time) to it) }

                    // 2. Optics & Exposure Dashboard
                    val focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                    val focal35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
                    if (focalLength > 0) {
                        focalMetric = if (focal35 > 0) "${focal35}mm" else "${String.format(Locale.US, "%.1f", focalLength).trimEnd('0').trimEnd('.')}mm"
                    }

                    val fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
                    if (fNumber > 0) {
                        apertureMetric = "f/${String.format(Locale.US, "%.1f", fNumber).trimEnd('0').trimEnd('.')}"
                    }

                    val exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                    if (exposureTime > 0) {
                        shutterMetric = if (exposureTime < 1.0) {
                            "1/${round(1.0 / exposureTime).toInt()}s"
                        } else {
                            "${String.format(Locale.US, "%.1f", exposureTime)}s"
                        }
                    }

                    val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                        ?: exif.getAttribute("ISOSpeedRatings")
                    if (!iso.isNullOrBlank()) isoMetric = iso

                    val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                    val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                    if (make != null || model != null) {
                        techExtraDetails.add(appContext.getString(R.string.details_device) to "${make ?: ""} ${model ?: ""}".trim())
                    }
                    val colorSpaceVal = exif.getAttributeInt(ExifInterface.TAG_COLOR_SPACE, -1)
                    if (colorSpaceVal == 1) {
                        techExtraDetails.add(appContext.getString(R.string.details_color_space) to "sRGB")
                    } else if (colorSpaceVal == ExifInterface.COLOR_SPACE_UNCALIBRATED) {
                        techExtraDetails.add(appContext.getString(R.string.details_color_space) to "Uncalibrated / Wide")
                    }

                    // 3. Darkbag Recipe & Pipeline (UserComment JSON + EditConfig)
                    val comment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                    if (comment?.startsWith("{") == true) {
                        try {
                            val json = JSONObject(comment)
                            parseSemanticConfigJson(appContext, json, configDetails)
                        } catch (_: Exception) {
                            configDetails.add(appContext.getString(R.string.details_custom_info) to comment)
                        }
                    } else if (!comment.isNullOrBlank()) {
                        configDetails.add(appContext.getString(R.string.details_custom_info) to comment)
                    } else if (group?.editConfig != null) {
                        val cfg = group.editConfig
                        val recipe = listOfNotNull(
                            cfg.log?.takeIf { it != "None" && it.isNotBlank() },
                            cfg.lut?.takeIf { it != "None" && it.isNotBlank() }
                        ).joinToString(" + ")
                        if (recipe.isNotEmpty()) {
                            configDetails.add(appContext.getString(R.string.details_recipe_label) to recipe)
                        }
                    }

                    // 4. Assets & Location
                    if (group != null) {
                        if (group.photoMasterUris.isNotEmpty()) {
                            val rawSize = group.photoMasterUris.joinToString(" + ") { getFileSize(appContext, it) }
                            assetDetails.add(appContext.getString(R.string.details_asset_raw) to rawSize)
                        }
                        if (group.jpgUri != null) {
                            assetDetails.add(appContext.getString(R.string.details_asset_jpg) to getFileSize(appContext, group.jpgUri))
                        }
                        if (group.isMotionPhoto && group.motionPhotoVideoLength > 0) {
                            assetDetails.add(appContext.getString(R.string.details_asset_motion) to formatFileSize(group.motionPhotoVideoLength))
                        }
                        if (group.derivativeJpgUris.size > 1) {
                            assetDetails.add(appContext.getString(R.string.details_asset_derivatives) to "${group.derivativeJpgUris.size}")
                        }
                    }

                    copyablePath = uri.path ?: uri.toString()
                    val latLong = exif.latLong
                    if (latLong != null) {
                        copyableGps = String.format(Locale.US, "%.5f, %.5f", latLong[0], latLong[1])
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error reading metadata details", e)
            }

            // 5. RGB Histogram
            val histogramUri = group?.jpgUri ?: uri
            val histogram = HistogramCalculator.decodeSampledHistogram(appContext, histogramUri)

            withContext(Dispatchers.Main) {
                if (_binding == null || !isAdded) return@withContext

                binding.tvMetricFocal.text = focalMetric
                binding.tvMetricAperture.text = apertureMetric
                binding.tvMetricShutter.text = shutterMetric
                binding.tvMetricIso.text = isoMetric

                overviewDetails.forEach { addDetailRow(binding.layoutBasicInfo, it.first, it.second) }

                if (techExtraDetails.isEmpty()) {
                    binding.cardTechExtra.visibility = View.GONE
                } else {
                    techExtraDetails.forEach { addDetailRow(binding.layoutTechInfo, it.first, it.second) }
                }

                if (histogram != null) {
                    binding.trayHistogram.visibility = View.VISIBLE
                    binding.rgbHistogramView.setHistogramData(histogram)
                } else {
                    binding.trayHistogram.visibility = View.GONE
                }

                if (configDetails.isEmpty()) {
                    binding.cardConfigInfo.visibility = View.GONE
                } else {
                    configDetails.forEach { addDetailRow(binding.layoutConfigInfo, it.first, it.second) }
                }

                assetDetails.forEach { addDetailRow(binding.layoutAssetsInfo, it.first, it.second) }
                copyablePath?.let { pathStr ->
                    addCopyableRow(binding.layoutAssetsInfo, appContext.getString(R.string.details_path), pathStr)
                }
                copyableGps?.let { gpsStr ->
                    addCopyableRow(binding.layoutAssetsInfo, appContext.getString(R.string.details_location), gpsStr)
                }
            }
        }
    }

    private fun parseSemanticConfigJson(
        appContext: Context,
        json: JSONObject,
        out: MutableList<Pair<String, String>>
    ) {
        val handledKeys = mutableSetOf<String>()
        val logVal = json.optString("log", "").takeIf { it.isNotBlank() && it != "None" }
        val lutVal = json.optString("lut", "").takeIf { it.isNotBlank() && it != "None" }
        if (logVal != null || lutVal != null) {
            out.add(appContext.getString(R.string.details_recipe_label) to listOfNotNull(logVal, lutVal).joinToString(" + "))
        }
        handledKeys.addAll(listOf("log", "lut"))

        val semanticMap = mapOf(
            "color_engine" to R.string.details_color_engine,
            "hdr_fusion" to R.string.details_hdr_fusion,
            "wb_strategy" to R.string.details_wb_strategy
        )
        semanticMap.forEach { (key, resId) ->
            if (json.has(key)) {
                val value = json.optString(key, "")
                if (value.isNotBlank()) out.add(appContext.getString(resId) to value)
                handledKeys.add(key)
            }
        }

        val adjObj = json.optJSONObject("adjustments")
        if (adjObj != null) {
            val adjSummary = formatNonDefaultAdjustments(adjObj)
            if (adjSummary.isNotBlank()) {
                out.add(appContext.getString(R.string.details_adjustments) to adjSummary)
            }
        }
        handledKeys.add("adjustments")

        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in handledKeys) continue
            val value = json.get(key)
            val label = key.replace("_", " ").replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
            }
            out.add(label to value.toString())
        }
    }

    private fun addDetailRow(container: ViewGroup, label: String, value: String) {
        val rowBinding = ItemDetailRowBinding.inflate(layoutInflater, container, true)
        rowBinding.tvLabel.text = label
        rowBinding.tvValue.text = value
    }

    private fun addCopyableRow(container: ViewGroup, label: String, value: String) {
        val rowBinding = ItemDetailRowBinding.inflate(layoutInflater, container, true)
        rowBinding.tvLabel.text = "$label (${getString(R.string.details_tap_to_copy)})"
        rowBinding.tvValue.text = value
        rowBinding.root.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
            Toast.makeText(requireContext(), R.string.details_copied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
