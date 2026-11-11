package top.maary.darkbag.viewer.metadata

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

/**
 * 图像详细信息与 EXIF 元数据 Bento 底部表单 (ViewerMetadataSheet).
 * 封装异步 EXIF 解析、硬件快门/光圈/ISO 计算、大小格式化及 M3 Bento 矩阵容器展示。
 * 遵循 Invariant 4 (零阻塞异步加载) 与 Invariant 5 (Bento Matrix & 16dp 圆角卡片规范)。
 */
class ViewerMetadataSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetImageDetailsBinding? = null
    private val binding get() = _binding!!

    companion object {
        const val TAG = "ViewerMetadataSheet"
        private const val ARG_URI = "arg_uri"

        fun newInstance(uri: Uri): ViewerMetadataSheet {
            return ViewerMetadataSheet().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_URI, uri)
                }
            }
        }

        fun show(fragmentManager: FragmentManager, uri: Uri) {
            newInstance(uri).show(fragmentManager, TAG)
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
            } catch (e: Exception) {
                null
            }
        }

        fun getFileName(context: Context, uri: Uri): String {
            return queryContentResolver(context, uri, OpenableColumns.DISPLAY_NAME)
                ?: uri.lastPathSegment ?: "Unknown"
        }

        fun getFileSize(context: Context, uri: Uri): String {
            val sizeStr = queryContentResolver(context, uri, OpenableColumns.SIZE)
            return sizeStr?.toLongOrNull()?.let { formatFileSize(it) } ?: "Unknown"
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
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
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

        if (uri == null) {
            dismiss()
            return
        }

        loadMetadata(uri)
    }

    private fun loadMetadata(uri: Uri) {
        val ctx = context ?: return
        val appContext = ctx.applicationContext

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val details = mutableListOf<Pair<String, String>>()
            val techDetails = mutableListOf<Pair<String, String>>()
            val configDetails = mutableListOf<Pair<String, String>>()

            try {
                appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)

                    // 1. Basic Info
                    val fileName = getFileName(appContext, uri)
                    details.add("File Name" to fileName)

                    val fileSize = getFileSize(appContext, uri)
                    details.add("File Size" to fileSize)

                    val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                    val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                    if (width > 0 && height > 0) {
                        details.add("Resolution" to "${width}x${height} (${String.format(Locale.US, "%.1f MP", width * height / 1000000f)})")
                    }

                    val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    dateStr?.let { details.add("Date & Time" to it) }

                    details.add("Path" to (uri.path ?: "Unknown"))

                    // 2. Technical Info
                    val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                    val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                    if (make != null || model != null) {
                        techDetails.add("Device" to "${make ?: ""} ${model ?: ""}".trim())
                    }

                    val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                        ?: exif.getAttribute("ISOSpeedRatings")
                    iso?.let { techDetails.add("ISO" to it) }

                    val exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                    if (exposureTime > 0) {
                        val expStr = if (exposureTime < 1.0) {
                            "1/${round(1.0 / exposureTime).toInt()}s"
                        } else {
                            "${String.format(Locale.US, "%.1f", exposureTime)}s"
                        }
                        techDetails.add("Shutter Speed" to expStr)
                    }

                    val fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
                    if (fNumber > 0) techDetails.add("Aperture" to "f/${fNumber}")

                    val focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                    if (focalLength > 0) techDetails.add("Focal Length" to "${focalLength}mm")

                    // 3. UserComment Configuration
                    val comment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                    if (comment?.startsWith("{") == true) {
                        try {
                            val json = JSONObject(comment)
                            val keys = json.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                if (key == "adjustments") continue
                                val value = json.get(key)
                                val label = key.replace("_", " ").replaceFirstChar {
                                    if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
                                }
                                configDetails.add(label to value.toString())
                            }
                        } catch (e: Exception) {
                            configDetails.add("Custom Info" to comment)
                        }
                    } else if (comment != null) {
                        configDetails.add("Custom Info" to comment)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error reading metadata details", e)
            }

            withContext(Dispatchers.Main) {
                if (_binding == null || !isAdded) return@withContext
                details.forEach { addDetailRow(binding.layoutBasicInfo, it.first, it.second) }
                techDetails.forEach { addDetailRow(binding.layoutTechInfo, it.first, it.second) }
                if (configDetails.isEmpty()) {
                    binding.cardConfigInfo.visibility = View.GONE
                    binding.tvConfigHeader.visibility = View.GONE
                } else {
                    configDetails.forEach { addDetailRow(binding.layoutConfigInfo, it.first, it.second) }
                }
            }
        }
    }

    private fun addDetailRow(container: ViewGroup, label: String, value: String) {
        val rowBinding = ItemDetailRowBinding.inflate(layoutInflater, container, true)
        rowBinding.tvLabel.text = label
        rowBinding.tvValue.text = value
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
