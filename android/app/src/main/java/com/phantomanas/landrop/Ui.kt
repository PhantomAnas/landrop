package com.phantomanas.landrop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val AppFont = FontFamily(
    Font(R.font.nunito, weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.nunito, weight = FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

object Palette {
    val Green = Color(0xFF22C55E)
    val Red = Color(0xFFEF4444)
    val Blue = Color(0xFF5B8CFF)
    val Cyan = Color(0xFF22D3EE)
    val Amber = Color(0xFFF59E0B)
    val Violet = Color(0xFFA78BFA)
    val Text = Color.White
    val Muted = Color(0xB8FFFFFF)
    val Faint = Color(0x73FFFFFF)
}

/** The luminous glass pill. Width comes from [modifier] (fillMaxWidth / weight). */
@Composable
fun GlassButton(
    text: String,
    modifier: Modifier = Modifier,
    height: Dp = 54.dp,
    accent: Color = Color(0x00FFFFFF),
    enabled: Boolean = true,
    textSize: TextUnit = TextUnit.Unspecified,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.95f else 1f, tween(90), label = "press")

    Box(
        modifier
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.42f
            }
            .focusable(interactionSource = interaction)
            .clickable(interaction, indication = null, enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val r = size.height / 2f
            val rect = Rect(Offset.Zero, Size(size.width, size.height)).deflate(size.height * 0.03f)
            val path = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(r))) }
            drawPath(Path().apply {
                addRoundRect(RoundRect(rect.translate(0f, size.height * 0.03f), CornerRadius(r)))
            }, Color(0x16000000))
            drawPath(path, Brush.verticalGradient(
                0f to Color(0x45FFFFFF), 0.5f to Color(0x1CFFFFFF), 1f to Color(0x21000000),
                startY = rect.top, endY = rect.bottom))
            if (accent.alpha > 0f) {
                drawPath(path, Brush.verticalGradient(
                    0f to accent.copy(alpha = 0.3f), 1f to accent.copy(alpha = 0.48f),
                    startY = rect.top, endY = rect.bottom))
            }
            val rim = if (focused) 1f else 0.6f
            drawPath(path, Brush.verticalGradient(
                0f to Color.White.copy(alpha = rim),
                1f to Color.White.copy(alpha = rim * 0.45f),
                startY = rect.top, endY = rect.bottom,
            ), style = Stroke((size.height * if (focused) 0.045f else 0.022f).coerceIn(2f, 7f)))
        }
        BasicText(
            text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp),
            style = TextStyle(Color.White,
                if (textSize != TextUnit.Unspecified) textSize else (height.value * 0.3f).sp,
                FontWeight.ExtraBold, fontFamily = AppFont, textAlign = TextAlign.Center),
        )
    }
}

/** A frosted card; [accent] adds a faint wash so cards are tellable apart. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    padding: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    val base = modifier
        .clip(shape)
        .background(Brush.verticalGradient(0f to Color(0x2EFFFFFF), 1f to Color(0x12FFFFFF)))
        .let { m ->
            if (accent != null) m.background(Brush.verticalGradient(
                0f to accent.copy(alpha = 0.16f), 1f to accent.copy(alpha = 0.06f))) else m
        }
        .border(1.dp, Brush.verticalGradient(0f to Color(0x80FFFFFF), 1f to Color(0x26FFFFFF)), shape)
        .let { m -> if (onClick != null) m.clickable { onClick() } else m }
        .padding(padding)
    Column(base, content = content)
}

@Composable
fun Title(text: String, size: TextUnit = 28.sp, color: Color = Palette.Text, align: TextAlign = TextAlign.Start,
          modifier: Modifier = Modifier, maxLines: Int = 2) {
    BasicText(text, modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(color, size, FontWeight.ExtraBold, fontFamily = AppFont, textAlign = align))
}

@Composable
fun Body(text: String, size: TextUnit = 15.sp, color: Color = Palette.Muted, align: TextAlign = TextAlign.Start,
         modifier: Modifier = Modifier, maxLines: Int = 4) {
    BasicText(text, modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(color, size, FontWeight.Bold, fontFamily = AppFont, textAlign = align,
            lineHeight = size * 1.3f))
}

/** Glowing status dot: green when on, grey when off, amber when searching. */
@Composable
fun StatusDot(color: Color, size: Dp = 14.dp, pulsing: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "dot")
    val pulse by transition.animateFloat(0.55f, 1f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val a = if (pulsing) pulse else 1f
    Canvas(Modifier.size(size * 2)) {
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f * a), Color.Transparent)), radius = this.size.minDimension / 2)
        drawCircle(color.copy(alpha = a), radius = this.size.minDimension / 4.4f)
        drawCircle(Color.White.copy(alpha = 0.35f), radius = this.size.minDimension / 4.4f, style = Stroke(1.5f))
    }
}

/** Progress bar on glass; [indeterminate] sweeps a light band instead. */
@Composable
fun GlassProgress(fraction: Float, accent: Color, indeterminate: Boolean = false, height: Dp = 14.dp) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(350), label = "progress")
    val transition = rememberInfiniteTransition(label = "sweep")
    val sweep by transition.animateFloat(-0.4f, 1.4f,
        infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sweepX")
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50))
        .background(Color(0x24FFFFFF))
        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50))) {
        if (indeterminate) {
            Canvas(Modifier.matchParentSize()) {
                val w = size.width
                drawRect(Brush.horizontalGradient(
                    listOf(Color.Transparent, accent.copy(alpha = 0.9f), Color.Transparent),
                    startX = w * sweep - w * 0.25f, endX = w * sweep + w * 0.25f))
            }
        } else {
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.75f), accent))))
            Canvas(Modifier.matchParentSize()) {
                val w = size.width * f
                if (w > 0f) drawRect(Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
                    startX = w * sweep - w * 0.2f, endX = w * sweep + w * 0.2f), size = Size(w, size.height))
            }
        }
    }
}

@Composable
fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    size: TextUnit = 18.sp,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.clip(shape).background(Color(0x33000000))
        .border(1.dp, Color(0x55FFFFFF), shape).padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (value.isEmpty()) Body(placeholder, size, Palette.Faint, maxLines = 1)
        BasicTextField(
            value, onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = TextStyle(Color.White, size, FontWeight.Bold, fontFamily = AppFont),
            cursorBrush = SolidColor(Palette.Blue),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A settings row with a glass toggle - applies instantly. */
@Composable
fun SwitchRow(label: String, detail: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val knob by animateFloatAsState(if (checked) 1f else 0f, tween(180), label = "knob")
    GlassCard(Modifier.fillMaxWidth(), padding = 16.dp, onClick = { onToggle(!checked) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Title(label, 17.sp)
                if (detail.isNotBlank()) Body(detail, 13.sp)
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(58.dp).height(32.dp).clip(RoundedCornerShape(50))
                .background(if (checked) Palette.Green.copy(alpha = 0.75f) else Color(0x33FFFFFF))
                .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(50))) {
                Box(Modifier.offset(x = (3 + 26 * knob).dp, y = 3.dp).size(26.dp)
                    .clip(CircleShape).background(Color.White))
            }
        }
    }
}

/** A settings row with a few exclusive choices as small pills - applies instantly. */
@Composable
fun ChoiceRow(label: String, detail: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        Title(label, 17.sp)
        if (detail.isNotBlank()) Body(detail, 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (key, text) ->
                GlassButton(text, Modifier.weight(1f), 40.dp,
                    accent = if (key == selected) Palette.Green else Color(0x00FFFFFF), textSize = 12.sp) {
                    onSelect(key)
                }
            }
        }
    }
}

/** Dim everything and centre one card. */
@Composable
fun Dimmer(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xA8000000))
        .clickable(remember { MutableInteractionSource() }, indication = null) {}
        .padding(24.dp), contentAlignment = Alignment.Center) {
        content()
    }
}

/** Up (sending) or down (receiving) arrow in a tinted ring. */
@Composable
fun ArrowBadge(up: Boolean, color: Color, size: Dp = 44.dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(color.copy(alpha = 0.22f), r)
        drawCircle(color.copy(alpha = 0.8f), r, style = Stroke(2f))
        val cx = this.size.width / 2
        val top = this.size.height * 0.28f
        val bottom = this.size.height * 0.72f
        val stroke = Stroke(width = this.size.width * 0.1f, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(cx, top), Offset(cx, bottom), stroke.width, StrokeCap.Round)
        val headY = if (up) top else bottom
        val dir = if (up) 1f else -1f
        val w = this.size.width * 0.2f
        drawLine(Color.White, Offset(cx, headY), Offset(cx - w, headY + dir * w), stroke.width, StrokeCap.Round)
        drawLine(Color.White, Offset(cx, headY), Offset(cx + w, headY + dir * w), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun Gap(h: Dp = 12.dp) = Spacer(Modifier.height(h))

/** Two buttons side by side, equal width. */
@Composable
fun ButtonRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}
