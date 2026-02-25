package top.maary.darkbag.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import top.maary.darkbag.R
import java.io.File

class LutAdapter(
    private val luts: List<File>,
    private val onLutClick: (File?) -> Unit
) : RecyclerView.Adapter<LutAdapter.ViewHolder>() {

    private var selectedPosition = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lut, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (position == 0) {
            holder.tvName.text = "None"
            holder.itemView.setOnClickListener {
                selectedPosition = 0
                notifyDataSetChanged()
                onLutClick(null)
            }
        } else {
            val file = luts[position - 1]
            holder.tvName.text = file.nameWithoutExtension
            holder.itemView.setOnClickListener {
                selectedPosition = position
                notifyDataSetChanged()
                onLutClick(file)
            }
        }

        holder.itemView.isSelected = selectedPosition == position
    }

    override fun getItemCount() = luts.size + 1

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_lut_name)
    }
}
