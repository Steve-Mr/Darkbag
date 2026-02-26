package top.maary.darkbag.fragments

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import java.io.File

class LutAdapter(
    private val luts: List<File>,
    private var currentLutName: String?,
    private val onSelected: (String?) -> Unit
) : RecyclerView.Adapter<LutAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        view.setBackgroundColor(Color.TRANSPARENT)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val colorOnSurface = MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnSurface)
        val colorPrimary = MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorPrimary)

        holder.text.setTextColor(colorOnSurface)
        holder.text.textSize = 14f

        if (position == 0) {
            holder.text.text = "None"
            if (currentLutName == null) holder.text.setTextColor(colorPrimary)
            holder.itemView.setOnClickListener {
                onSelected(null)
                updateCurrent(null)
            }
        } else {
            val file = luts[position - 1]
            holder.text.text = file.nameWithoutExtension
            if (currentLutName == file.name) holder.text.setTextColor(colorPrimary)
            holder.itemView.setOnClickListener {
                onSelected(file.name)
                updateCurrent(file.name)
            }
        }
    }

    fun updateCurrent(name: String?) {
        currentLutName = name
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = luts.size + 1
}
