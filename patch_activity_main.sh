cat << 'INNER_EOF' > app/src/main/res/layout/activity_main.xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    tools:context=".MainActivity">

    <androidx.fragment.app.FragmentContainerView
        android:id="@+id/fragment_container"
        android:name="androidx.navigation.fragment.NavHostFragment"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:keepScreenOn="true"
        app:defaultNavHost="true"
        app:navGraph="@navigation/nav_graph"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <com.google.android.material.card.MaterialCardView
        android:id="@+id/floating_toolbar"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        app:cardElevation="4dp"
        app:cardCornerRadius="24dp"
        app:cardBackgroundColor="?attr/colorSurfaceContainerHigh"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="8dp"
        android:layout_marginBottom="16dp">

        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:padding="4dp">

            <com.google.android.material.button.MaterialButton
                android:id="@+id/floating_toolbar_button_camera"
                style="@style/Widget.Material3.Button.IconButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:checkable="true"
                android:contentDescription="Camera"
                app:icon="@drawable/ic_mode_normal" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/floating_toolbar_button_playground"
                style="@style/Widget.Material3.Button.IconButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:checkable="true"
                android:contentDescription="Playground"
                app:icon="@drawable/ic_photo" />
        </LinearLayout>

    </com.google.android.material.card.MaterialCardView>

</androidx.constraintlayout.widget.ConstraintLayout>
INNER_EOF
