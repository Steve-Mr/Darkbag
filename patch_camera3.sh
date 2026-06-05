cat << 'INNER_EOF' > replacement3.txt
        cameraUiContainerBinding?.root?.let { rootView ->
            var currentBottomInset = 0
            var currentToolbarHeight = 0

            fun updateContainerPadding() {
                rootView.updatePadding(
                    bottom = kotlin.math.max(currentBottomInset, currentToolbarHeight)
                )
            }

            val mainActivity = activity as? top.maary.darkbag.MainActivity
            mainActivity?.let {
                viewLifecycleOwner.lifecycleScope.launch {
                    it.toolbarHeightFlow.collect { height ->
                        currentToolbarHeight = height
                        updateContainerPadding()
                    }
                }
            }

            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
INNER_EOF
sed -i -e '/cameraUiContainerBinding?.root?.let { rootView ->/,/ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->/!b' -e '/cameraUiContainerBinding?.root?.let { rootView ->/r replacement3.txt' -e 'd' app/src/main/java/top/maary/darkbag/fragments/CameraFragment.kt
