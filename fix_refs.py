import re
with open('app/src/main/java/top/maary/darkbag/fragments/CameraFragment.kt', 'r') as f:
    content = f.read()

content = content.replace("top.maary.darkbag.processor.HdrPlusRequest(", "top.maary.darkbag.processor.HdrPlusRequest(")
content = content.replace("linearDngPath = linearDngPath", 'linearDngPath = linearDngFile.absolutePath')

with open('app/src/main/java/top/maary/darkbag/fragments/CameraFragment.kt', 'w') as f:
    f.write(content)
