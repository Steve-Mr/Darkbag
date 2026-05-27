import sys

filepath = "app/src/main/java/top/maary/darkbag/utils/HalfFrameUtils.kt"
with open(filepath, 'r') as f:
    content = f.read()

# Refactor the file to add composeBitmaps and modify stitchImages
# We will use string replacement for surgical precision.

# 1. Update ensureOrientation to be public and more robust.
old_ensureOrientation = """private fun ensureOrientation(bitmap: Bitmap, wantPortrait: Boolean): Bitmap {
        val isPortrait = bitmap.height > bitmap.width
        val isSquare = bitmap.height == bitmap.width

        // If it's already the desired orientation or square, return as is
        if (isPortrait == wantPortrait || isSquare) return bitmap

        // Make it robust: if we want portrait and it's landscape, or we want landscape and it's portrait, rotate 90.
        // It's always a 90 degree rotation to flip orientation.
        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }"""

new_ensureOrientation = """fun ensureOrientation(bitmap: Bitmap, wantPortrait: Boolean): Bitmap {
        val isPortrait = bitmap.height > bitmap.width
        val isSquare = bitmap.height == bitmap.width

        // If it's already the desired orientation or square, return as is
        if (isPortrait == wantPortrait || isSquare) return bitmap

        val matrix = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }"""

content = content.replace(old_ensureOrientation, new_ensureOrientation)


# 2. Add composeBitmaps
new_composeBitmaps = """
    /**
     * Composes two bitmaps into a single half-frame layout.
     * Assumes the bitmaps have already been correctly oriented (e.g., using ensureOrientation).
     */
    fun composeBitmaps(firstBitmap: Bitmap, secondBitmap: Bitmap, isSideBySide: Boolean): Bitmap {
        val w1 = firstBitmap.width
        val h1 = firstBitmap.height
        val w2 = secondBitmap.width
        val h2 = secondBitmap.height

        // Target size for each slot (use first frame as reference)
        val targetW = w1
        val targetH = h1

        // Divider width
        val divider = calculateGap(maxOf(targetW, targetH))

        return if (isSideBySide) {
            val combinedW = targetW + divider + targetW
            val combinedH = targetH

            val result = Bitmap.createBitmap(combinedW, combinedH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawColor(Color.BLACK)

            // Draw first (Left)
            canvas.drawBitmap(firstBitmap, 0f, 0f, null)
            // Draw second (Right) - scaled to fit if necessary, but keep aspect ratio
            val rect2 = getFitRect(w2, h2, targetW, targetH)
            val dest2 = Rect(targetW + divider + rect2.left, rect2.top, targetW + divider + rect2.right, rect2.bottom)
            canvas.drawBitmap(secondBitmap, Rect(0, 0, w2, h2), dest2, Paint(Paint.FILTER_BITMAP_FLAG))
            result
        } else {
            // Top-bottom: [Img1 / Divider / Img2]
            val combinedW = targetW
            val combinedH = targetH + divider + targetH

            val result = Bitmap.createBitmap(combinedW, combinedH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawColor(Color.BLACK)

            // Draw first (Top)
            canvas.drawBitmap(firstBitmap, 0f, 0f, null)
            // Draw second (Bottom)
            val rect2 = getFitRect(w2, h2, targetW, targetH)
            val dest2 = Rect(rect2.left, targetH + divider + rect2.top, rect2.right, targetH + divider + rect2.bottom)
            canvas.drawBitmap(secondBitmap, Rect(0, 0, w2, h2), dest2, Paint(Paint.FILTER_BITMAP_FLAG))
            result
        }
    }
"""

# 3. Modify stitchImages to use composeBitmaps
old_stitchImages = """    fun stitchImages(
        firstPath: String,
        secondPath: String,
        layout: String,
        downsample: Boolean
    ): Bitmap? {
        val firstRaw = BitmapFactory.decodeFile(firstPath) ?: return null
        val secondRaw = BitmapFactory.decodeFile(secondPath) ?: return null

        val isSideBySide = layout == "Side-by-side" || layout == "左右排列" || layout.contains("side", ignoreCase = true) || layout == "SBS"

        // Side-by-side wants Portrait inputs; Top-bottom wants Landscape inputs
        val firstBitmap = ensureOrientation(firstRaw, isSideBySide)
        val secondBitmap = ensureOrientation(secondRaw, isSideBySide)

        try {
            val w1 = firstBitmap.width
            val h1 = firstBitmap.height
            val w2 = secondBitmap.width
            val h2 = secondBitmap.height

            // Target size for each slot (use first frame as reference)
            val targetW = w1
            val targetH = h1

            // Divider width
            val divider = calculateGap(maxOf(targetW, targetH))

            var resultBitmap = if (isSideBySide) {
                val combinedW = targetW + divider + targetW
                val combinedH = targetH

                val result = Bitmap.createBitmap(combinedW, combinedH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)
                canvas.drawColor(Color.BLACK)

                // Draw first (Left)
                canvas.drawBitmap(firstBitmap, 0f, 0f, null)
                // Draw second (Right) - scaled to fit if necessary, but keep aspect ratio
                val rect2 = getFitRect(w2, h2, targetW, targetH)
                val dest2 = Rect(targetW + divider + rect2.left, rect2.top, targetW + divider + rect2.right, rect2.bottom)
                canvas.drawBitmap(secondBitmap, Rect(0, 0, w2, h2), dest2, Paint(Paint.FILTER_BITMAP_FLAG))
                result
            } else {
                // Top-bottom: [Img1 / Divider / Img2]
                val combinedW = targetW
                val combinedH = targetH + divider + targetH

                val result = Bitmap.createBitmap(combinedW, combinedH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)
                canvas.drawColor(Color.BLACK)

                // Draw first (Top)
                canvas.drawBitmap(firstBitmap, 0f, 0f, null)
                // Draw second (Bottom)
                val rect2 = getFitRect(w2, h2, targetW, targetH)
                val dest2 = Rect(rect2.left, targetH + divider + rect2.top, rect2.right, targetH + divider + rect2.bottom)
                canvas.drawBitmap(secondBitmap, Rect(0, 0, w2, h2), dest2, Paint(Paint.FILTER_BITMAP_FLAG))
                result
            }

            if (downsample) {
                // Digital "film saving": Downsample so the final area is approx equal to a single frame.
                // Combined area is ~2x. Scale factor = sqrt(0.5) ~ 0.707
                val scale = 0.707f
                val scaledW = (resultBitmap.width * scale).toInt()
                val scaledH = (resultBitmap.height * scale).toInt()
                val scaled = Bitmap.createScaledBitmap(resultBitmap, scaledW, scaledH, true)
                if (scaled != resultBitmap) {
                    resultBitmap.recycle()
                }
                return scaled
            }

            return resultBitmap
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during stitching", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error stitching images", e)
            return null
        } finally {
            firstRaw.recycle()
            secondRaw.recycle()
            if (firstBitmap != firstRaw) firstBitmap.recycle()
            if (secondBitmap != secondRaw) secondBitmap.recycle()
        }
    }"""

new_stitchImages = """    fun stitchImages(
        firstPath: String,
        secondPath: String,
        layout: String,
        downsample: Boolean
    ): Bitmap? {
        val firstRaw = BitmapFactory.decodeFile(firstPath) ?: return null
        val secondRaw = BitmapFactory.decodeFile(secondPath) ?: return null

        val isSideBySide = layout == "Side-by-side" || layout == "左右排列" || layout.contains("side", ignoreCase = true) || layout == "SBS"

        // Side-by-side wants Portrait inputs; Top-bottom wants Landscape inputs
        val firstBitmap = ensureOrientation(firstRaw, isSideBySide)
        val secondBitmap = ensureOrientation(secondRaw, isSideBySide)

        try {
            var resultBitmap = composeBitmaps(firstBitmap, secondBitmap, isSideBySide)

            if (downsample) {
                // Digital "film saving": Downsample so the final area is approx equal to a single frame.
                // Combined area is ~2x. Scale factor = sqrt(0.5) ~ 0.707
                val scale = 0.707f
                val scaledW = (resultBitmap.width * scale).toInt()
                val scaledH = (resultBitmap.height * scale).toInt()
                val scaled = Bitmap.createScaledBitmap(resultBitmap, scaledW, scaledH, true)
                if (scaled != resultBitmap) {
                    resultBitmap.recycle()
                }
                return scaled
            }

            return resultBitmap
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during stitching", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error stitching images", e)
            return null
        } finally {
            // Note: we can't recycle firstBitmap/secondBitmap here if we are not creating copies in ensureOrientation.
            // Let's rely on the caller or GC for the original ones, or carefully check if they are the same instance.
            // Since ensureOrientation modifies the bitmap, we should handle recycling carefully.
            if (firstBitmap != firstRaw) firstBitmap.recycle()
            if (secondBitmap != secondRaw) secondBitmap.recycle()
        }
    }"""

content = content.replace(old_stitchImages, new_composeBitmaps + "\n" + new_stitchImages)

# Make getFitRect public as well if needed, but it's used inside composeBitmaps so private is fine for now.

with open(filepath, 'w') as f:
    f.write(content)
