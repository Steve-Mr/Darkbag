import sys

# 1. Fix HalfFrameUtils.kt
filepath_hf = "app/src/main/java/top/maary/darkbag/utils/HalfFrameUtils.kt"
with open(filepath_hf, 'r') as f:
    content_hf = f.read()

# Fix ensureOrientation to NOT recycle the input
old_ensureOrientation = """fun ensureOrientation(bitmap: Bitmap, wantPortrait: Boolean): Bitmap {
        val isPortrait = bitmap.height > bitmap.width
        val isSquare = bitmap.height == bitmap.width

        // If it's already the desired orientation or square, return as is
        if (isPortrait == wantPortrait || isSquare) return bitmap

        val matrix = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }"""

new_ensureOrientation = """fun ensureOrientation(bitmap: Bitmap, wantPortrait: Boolean): Bitmap {
        val isPortrait = bitmap.height > bitmap.width
        val isSquare = bitmap.height == bitmap.width

        // If it's already the desired orientation or square, return as is
        if (isPortrait == wantPortrait || isSquare) return bitmap

        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }"""
content_hf = content_hf.replace(old_ensureOrientation, new_ensureOrientation)

# Fix stitchImages finally block
old_stitch_finally = """        } finally {
            // Note: we can't recycle firstBitmap/secondBitmap here if we are not creating copies in ensureOrientation.
            // Let's rely on the caller or GC for the original ones, or carefully check if they are the same instance.
            // Since ensureOrientation modifies the bitmap, we should handle recycling carefully.
            if (firstBitmap != firstRaw) firstBitmap.recycle()
            if (secondBitmap != secondRaw) secondBitmap.recycle()
        }"""

new_stitch_finally = """        } finally {
            firstRaw.recycle()
            secondRaw.recycle()
            if (firstBitmap != firstRaw) firstBitmap.recycle()
            if (secondBitmap != secondRaw) secondBitmap.recycle()
        }"""
content_hf = content_hf.replace(old_stitch_finally, new_stitch_finally)

with open(filepath_hf, 'w') as f:
    f.write(content_hf)

# 2. Fix ImageUtils.kt
filepath_img = "app/src/main/java/top/maary/darkbag/utils/ImageUtils.kt"
with open(filepath_img, 'r') as f:
    content_img = f.read()

old_generateHalfFrameComposite = """            val isSBS = layout != "TB"

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
        }"""

new_generateHalfFrameComposite = """            val isSBS = layout != "TB"

            val final1 = oriented1 ?: createPlaceholderBitmap(context, w, h)
            val final2 = oriented2 ?: createPlaceholderBitmap(context, w, h)

            val composite = HalfFrameUtils.composeBitmaps(final1, final2, isSBS)

            if (final1 != oriented1) final1.recycle()
            if (final2 != oriented2) final2.recycle()

            return@withContext composite
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "Failed to generate composite", e)
            null
        } finally {
            // Cleanup oriented bitmaps as they are intermediate
            if (oriented1 != bit1) oriented1?.recycle()
            if (oriented2 != bit2) oriented2?.recycle()
        }"""
content_img = content_img.replace(old_generateHalfFrameComposite, new_generateHalfFrameComposite)

with open(filepath_img, 'w') as f:
    f.write(content_img)

# 3. Fix ImageViewerFragment.kt
# We just need to make sure the recycling handles the new behavior of ensureOrientation safely,
# but we already did that well in ImageViewerFragment! We have:
# val oriented1 = b1?.let { ... }
# if (oriented1 != b1) oriented1?.recycle()
# This logic is completely correct now that ensureOrientation doesn't recycle.
