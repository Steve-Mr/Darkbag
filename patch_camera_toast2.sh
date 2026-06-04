sed -i 's/Toast.makeText(requireContext(), context, /Toast.makeText(requireContext(), /g' app/src/main/java/top/maary/darkbag/fragments/CameraFragment.kt
sed -i 's/Toast.makeText(requireContext(), safeContext, /Toast.makeText(safeContext, /g' app/src/main/java/top/maary/darkbag/fragments/CameraFragment.kt
sed -i 's/Toast.makeText(requireContext(), appContext, /Toast.makeText(appContext, /g' app/src/main/java/top/maary/darkbag/fragments/CameraFragment.kt
