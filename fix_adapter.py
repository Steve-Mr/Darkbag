with open('./app/src/main/java/top/maary/darkbag/fragments/ImageViewerAdapter.kt', 'r') as f:
    content = f.read()

content = content.replace("""    fun setManualBitmap(position: Int, bitmap: android.graphics.Bitmap) {
        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
        if (holder != null) {
            setBitmapAndRecyclePrevious(holder, bitmap)
        } else {
            bitmap.recycle()
        }
    }
    }""", """    fun setManualBitmap(position: Int, bitmap: android.graphics.Bitmap) {
        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
        if (holder != null) {
            setBitmapAndRecyclePrevious(holder, bitmap)
        } else {
            bitmap.recycle()
        }
    }""")

with open('./app/src/main/java/top/maary/darkbag/fragments/ImageViewerAdapter.kt', 'w') as f:
    f.write(content)
