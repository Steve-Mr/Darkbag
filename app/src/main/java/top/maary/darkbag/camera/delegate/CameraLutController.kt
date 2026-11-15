package top.maary.darkbag.camera.delegate

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.maary.darkbag.R
import top.maary.darkbag.processor.ColorProcessor
import top.maary.darkbag.repository.PreferenceKeys
import top.maary.darkbag.repository.SettingsRepository
import top.maary.darkbag.ui.CameraBentoDeckView
import top.maary.darkbag.ui.ColorRecipeBottomSheet
import java.io.File

/**
 * Controller responsible for 3D LUT and LOG profile selection, live OpenGL viewfinder preview,
 * pop-up pill menus, and flash / underexposure toggle synchronization.
 */
class CameraLutController(
    private val env: CameraEnvironmentHost,
    private val ui: CameraUiHost,
    private val coordinator: CameraCoordinatorHost
) {
    private var activeLutJob: Job? = null
    private var lutAdapter: LutPreviewAdapter? = null

    /** Connects the viewfinder TextureView to the LUT processor output and pushes the live LUT. */
    fun setupViewFinderBinding() {
        val proc = env.sessionArbiter.lutProcessor ?: return
        val vf = ui.fragmentCameraBinding?.viewFinder ?: return
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
        val binding = ui.cameraUiContainerBinding ?: return
        val rv = binding.lutList ?: return

        if (rv.layoutManager == null) {
            rv.layoutManager = LinearLayoutManager(env.hostContext)
        }

        val luts = env.lutManager.getLuts()
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
            val settingsRepository = SettingsRepository.getInstance(env.hostContext)
            val currentName = settingsRepository.current().activeLut.takeIf { it != "None" }

            val colorOnSurface = MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnSurface)
            val colorPrimary = MaterialColors.getColor(holder.itemView, android.R.attr.colorPrimary)

            holder.text.setTextColor(colorOnSurface)
            holder.text.textSize = 12f
            holder.text.setPadding(10, 10, 10, 10)

            if (position == 0) {
                holder.text.text = holder.itemView.context.getString(R.string.lut_none)
                if (currentName == null) holder.text.setTextColor(colorPrimary)
                holder.itemView.setOnClickListener {
                    settingsRepository.setActiveLut(null, null)
                    val logName = env.cameraViewModel.uiState.value.activeLogName
                    env.cameraViewModel.updateColorRecipe(logName, "None")
                    updateLiveLut(logName, null)

                    val oldPosition = if (currentName != null) luts.indexOfFirst { it.name == currentName } + 1 else 0
                    notifyItemChanged(oldPosition)
                    notifyItemChanged(0)

                    ui.cameraUiContainerBinding?.lutListContainer?.visibility = View.GONE
                    ui.cameraUiContainerBinding?.touchOverlay?.visibility = View.GONE
                }
            } else {
                val file = luts[position - 1]
                holder.text.text = file.nameWithoutExtension
                if (currentName == file.name) holder.text.setTextColor(colorPrimary)
                holder.itemView.setOnClickListener {
                    settingsRepository.setActiveLut(file.name, null)
                    val logName = env.cameraViewModel.uiState.value.activeLogName
                    env.cameraViewModel.updateColorRecipe(logName, file.name)
                    updateLiveLut(logName, file.name)

                    val oldPosition = if (currentName != null) luts.indexOfFirst { it.name == currentName } + 1 else 0
                    notifyItemChanged(oldPosition)
                    notifyItemChanged(position)

                    ui.cameraUiContainerBinding?.lutListContainer?.visibility = View.GONE
                    ui.cameraUiContainerBinding?.touchOverlay?.visibility = View.GONE
                }
            }
        }

        override fun getItemCount() = luts.size + 1
    }

    fun updateFlashIcon(btn: MaterialButton) {
        btn.text = null
        val flashOn = env.runtimeState.isFlashEnabled
        btn.setIconResource(if (flashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        CameraBentoDeckView.styleBentoToggleTile(btn, flashOn, isTertiaryAccent = false)
    }

    fun updateUnderexposureButton() {
        val btn = ui.cameraUiContainerBinding?.flashButton ?: return
        val mode = SettingsRepository.getInstance(env.hostContext).current().hdrUnderexposureMode

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
        val settingsRepository = SettingsRepository.getInstance(env.hostContext)
        val currentMode = settingsRepository.current().hdrUnderexposureMode
        val modes = PreferenceKeys.HDR_UNDEREXPOSURE_MODES
        val currentIndex = modes.indexOf(currentMode)
        val nextIndex = (currentIndex + 1) % modes.size
        val nextMode = modes[nextIndex]

        settingsRepository.setHdrUnderexposureMode(nextMode)
        updateUnderexposureButton()

        // Update lastHdrPlusConfig to reflect new mode immediately for the next shot
        env.runtimeState.lastHdrPlusConfig = null
    }

    fun updateLiveLut(
        targetLogName: String = env.cameraViewModel.uiState.value.activeLogName,
        activeLutName: String? = env.cameraViewModel.uiState.value.activeLutName.takeIf { !it.equals("None", ignoreCase = true) }
    ) {
        val settingsRepository = SettingsRepository.getInstance(env.hostContext)
        val snapshot = settingsRepository.current()
        val cleanLutName = activeLutName?.takeIf { !it.equals("None", ignoreCase = true) }
        val targetLogIndex = PreferenceKeys.LOG_CURVES.indexOf(targetLogName)
        val isPreviewEnabled = snapshot.enableLutPreview

        ui.cameraUiContainerBinding?.let { CameraBentoDeckView.bindRecipeRow(it, targetLogName, cleanLutName) }

        val proc = env.sessionArbiter.lutProcessor ?: return

        activeLutJob?.cancel()
        activeLutJob = env.lifecycleScope.launch(Dispatchers.IO) {
            var lutData: FloatArray? = null
            var size = 0
            if (isPreviewEnabled && cleanLutName != null) {
                val file = File(env.lutManager.lutDir, cleanLutName)
                if (file.exists()) {
                    lutData = ColorProcessor.loadLutData(file.absolutePath)
                    if (lutData != null) {
                        size = Math.round(Math.pow((lutData.size / 3).toDouble(), 1.0 / 3.0)).toInt()
                    }
                }
            }
            if (isActive) {
                val engineMode = settingsRepository.current().colorEngineMode
                proc.updateColorEngineMode(engineMode)
                proc.updateLut(lutData, size, targetLogIndex)
            }
        }
    }

    fun showLutMenu() {
        val currentLog = env.cameraViewModel.uiState.value.activeLogName
        val currentLut = env.cameraViewModel.uiState.value.activeLutName.takeIf { !it.equals("None", ignoreCase = true) }
        ColorRecipeBottomSheet.show(
            context = env.hostContext,
            lutManager = env.lutManager,
            initialLog = currentLog,
            initialLut = currentLut
        ) { logName, lutName ->
            SettingsRepository.getInstance(env.hostContext).edit {
                this[PreferenceKeys.TARGET_LOG] = logName
                if (lutName == null || lutName.equals("None", ignoreCase = true)) {
                    remove(PreferenceKeys.ACTIVE_LUT)
                } else {
                    this[PreferenceKeys.ACTIVE_LUT] = lutName
                }
            }

            env.cameraViewModel.updateColorRecipe(logName, lutName ?: "None")
        }
    }

    fun showLogSelectionMenu() {
        val items = mutableListOf("← Back" to true)
        PreferenceKeys.LOG_CURVES.forEach { log ->
            items.add(log to true)
        }

        showPillPopup(items, autoDismiss = false) { selectedLog, position ->
            if (position == 0) {
                showLutMenu()
            } else {
                val settingsRepository = SettingsRepository.getInstance(env.hostContext)
                var currentLut: String? = null
                settingsRepository.edit {
                    this[PreferenceKeys.TARGET_LOG] = selectedLog
                    if (selectedLog == env.hostContext.getString(R.string.lut_none)) {
                        remove(PreferenceKeys.ACTIVE_LUT)
                        currentLut = null
                    } else {
                        currentLut = this[PreferenceKeys.ACTIVE_LUT]
                    }
                }

                env.cameraViewModel.updateColorRecipe(selectedLog, currentLut ?: "None")
                updateLiveLut(selectedLog, currentLut)
                showLutMenu()
            }
        }
    }

    fun showLutSelectionMenu() {
        val luts = env.lutManager.getLuts()
        val items = mutableListOf("← Back" to true, env.hostContext.getString(R.string.lut_none) to true)
        luts.forEach { file ->
            items.add(file.nameWithoutExtension to true)
        }

        showPillPopup(items, autoDismiss = false) { selectedName, position ->
            if (position == 0) {
                showLutMenu()
            } else {
                val settingsRepository = SettingsRepository.getInstance(env.hostContext)
                val newLut = if (position == 1) {
                    settingsRepository.setActiveLut(null, null)
                    null
                } else {
                    val filename = luts[position - 2].name
                    settingsRepository.setActiveLut(filename, null)
                    filename
                }
                val currentLog = env.cameraViewModel.uiState.value.activeLogName
                env.cameraViewModel.updateColorRecipe(currentLog, newLut ?: "None")
                updateLiveLut(currentLog, newLut)
                showLutMenu()
            }
        }
    }

    fun showPillPopup(items: List<Pair<String, Boolean>>, autoDismiss: Boolean = true, onSelected: (String, Int) -> Unit) {
        val binding = ui.cameraUiContainerBinding ?: return
        val container = binding.lutListContainer ?: return
        val rv = binding.lutList ?: return

        binding.touchOverlay?.bringToFront()
        binding.touchOverlay?.visibility = View.VISIBLE
        container.bringToFront()

        val colorSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorSurfaceContainerHigh)
        container.setCardBackgroundColor(colorSurface)
        container.cardElevation = 8f
        container.layoutParams.width = env.hostContext.resources.getDimensionPixelSize(R.dimen.round_button_large) * 3
        container.visibility = View.VISIBLE

        rv.layoutManager = LinearLayoutManager(env.hostContext)
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
                        ui.dismissFloatingOverlays()
                    }
                }
            }

            override fun getItemCount() = items.size
        }
    }

    fun bindRecipeControls() {
        val uiBinding = ui.cameraUiContainerBinding ?: return
        val openColorRecipeSheet = View.OnClickListener {
            ui.dismissFloatingOverlays()
            showLutMenu()
        }
        uiBinding.bentoLogButton?.setOnClickListener(openColorRecipeSheet)
        uiBinding.lutSwitcherButton?.setOnClickListener(openColorRecipeSheet)

        val snapshot = SettingsRepository.getInstance(env.hostContext).current()
        val activeLutName = snapshot.activeLut.takeIf { it != "None" }
        val targetLogName = snapshot.targetLog
        env.cameraViewModel.updateColorRecipe(targetLogName, activeLutName ?: "None")
        CameraBentoDeckView.bindRecipeRow(uiBinding, targetLogName, activeLutName)
        CameraBentoDeckView.applyDynamicRowHeights(uiBinding)
        updateLiveLut(targetLogName, activeLutName)
    }

    fun onDestroy() {
        activeLutJob?.cancel()
        activeLutJob = null
    }
}
