import re

with open('app/src/main/java/top/maary/darkbag/processor/ColorProcessor.kt', 'r') as f:
    content = f.read()

# remove exportHdrPlus
content = re.sub(r"""\s*external fun exportHdrPlus\([^)]*\): Int\s*""", "\n", content, flags=re.DOTALL)

# remove tempRawPath from processSingleFrameRaw
content = content.replace("tempRawPath: String? = null,", "")

# remove tempRawPath from processHdrPlus
content = content.replace("outputBitmap: android.graphics.Bitmap? = null,\n        tempRawPath: String? = null,\n        zoomFactor: Float,", "outputBitmap: android.graphics.Bitmap? = null,\n        zoomFactor: Float,")

with open('app/src/main/java/top/maary/darkbag/processor/ColorProcessor.kt', 'w') as f:
    f.write(content)
