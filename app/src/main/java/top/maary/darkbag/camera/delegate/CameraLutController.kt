package top.maary.darkbag.camera.delegate

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.maary.darkbag.R
import top.maary.darkbag.databinding.CameraUiContainerBinding
import top.maary.darkbag.databinding.FragmentCameraBinding
import top.maary.darkbag.fragments.SettingsFragment
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.processor.LutSurfaceProcessor
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.ui.ColorRecipeBottomSheet
import top.maary.darkbag.utils.ExposureUtils
import top.maary.darkbag.utils.LutManager
import top.maary.darkbag.viewmodel.CameraViewModel
import java.io.File

/**
 * Host contract for LUT and color recipe controls.
 */
interface CameraLutHost {
    val hostContext: Context
    val lifecycleScope: CoroutineScope
    val lutManager: LutManager
    val lutProcessor: LutSurfaceProcessor?
    val cameraViewModel: CameraViewModel
    val cameraUiContainerBinding: CameraUiContainerBinding?
    val fragmentCameraBinding: FragmentCameraBinding?
    val isFlashEnabled: Boolean
    val isHdrPlusEnabled: Boolean
    var lastHdrPlusConfig: ExposureUtils.ExposureConfig?

    fun dismissFloatingOverlays()
    fun applyCameraControls()
}

/**
 * Controller responsible for 3D LUT and LOG profile selection, live OpenGL viewfinder preview,
 * pop-up pill menus, and flash / underexposure toggle synchronization.
 */
class CameraLutController(
    private val host: CameraLutHost
) {
    private var activeLutJob: Job? = null
    private var lutAdapter: LutPreviewAdapter? = null

    /** Connects the viewfinder TextureView to the LUT processor output and pushes the live LUT. */
    fun setupViewFinderBinding() {
        val proc = host.lutProcessor ?: return
        val vf = host.fragmentCameraBinding?.viewFinder ?: return
        vf.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                proc.setOutputSurface(Surface(st), w, h)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                proc.setOutputSurface(Surface(st), w, h)
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                proc.setOutputSurface(null, 0, 0)
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        if (vf.isAvailable) {
            vf.surfaceTexture?.let { st -> proc.setOutputSurface(Surface(st), vf.width, vf.height) }
        }
        updateLiveLut()
    }

    fun refreshLutList() {
        val binding = host.cameraUiContainerBinding ?: return
        val rv = binding.lutList ?: return

        if (rv.layoutManager == null) {
            rv.layoutManager = LinearLayoutManager(host.hostContext)
        }

        val luts = host.lutManager.getLuts()
        lutAdapter = LutPreviewAdapter(luts)
        rv.adapter = lutAdapter
    }

    private inner class LutPreviewAdapter(val luts: List<File>) :
        RecyclerView.Adapter<LutPreviewAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: android.widget.TextView = view.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            view.setBackgroundColor(Color.TRANSPARENT)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val prefs = host.hostContext.getSharedPreferences(
                SettingsFragment.PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val currentName = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, null)

            val colorOnSurface = MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnSurface)
            val colorPrimary = MaterialColors.getColor(holder.itemView, android.R.attr.colorPrimary)

            holder.text.setTextColor(colorOnSurface)
            holder.text.textSize = 12f
            holder.text.setPadding(10, 10, 10, 10)

            if (position == 0) {
                holder.text.text = holder.itemView.context.getString(R.string.lut_none)
                if (currentName == null) holder.text.setTextColor(colorPrimary)
                holder.itemView.setOnClickListener {
                    prefs.edit().remove(SettingsFragment.KEY_ACTIVE_LUT).apply()
                    val logName = host.cameraViewModel.uiState.value.activeLogName
                    host.cameraViewModel.updateColorRecipe(logName, "None")
                    updateLiveLut(logName, null)

                    val oldPosition = if (currentName != null) luts.indexOfFirst { it.name == currentName } + 1 else 0
                    notifyItemChanged(oldPosition)
                    notifyItemChanged(0)

                    host.cameraUiContainerBinding?.lutListContainer?.visibility = View.GONE
                    host.cameraUiContainerBinding?.touchOverlay?.visibility = View.GONE
                }
            } else {
                val file = luts[position - 1]
                holder.text.text = file.nameWithoutExtension
                if (currentName == file.name) holder.text.setTextColor(colorPrimary)
                holder.itemView.setOnClickListener {
                    prefs.edit().putString(SettingsFragment.KEY_ACTIVE_LUT, file.name).apply()
                    val logName = host.cameraViewModel.uiState.value.activeLogName
                    host.cameraViewModel.updateColorRecipe(logName, file.name)
                    updateLiveLut(logName, file.name)

                    val oldPosition = if (currentName != null) luts.indexOfFirst { it.name == currentName } + 1 else 0
                    notifyItemChanged(oldPosition)
                    notifyItemChanged(position)

                    host.cameraUiContainerBinding?.lutListContainer?.visibility = View.GONE
                    host.cameraUiContainerBinding?.touchOverlay?.visibility = View.GONE
                }
            }
        }

        override fun getItemCount() = luts.size + 1
    }

    fun updateFlashIcon(btn: MaterialButton) {
        btn.text = null
        btn.setIconResource(if (host.isFlashEnabled) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        CameraBentoDeckView.styleBentoToggleTile(btn, host.isFlashEnabled, isTertiaryAccent = false)
    }

    fun updateUnderexposureButton() {
        val btn = host.cameraUiContainerBinding?.flashButton ?: return
        val prefs = host.hostContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(SettingsFragment.KEY_HDR_UNDEREXPOSURE_MODE, "Dynamic (Experimental)") ?: "Dynamic (Experimental)"

        btn.text = null
        val iconRes = when {
            mode.contains("Dynamic") -> R.drawable.ic_hdr_dynamic
            mode == "Off" -> R.drawable.ic_exposure_off
            mode == "-1 EV" -> R.drawable.ic_exposure_neg_1
            mode == "-2 EV" -> R.drawable.ic_exposure_neg_2
            else -> R.drawable.ic_hdr_dynamic
        }
        btn.setIconResource(iconRes)
        CameraBentoDeckView.styleBentoToggleTile(btn, isActive = mode != "Off", isTertiaryAccent = false)
    }

    fun cycleUnderexposureMode() {
        val prefs = host.hostContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val currentMode = prefs.getString(SettingsFragment.KEY_HDR_UNDEREXPOSURE_MODE, "Dynamic (Experimental)") ?: "Dynamic (Experimental)"
        val modes = SettingsFragment.HDR_UNDEREXPOSURE_MODES
        val currentIndex = modes.indexOf(currentMode)
        val nextIndex = (currentIndex + 1) % modes.size
        val nextMode = modes[nextIndex]

        prefs.edit().putString(SettingsFragment.KEY_HDR_UNDEREXPOSURE_MODE, nextMode).apply()
        updateUnderexposureButton()

        // Update lastHdrPlusConfig to reflect new mode immediately for the next shot
        host.lastHdrPlusConfig = null
    }

    fun updateLiveLut(
        targetLogName: String = host.cameraViewModel.uiState.value.activeLogName,
        activeLutName: String? = host.cameraViewModel.uiState.value.activeLutName.takeIf { !it.equals("None", ignoreCase = true) }
    ) {
        val prefs = host.hostContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val cleanLutName = activeLutName?.takeIf { !it.equals("None", ignoreCase = true) }
        val targetLogIndex = SettingsFragment.LOG_CURVES.indexOf(targetLogName)
        val isPreviewEnabled = prefs.getBoolean(SettingsFragment.KEY_ENABLE_LUT_PREVIEW, true)

        host.cameraUiContainerBinding?.let { CameraBentoDeckView.bindRecipeRow(it, targetLogName, cleanLutName) }

        val proc = host.lutProcessor ?: return

        activeLutJob?.cancel()
        activeLutJob = host.lifecycleScope.launch(Dispatchers.IO) {
            var lutData: FloatArray? = null
            var size = 0
            if (isPreviewEnabled && cleanLutName != null) {
                val file = File(host.lutManager.lutDir, cleanLutName)
                if (file.exists()) {
                    lutData = ColorProcessor.loadLutData(file.absolutePath)
                    if (lutData != null) {
                        size = Math.round(Math.pow((lutData.size / 3).toDouble(), 1.0 / 3.0)).toInt()
                    }
                }
            }
            if (isActive) {
                val engineMode = prefs.getInt(SettingsFragment.KEY_COLOR_ENGINE_MODE, 2)
                proc.updateColorEngineMode(engineMode)
                proc.updateLut(lutData, size, targetLogIndex)
            }
        }
    }

    fun showLutMenu() {
        val currentLog = host.cameraViewModel.uiState.value.activeLogName
        val currentLut = host.cameraViewModel.uiState.value.activeLutName.takeIf { !it.equals("None", ignoreCase = true) }
        ColorRecipeBottomSheet.show(
            context = host.hostContext,
            lutManager = host.lutManager,
            initialLog = currentLog,
            initialLut = currentLut
        ) { logName, lutName ->
            val prefs = host.hostContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            editor.putString(SettingsFragment.KEY_TARGET_LOG, logName)
            if (lutName == null || lutName.equals("None", ignoreCase = true)) {
                editor.remove(SettingsFragment.KEY_ACTIVE_LUT)
            } else {
                editor.putString(SettingsFragment.KEY_ACTIVE_LUT, lutName)
            }
            editor.apply()

            host.cameraViewModel.updateColorRecipe(logName, lutName ?: "None")
        }
    }

    fun showLogSelectionMenu() {
        val items = mutableListOf("← Back" to true)
        SettingsFragment.LOG_CURVES.forEach { log ->
            items.add(log to true)
        }

        showPillPopup(items, autoDismiss = false) { selectedLog, position ->
            if (position == 0) {
                showLutMenu()
            } else {
                val prefs = host.hostContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                editor.putString(SettingsFragment.KEY_TARGET_LOG, selectedLog)
                val currentLut = if (selectedLog == host.hostContext.getString(R.string.lut_none)) {
                    editor.remove(SettingsFragment.KEY_ACTIVE_LUT)
                    null
                } else {
                    prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, null)
                }
                editor.apply()

                host.cameraViewModel.updateColorRecipe(selectedLog, currentLut ?: "None")
                updateLiveLut(selectedLog, currentLut)
                showLutMenu()
            }
        }
    }

    fun showLutSelectionMenu() {
        val luts = host.lutManager.getLuts()
        val items = mutableListOf("← Back" to true, host.hostContext.getString(R.string.lut_none) to true)
        luts.forEach { file ->
            items.add(file.nameWithoutExtension to true)
        }

        showPillPopup(items, autoDismiss = false) { selectedName, position ->
            if (position == 0) {
                showLutMenu()
            } else {
                val prefs = host.hostContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                val newLut = if (position == 1) {
                    prefs.edit().remove(SettingsFragment.KEY_ACTIVE_LUT).apply()
                    null
                } else {
                    val filename = luts[position - 2].name
                    prefs.edit().putString(SettingsFragment.KEY_ACTIVE_LUT, filename).apply()
                    filename
                }
                val currentLog = host.cameraViewModel.uiState.value.activeLogName
                host.cameraViewModel.updateColorRecipe(currentLog, newLut ?: "None")
                updateLiveLut(currentLog, newLut)
                showLutMenu()
            }
        }
    }

    fun showPillPopup(items: List<Pair<String, Boolean>>, autoDismiss: Boolean = true, onSelected: (String, Int) -> Unit) {
        val binding = host.cameraUiContainerBinding ?: return
        val container = binding.lutListContainer ?: return
        val rv = binding.lutList ?: return

        binding.touchOverlay?.bringToFront()
        binding.touchOverlay?.visibility = View.VISIBLE
        container.bringToFront()

        val colorSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorSurfaceContainerHigh)
        container.setCardBackgroundColor(colorSurface)
        container.cardElevation = 8f
        container.layoutParams.width = host.hostContext.resources.getDimensionPixelSize(R.dimen.round_button_large) * 3
        container.visibility = View.VISIBLE

        rv.layoutManager = LinearLayoutManager(host.hostContext)
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class PillViewHolder(val btn: MaterialButton) : RecyclerView.ViewHolder(btn)

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val btn = LayoutInflater.from(parent.context).inflate(R.layout.item_popup_pill, parent, false) as MaterialButton
                return PillViewHolder(btn)
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val pillHolder = holder as PillViewHolder
                val (title, isEnabled) = items[position]
                pillHolder.btn.text = title
                pillHolder.btn.isEnabled = isEnabled
                pillHolder.btn.alpha = if (isEnabled) 1.0f else 0.5f
                pillHolder.btn.setOnClickListener {
                    onSelected(title, position)
                    if (autoDismiss) {
                        host.dismissFloatingOverlays()
                    }
                }
            }

            override fun getItemCount() = items.size
        }
    }

    fun bindRecipeControls() {
        val uiBinding = host.cameraUiContainerBinding ?: return
        val openColorRecipeSheet = View.OnClickListener {
            host.dismissFloatingOverlays()
            showLutMenu()
        }
        uiBinding.bentoLogButton?.setOnClickListener(openColorRecipeSheet)
        uiBinding.lutSwitcherButton?.setOnClickListener(openColorRecipeSheet)

        val prefs = host.hostContext.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val activeLutName = prefs.getString(SettingsFragment.KEY_ACTIVE_LUT, null)
        val targetLogName = prefs.getString(SettingsFragment.KEY_TARGET_LOG, "None") ?: "None"
        host.cameraViewModel.updateColorRecipe(targetLogName, activeLutName ?: "None")
        CameraBentoDeckView.bindRecipeRow(uiBinding, targetLogName, activeLutName)
        CameraBentoDeckView.applyDynamicRowHeights(uiBinding)
        updateLiveLut(targetLogName, activeLutName)
    }

    fun onDestroy() {
        activeLutJob?.cancel()
        activeLutJob = null
    }
}
