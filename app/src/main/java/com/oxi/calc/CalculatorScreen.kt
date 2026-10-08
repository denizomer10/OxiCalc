package com.oxi.calc

import android.content.res.Configuration
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oxi.calc.shared.CalculatorViewModel
import com.oxi.calc.shared.HistoryItem
import com.oxi.calc.shared.ButtonType
import com.oxi.calc.shared.getButtonType
import com.oxi.calc.shared.handleAction
import com.oxi.calc.shared.R
import kotlin.math.roundToInt

@Composable
fun CalculatorScreen(viewModel: CalculatorViewModel = viewModel()) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isDarkMode = viewModel.isDarkMode

    val density = LocalDensity.current
    val isScientific = viewModel.isScientificMode
    val historyItems = viewModel.calculationHistory
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
            // BoxWithConstraints is our brain for multi-device scaling
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val screenHeight = maxHeight
                val screenWidth = maxWidth
                val isTablet = screenWidth > 600.dp
                
                // Side-by-side layout for tablets in landscape
                if (isTablet && isLandscape) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Permanent History Panel for Tablets
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            IntegratedHistoryList(
                                historyItems = historyItems,
                                onItemClick = { viewModel.onHistoryItemClick(it) },
                                onClear = { viewModel.clearHistory() }
                            )
                        }
                        
                        // Main Calculator Area
                        Column(
                            modifier = Modifier
                                .weight(2f)
                                .fillMaxHeight()
                                .windowInsetsPadding(WindowInsets.safeDrawing)
                                .padding(horizontal = 32.dp, vertical = 8.dp)
                        ) {
                            TopBar(
                                onModeToggle = { viewModel.isScientificMode = !viewModel.isScientificMode },
                                onThemeToggle = { viewModel.toggleTheme() },
                                isScientific = isScientific,
                                isDarkMode = isDarkMode,
                                isLandscape = isLandscape
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))

                            DisplaySection(
                                viewModel = viewModel,
                                isLandscape = isLandscape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 120.dp)
                                    .weight(1f)
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            AdaptiveKeypadArea(
                                viewModel = viewModel,
                                isLandscape = isLandscape,
                                isScientific = isScientific,
                                availableHeight = screenHeight,
                                modifier = Modifier.weight(3f).padding(horizontal = 48.dp) // Extra padding for wide tablets
                            )
                        }
                    }
                } else {
                    // Standard Phone / Portrait Tablet Layout (Overlay History)
                    val maxHistoryHeight = remember(screenHeight, isLandscape) {
                        if (isLandscape) screenHeight * 0.45f else screenHeight * 0.35f
                    }
                    val maxHistoryHeightPx = with(density) { maxHistoryHeight.toPx() }
                    
                    var historyOffsetPx by remember { mutableStateOf(0f) }
                    
                    val animatedOffset by animateFloatAsState(
                        targetValue = historyOffsetPx,
                        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioLowBouncy),
                        label = "historyOffset"
                    )

                    Box(modifier = Modifier.fillMaxSize()) {
                        // HISTORY LAYER (Performance optimized with graphicsLayer)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(maxHistoryHeight)
                                .graphicsLayer { 
                                    translationY = animatedOffset - maxHistoryHeightPx
                                }
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.98f))
                                .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                        ) {
                            IntegratedHistoryList(
                                historyItems = historyItems,
                                onItemClick = { item -> 
                                    viewModel.onHistoryItemClick(item)
                                    historyOffsetPx = 0f
                                },
                                onClear = { viewModel.clearHistory() }
                            )
                        }

                        // MAIN UI LAYER
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { 
                                    translationY = animatedOffset 
                                }
                                .windowInsetsPadding(WindowInsets.safeDrawing)
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            TopBar(
                                onModeToggle = { viewModel.isScientificMode = !viewModel.isScientificMode },
                                onThemeToggle = { viewModel.toggleTheme() },
                                isScientific = isScientific,
                                isDarkMode = isDarkMode,
                                isLandscape = isLandscape
                            )

                            HistoryHandle(
                                onDrag = { delta ->
                                    historyOffsetPx = (historyOffsetPx + delta).coerceIn(0f, maxHistoryHeightPx)
                                },
                                onDragStopped = {
                                    historyOffsetPx = if (historyOffsetPx > maxHistoryHeightPx / 2) maxHistoryHeightPx else 0f
                                },
                                onToggle = {
                                    historyOffsetPx = if (historyOffsetPx > 0) 0f else maxHistoryHeightPx
                                }
                            )

                            DisplaySection(
                                viewModel = viewModel,
                                isLandscape = isLandscape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = if (isLandscape) 60.dp else 100.dp)
                                    .weight(if (isLandscape) 0.8f else 1.2f)
                            )

                            Spacer(modifier = Modifier.height(if (isLandscape) 8.dp else 14.dp))

                            AdaptiveKeypadArea(
                                viewModel = viewModel,
                                isLandscape = isLandscape,
                                isScientific = isScientific,
                                availableHeight = screenHeight,
                                modifier = Modifier.weight(if (isLandscape) 3.5f else 4.5f)
                            )
                        }
                    }
                }
            }
        }
}

@Composable
fun TopBar(
    onModeToggle: () -> Unit,
    onThemeToggle: () -> Unit,
    isScientific: Boolean,
    isDarkMode: Boolean,
    isLandscape: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isLandscape) 36.dp else 48.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = onThemeToggle,
            shape = CircleShape,
            color = if (isDarkMode) Color.White else Color.Black,
            modifier = Modifier.size(if (isLandscape) 30.dp else 38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                val icon = if (isDarkMode) "☀️" else "🌙"
                val tint = if (isDarkMode) Color.Black else Color.White
                Text(icon, fontSize = if (isLandscape) 14.sp else 18.sp, color = tint)
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Surface(
            onClick = onModeToggle,
            shape = CircleShape,
            color = if (isScientific) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(if (isLandscape) 30.dp else 38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    if (isScientific) stringResource(R.string.scientific_mode) else stringResource(R.string.standard_mode), 
                    fontSize = if (isLandscape) 12.sp else 16.sp, 
                    fontWeight = FontWeight.Bold,
                    color = if (isScientific) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun AdaptiveKeypadArea(
    viewModel: CalculatorViewModel,
    isLandscape: Boolean,
    isScientific: Boolean,
    availableHeight: Dp,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        if (isLandscape) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AnimatedVisibility(
                    visible = isScientific,
                    enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
                    modifier = Modifier.weight(1f)
                ) {
                    ScientificPanel(viewModel, isLandscape = true)
                }
                
                Box(modifier = Modifier.weight(if (isScientific) 1.2f else 1f)) {
                    LandscapeKeypad(viewModel)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = isScientific,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column {
                        ScientificPanel(viewModel, isLandscape = false)
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }
                
                Box(modifier = Modifier.weight(1f)) {
                    PortraitLayout(viewModel)
                }
            }
        }
    }
}

@Composable
fun DisplaySection(viewModel: CalculatorViewModel, isLandscape: Boolean, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    val historyScrollState = rememberScrollState()
    val colorScheme = MaterialTheme.colorScheme

    LaunchedEffect(viewModel.textFieldValue.text) {
        if (scrollState.maxValue > 0) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 24.dp, vertical = if (isLandscape) 4.dp else 12.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center
        ) {
            SelectionContainer {
                BasicTextField(
                    value = viewModel.historyTextFieldValue,
                    onValueChange = { viewModel.updateHistoryTextFieldValue(it) },
                    readOnly = true,
                    textStyle = TextStyle(
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = if (isLandscape) 11.sp else 16.sp,
                        textAlign = TextAlign.End
                    ),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(historyScrollState),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            SelectionContainer {
                BasicTextField(
                    value = viewModel.textFieldValue,
                    onValueChange = { viewModel.updateTextFieldValue(it) },
                    readOnly = true, 
                    enabled = true,
                    textStyle = TextStyle(
                        color = colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        fontSize = when {
                            // Aggressive font scaling for extreme landscape zoom
                            viewModel.textFieldValue.text.length > 20 -> if (isLandscape) 12.sp else 22.sp
                            viewModel.textFieldValue.text.length > 12 -> if (isLandscape) 18.sp else 32.sp
                            else -> if (isLandscape) 24.sp else 60.sp
                        },
                        textAlign = TextAlign.End
                    ),
                    cursorBrush = SolidColor(colorScheme.primary),
                    interactionSource = remember { MutableInteractionSource() },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (scrollState.maxValue > 0) Modifier.horizontalScroll(scrollState) else Modifier),
                    singleLine = true
                )
            }
        }
    }
}

@Composable
fun PortraitLayout(viewModel: CalculatorViewModel) {
    val buttons = remember {
        listOf(
            listOf("DEL", "AC", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "-"),
            listOf("1", "2", "3", "+"),
            listOf("+/-", "0", ".", "=")
        )
    }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        buttons.forEach { row ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { btn ->
                    CalcButton(text = btn, modifier = Modifier.weight(1f), type = getButtonType(btn), isWide = false) { handleAction(btn, viewModel) }
                }
            }
        }
    }
}

@Composable
fun LandscapeKeypad(viewModel: CalculatorViewModel) {
    val buttons = remember {
        listOf(
            listOf("DEL", "AC", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "-"),
            listOf("1", "2", "3", "+"),
            listOf("+/-", "0", ".", "=")
        )
    }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { row ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { btn ->
                    CalcButton(text = btn, modifier = Modifier.weight(1f), type = getButtonType(btn), isWide = true) { handleAction(btn, viewModel) }
                }
            }
        }
    }
}

@Composable
fun ScientificPanel(viewModel: CalculatorViewModel, isLandscape: Boolean) {
    val sciButtons = remember {
        listOf(
            listOf("sin", "cos", "tan", "log", "ln"),
            listOf("sqrt", "sq", "pi", "e", "pow")
        )
    }

    if (isLandscape) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sciButtons.forEach { row ->
                Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { btn ->
                        CalcButton(text = btn, modifier = Modifier.weight(1f), type = ButtonType.Scientific, isWide = true) {
                            if (btn == "pow") viewModel.onOperationClick("pow") else viewModel.onScientificClick(btn)
                        }
                    }
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            sciButtons.forEach { row ->
                Row(modifier = Modifier.height(48.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { btn ->
                        CalcButton(text = btn, modifier = Modifier.weight(1f), type = ButtonType.Scientific, isWide = false) {
                            if (btn == "pow") viewModel.onOperationClick("pow") else viewModel.onScientificClick(btn)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}

@Composable
fun CalcButton(text: String, type: ButtonType, isWide: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.90f else 1f, animationSpec = spring(stiffness = Spring.StiffnessMedium), label = "scale")
    val colorScheme = MaterialTheme.colorScheme
    
    val containerColor = remember(type, colorScheme) {
        when (type) {
            ButtonType.Number -> colorScheme.surfaceVariant
            ButtonType.Operation -> colorScheme.primary
            ButtonType.Special -> colorScheme.secondaryContainer
            ButtonType.Scientific -> colorScheme.tertiaryContainer.copy(alpha = 0.6f)
        }
    }
    val contentColor = remember(type, colorScheme) {
        when (type) {
            ButtonType.Number -> colorScheme.onSurfaceVariant
            ButtonType.Operation -> colorScheme.onPrimary
            ButtonType.Special -> colorScheme.onSecondaryContainer
            ButtonType.Scientific -> colorScheme.onTertiaryContainer
        }
    }

    Box(
        modifier = modifier
            .padding(1.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                clip = true
                shape = if (isWide) RoundedCornerShape(20.dp) else CircleShape
            }
            .background(containerColor)
            .then(if (!isWide) Modifier.fillMaxSize(0.88f).aspectRatio(1f) else Modifier.fillMaxHeight())
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (text == "DEL") "⌫" else text,
            // Strict text limits for buttons in extreme zoom
            style = TextStyle(
                color = contentColor,
                fontWeight = FontWeight.Medium,
                fontSize = if (type == ButtonType.Scientific) 12.sp else if (isWide) 16.sp else 22.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun HistoryHandle(onDrag: (Float) -> Unit, onDragStopped: () -> Unit, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> onDrag(delta) },
                onDragStopped = { onDragStopped() }
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onToggle() },
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.width(40.dp).height(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)))
    }
}

@Composable
fun IntegratedHistoryList(historyItems: List<HistoryItem>, onItemClick: (HistoryItem) -> Unit, onClear: () -> Unit) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val screenWidth = configuration.screenWidthDp.dp
    val isTablet = screenWidth > 600.dp

    // Dynamic scaling based on device and orientation
    val titleFontSize = when {
        isTablet -> 24.sp
        isLandscape -> 18.sp
        else -> 20.sp
    }
    
    val itemExpFontSize = when {
        isTablet -> 14.sp
        isLandscape -> 11.sp
        else -> 12.sp
    }
    
    val itemResFontSize = when {
        isTablet -> 22.sp
        isLandscape -> 16.sp
        else -> 18.sp
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (isTablet) 24.dp else 16.dp)
            .padding(top = if (!isLandscape && !isTablet) 12.dp else 0.dp) // Extra top padding for vertical phone overlay
    ) {
        Text(
            text = stringResource(R.string.history), 
            style = TextStyle(
                fontSize = titleFontSize,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        
        Spacer(modifier = Modifier.height(if (isTablet) 12.dp else 8.dp))
        
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (isTablet) 12.dp else 8.dp)
        ) {
            items(historyItems, key = { it.id }) { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onItemClick(item) }
                        .padding(if (isTablet) 12.dp else 8.dp), 
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = item.expression, 
                        fontSize = itemExpFontSize, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Text(
                        text = item.result, 
                        fontSize = itemResFontSize, 
                        color = MaterialTheme.colorScheme.primary, 
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            if (historyItems.isNotEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = if (isTablet) 24.dp else 16.dp), contentAlignment = Alignment.Center) {
                        TextButton(onClick = onClear) { 
                            Text(
                                text = stringResource(R.string.clear_history), 
                                fontSize = if (isTablet) 16.sp else 14.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}
