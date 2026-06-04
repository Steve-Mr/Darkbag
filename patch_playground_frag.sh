sed -i '445,458c\
    }\
\
    override fun onResume() {\
        super.onResume()\
        // Trigger a fresh load to catch any changes from PlaygroundViewerFragment\
        loadImages()\
    }\
' app/src/main/java/top/maary/darkbag/fragments/PlaygroundGalleryFragment.kt
