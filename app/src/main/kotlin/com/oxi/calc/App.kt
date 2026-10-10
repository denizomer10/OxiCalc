package com.oxi.calc

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oxi.calc.ui.theme.OxiCalcTheme

@Composable
fun App() {
    val viewModel: CalculatorViewModel = viewModel()

    OxiCalcTheme(darkTheme = viewModel.isDarkMode) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            CalculatorScreen(viewModel = viewModel)
        }
    }
}
