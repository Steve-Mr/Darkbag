package top.maary.darkbag.viewer.metadata

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
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
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.maary.darkbag.R
import top.maary.darkbag.databinding.BottomSheetImageDetailsBinding
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.ui.bento.BentoRowLayout
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

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

        internal fun extractAdjustmentChips(adjJson: JSONObject): List<String> {
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
            return parts
        }

        internal fun formatNonDefaultAdjustments(adjJson: JSONObject): String {
            return extractAdjustmentChips(adjJson).joinToString(" · ")
        }

        internal fun splitExifDateTime(raw: String?): Pair<String, String> {
            if (raw.isNullOrBlank()) return "--" to "--"
            val parts = raw.trim().split(Regex("\\s+"), limit = 2)
            val date = parts[0].replace(':', '-')
            val time = parts.getOrNull(1) ?: ""
            return date to time
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
        bindHistogramLegend()
        loadMetadata(uri, group)
    }

    private fun bindHistogramLegend() {
        val sb = SpannableStringBuilder()
        fun appendDot(label: String, color: Int) {
            if (sb.isNotEmpty()) sb.append("  ")
            val start = sb.length
            sb.append("● ")
            sb.setSpan(ForegroundColorSpan(color), start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(label)
        }
        appendDot("R", 0xFFEF5350.toInt())
        appendDot("G", 0xFF66BB6A.toInt())
        appendDot("B", 0xFF42A5F5.toInt())
        binding.tvHistogramLegend.text = sb
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
                setTypeface(typeface, Typeface.BOLD)
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
            var fileName = getFileName(appContext, uri)
            var fileSize = getFileSize(appContext, uri)
            var resMain = "--"
            var resSub = ""
            var capturedDate = "--"
            var capturedTime = ""

            var focalMetric = "--"
            var apertureMetric = "--"
            var shutterMetric = "--"
            var isoMetric = "--"
            var deviceValue: String? = null
            var colorSpaceValue: String? = null

            var recipeHero: String? = null
            val pipelineTiles = mutableListOf<Pair<String, String>>()
            val adjustmentChips = mutableListOf<String>()
            val assetTiles = mutableListOf<Pair<String, String>>()
            var copyablePath: String? = null
            var copyableGps: String? = null

            try {
                appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)

                    // 1. Overview Info
                    val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                    val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                    if (width > 0 && height > 0) {
                        resMain = "$width × $height"
                        val mp = String.format(Locale.US, "%.1f MP", width * height / 1000000f)
                        val ratio = group?.aspectRatio?.let { " · $it" } ?: ""
                        resSub = "$mp$ratio"
                    }

                    val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    val (d, t) = splitExifDateTime(dateStr)
                    capturedDate = d
                    capturedTime = t

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
                        deviceValue = "${make ?: ""} ${model ?: ""}".trim()
                    }
                    val csVal = exif.getAttributeInt(ExifInterface.TAG_COLOR_SPACE, -1)
                    colorSpaceValue = when (csVal) {
                        1 -> "sRGB"
                        ExifInterface.COLOR_SPACE_UNCALIBRATED -> "Uncalibrated / Wide"
                        else -> null
                    }

                    // 3. Darkbag Recipe & Pipeline (UserComment JSON + EditConfig)
                    val comment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                    if (comment?.startsWith("{") == true) {
                        try {
                            val json = JSONObject(comment)
                            val logVal = json.optString("log", "").takeIf { it.isNotBlank() && it != "None" }
                            val lutVal = json.optString("lut", "").takeIf { it.isNotBlank() && it != "None" }
                            if (logVal != null || lutVal != null) {
                                recipeHero = listOfNotNull(logVal, lutVal).joinToString(" + ")
                            }
                            parseSemanticConfigJson(appContext, json, pipelineTiles, adjustmentChips)
                        } catch (_: Exception) {
                            pipelineTiles.add(appContext.getString(R.string.details_custom_info) to comment)
                        }
                    } else if (!comment.isNullOrBlank()) {
                        pipelineTiles.add(appContext.getString(R.string.details_custom_info) to comment)
                    } else if (group?.editConfig != null) {
                        val cfg = group.editConfig
                        val recipe = listOfNotNull(
                            cfg.log?.takeIf { it != "None" && it.isNotBlank() },
                            cfg.lut?.takeIf { it != "None" && it.isNotBlank() }
                        ).joinToString(" + ")
                        if (recipe.isNotEmpty()) {
                            recipeHero = recipe
                        }
                    }

                    // 4. Assets & Location
                    if (group != null) {
                        if (group.photoMasterUris.isNotEmpty()) {
                            val rawSize = group.photoMasterUris.joinToString(" + ") { getFileSize(appContext, it) }
                            assetTiles.add(appContext.getString(R.string.details_asset_raw) to rawSize)
                        }
                        if (group.jpgUri != null) {
                            assetTiles.add(appContext.getString(R.string.details_asset_jpg) to getFileSize(appContext, group.jpgUri))
                        }
                        if (group.isMotionPhoto && group.motionPhotoVideoLength > 0) {
                            assetTiles.add(appContext.getString(R.string.details_asset_motion) to formatFileSize(group.motionPhotoVideoLength))
                        }
                        if (group.derivativeJpgUris.size > 1) {
                            assetTiles.add(appContext.getString(R.string.details_asset_derivatives) to "${group.derivativeJpgUris.size}")
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

                // Tray 1: Overview
                binding.tvFileName.text = fileName
                binding.tvFileSizeBadge.text = fileSize
                binding.tileFileIdentity.setOnClickListener {
                    copyToClipboard(getString(R.string.details_file_name), fileName)
                }
                binding.tvResolutionMain.text = resMain
                binding.tvResolutionSub.text = resSub
                binding.tvResolutionSub.visibility = if (resSub.isBlank()) View.GONE else View.VISIBLE
                binding.tvCapturedDate.text = capturedDate
                binding.tvCapturedTime.text = capturedTime
                binding.tvCapturedTime.visibility = if (capturedTime.isBlank()) View.GONE else View.VISIBLE

                // Tray 2: Optics & Exposure
                binding.tvMetricFocal.text = focalMetric
                binding.tvMetricAperture.text = apertureMetric
                binding.tvMetricShutter.text = shutterMetric
                binding.tvMetricIso.text = isoMetric

                if (deviceValue == null && colorSpaceValue == null) {
                    binding.rowTechExtra.visibility = View.GONE
                } else {
                    binding.rowTechExtra.visibility = View.VISIBLE
                    binding.tileDevice.visibility = if (deviceValue != null) View.VISIBLE else View.GONE
                    binding.tvDeviceValue.text = deviceValue ?: ""
                    binding.tileColorSpace.visibility = if (colorSpaceValue != null) View.VISIBLE else View.GONE
                    binding.tvColorSpaceValue.text = colorSpaceValue ?: ""
                }

                // Tray 3: Histogram
                if (histogram != null) {
                    binding.trayHistogram.visibility = View.VISIBLE
                    binding.rgbHistogramView.setHistogramData(histogram)
                } else {
                    binding.trayHistogram.visibility = View.GONE
                }

                // Tray 4: Darkbag Recipe & Pipeline
                val hasRecipeSection = recipeHero != null || pipelineTiles.isNotEmpty() || adjustmentChips.isNotEmpty()
                binding.cardConfigInfo.visibility = if (hasRecipeSection) View.VISIBLE else View.GONE
                if (hasRecipeSection) {
                    if (recipeHero != null) {
                        binding.tileRecipeHero.visibility = View.VISIBLE
                        binding.tvRecipeHeroValue.text = recipeHero
                    } else {
                        binding.tileRecipeHero.visibility = View.GONE
                    }
                    renderTwoColumnTileGrid(
                        binding.layoutPipelineGrid,
                        pipelineTiles,
                        addBottomGap = adjustmentChips.isNotEmpty()
                    )
                    if (adjustmentChips.isNotEmpty()) {
                        binding.layoutAdjustmentsContainer.visibility = View.VISIBLE
                        renderAdjustmentChips(adjustmentChips)
                    } else {
                        binding.layoutAdjustmentsContainer.visibility = View.GONE
                    }
                }

                // Tray 5: Assets & Location
                renderTwoColumnTileGrid(binding.layoutAssetsGrid, assetTiles, addBottomGap = false)
                val pathValue = copyablePath
                if (pathValue != null) {
                    binding.tileStoragePath.visibility = View.VISIBLE
                    binding.tvStoragePathValue.text = pathValue
                    binding.tileStoragePath.setOnClickListener {
                        copyToClipboard(getString(R.string.details_path), pathValue)
                    }
                } else {
                    binding.tileStoragePath.visibility = View.GONE
                }
                val gpsValue = copyableGps
                if (gpsValue != null) {
                    binding.tileGpsLocation.visibility = View.VISIBLE
                    binding.tvGpsValue.text = gpsValue
                    binding.tileGpsLocation.setOnClickListener {
                        copyToClipboard(getString(R.string.details_location), gpsValue)
                    }
                } else {
                    binding.tileGpsLocation.visibility = View.GONE
                }
            }
        }
    }

    private fun parseSemanticConfigJson(
        appContext: Context,
        json: JSONObject,
        outTiles: MutableList<Pair<String, String>>,
        outChips: MutableList<String>
    ) {
        val handledKeys = mutableSetOf("log", "lut", "adjustments")
        val semanticMap = mapOf(
            "color_engine" to R.string.details_color_engine,
            "hdr_fusion" to R.string.details_hdr_fusion,
            "wb_strategy" to R.string.details_wb_strategy
        )
        semanticMap.forEach { (key, resId) ->
            if (json.has(key)) {
                val value = json.optString(key, "")
                if (value.isNotBlank()) outTiles.add(appContext.getString(resId) to value)
                handledKeys.add(key)
            }
        }

        val adjObj = json.optJSONObject("adjustments")
        if (adjObj != null) {
            outChips.addAll(extractAdjustmentChips(adjObj))
        }

        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in handledKeys) continue
            val value = json.get(key)
            val label = key.replace("_", " ").replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
            }
            outTiles.add(label to value.toString())
        }
    }

    private fun renderTwoColumnTileGrid(
        container: LinearLayout,
        items: List<Pair<String, String>>,
        addBottomGap: Boolean
    ) {
        container.removeAllViews()
        if (items.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val gap8dp = (8f * density).roundToInt()

        val colorSurface = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val colorOnSurface = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val colorOnSurfaceVariant = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)

        val rows = items.chunked(2)
        rows.forEachIndexed { rowIdx, pair ->
            val isLastRow = rowIdx == rows.lastIndex
            val row = BentoRowLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (!isLastRow || addBottomGap) bottomMargin = gap8dp
                }
            }
            pair.forEach { (label, value) ->
                row.addView(buildPorcelainMetricTile(label, value, density, colorSurface, colorOnSurface, colorOnSurfaceVariant))
            }
            container.addView(row)
        }
    }

    private fun buildPorcelainMetricTile(
        label: String,
        value: String,
        density: Float,
        bgColor: Int,
        fgColor: Int,
        labelColor: Int
    ): MaterialCardView {
        val pad = (11f * density).roundToInt()
        return MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            radius = 14f * density
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(bgColor)

            val inner = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }
            val tvLabel = TextView(requireContext()).apply {
                text = label.uppercase(Locale.getDefault())
                textSize = 9.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(labelColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val tvValue = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (2f * density).roundToInt()
                }
                text = value
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(fgColor)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            inner.addView(tvLabel)
            inner.addView(tvValue)
            addView(inner)
        }
    }

    private fun renderAdjustmentChips(chips: List<String>) {
        val group = binding.chipGroupAdjustments
        group.removeAllViews()
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val bg = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val fg = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val padH = (10f * density).roundToInt()
        val padV = (5f * density).roundToInt()

        chips.forEach { text ->
            val card = MaterialCardView(ctx).apply {
                radius = 12f * density
                cardElevation = 0f
                strokeWidth = 0
                setCardBackgroundColor(bg)
                val tv = TextView(ctx).apply {
                    this.text = text
                    textSize = 10.5f
                    typeface = Typeface.MONOSPACE
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(fg)
                    setPadding(padH, padV, padH, padV)
                }
                addView(tv)
            }
            group.addView(card)
        }
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(requireContext(), R.string.details_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
