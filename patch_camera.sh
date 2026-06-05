cat << 'INNER_EOF' > replacement.txt
        // Apply WindowInsets to UI Container to avoid system bar overlap
        cameraUiContainerBinding?.root?.let { rootView ->
            var currentBottomInset = 0
            var currentToolbarHeight = 0

            fun updateContainerPadding() {
                rootView.updatePadding(
                    bottom = kotlin.math.max(currentBottomInset, currentToolbarHeight)
                )
            }

            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.mandatorySystemGestures()
                )

                view.updatePadding(
                    left = insets.left,
                    top = insets.top,
                    right = insets.right
                )
                currentBottomInset = insets.bottom
                updateContainerPadding()

                // Update Viewfinder and Lens Group constraints
INNER_EOF
