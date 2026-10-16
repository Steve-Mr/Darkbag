package top.maary.darkbag.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.utils.LutManager
import kotlin.math.roundToInt

/**
 * Material 3 modal BottomSheet helper for Darkbag's Modular Darkroom Bento Matrix.
 * Allows photographers to preview and select Log curves and 3D LUT recipes without
 * camera preview layout shifts or jumps.
 */
object ColorRecipeBottomSheet {

    private data class LutEntry(
        val displayName: String,
        val fileName: String?
    )

    /**
     * Custom RecyclerView that caps its measured height to a percentage of the display height,
     * preventing oversized sheets on large LUT collections while hugging content when small.
     */
    private class MaxHeightRecyclerView(context: Context) : RecyclerView(context) {
        var maxHeightPx: Int = (context.resources.displayMetrics.heightPixels * 0.48).toInt()

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val customHeightSpec = if (maxHeightPx > 0) {
                MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            } else {
                heightSpec
            }
            super.onMeasure(widthSpec, customHeightSpec)
        }
    }

    /**
     * Shows the Color Recipe BottomSheet.
     *
     * @param context Host Android context.
     * @param lutManager Darkbag LUT manager providing available `.cube` files.
     * @param onRecipeSelected Callback triggered whenever the log curve or LUT selection changes.
     */
    fun show(
        context: Context,
        lutManager: LutManager,
        onRecipeSelected: (logName: String, lutFileName: String?) -> Unit
    ): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
        val density = context.resources.displayMetrics.density

        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        var currentLog = prefs.getString(SettingsFragment.KEY_TARGET_LOG, "None") ?: "None"
        var currentLut = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, null)

        // Dynamic Material 3 colors
        val colorSurfaceContainerLow = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurfaceContainerLow,
            Color.DKGRAY
        )
        val colorSurfaceContainerHigh = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            Color.GRAY
        )
        val colorSurfaceContainerHighest = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurfaceContainerHighest,
            Color.LTGRAY
        )
        val colorPrimaryContainer = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorPrimaryContainer,
            Color.DKGRAY
        )
        val colorOnPrimaryContainer = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnPrimaryContainer,
            Color.WHITE
        )
        val colorSecondaryContainer = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSecondaryContainer,
            Color.DKGRAY
        )
        val colorOnSecondaryContainer = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSecondaryContainer,
            Color.WHITE
        )
        val colorPrimary = MaterialColors.getColor(
            context,
            android.R.attr.colorPrimary,
            Color.WHITE
        )
        val colorOnSurface = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurface,
            Color.WHITE
        )
        val colorOnSurfaceVariant = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.LTGRAY
        )
        val colorOutlineVariant = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOutlineVariant,
            Color.GRAY
        )

        // Root container with M3 rounded top corners
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val topRadius = 28f * density
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    topRadius, topRadius,
                    topRadius, topRadius,
                    0f, 0f,
                    0f, 0f
                )
                setColor(colorSurfaceContainerLow)
            }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Apply Edge-to-Edge window insets
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                (12 * density).toInt() + navBars.bottom
            )
            insets
        }

        // 1. Top Drag Handle (36dp x 4dp pill)
        val dragHandle = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (36 * density).roundToInt(),
                (4 * density).roundToInt()
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (12 * density).roundToInt()
                bottomMargin = (8 * density).roundToInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 2f * density
                setColor(colorOutlineVariant)
            }
        }
        rootLayout.addView(dragHandle)

        // Header Row: Title & Reset Button
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val hPad = (16 * density).roundToInt()
                val vPad = (4 * density).roundToInt()
                setPadding(hPad, vPad, hPad, vPad)
            }
        }

        val titleView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f
            )
            text = "COLOR RECIPE"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
            setTextColor(colorOnSurface)
        }
        headerLayout.addView(titleView)

        val resetButton = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonTonalStyle
        ).apply {
            text = "Reset"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorOnSurface)
            backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHighest)
            cornerRadius = (14 * density).roundToInt()
            insetTop = 0
            insetBottom = 0
            minHeight = (32 * density).roundToInt()
            minimumHeight = (32 * density).roundToInt()
            val hPad = (12 * density).roundToInt()
            val vPad = (4 * density).roundToInt()
            setPadding(hPad, vPad, hPad, vPad)
        }
        headerLayout.addView(resetButton)
        rootLayout.addView(headerLayout)

        // 2. Section: BASE LOG CURVE
        val logSectionTitle = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val hPad = (16 * density).roundToInt()
                val topPad = (12 * density).roundToInt()
                val btmPad = (6 * density).roundToInt()
                setPadding(hPad, topPad, hPad, btmPad)
            }
            text = "BASE LOG CURVE"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
            setTextColor(colorOnSurfaceVariant)
        }
        rootLayout.addView(logSectionTitle)

        val logScrollView = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding((12 * density).roundToInt(), 0, (12 * density).roundToInt(), (4 * density).roundToInt())
        }

        val logButtonsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        logScrollView.addView(logButtonsContainer)
        rootLayout.addView(logScrollView)

        // 3. Section: 3D LUT (.CUBE)
        val lutSectionTitle = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val hPad = (16 * density).roundToInt()
                val topPad = (14 * density).roundToInt()
                val btmPad = (6 * density).roundToInt()
                setPadding(hPad, topPad, hPad, btmPad)
            }
            text = "3D LUT (.CUBE)"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
            setTextColor(colorOnSurfaceVariant)
        }
        rootLayout.addView(lutSectionTitle)

        // 2-Column RecyclerView for Bento LUT tiles
        val lutRecyclerView = MaxHeightRecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 2)
            clipToPadding = false
            val hPad = (12 * density).roundToInt()
            val bPad = (8 * density).roundToInt()
            setPadding(hPad, 0, hPad, bPad)
        }
        rootLayout.addView(lutRecyclerView)

        // Build LUT dataset
        val lutEntries = mutableListOf<LutEntry>()
        lutEntries.add(LutEntry(displayName = "None", fileName = null))
        lutManager.getLuts().forEach { file ->
            val display = CameraBentoDeckView.formatRecipeLabel(file.name, file.nameWithoutExtension)
            lutEntries.add(LutEntry(displayName = display, fileName = file.name))
        }

        // Helper to check if an entry matches current active LUT
        fun isEntryActive(entry: LutEntry, activeLut: String?): Boolean {
            if (entry.fileName == null) {
                return activeLut.isNullOrBlank() || activeLut.equals("None", ignoreCase = true)
            }
            if (activeLut.isNullOrBlank() || activeLut.equals("None", ignoreCase = true)) {
                return false
            }
            return activeLut.equals(entry.fileName, ignoreCase = true) ||
                   activeLut.substringBeforeLast(".").equals(entry.fileName.substringBeforeLast("."), ignoreCase = true)
        }

        // Adapter for LUT Grid
        lateinit var lutAdapter: RecyclerView.Adapter<*>

        val updateLogButtonsUi = {
            for (i in 0 until logButtonsContainer.childCount) {
                val btn = logButtonsContainer.getChildAt(i) as? MaterialButton ?: continue
                val logName = btn.tag as? String ?: continue
                val isActive = logName == currentLog
                if (isActive) {
                    btn.backgroundTintList = ColorStateList.valueOf(colorPrimaryContainer)
                    btn.setTextColor(colorOnPrimaryContainer)
                    btn.cornerRadius = (10 * density).roundToInt()
                    btn.strokeWidth = 0
                } else {
                    btn.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                    btn.setTextColor(colorOnSurfaceVariant)
                    btn.cornerRadius = (20 * density).roundToInt()
                    btn.strokeWidth = 0
                }
            }
        }

        class LutViewHolder(val button: MaterialButton) : RecyclerView.ViewHolder(button)

        lutAdapter = object : RecyclerView.Adapter<LutViewHolder>() {
            override fun getItemCount(): Int = lutEntries.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LutViewHolder {
                val btn = MaterialButton(
                    parent.context,
                    null,
                    com.google.android.material.R.attr.materialButtonStyle
                ).apply {
                    val margin = (4 * density).roundToInt()
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(margin, margin, margin, margin)
                    }
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    isAllCaps = false
                    textSize = 13f
                    insetTop = 0
                    insetBottom = 0
                    setPadding(0, 0, 0, 0)
                }
                return LutViewHolder(btn)
            }

            override fun onBindViewHolder(holder: LutViewHolder, position: Int) {
                val entry = lutEntries[position]
                val btn = holder.button
                btn.text = entry.displayName

                val isActive = isEntryActive(entry, currentLut)
                if (isActive) {
                    // Bento active tile: 8dp corner radius morph, colorSecondaryContainer fill, colorPrimary stroke
                    btn.backgroundTintList = ColorStateList.valueOf(colorSecondaryContainer)
                    btn.setTextColor(colorOnSecondaryContainer)
                    btn.strokeColor = ColorStateList.valueOf(colorPrimary)
                    btn.strokeWidth = (1.5f * density).roundToInt()
                    btn.cornerRadius = (8 * density).roundToInt()
                } else {
                    // Bento inactive tile: 12dp corner radius, colorSurfaceContainerHigh fill, 0 stroke
                    btn.backgroundTintList = ColorStateList.valueOf(colorSurfaceContainerHigh)
                    btn.setTextColor(colorOnSurfaceVariant)
                    btn.strokeWidth = 0
                    btn.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
                    btn.cornerRadius = (12 * density).roundToInt()
                }

                btn.setOnClickListener {
                    if (entry.fileName == null) {
                        // User chose "None" LUT
                        currentLut = null
                        prefs.edit().remove(SettingsFragment.KEY_ACTIVE_LUT).apply()
                    } else {
                        // If current Log is "None" when picking a LUT, auto-select standard Log curve
                        if (currentLog == "None") {
                            val defaultLog = SettingsFragment.LOG_CURVES.getOrNull(1) ?: "Arri LogC3"
                            currentLog = defaultLog
                            prefs.edit().putString(SettingsFragment.KEY_TARGET_LOG, currentLog).apply()
                            updateLogButtonsUi()
                        }
                        currentLut = entry.fileName
                        prefs.edit().putString(SettingsFragment.KEY_ACTIVE_LUT, currentLut).apply()
                    }
                    onRecipeSelected(currentLog, currentLut)
                    notifyDataSetChanged()
                }
            }
        }
        lutRecyclerView.adapter = lutAdapter

        // Populate BASE LOG CURVE buttons
        SettingsFragment.LOG_CURVES.forEach { logName ->
            val logBtn = MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonStyle
            ).apply {
                val margin = (4 * density).roundToInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(margin, 0, margin, 0)
                }
                text = logName
                tag = logName
                isAllCaps = false
                textSize = 13f
                insetTop = 0
                insetBottom = 0

                setOnClickListener {
                    currentLog = logName
                    if (currentLog == "None") {
                        currentLut = null
                        prefs.edit().remove(SettingsFragment.KEY_ACTIVE_LUT)
                    }
                    prefs.edit().putString(SettingsFragment.KEY_TARGET_LOG, currentLog).apply()
                    onRecipeSelected(currentLog, currentLut)
                    updateLogButtonsUi()
                    lutAdapter.notifyDataSetChanged()
                }
            }
            logButtonsContainer.addView(logBtn)
        }
        updateLogButtonsUi()

        // Reset button action
        resetButton.setOnClickListener {
            currentLog = "None"
            currentLut = null
            prefs.edit()
                .putString(SettingsFragment.KEY_TARGET_LOG, "None")
                .remove(SettingsFragment.KEY_ACTIVE_LUT)
                .apply()
            onRecipeSelected("None", null)
            updateLogButtonsUi()
            lutAdapter.notifyDataSetChanged()
        }

        // Configure Dialog Window and Behavior
        dialog.setContentView(rootLayout)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.skipCollapsed = true
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        dialog.show()
        return dialog
    }
}
