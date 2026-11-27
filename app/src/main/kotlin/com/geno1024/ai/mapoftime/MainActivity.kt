package com.geno1024.ai.mapoftime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.geno1024.ai.mapoftime.ui.App
import com.geno1024.ai.mapoftime.ui.AppViewModel
import com.geno1024.ai.mapoftime.ui.theme.MapOfTimeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the system bars on purpose: Scaffold below applies the
        // status bar inset to the tab content, so the header sits under the
        // clock instead of underneath it.
        enableEdgeToEdge()
        val container = (application as MapOfTimeApp).container
        setContent {
            val appViewModel: AppViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { AppViewModel(container) }
                },
            )
            // One preference swaps the whole shell and colour scheme. No
            // Activity recreate: the old tree simply leaves composition.
            val appMode by appViewModel.settings.state.collectAsStateWithLifecycle()
            MapOfTimeTheme(appMode = appMode.appMode) {
                App(appViewModel, appMode.appMode)
            }
        }
    }
}
