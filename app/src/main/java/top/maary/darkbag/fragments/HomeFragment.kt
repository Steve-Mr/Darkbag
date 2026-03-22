package top.maary.darkbag.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.carousel.CarouselLayoutManager
import com.google.android.material.carousel.MultiBrowseCarouselStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentHomeBinding
import top.maary.darkbag.databinding.ItemCarouselImageBinding
import top.maary.darkbag.databinding.ItemFolderRowBinding
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.repository.ImageRepository
import top.maary.darkbag.utils.ImageUtils

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: ImageRepository

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
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
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.homeRoot) { _, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            binding.toolbar.setPadding(0, systemBars.top, 0, 0)
            binding.homeContentContainer.setPadding(
                systemBars.left,
                0,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }
    }

    private fun setupUi() {
        binding.rvDarkbagCarousel.layoutManager = CarouselLayoutManager(MultiBrowseCarouselStrategy())
        binding.rvExternalFolders.layoutManager = LinearLayoutManager(requireContext())

        binding.btnSeeAll.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_gallery)
        }

        binding.cardCameraEntry.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_camera)
        }

        binding.toolbar.setOnMenuItemClickListener {
             if (it.itemId == R.id.action_settings) {
                 findNavController().navigate(R.id.action_home_to_settings)
                 true
             } else false
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            val allGroups = repository.getGroupedImages()
            val darkbagGroups = allGroups.filter { it.isDarkbag }
            val externalGroupsByFolder = allGroups.filter { !it.isDarkbag }
                .groupBy { it.folderName ?: "Other" }

            // Update Darkbag Carousel
            binding.rvDarkbagCarousel.adapter = CarouselAdapter(darkbagGroups) { group ->
                val uri = group.jpgUri ?: group.dngUri ?: group.dngUri1
                val action = HomeFragmentDirections.actionHomeToImageViewer(uri.toString())
                findNavController().navigate(action)
            }

            // Update External Folders
            binding.rvExternalFolders.adapter = FolderRowAdapter(externalGroupsByFolder) { group ->
                val uri = group.jpgUri ?: group.dngUri ?: group.dngUri1
                val action = HomeFragmentDirections.actionHomeToImageViewer(uri.toString())
                findNavController().navigate(action)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadPreview(imageView: ImageView, group: ImageGroup) {
        val context = imageView.context
        val uri = group.jpgUri ?: group.dngUri ?: group.dngUri1 ?: return

        if (uri.toString().endsWith(".dng", ignoreCase = true)) {
            // Use specialized DNG decoder for thumbnails
            lifecycleScope.launch {
                val bitmap = ImageUtils.decodeDngThumbnail(context, uri)
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                } else {
                    Glide.with(imageView).load(uri).centerCrop().into(imageView)
                }
            }
        } else {
            Glide.with(imageView).load(uri).centerCrop().into(imageView)
        }
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
            loadPreview(holder.binding.ivThumbnail, item)

            holder.binding.ivFormatBadge.visibility = if (item.dngUri != null || item.dngUri1 != null) View.VISIBLE else View.GONE
            holder.binding.root.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount() = items.size
    }

    inner class FolderRowAdapter(
        private val folders: Map<String, List<ImageGroup>>,
        private val onItemClick: (ImageGroup) -> Unit
    ) : RecyclerView.Adapter<FolderRowAdapter.ViewHolder>() {

        private val folderNames = folders.keys.toList()

        inner class ViewHolder(val binding: ItemFolderRowBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemFolderRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val name = folderNames[position]
            val items = folders[name] ?: emptyList()
            holder.binding.tvFolderName.text = name
            holder.binding.rvFolderCarousel.layoutManager = CarouselLayoutManager(MultiBrowseCarouselStrategy())
            holder.binding.rvFolderCarousel.adapter = CarouselAdapter(items, onItemClick)
        }

        override fun getItemCount() = folderNames.size
    }
}
