package com.oxi.calc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.DecimalMode
import com.ionspin.kotlin.bignum.decimal.RoundingMode
import com.russhwolf.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class HistoryItem(
    val id: String,
    val expression: String,
    val result: String
)

@OptIn(ExperimentalUuidApi::class)
private fun newHistoryId(): String = Uuid.random().toString()

class CalculatorViewModel(
    private val settings: Settings,
    private val errorText: String
) : ViewModel() {
    var textFieldValue by mutableStateOf(TextFieldValue("0", selection = TextRange(1)))
    var historyTextFieldValue by mutableStateOf(TextFieldValue("", selection = TextRange(0)))
    var isScientificMode by mutableStateOf(false)
    var isDarkMode by mutableStateOf(true)

    private val _calculationHistory = mutableStateListOf<HistoryItem>()
    val calculationHistory: List<HistoryItem> = _calculationHistory

    private var firstOperand: BigDecimal? = null
    private var pendingOperation: String? = null
    private var shouldResetDisplay = false

    private var lastOperand: BigDecimal? = null
    private var lastOperation: String? = null

    private val decimalMode = DecimalMode(
        decimalPrecision = 32L,
        roundingMode = RoundingMode.ROUND_HALF_AWAY_FROM_ZERO
    )
    private val smallThreshold = BigDecimal.parseString("0.00000001")
    private val json = Json { ignoreUnknownKeys = true }

    init {
        loadHistory()
        isDarkMode = settings.getBoolean("is_dark_mode", true)
    }

    fun toggleTheme() {
        isDarkMode = !isDarkMode
        settings.putBoolean("is_dark_mode", isDarkMode)
    }

    fun onDigitClick(digit: String) {
        val currentText = textFieldValue.text

        if (currentText == errorText || currentText == "NaN" || currentText == "Infinity") {
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
                updateText(errorText)
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
        if (textFieldValue.text == errorText) return
        val currentValue = safeToBigDecimal(textFieldValue.text) ?: return

        try {
            if (pendingOperation != null && firstOperand != null) {
                lastOperand = currentValue
                lastOperation = pendingOperation

                val result = calculateResult(firstOperand!!, currentValue, pendingOperation!!)
                val expression = "${formatResult(firstOperand!!)} $pendingOperation ${formatResult(currentValue)} ="
                val resultStr = formatResult(result)

                addHistoryItem(HistoryItem(id = newHistoryId(), expression = expression, result = resultStr))

                historyTextFieldValue = TextFieldValue(expression)
                updateText(resultStr)
                firstOperand = result
                pendingOperation = null
                shouldResetDisplay = true
            } else if (lastOperation != null && lastOperand != null) {
                val result = calculateResult(currentValue, lastOperand!!, lastOperation!!)
                val expression = "${formatResult(currentValue)} $lastOperation ${formatResult(lastOperand!!)} ="
                val resultStr = formatResult(result)

                addHistoryItem(HistoryItem(id = newHistoryId(), expression = expression, result = resultStr))

                historyTextFieldValue = TextFieldValue(expression)
                updateText(resultStr)
                shouldResetDisplay = true
            }
        } catch (e: Exception) {
            updateText(errorText)
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
        if (shouldResetDisplay || textFieldValue.text == errorText) return
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
        val result = currentValue.divide(BigDecimal.fromLong(100), decimalMode)
        updateText(formatResult(result))
        shouldResetDisplay = true
    }

    fun onNegateClick() {
        val text = textFieldValue.text
        if (text == "0" || text == errorText || text.isEmpty()) return
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
                "sin" -> sin(currentValue * PI / 180.0)
                "cos" -> cos(currentValue * PI / 180.0)
                "tan" -> tan(currentValue * PI / 180.0)
                "log" -> if (currentValue <= 0) Double.NaN else log10(currentValue)
                "ln" -> if (currentValue <= 0) Double.NaN else ln(currentValue)
                "sqrt" -> if (currentValue < 0) Double.NaN else sqrt(currentValue)
                "sq" -> currentValue.pow(2.0)
                "pi" -> PI
                "e" -> kotlin.math.E
                else -> currentValue
            }
        } catch (e: Exception) {
            Double.NaN
        }

        if (result.isNaN() || result.isInfinite()) {
            updateText(errorText)
        } else {
            val bigResult = BigDecimal.fromDouble(result).roundToDigitPosition(32L, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)
            val resultStr = formatResult(bigResult)
            val expression = if (operation == "pi" || operation == "e") "$operation =" else "$operation($currentValueStr) ="

            addHistoryItem(HistoryItem(id = newHistoryId(), expression = expression, result = resultStr))

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
            BigDecimal.parseString(input)
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
        viewModelScope.launch(Dispatchers.Default) {
            val jsonStr = json.encodeToString(_calculationHistory.take(100))
            settings.putString("history_json", jsonStr)
        }
    }

    private fun loadHistory() {
        val jsonStr = settings.getStringOrNull("history_json") ?: return
        // Parse off the main thread, then publish on the main thread (snapshot state).
        viewModelScope.launch {
            val items = withContext(Dispatchers.Default) {
                try {
                    json.decodeFromString<List<HistoryItem>>(jsonStr)
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
            "+" -> op1.add(op2, decimalMode)
            "-" -> op1.subtract(op2, decimalMode)
            "×" -> op1.multiply(op2, decimalMode)
            "÷" -> {
                if (op2.signum() == 0) throw ArithmeticException("Division by zero")
                op1.divide(op2, decimalMode)
            }
            "pow" -> BigDecimal.fromDouble(op1.doubleValue(false).pow(op2.doubleValue(false)))
                .roundToDigitPosition(32L, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)
            else -> op2
        }
    }

    private fun formatResult(result: BigDecimal): String {
        val plain = result.toStringExpanded()
        val stripped = if (plain.contains('.')) plain.trimEnd('0').trimEnd('.') else plain

        return when {
            stripped.length > 15 || (result.abs() < smallThreshold && result.signum() != 0) ->
                toScientific(result.doubleValue(false))
            else -> stripped
        }
    }

    /** Locale-independent scientific notation, e.g. "1.2345678901e-05". */
    private fun toScientific(value: Double): String {
        if (value == 0.0 || value.isNaN() || value.isInfinite()) return value.toString()
        val exponent = floor(log10(abs(value))).toInt()
        val mantissa = value / 10.0.pow(exponent)
        val rounded = round(mantissa * 1e10) / 1e10
        val expStr = (if (exponent < 0) "-" else "+") + abs(exponent).toString().padStart(2, '0')
        return "${rounded}e${expStr}"
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
