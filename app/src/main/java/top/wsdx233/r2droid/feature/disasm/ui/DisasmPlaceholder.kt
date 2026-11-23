package top.wsdx233.r2droid.feature.disasm.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.wsdx233.r2droid.ui.theme.LocalAppFont
import top.wsdx233.r2droid.ui.theme.LocalDarkTheme

/** Shared with decoded rows so the skeleton doesn't shift columns when content arrives. */
internal object DisasmRowLayout {
    val GutterWidth = 26.dp
    val AddressWidth = 56.dp
    val BytesWidth = 60.dp
    val AnnotationIndent = 80.dp
    val OpcodeFontSize = 12.sp
}

/** Fallback for a single unloaded instruction. */
@Composable
fun DisasmPlaceholderRow(rowIndex: Int = 0) {
    DisasmPlaceholderContent(rowIndex = rowIndex, singleRow = true)
}

/**
 * One cached drawing and one animation for the whole preview, not an animation per row.
 * The animated value is read only during drawing: dragging and shimmer don't rebuild the layout.
 */
@Composable
internal fun DisasmPlaceholderContent(
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    rowIndex: Int = 0,
    singleRow: Boolean = false
) {
    val dark = LocalDarkTheme.current
    val surface = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val baseColor = ink.copy(alpha = if (dark) 0.07f else 0.055f)
    val highlightColor = if (dark) ink.copy(alpha = 0.075f) else surface.copy(alpha = 0.68f)
    fun columnColor(light: Long, night: Long) =
        lerp(surface, Color(if (dark) night else light), 0.32f)
    val gutterColor = columnColor(0xFFF3F8FF, 0xFF1E2A3A)
    val addressColor = columnColor(0xFFEFF8EE, 0xFF1E2E28)
    val bytesColor = columnColor(0xFFFFF8E1, 0xFF2E2818)
    val opcodeColor = columnColor(0xFFFFFFFF, 0xFF1E1E1E)

    // Use the same font and inherited line height as Text in DisasmRow, including font scaling.
    val textMeasurer = rememberTextMeasurer()
    val textStyle = LocalTextStyle.current.copy(
        fontFamily = LocalAppFont.current,
        fontSize = DisasmRowLayout.OpcodeFontSize
    )
    val lineHeight = textMeasurer.measure("0000 mov x0, x1", textStyle).size.height.toFloat()
    val rowHeight = with(LocalDensity.current) { lineHeight.toDp() }
    val progress = if (animate) {
        rememberInfiniteTransition(label = "disasm-placeholder").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1600, delayMillis = 400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "disasm-placeholder-sweep"
        )
    } else {
        rememberUpdatedState(0f)
    }

    Spacer(
        modifier
            .fillMaxWidth()
            .then(if (singleRow) Modifier.height(rowHeight) else Modifier)
            .clipToBounds()
            .drawWithCache {
                val gutterEnd = DisasmRowLayout.GutterWidth.toPx()
                val addressEnd = gutterEnd + DisasmRowLayout.AddressWidth.toPx()
                val bytesEnd = addressEnd + DisasmRowLayout.BytesWidth.toPx()
                val opcodeStart = bytesEnd + 4.dp.toPx()
                val opcodeWidth = (size.width - opcodeStart - 4.dp.toPx()).coerceAtLeast(0f)
                val barHeight = minOf(8.sp.toPx(), lineHeight * 0.48f)
                val radius = CornerRadius(2.dp.toPx())
                val bars = Path()
                val gutter = Path()
                val addresses = Path()
                val bytes = Path()
                val opcodes = Path()

                fun bar(left: Float, centerY: Float, width: Float, height: Float = barHeight) {
                    val right = minOf(left + width, size.width)
                    if (right <= left) return
                    bars.addRoundRect(RoundRect(
                        Rect(left, centerY - height / 2, right, centerY + height / 2), radius
                    ))
                }

                var top = 0f
                var index = rowIndex
                while (top < size.height) {
                    val pattern = index % 12
                    // Occasional indented annotation, rather than identical wall-to-wall bars.
                    if (!singleRow && pattern == 4) {
                        bar(DisasmRowLayout.AnnotationIndent.toPx(), top + lineHeight / 2,
                            opcodeWidth * 0.62f, barHeight * 0.8f)
                        top += lineHeight + 2.dp.toPx()
                    }
                    val bottom = top + lineHeight
                    val centerY = top + lineHeight / 2
                    gutter.addRect(Rect(0f, top, gutterEnd, bottom))
                    addresses.addRect(Rect(gutterEnd, top, addressEnd, bottom))
                    bytes.addRect(Rect(addressEnd, top, bytesEnd, bottom))
                    if (size.width > bytesEnd) {
                        opcodes.addRect(Rect(bytesEnd, top, size.width, bottom))
                    }
                    if (pattern == 3 || pattern == 9) {
                        bar(8.dp.toPx(), centerY, 10.dp.toPx(), barHeight * 0.65f)
                    }
                    // Right-aligned compact addresses, shorter bytes and token-shaped opcodes.
                    val addressWidth = (if (pattern < 8) 36.dp else 44.dp).toPx()
                    bar(addressEnd - 2.dp.toPx() - addressWidth, centerY, addressWidth)
                    val byteWidth = (22 + (index % 4) * 9).dp.toPx()
                    bar(addressEnd + 2.dp.toPx(), centerY, byteWidth, barHeight * 0.85f)
                    val mnemonicWidth = minOf((18 + (index % 3) * 6).dp.toPx(), opcodeWidth)
                    bar(opcodeStart, centerY, mnemonicWidth)
                    if (pattern != 7) { // A short instruction, e.g. ret, has no operands.
                        val operandStart = opcodeStart + mnemonicWidth + 7.dp.toPx()
                        val available = (size.width - operandStart - 8.dp.toPx()).coerceAtLeast(0f)
                        val fraction = 0.35f + (index % 5) * 0.12f
                        bar(operandStart, centerY, available * fraction, barHeight * 0.85f)
                    }
                    top = bottom
                    if (!singleRow && pattern == 8) {
                        bar(DisasmRowLayout.AnnotationIndent.toPx(), top + lineHeight / 2,
                            opcodeWidth * 0.78f, barHeight * 0.75f)
                        top += lineHeight + 2.dp.toPx()
                    }
                    index++
                }

                val bandWidth = maxOf(size.width * 0.38f, 96.dp.toPx())
                // Cache a local gradient and translate it at draw time instead of allocating
                // a brush per frame. Clip to the bars so the background never flashes.
                val shimmer = Brush.linearGradient(
                    0f to highlightColor.copy(alpha = 0f),
                    0.5f to highlightColor,
                    1f to highlightColor.copy(alpha = 0f),
                    start = Offset.Zero,
                    end = Offset(bandWidth, 0f)
                )
                onDrawBehind {
                    drawPath(gutter, gutterColor)
                    drawPath(addresses, addressColor)
                    drawPath(bytes, bytesColor)
                    drawPath(opcodes, opcodeColor)
                    drawPath(bars, baseColor)
                    if (animate) {
                        val left = -bandWidth + (size.width + bandWidth) * progress.value
                        clipPath(bars) {
                            translate(left = left) {
                                drawRect(shimmer, size = Size(bandWidth, size.height))
                            }
                        }
                    }
                }
            }
    )
}
