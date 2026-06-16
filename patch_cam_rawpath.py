import re

with open('app/src/main/java/top/maary/darkbag/fragments/CameraFragment.kt', 'r') as f:
    content = f.read()

content = content.replace("tempRawPath = tempRawFile.absolutePath,", "")
content = content.replace("tempRawFile.absolutePath,", "")

with open('app/src/main/java/top/maary/darkbag/fragments/CameraFragment.kt', 'w') as f:
    f.write(content)
