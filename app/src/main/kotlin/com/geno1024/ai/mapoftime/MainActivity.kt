package com.geno1024.ai.mapoftime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.geno1024.ai.mapoftime.ui.App
import com.geno1024.ai.mapoftime.ui.AppViewModel
import com.geno1024.ai.mapoftime.ui.theme.MapOfTimeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MapOfTimeApp).container
        setContent {
            MapOfTimeTheme {
                val appViewModel: AppViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { AppViewModel(container) }
                    },
                )
                App(appViewModel)
            }
        }
    }
}
