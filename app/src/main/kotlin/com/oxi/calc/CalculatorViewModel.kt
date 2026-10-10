package com.oxi.calc

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oxi.calc.engine.RustEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Immutable
data class HistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val expression: String,
    val result: String
)

class CalculatorViewModel(application: Application) : AndroidViewModel(application) {
    var textFieldValue by mutableStateOf(TextFieldValue("0", selection = TextRange(1)))
    var historyTextFieldValue by mutableStateOf(TextFieldValue("", selection = TextRange(0)))
    var isScientificMode by mutableStateOf(false)
    var isDarkMode by mutableStateOf(true)

    private val prefs = application.getSharedPreferences("oxi_calc_prefs", Context.MODE_PRIVATE)
    private val _calculationHistory = mutableStateListOf<HistoryItem>()
    val calculationHistory: List<HistoryItem> = _calculationHistory

    // Operands are kept as formatted strings; the arithmetic itself lives in the Rust core.
    private var firstOperand: String? = null
    private var pendingOperation: String? = null
    private var shouldResetDisplay = false

    private var lastOperand: String? = null
    private var lastOperation: String? = null

    // Resolved once instead of hitting resources on every key press.
    private val errorString: String by lazy { getApplication<Application>().getString(R.string.error) }

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

        if (currentText == errorString || currentText == "NaN" || currentText == "Infinity") {
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
        val currentValue = textFieldValue.text
        if (!isNumber(currentValue)) return

        if (firstOperand == null) {
            firstOperand = currentValue
        } else if (pendingOperation != null && !shouldResetDisplay) {
            val result = RustEngine.binary(firstOperand!!, currentValue, pendingOperation!!).ifEmpty { null }
            if (result == null) {
                updateText(errorString)
                firstOperand = null
                pendingOperation = null
                return
            }
            firstOperand = result
            updateText(result)
        } else {
            firstOperand = currentValue
        }

        pendingOperation = operation
        historyTextFieldValue = TextFieldValue("${firstOperand!!} $operation")
        shouldResetDisplay = true
        lastOperand = null
        lastOperation = null
    }

    fun onEqualClick() {
        if (textFieldValue.text == errorString) return
        val currentValue = textFieldValue.text
        if (!isNumber(currentValue)) return

        if (pendingOperation != null && firstOperand != null) {
            val result = RustEngine.binary(firstOperand!!, currentValue, pendingOperation!!).ifEmpty { null }
            if (result == null) {
                updateText(errorString)
                shouldResetDisplay = true
                return
            }
            lastOperand = currentValue
            lastOperation = pendingOperation

            val expression = "${firstOperand!!} $pendingOperation $currentValue ="
            addHistoryItem(HistoryItem(expression = expression, result = result))

            historyTextFieldValue = TextFieldValue(expression)
            updateText(result)
            firstOperand = result
            pendingOperation = null
            shouldResetDisplay = true
        } else if (lastOperation != null && lastOperand != null) {
            val result = RustEngine.binary(currentValue, lastOperand!!, lastOperation!!).ifEmpty { null }
            if (result == null) {
                updateText(errorString)
                shouldResetDisplay = true
                return
            }
            val expression = "$currentValue $lastOperation ${lastOperand!!} ="
            addHistoryItem(HistoryItem(expression = expression, result = result))

            historyTextFieldValue = TextFieldValue(expression)
            updateText(result)
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
        if (shouldResetDisplay || textFieldValue.text == errorString) return
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
        val result = RustEngine.unary("percent", textFieldValue.text).ifEmpty { null } ?: return
        updateText(result)
        shouldResetDisplay = true
    }

    fun onNegateClick() {
        val text = textFieldValue.text
        if (text == "0" || text == errorString || text.isEmpty()) return
        if (text.startsWith("-")) {
            updateText(text.substring(1))
        } else {
            updateText("-$text")
        }
    }

    fun onScientificClick(operation: String) {
        val currentValueStr = textFieldValue.text
        val resultStr = RustEngine.unary(operation, currentValueStr).ifEmpty { null }

        if (resultStr == null) {
            updateText(errorString)
        } else {
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
        firstOperand = item.result
        pendingOperation = null
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

    /** Light client-side guard so obviously invalid text isn't treated as an operand. */
    private fun isNumber(text: String): Boolean {
        if (text.isEmpty()) return false
        var hasDigit = false
        text.forEachIndexed { index, c ->
            when {
                c.isDigit() -> hasDigit = true
                c == '.' || c == '+' || c == 'E' || c == 'e' -> Unit
                c == '-' -> if (index != 0) return false
                else -> return false
            }
        }
        return hasDigit
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
