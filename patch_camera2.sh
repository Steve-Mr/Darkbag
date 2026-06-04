cat << 'INNER_EOF' > replacement2.txt
            val mainActivity = activity as? top.maary.darkbag.MainActivity
            mainActivity?.let {
                viewLifecycleOwner.lifecycleScope.launch {
                    it.toolbarHeightFlow.collect { height ->
                        currentToolbarHeight = height
                        updateContainerPadding()
                    }
                }
            }
INNER_EOF
sed -i '/fun updateContainerPadding() {/r replacement2.txt' app/src/main/java/top/maary/darkbag/fragments/CameraFragment.kt
