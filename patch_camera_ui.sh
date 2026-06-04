sed -i 's/app:layout_constraintBottom_toTopOf="@id\/floating_toolbar"/app:layout_constraintBottom_toBottomOf="parent"/g' app/src/main/res/layout/camera_ui_container.xml
sed -i '351,385d' app/src/main/res/layout/camera_ui_container.xml
