import sys

filepath = "app/src/main/java/top/maary/darkbag/utils/ImageUtils.kt"
with open(filepath, 'r') as f:
    content = f.read()

old_generateHalfFrameComposite = """    suspend fun generateHalfFrameComposite(
        context: Context,
        uri1: Uri?,
        uri2: Uri?,
        layout: String?, // "SBS" or "TB"
        zoomFactor: Float = 1.0f
    ): Bitmap? = withContext(Dispatchers.IO) {
        val bit1 = uri1?.let { decodeDngThumbnail(context, it, zoomFactor) }
        val bit2 = uri2?.let { decodeDngThumbnail(context, it, zoomFactor) }

        if (bit1 == null && bit2 == null) return@withContext null

        val wantPortrait = layout != "TB"
        val oriented1 = bit1?.let { ensureOrientation(it, wantPortrait) }
        val oriented2 = bit2?.let { ensureOrientation(it, wantPortrait) }

        try {
            // Use first available frame as reference for dimensions
            val ref = oriented1 ?: oriented2!!
            val w = ref.width
            val h = ref.height

            val isSBS = layout != "TB"
            // Ensure gap calculation matches HalfFrameUtils (use long dimension of single frame)
            val gap = HalfFrameUtils.calculateGap(maxOf(w, h)).toFloat()

            val resultW = if (isSBS) (w * 2 + gap).toInt() else w
            val resultH = if (isSBS) h else (h * 2 + gap).toInt()

            val composite = Bitmap.createBitmap(resultW, resultH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(composite)
            canvas.drawColor(Color.BLACK)

            val paint = Paint(Paint.FILTER_BITMAP_FLAG)

            if (isSBS) {
                if (oriented1 != null) {
                    canvas.drawBitmap(oriented1, 0f, 0f, paint)
                } else {
                    drawPlaceholder(context, canvas, RectF(0f, 0f, w.toFloat(), h.toFloat()))
                }
                if (oriented2 != null) {
                    canvas.drawBitmap(oriented2, w + gap, 0f, paint)
                } else {
                    drawPlaceholder(context, canvas, RectF(w + gap, 0f, w * 2 + gap, h.toFloat()))
                }
            } else {
                if (oriented1 != null) {
                    canvas.drawBitmap(oriented1, 0f, 0f, paint)
                } else {
                    drawPlaceholder(context, canvas, RectF(0f, 0f, w.toFloat(), h.toFloat()))
                }
                if (oriented2 != null) {
                    canvas.drawBitmap(oriented2, 0f, h + gap, paint)
                } else {
                    drawPlaceholder(context, canvas, RectF(0f, h + gap, w.toFloat(), h * 2 + gap))
                }
            }

            return@withContext composite
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "Failed to generate composite", e)
            null
        } finally {
            // Cleanup oriented bitmaps as they are intermediate
            oriented1?.recycle()
            oriented2?.recycle()
        }
    }"""

new_generateHalfFrameComposite = """    suspend fun generateHalfFrameComposite(
        context: Context,
        uri1: Uri?,
        uri2: Uri?,
        layout: String?, // "SBS" or "TB"
        zoomFactor: Float = 1.0f
    ): Bitmap? = withContext(Dispatchers.IO) {
        val bit1 = uri1?.let { decodeDngThumbnail(context, it, zoomFactor) }
        val bit2 = uri2?.let { decodeDngThumbnail(context, it, zoomFactor) }

        if (bit1 == null && bit2 == null) return@withContext null

        val wantPortrait = layout != "TB"
        val oriented1 = bit1?.let { ensureOrientation(it, wantPortrait) }
        val oriented2 = bit2?.let { ensureOrientation(it, wantPortrait) }

        try {
            // Use first available frame as reference for dimensions
            val ref = oriented1 ?: oriented2!!
            val w = ref.width
            val h = ref.height

            val isSBS = layout != "TB"

            val final1 = oriented1 ?: createPlaceholderBitmap(context, w, h)
            val final2 = oriented2 ?: createPlaceholderBitmap(context, w, h)

            return@withContext HalfFrameUtils.composeBitmaps(final1, final2, isSBS)
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "Failed to generate composite", e)
            null
        } finally {
            // Cleanup oriented bitmaps as they are intermediate
            oriented1?.recycle()
            oriented2?.recycle()
            // Note: we don't recycle bit1/bit2 as ensureOrientation might return the same instance
        }
    }

    private fun createPlaceholderBitmap(context: Context, w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val paint = Paint().apply {
            color = Color.DKGRAY
            style = Paint.Style.FILL
        }
        canvas.drawRect(rect, paint)

        val icon = androidx.core.content.ContextCompat.getDrawable(context, android.R.drawable.ic_menu_gallery)
        icon?.let {
            val iconSize = (kotlin.math.min(w, h) * 0.3f).toInt()
            val left = (w / 2) - (iconSize / 2)
            val top = (h / 2) - (iconSize / 2)
            it.setBounds(left, top, left + iconSize, top + iconSize)
            it.setTint(Color.LTGRAY)
            it.draw(canvas)
        }
        return bitmap
    }"""

# Also remove drawPlaceholder method as it's no longer used
old_drawPlaceholder = """    private fun drawPlaceholder(context: Context, canvas: Canvas, rect: RectF) {
        val paint = Paint().apply {
            color = Color.DKGRAY
            style = Paint.Style.FILL
        }
        canvas.drawRect(rect, paint)

        val icon = androidx.core.content.ContextCompat.getDrawable(context, android.R.drawable.ic_menu_gallery)
        icon?.let {
            val iconSize = (kotlin.math.min(rect.width(), rect.height()) * 0.3f).toInt()
            val left = (rect.centerX() - iconSize / 2).toInt()
            val top = (rect.centerY() - iconSize / 2).toInt()
            it.setBounds(left, top, left + iconSize, top + iconSize)
            it.setTint(Color.LTGRAY)
            it.draw(canvas)
        }
    }"""

content = content.replace(old_generateHalfFrameComposite, new_generateHalfFrameComposite)
content = content.replace(old_drawPlaceholder, "")

with open(filepath, 'w') as f:
    f.write(content)
