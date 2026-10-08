package com.oxi.calc

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oxi.calc.resources.Res
import com.oxi.calc.resources.error
import com.oxi.calc.ui.theme.OxiCalcTheme
import org.jetbrains.compose.resources.stringResource

@Composable
fun App() {
    val settings = rememberSettings()
    val errorText = stringResource(Res.string.error)
    val viewModel: CalculatorViewModel = viewModel { CalculatorViewModel(settings, errorText) }

    OxiCalcTheme(darkTheme = viewModel.isDarkMode) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            CalculatorScreen(viewModel = viewModel)
        }
    }
}
