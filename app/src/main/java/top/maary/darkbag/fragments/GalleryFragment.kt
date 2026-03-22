package top.maary.darkbag.fragments

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.carousel.CarouselLayoutManager
import com.google.android.material.carousel.MultiBrowseCarouselStrategy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import top.maary.darkbag.R
import top.maary.darkbag.databinding.*
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.repository.ImageRepository

class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: ImageRepository
    private var isSelectionMode = false
    private val selectedItems = mutableSetOf<ImageGroup>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = ImageRepository(requireContext())
        setupEdgeToEdge()
        setupUi()
        loadData()
    }

    private fun setupEdgeToEdge() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            binding.toolbar.setPadding(0, systemBars.top, 0, 0)
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun setupUi() {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_select) {
                toggleSelectionMode()
                true
            } else false
        }

        binding.rvDarkbagCarousel.layoutManager = CarouselLayoutManager(MultiBrowseCarouselStrategy())
        binding.rvGallery.layoutManager = GridLayoutManager(requireContext(), 3)

        binding.fabStitch.setOnClickListener {
             if (selectedItems.size == 2) {
                 showStitchDialog()
             }
        }
    }

    private fun toggleSelectionMode() {
        isSelectionMode = !isSelectionMode
        selectedItems.clear()
        binding.fabStitch.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
        binding.rvGallery.adapter?.notifyDataSetChanged()
    }

    private fun loadData() {
        lifecycleScope.launch {
            val allGroups = repository.getGroupedImages()
            val darkbagGroups = allGroups.filter { it.isDarkbag }
            val otherGroups = allGroups.filter { !it.isDarkbag }

            binding.rvDarkbagCarousel.adapter = CarouselAdapter(darkbagGroups) { group ->
                val action = GalleryFragmentDirections.actionGalleryToImageViewer((group.jpgUri ?: group.dngUri).toString())
                findNavController().navigate(action)
            }

            binding.rvGallery.adapter = GalleryGridAdapter(otherGroups)
        }
    }

    private fun showStitchDialog() {
        val options = arrayOf("Side-by-Side (SBS)", "Top-Bottom (TB)")
        var selected = 0
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Stitch Mode")
            .setSingleChoiceItems(options, 0) { _, which -> selected = which }
            .setPositiveButton("Stitch") { _, _ ->
                val items = selectedItems.toList()
                val layout = if (selected == 0) "SBS" else "TB"
                performStitch(items[0], items[1], layout)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performStitch(g1: ImageGroup, g2: ImageGroup, layout: String) {
        val stitchedGroup = ImageGroup(
            baseName = "stitched_temp_${System.currentTimeMillis()}",
            dngUri1 = g1.dngUri ?: g1.dngUri1,
            dngUri2 = g2.dngUri ?: g2.dngUri1,
            hfLayout = layout,
            captureTime = System.currentTimeMillis(),
            isDarkbag = true
        )
        val bundle = Bundle().apply {
            putParcelable("stitched_group", stitchedGroup)
            putString("initial_uri", stitchedGroup.dngUri1.toString())
        }
        findNavController().navigate(R.id.action_gallery_to_image_viewer, bundle)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class GalleryGridAdapter(
        private val items: List<ImageGroup>
    ) : RecyclerView.Adapter<GalleryGridAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemGalleryImageBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemGalleryImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            Glide.with(holder.binding.ivThumbnail)
                .load(item.jpgUri ?: item.dngUri)
                .centerCrop()
                .into(holder.binding.ivThumbnail)

            holder.binding.ivFormatBadge.visibility = if (item.dngUri != null) View.VISIBLE else View.GONE

            holder.binding.cbSelection.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            holder.binding.cbSelection.isChecked = selectedItems.contains(item)
            holder.binding.vSelectionOverlay.visibility = if (selectedItems.contains(item)) View.VISIBLE else View.GONE

            holder.binding.root.setOnClickListener {
                if (isSelectionMode) {
                    if (selectedItems.contains(item)) {
                        selectedItems.remove(item)
                    } else if (selectedItems.size < 2) {
                        selectedItems.add(item)
                    }
                    notifyItemChanged(position)
                    binding.fabStitch.isEnabled = selectedItems.size == 2
                } else {
                        val action = GalleryFragmentDirections.actionGalleryToImageViewer((item.jpgUri ?: item.dngUri).toString())
                    findNavController().navigate(action)
                }
            }
        }

        override fun getItemCount() = items.size
    }

    inner class CarouselAdapter(
        private val items: List<ImageGroup>,
        private val onItemClick: (ImageGroup) -> Unit
    ) : RecyclerView.Adapter<CarouselAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemCarouselImageBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemCarouselImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            Glide.with(holder.binding.ivThumbnail)
                .load(item.jpgUri ?: item.dngUri)
                .centerCrop()
                .into(holder.binding.ivThumbnail)
            holder.binding.root.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
