import sys

filepath = "app/src/main/java/top/maary/darkbag/fragments/ImageViewerFragment.kt"
with open(filepath, 'r') as f:
    content = f.read()

# 1. Update applyEditPreviewInternal
old_preview_composition = """                        if (b1 != null || b2 != null) {
                            val isSBS = currentGroup.hfLayout != "TB"
                            val gap = top.maary.darkbag.utils.HalfFrameUtils.calculateGap(maxOf(b1?.width ?: 0, b1?.height ?: 0)).toFloat()
                            val w1 = b1?.width ?: b2?.width ?: 0
                            val h1 = b1?.height ?: b2?.height ?: 0
                            val w2 = b2?.width ?: w1
                            val h2 = b2?.height ?: h1
                            val resW = if (isSBS) (w1 + gap + w2).toInt() else maxOf(w1, w2)
                            val resH = if (isSBS) maxOf(h1, h2) else (h1 + gap + h2).toInt()

                            val composite = android.graphics.Bitmap.createBitmap(resW, resH, android.graphics.Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(composite)
                            canvas.drawColor(android.graphics.Color.BLACK)
                            b1?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                            b2?.let {
                                if (isSBS) canvas.drawBitmap(it, w1 + gap, 0f, null)
                                else canvas.drawBitmap(it, 0f, h1 + gap, null)
                            }

                            compositeBitmap = top.maary.darkbag.utils.HalfFrameUtils.addEffects(
                                composite,
                                config.showTimestamp,
                                config.flareType >= 0,
                                currentGroup.hfLayout ?: "SBS",
                                time1 = currentGroup.captureTime,
                                time2 = currentGroup.captureTime,
                                flareType = config.flareType
                            )
                            if (compositeBitmap != composite) {
                                composite.recycle()
                            }

                            if (isIndividual) {
                                selectedFrameBitmap = if (selectedDngIndex == 0) {
                                    android.graphics.Bitmap.createBitmap(compositeBitmap!!, 0, 0, w1, h1)
                                } else {
                                    if (isSBS) {
                                        android.graphics.Bitmap.createBitmap(compositeBitmap!!, (w1 + gap).toInt(), 0, w2, h2)
                                    } else {
                                        android.graphics.Bitmap.createBitmap(compositeBitmap!!, 0, (h1 + gap).toInt(), w2, h2)
                                    }
                                }
                            }"""

new_preview_composition = """                        if (b1 != null || b2 != null) {
                            val isSBS = currentGroup.hfLayout != "TB"

                            val refW = b1?.width ?: b2?.width ?: 0
                            val refH = b1?.height ?: b2?.height ?: 0

                            // We need to ensure orientations are correct before composing
                            val oriented1 = b1?.let { top.maary.darkbag.utils.HalfFrameUtils.ensureOrientation(it, isSBS) }
                            val oriented2 = b2?.let { top.maary.darkbag.utils.HalfFrameUtils.ensureOrientation(it, isSBS) }

                            val w1 = oriented1?.width ?: refW
                            val h1 = oriented1?.height ?: refH
                            val w2 = oriented2?.width ?: refW
                            val h2 = oriented2?.height ?: refH
                            val gap = top.maary.darkbag.utils.HalfFrameUtils.calculateGap(maxOf(w1, h1)).toFloat()

                            // If one is missing, create a temporary black bitmap to avoid crashes
                            val tempB1 = oriented1 ?: android.graphics.Bitmap.createBitmap(w2, h2, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
                            val tempB2 = oriented2 ?: android.graphics.Bitmap.createBitmap(w1, h1, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }

                            val composite = top.maary.darkbag.utils.HalfFrameUtils.composeBitmaps(tempB1, tempB2, isSBS)

                            compositeBitmap = top.maary.darkbag.utils.HalfFrameUtils.addEffects(
                                composite,
                                config.showTimestamp,
                                config.flareType >= 0,
                                currentGroup.hfLayout ?: "SBS",
                                time1 = currentGroup.captureTime,
                                time2 = currentGroup.captureTime,
                                flareType = config.flareType
                            )
                            if (compositeBitmap != composite) {
                                composite.recycle()
                            }

                            if (oriented1 != b1) oriented1?.recycle()
                            if (oriented2 != b2) oriented2?.recycle()
                            if (tempB1 != oriented1) tempB1.recycle()
                            if (tempB2 != oriented2) tempB2.recycle()

                            if (isIndividual) {
                                selectedFrameBitmap = if (selectedDngIndex == 0) {
                                    android.graphics.Bitmap.createBitmap(compositeBitmap!!, 0, 0, w1, h1)
                                } else {
                                    if (isSBS) {
                                        android.graphics.Bitmap.createBitmap(compositeBitmap!!, (w1 + gap).toInt(), 0, w2, h2)
                                    } else {
                                        android.graphics.Bitmap.createBitmap(compositeBitmap!!, 0, (h1 + gap).toInt(), w2, h2)
                                    }
                                }
                            }"""
content = content.replace(old_preview_composition, new_preview_composition)


# 2. Update processFullToTiff
old_tiff_composition = """                        if (b1 != null || b2 != null) {
                            val isSBS = currentGroup.hfLayout != "TB"
                            val gap = top.maary.darkbag.utils.HalfFrameUtils.calculateGap(maxOf(b1?.width ?: 0, b1?.height ?: 0)).toFloat()
                            val w1 = b1?.width ?: b2?.width ?: 0
                            val h1 = b1?.height ?: b2?.height ?: 0
                            val w2 = b2?.width ?: w1
                            val h2 = b2?.height ?: h1
                            val resW = if (isSBS) (w1 + gap + w2).toInt() else maxOf(w1, w2)
                            val resH = if (isSBS) maxOf(h1, h2) else (h1 + gap + h2).toInt()

                            val composite = android.graphics.Bitmap.createBitmap(resW, resH, android.graphics.Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(composite)
                            canvas.drawColor(android.graphics.Color.BLACK)
                            b1?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                            b2?.let {
                                if (isSBS) canvas.drawBitmap(it, w1 + gap, 0f, null)
                                else canvas.drawBitmap(it, 0f, h1 + gap, null)
                            }
                            val finalComposite = top.maary.darkbag.utils.HalfFrameUtils.addEffects("""

new_tiff_composition = """                        if (b1 != null || b2 != null) {
                            val isSBS = currentGroup.hfLayout != "TB"

                            val refW = b1?.width ?: b2?.width ?: 0
                            val refH = b1?.height ?: b2?.height ?: 0

                            val oriented1 = b1?.let { top.maary.darkbag.utils.HalfFrameUtils.ensureOrientation(it, isSBS) }
                            val oriented2 = b2?.let { top.maary.darkbag.utils.HalfFrameUtils.ensureOrientation(it, isSBS) }

                            val tempB1 = oriented1 ?: android.graphics.Bitmap.createBitmap(refW, refH, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
                            val tempB2 = oriented2 ?: android.graphics.Bitmap.createBitmap(refW, refH, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }

                            val composite = top.maary.darkbag.utils.HalfFrameUtils.composeBitmaps(tempB1, tempB2, isSBS)

                            if (oriented1 != b1) oriented1?.recycle()
                            if (oriented2 != b2) oriented2?.recycle()
                            if (tempB1 != oriented1) tempB1.recycle()
                            if (tempB2 != oriented2) tempB2.recycle()

                            val finalComposite = top.maary.darkbag.utils.HalfFrameUtils.addEffects("""
content = content.replace(old_tiff_composition, new_tiff_composition)


# 3. Update generateProcessedBitmap
old_save_composition = """                if (b1 != null || b2 != null) {
                    val isSBS = currentGroup.hfLayout != "TB"
                    val gap = top.maary.darkbag.utils.HalfFrameUtils.calculateGap(maxOf(b1?.width ?: 0, b1?.height ?: 0)).toFloat()
                    val w1 = b1?.width ?: b2?.width ?: 0
                    val h1 = b1?.height ?: b2?.height ?: 0
                    val w2 = b2?.width ?: w1
                    val h2 = b2?.height ?: h1
                    val resW = if (isSBS) (w1 + gap + w2).toInt() else maxOf(w1, w2)
                    val resH = if (isSBS) maxOf(h1, h2) else (h1 + gap + h2).toInt()

                    val composite = android.graphics.Bitmap.createBitmap(resW, resH, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(composite)
                    canvas.drawColor(android.graphics.Color.BLACK)
                    b1?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                    b2?.let {
                        if (isSBS) canvas.drawBitmap(it, w1 + gap, 0f, null)
                        else canvas.drawBitmap(it, 0f, h1 + gap, null)
                    }
                    val finalComposite = top.maary.darkbag.utils.HalfFrameUtils.addEffects("""

new_save_composition = """                if (b1 != null || b2 != null) {
                    val isSBS = currentGroup.hfLayout != "TB"

                    val refW = b1?.width ?: b2?.width ?: 0
                    val refH = b1?.height ?: b2?.height ?: 0

                    val oriented1 = b1?.let { top.maary.darkbag.utils.HalfFrameUtils.ensureOrientation(it, isSBS) }
                    val oriented2 = b2?.let { top.maary.darkbag.utils.HalfFrameUtils.ensureOrientation(it, isSBS) }

                    val tempB1 = oriented1 ?: android.graphics.Bitmap.createBitmap(refW, refH, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
                    val tempB2 = oriented2 ?: android.graphics.Bitmap.createBitmap(refW, refH, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }

                    val composite = top.maary.darkbag.utils.HalfFrameUtils.composeBitmaps(tempB1, tempB2, isSBS)

                    if (oriented1 != b1) oriented1?.recycle()
                    if (oriented2 != b2) oriented2?.recycle()
                    if (tempB1 != oriented1) tempB1.recycle()
                    if (tempB2 != oriented2) tempB2.recycle()

                    val finalComposite = top.maary.darkbag.utils.HalfFrameUtils.addEffects("""
content = content.replace(old_save_composition, new_save_composition)


with open(filepath, 'w') as f:
    f.write(content)
