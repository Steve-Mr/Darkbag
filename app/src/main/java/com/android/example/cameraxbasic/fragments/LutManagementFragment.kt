package com.android.example.cameraxbasic.fragments

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.example.cameraxbasic.R
import com.android.example.cameraxbasic.utils.LutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LutManagementFragment : Fragment() {

    private lateinit var lutManager: LutManager
    private lateinit var adapter: LutManagementAdapter
    private lateinit var rvLutList: RecyclerView

    private val lutPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            lifecycleScope.launch(Dispatchers.IO) {
                if (lutManager.importLut(it)) {
                    withContext(Dispatchers.Main) {
                        refreshList()
                        Toast.makeText(context, "Imported successfully", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Import failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_lut_management, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lutManager = LutManager(requireContext())

        view.findViewById<View>(R.id.btn_back).setOnClickListener {
            Navigation.findNavController(view).navigateUp()
        }

        view.findViewById<View>(R.id.btn_import).setOnClickListener {
            lutPicker.launch(arrayOf("*/*"))
        }

        rvLutList = view.findViewById(R.id.rv_lut_list)
        rvLutList.layoutManager = LinearLayoutManager(context)
        adapter = LutManagementAdapter(
            onDelete = { file -> deleteLut(file) },
            onRename = { file -> showRenameDialog(file) }
        )
        rvLutList.adapter = adapter
        refreshList()
    }

    private fun refreshList() {
        adapter.items = lutManager.getLutList()
        adapter.notifyDataSetChanged()
    }

    private fun deleteLut(file: File) {
        AlertDialog.Builder(context)
            .setTitle("Delete LUT")
            .setMessage("Are you sure you want to delete ${lutManager.getDisplayName(file)}?")
            .setPositiveButton("Delete") { _, _ ->
                if (lutManager.deleteLut(file)) {
                    refreshList()
                } else {
                    Toast.makeText(context, "Failed to delete", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameDialog(file: File) {
        val input = EditText(context)
        input.setText(lutManager.getDisplayName(file))

        AlertDialog.Builder(context)
            .setTitle("Rename LUT")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    if (lutManager.renameLut(file, newName)) {
                        refreshList()
                    } else {
                        Toast.makeText(context, "Failed to rename", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class LutManagementAdapter(
        var items: List<File> = emptyList(),
        val onDelete: (File) -> Unit,
        val onRename: (File) -> Unit
    ) : RecyclerView.Adapter<LutManagementAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_item_name)
            val btnDelete: ImageButton = view.findViewById(R.id.btn_item_delete)
            val btnEdit: ImageButton = view.findViewById(R.id.btn_item_edit)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lut_management, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = items[position]
            holder.tvName.text = lutManager.getDisplayName(file)
            holder.btnDelete.setOnClickListener { onDelete(file) }
            holder.btnEdit.setOnClickListener { onRename(file) }
        }

        override fun getItemCount(): Int = items.size
    }
}
