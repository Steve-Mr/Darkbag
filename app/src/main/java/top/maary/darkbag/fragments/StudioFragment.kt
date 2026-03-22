package top.maary.darkbag.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import top.maary.darkbag.R
import top.maary.darkbag.databinding.FragmentStudioBinding
import top.maary.darkbag.databinding.ItemStudioImageBinding
import top.maary.darkbag.models.ImageGroup
import top.maary.darkbag.repository.ImageRepository

class StudioFragment : Fragment() {

    private var _binding: FragmentStudioBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: ImageRepository
    private val selectedItems = mutableListOf<ImageGroup>()
    private var isSelectionMode = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStudioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = ImageRepository(requireContext())
        setupEdgeToEdge()
        setupRecyclerView()
        setupFab()
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.rvStudio) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, systemBars.bottom + resources.getDimensionPixelSize(R.dimen.margin_xlarge))
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.fabStudioEdit) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom + resources.getDimensionPixelSize(R.dimen.margin_large)
            }
            insets
        }
    }

    private fun setupRecyclerView() {
        binding.rvStudio.layoutManager = GridLayoutManager(requireContext(), 3)
        loadImages()
    }

    private fun loadImages() {
        lifecycleScope.launch {
            val groups = repository.getGroupedImages(forceRefresh = true)
            binding.rvStudio.adapter = StudioAdapter(groups)
        }
    }

    private fun setupFab() {
        binding.fabStudioEdit.setOnClickListener {
            if (selectedItems.isEmpty()) return@setOnClickListener

            val first = selectedItems[0]
            val second = selectedItems.getOrNull(1)

            if (second != null) {
                // For stitching, we'll pass both URIs via the initialUri parameter by joining them with a separator
                val compositeUri = "${(first.dngUri ?: first.dngUri1 ?: first.jpgUri)}|${(second.dngUri ?: second.dngUri1 ?: second.jpgUri)}"
                val action = StudioFragmentDirections.actionStudioToImageViewer(
                    initialUri = compositeUri
                )
                findNavController().navigate(action)
            } else {
                val action = StudioFragmentDirections.actionStudioToImageViewer(
                    initialUri = (first.dngUri ?: first.dngUri1 ?: first.jpgUri).toString()
                )
                findNavController().navigate(action)
            }
        }
    }

    private fun toggleSelection(group: ImageGroup) {
        if (selectedItems.contains(group)) {
            selectedItems.remove(group)
        } else {
            if (selectedItems.size < 2) {
                selectedItems.add(group)
            }
        }

        isSelectionMode = selectedItems.isNotEmpty()
        binding.fabStudioEdit.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
        binding.fabStudioEdit.text = if (selectedItems.size == 2) "Stitch & Edit" else "Edit"
        binding.rvStudio.adapter?.notifyDataSetChanged()
    }

    inner class StudioAdapter(private val groups: List<ImageGroup>) :
        RecyclerView.Adapter<StudioAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemStudioImageBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemStudioImageBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val group = groups[position]
            val uri = group.jpgUri ?: group.dngUri ?: group.dngUri1

            Glide.with(holder.binding.ivThumbnail)
                .load(uri)
                .centerCrop()
                .into(holder.binding.ivThumbnail)

            holder.binding.tvName.text = group.baseName

            val isSelected = selectedItems.contains(group)
            holder.binding.vSelectionOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.binding.tvSelectionIndex.visibility = if (isSelected) View.VISIBLE else View.GONE
            if (isSelected) {
                holder.binding.tvSelectionIndex.text = (selectedItems.indexOf(group) + 1).toString()
            }

            holder.itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(group)
                } else {
                    val action = StudioFragmentDirections.actionStudioToImageViewer(
                        initialUri = (group.jpgUri ?: group.dngUri ?: group.dngUri1).toString()
                    )
                    findNavController().navigate(action)
                }
            }

            holder.itemView.setOnLongClickListener {
                if (!isSelectionMode) {
                    toggleSelection(group)
                    true
                } else false
            }
        }

        override fun getItemCount() = groups.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
