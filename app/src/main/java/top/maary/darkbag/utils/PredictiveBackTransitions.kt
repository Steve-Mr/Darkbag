package top.maary.darkbag.utils

import androidx.fragment.app.Fragment
import com.google.android.material.transition.MaterialSharedAxis

/**
 * Configures Material Motion shared-axis transitions that support predictive back
 * progress seeking when navigating between top-level and secondary fragments.
 */
object PredictiveBackTransitions {
    fun apply(fragment: Fragment, axis: Int = MaterialSharedAxis.X) {
        fragment.enterTransition = MaterialSharedAxis(axis, true)
        fragment.exitTransition = MaterialSharedAxis(axis, true)
        fragment.returnTransition = MaterialSharedAxis(axis, false)
        fragment.reenterTransition = MaterialSharedAxis(axis, false)
    }
}
