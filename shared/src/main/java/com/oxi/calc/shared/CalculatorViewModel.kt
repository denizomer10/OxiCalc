package com.oxi.calc.shared

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale
import java.util.UUID
import kotlin.math.*

data class HistoryItem(
    val id: String = UUID.randomUUID().toString(), // Unique ID to prevent crashes in LazyColumn
    val expression: String,
    val result: String
)

class CalculatorViewModel(application: Application) : AndroidViewModel(application) {
    var textFieldValue by mutableStateOf(TextFieldValue("0", selection = TextRange(1)))
    var historyTextFieldValue by mutableStateOf(TextFieldValue("", selection = TextRange(0)))
    var isScientificMode by mutableStateOf(false)
    var isDarkMode by mutableStateOf(true)
    
    private val prefs = application.getSharedPreferences("oxi_calc_prefs", Context.MODE_PRIVATE)

    // Resolved once instead of hitting resources on every key press.
    private val errorString: String by lazy { application.getString(R.string.error) }
    private val _calculationHistory = mutableStateListOf<HistoryItem>()
    val calculationHistory: List<HistoryItem> = _calculationHistory
    
    private var firstOperand: BigDecimal? = null
    private var pendingOperation: String? = null
    private var shouldResetDisplay = false
    
    private var lastOperand: BigDecimal? = null
    private var lastOperation: String? = null

    private val precisionContext = MathContext(32, RoundingMode.HALF_UP)

    init {
        loadHistory()
        isDarkMode = prefs.getBoolean("is_dark_mode", true)
    }

    fun toggleTheme() {
        isDarkMode = !isDarkMode
        prefs.edit().putBoolean("is_dark_mode", isDarkMode).apply()
    }

    fun onDigitClick(digit: String) {
        val currentText = textFieldValue.text
        val errorStr = errorString
        
        if (currentText == errorStr || currentText == "NaN" || currentText == "Infinity") {
            updateText("0")
            shouldResetDisplay = false
        }
        
        if (digit == "." && currentText.contains(".")) {
            if (shouldResetDisplay) {
                updateText("0.")
                shouldResetDisplay = false
            }
            return
        }
        
        if (currentText == "0" || shouldResetDisplay) {
            updateText(if (digit == ".") "0." else digit)
            shouldResetDisplay = false
        } else {
            val selection = textFieldValue.selection
            val start = selection.min.coerceIn(0, currentText.length)
            val end = selection.max.coerceIn(0, currentText.length)
            
            val newText = StringBuilder(currentText)
                .replace(start, end, digit)
                .toString()
            updateText(newText, TextRange(start + digit.length))
        }
    }

    fun updateTextFieldValue(newValue: TextFieldValue) {
        val filteredText = newValue.text.filter { it.isDigit() || it == '.' || it == '-' || it == 'E' || it == '+' }
        textFieldValue = newValue.copy(text = filteredText)
    }

    fun updateHistoryTextFieldValue(newValue: TextFieldValue) {
        historyTextFieldValue = newValue
    }

    private fun updateText(text: String, selection: TextRange? = null) {
        textFieldValue = TextFieldValue(
            text = text,
            selection = selection ?: TextRange(text.length)
        )
    }

    fun onOperationClick(operation: String) {
        val currentValue = safeToBigDecimal(textFieldValue.text) ?: return
        
        if (firstOperand == null) {
            firstOperand = currentValue
        } else if (pendingOperation != null && !shouldResetDisplay) {
            try {
                val result = calculateResult(firstOperand!!, currentValue, pendingOperation!!)
                firstOperand = result
                updateText(formatResult(result))
            } catch (e: Exception) {
                updateText(errorString)
                firstOperand = null
                pendingOperation = null
                return
            }
        } else {
            firstOperand = currentValue
        }
        
        pendingOperation = operation
        historyTextFieldValue = TextFieldValue("${formatResult(firstOperand!!)} $operation")
        shouldResetDisplay = true
        lastOperand = null
        lastOperation = null
    }

    fun onEqualClick() {
        val errorStr = errorString
        if (textFieldValue.text == errorStr) return
        val currentValue = safeToBigDecimal(textFieldValue.text) ?: return
        
        try {
            if (pendingOperation != null && firstOperand != null) {
                lastOperand = currentValue
                lastOperation = pendingOperation
                
                val result = calculateResult(firstOperand!!, currentValue, pendingOperation!!)
                val expression = "${formatResult(firstOperand!!)} $pendingOperation ${formatResult(currentValue)} ="
                val resultStr = formatResult(result)
                
                addHistoryItem(HistoryItem(expression = expression, result = resultStr))
                
                historyTextFieldValue = TextFieldValue(expression)
                updateText(resultStr)
                firstOperand = result
                pendingOperation = null
                shouldResetDisplay = true
            } else if (lastOperation != null && lastOperand != null) {
                val result = calculateResult(currentValue, lastOperand!!, lastOperation!!)
                val expression = "${formatResult(currentValue)} $lastOperation ${formatResult(lastOperand!!)} ="
                val resultStr = formatResult(result)
                
                addHistoryItem(HistoryItem(expression = expression, result = resultStr))
                
                historyTextFieldValue = TextFieldValue(expression)
                updateText(resultStr)
                shouldResetDisplay = true
            }
        } catch (e: Exception) {
            updateText(errorString)
            shouldResetDisplay = true
        }
    }

    fun onClearClick() {
        updateText("0", TextRange(1))
        historyTextFieldValue = TextFieldValue("")
        firstOperand = null
        pendingOperation = null
        lastOperand = null
        lastOperation = null
        shouldResetDisplay = false
    }

    fun onDeleteClick() {
        val errorStr = errorString
        if (shouldResetDisplay || textFieldValue.text == errorStr) return
        val text = textFieldValue.text
        val selection = textFieldValue.selection
        
        if (selection.length > 0) {
            val start = selection.min.coerceIn(0, text.length)
            val end = selection.max.coerceIn(0, text.length)
            val newText = StringBuilder(text).delete(start, end).toString()
            updateText(if (newText.isEmpty()) "0" else newText, TextRange(start.coerceAtMost(if (newText.isEmpty()) 1 else newText.length)))
        } else if (selection.start > 0) {
            val index = selection.start - 1
            val newText = StringBuilder(text).deleteCharAt(index).toString()
            updateText(if (newText.isEmpty()) "0" else newText, TextRange(index))
        }
    }

    fun onPercentClick() {
        val currentValue = safeToBigDecimal(textFieldValue.text) ?: return
        val result = currentValue.divide(BigDecimal("100"), precisionContext)
        updateText(formatResult(result))
        shouldResetDisplay = true
    }

    fun onNegateClick() {
        val text = textFieldValue.text
        val errorStr = errorString
        if (text == "0" || text == errorStr || text.isEmpty()) return
        if (text.startsWith("-")) {
            updateText(text.substring(1))
        } else {
            updateText("-$text")
        }
    }

    fun onScientificClick(operation: String) {
        val currentValueStr = textFieldValue.text
        val currentValue = currentValueStr.toDoubleOrNull() ?: return
        
        val result = try {
            when (operation) {
                "sin" -> sin(Math.toRadians(currentValue))
                "cos" -> cos(Math.toRadians(currentValue))
                "tan" -> tan(Math.toRadians(currentValue))
                "log" -> if (currentValue <= 0) Double.NaN else log10(currentValue)
                "ln" -> if (currentValue <= 0) Double.NaN else ln(currentValue)
                "sqrt" -> if (currentValue < 0) Double.NaN else sqrt(currentValue)
                "sq" -> currentValue.pow(2.0)
                "pi" -> PI
                "e" -> E
                else -> currentValue
            }
        } catch (e: Exception) {
            Double.NaN
        }
        
        if (result.isNaN() || result.isInfinite()) {
            updateText(errorString)
        } else {
            val bigResult = BigDecimal.valueOf(result).round(precisionContext)
            val resultStr = formatResult(bigResult)
            val expression = if (operation == "pi" || operation == "e") "$operation =" else "$operation($currentValueStr) ="
            
            addHistoryItem(HistoryItem(expression = expression, result = resultStr))
            
            historyTextFieldValue = TextFieldValue(expression)
            updateText(resultStr)
        }
        shouldResetDisplay = true
    }

    fun onHistoryItemClick(item: HistoryItem) {
        updateText(item.result)
        historyTextFieldValue = TextFieldValue(item.expression)
        shouldResetDisplay = true
        firstOperand = safeToBigDecimal(item.result)
        pendingOperation = null
    }

    private fun safeToBigDecimal(input: String): BigDecimal? {
        return try {
            BigDecimal(input)
        } catch (e: Exception) {
            null
        }
    }

    private fun addHistoryItem(item: HistoryItem) {
        _calculationHistory.add(0, item)
        saveHistory()
    }

    fun clearHistory() {
        _calculationHistory.clear()
        saveHistory()
    }

    private fun saveHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val jsonArray = JSONArray()
            _calculationHistory.take(100).forEach { 
                val jsonObj = JSONObject()
                jsonObj.put("exp", it.expression)
                jsonObj.put("res", it.result)
                jsonArray.put(jsonObj)
            }
            prefs.edit().putString("history_json", jsonArray.toString()).apply()
        }
    }

    private fun loadHistory() {
        val jsonStr = prefs.getString("history_json", null) ?: return
        // Parse off the main thread, then publish on the main thread (snapshot state).
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                try {
                    val jsonArray = JSONArray(jsonStr)
                    (0 until jsonArray.length()).map {
                        val obj = jsonArray.getJSONObject(it)
                        HistoryItem(expression = obj.getString("exp"), result = obj.getString("res"))
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            if (items.isNotEmpty()) {
                _calculationHistory.clear()
                _calculationHistory.addAll(items)
            }
        }
    }

    private fun calculateResult(op1: BigDecimal, op2: BigDecimal, operation: String): BigDecimal {
        return when (operation) {
            "+" -> op1.add(op2, precisionContext)
            "-" -> op1.subtract(op2, precisionContext)
            "×" -> op1.multiply(op2, precisionContext)
            "÷" -> {
                if (op2.signum() == 0) throw ArithmeticException("Division by zero")
                op1.divide(op2, precisionContext)
            }
            "pow" -> {
                BigDecimal.valueOf(op1.toDouble().pow(op2.toDouble())).round(precisionContext)
            }
            else -> op2
        }
    }

    private fun formatResult(result: BigDecimal): String {
        val stripped = result.stripTrailingZeros()
        val plain = stripped.toPlainString()
        
        return when {
            plain.contains("E") -> plain
            plain.length > 15 || (stripped.abs() < BigDecimal("0.00000001") && stripped.signum() != 0) -> {
                String.format(Locale.US, "%.10e", result.toDouble())
            }
            else -> plain
        }
    }
}

enum class ButtonType { Number, Operation, Special, Scientific }

fun getButtonType(btn: String): ButtonType {
    return when (btn) {
        "÷", "×", "-", "+", "=" -> ButtonType.Operation
        "DEL", "AC", "+/-", "%" -> ButtonType.Special
        else -> ButtonType.Number
    }
}

fun handleAction(btn: String, viewModel: CalculatorViewModel) {
    when (btn) {
        "AC" -> viewModel.onClearClick()
        "DEL" -> viewModel.onDeleteClick()
        "+/-" -> viewModel.onNegateClick()
        "%" -> viewModel.onPercentClick()
        "÷", "×", "-", "+" -> viewModel.onOperationClick(btn)
        "=" -> viewModel.onEqualClick()
        else -> viewModel.onDigitClick(btn)
    }
}
