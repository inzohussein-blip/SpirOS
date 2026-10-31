package com.spiros.app.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spiros.app.vault.Vault
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.MathContext

private val PIN_PATTERN = Regex("^\\d{$PIN_MIN,$PIN_MAX}$")

/**
 * Lock screen in disguise mode: a real, working calculator. Entering the PIN and
 * pressing "=" unlocks SpirOS; anything else simply calculates. Nothing on screen
 * hints at the vault (no fingerprint button, no error messages).
 */
@Composable
fun CalculatorScreen(vault: Vault) {
    val scope = rememberCoroutineScope()
    var display by remember { mutableStateOf("0") }
    var fresh by remember { mutableStateOf(true) }

    fun press(key: String) {
        when (key) {
            "C" -> { display = "0"; fresh = true }
            "⌫" -> display = display.dropLast(1).ifEmpty { "0" }
            "=" -> {
                val entry = display
                if (PIN_PATTERN.matches(entry)) {
                    scope.launch { vault.unlock(entry.toCharArray()) }
                }
                display = evaluate(entry) ?: entry
                fresh = true
            }
            "+", "−", "×", "÷" -> {
                display = if (display.last() in "+−×÷") display.dropLast(1) + key else display + key
                fresh = false
            }
            else -> {
                display = if (fresh || display == "0") key else display + key
                fresh = false
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF101114)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
                    Text(
                        display,
                        color = Color.White,
                        fontSize = if (display.length > 10) 40.sp else 64.sp,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                    )
                }
                listOf(
                    listOf("C", "⌫", "÷", "×"),
                    listOf("7", "8", "9", "−"),
                    listOf("4", "5", "6", "+"),
                    listOf("1", "2", "3", "="),
                    listOf("0", ".", "", ""),
                ).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                        row.forEach { key ->
                            Box(Modifier.weight(1f).aspectRatio(1f)) {
                                if (key.isNotEmpty()) {
                                    val operator = key in listOf("÷", "×", "−", "+", "=")
                                    Button(
                                        onClick = { press(key) },
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (operator) Color(0xFFFF9F0A) else Color(0xFF2C2D31),
                                            contentColor = Color.White,
                                        ),
                                        modifier = Modifier.fillMaxSize(),
                                    ) { Text(key, fontSize = 26.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Evaluates "12+3×4" with normal precedence. Returns null for incomplete input. */
private fun evaluate(expression: String): String? {
    val tokens = Regex("(\\d+\\.?\\d*|\\.\\d+|[+−×÷])").findAll(expression).map { it.value }.toList()
    if (tokens.isEmpty() || tokens.last() in listOf("+", "−", "×", "÷")) return null
    return runCatching { compute(tokens) }.getOrNull()
}

private fun compute(tokens: List<String>): String {
    val terms = ArrayList<BigDecimal>()
    val ops = ArrayList<String>()
    var i = 0
    var current = BigDecimal(tokens[i++])
    while (i < tokens.size) {
        val op = tokens[i++]
        val next = BigDecimal(tokens[i++])
        when (op) {
            "×" -> current = current.multiply(next)
            "÷" -> current = current.divide(next, MathContext.DECIMAL64)
            else -> {
                terms += current
                ops += op
                current = next
            }
        }
    }
    terms += current
    var result = terms[0]
    ops.forEachIndexed { k, op -> result = if (op == "+") result.add(terms[k + 1]) else result.subtract(terms[k + 1]) }
    return result.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
}
