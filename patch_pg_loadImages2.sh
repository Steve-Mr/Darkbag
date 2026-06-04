sed -i 's/viewModel.loadImages()/(binding.recyclerView.adapter as PlaygroundAdapter).refresh()/g' app/src/main/java/top/maary/darkbag/fragments/PlaygroundGalleryFragment.kt
