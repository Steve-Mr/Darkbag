package top.maary.darkbag.fragments

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import top.maary.darkbag.databinding.ItemImagePagerBinding
import top.maary.darkbag.image.DarkbagImageRequest
import top.maary.darkbag.utils.DarkbagImage
import top.maary.darkbag.utils.DarkbagMetadata

class ImagePagerAdapter(private var images: List<DarkbagImage>) : RecyclerView.Adapter<ImagePagerAdapter.ViewHolder>() {

    private var currentMetadata: DarkbagMetadata = DarkbagMetadata()
    private var currentFormat: String = "JPG"

    class ViewHolder(val binding: ItemImagePagerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemImagePagerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val image = images[position]

        val request = DarkbagImageRequest(
            uri = image.allUris[currentFormat] ?: image.allUris["JPG"] ?: image.primaryUri,
            dngUri = image.allUris["DNG"],
            metadata = currentMetadata,
            isRawMode = currentFormat == "DNG"
        )

        holder.binding.ivDisplay.load(request)
    }

    override fun getItemCount(): Int = images.size

    fun updateImages(newImages: List<DarkbagImage>) {
        images = newImages
        notifyDataSetChanged()
    }

    fun updateParams(metadata: DarkbagMetadata, format: String) {
        this.currentMetadata = metadata
        this.currentFormat = format
        notifyDataSetChanged()
    }

    fun getImage(position: Int): DarkbagImage? {
        if (position in images.indices) return images[position]
        return null
    }
}
