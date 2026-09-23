package app.signal.isolate.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * iOS controls, drawn in Compose.
 *
 * Material's own switch, segmented button and list items carry Material geometry and
 * ripples; these match UIKit's instead — 51×31 switch, 44 dp rows, hairlines inset to
 * the text, a highlight on press rather than a ripple. Sizes and motion mirror
 * `web/app.css`, so the preview and the app are the same design.
 */

// ---------------------------------------------------------------- icons

/** The same SVG paths the web preview uses, so both draw identical glyphs. */
object AppleIcons {
    val Check = icon("check", 16f, 16f,
        "M13.6 3.9a1 1 0 0 1 .06 1.35L7.2 12.9a1 1 0 0 1-1.46.06L2.4 9.62a1 1 0 1 1 1.4-1.42l2.57 2.54 5.85-6.9a1 1 0 0 1 1.38-.06z")
    val Chevron = icon("chevron", 12f, 20f,
        "M2.3 1.3a1 1 0 0 1 1.4 0l8 8.1a1 1 0 0 1 0 1.4l-8 8.1a1 1 0 1 1-1.42-1.4L9.5 10.1 2.3 2.7a1 1 0 0 1 0-1.4z")
    val Warning = icon("warning", 16f, 16f,
        "M8 1.2a1.4 1.4 0 0 1 1.22.71l6.1 10.7A1.4 1.4 0 0 1 14.1 14.7H1.9a1.4 1.4 0 0 1-1.22-2.09l6.1-10.7A1.4 1.4 0 0 1 8 1.2zm0 3.9a.85.85 0 0 0-.85.9l.2 3.4a.65.65 0 0 0 1.3 0l.2-3.4A.85.85 0 0 0 8 5.1zm0 5.6a.95.95 0 1 0 0 1.9.95.95 0 0 0 0-1.9z")
    val Share = icon("share", 16f, 20f,
        "M8 0.6a1 1 0 0 1 .7.3l3 3a1 1 0 0 1-1.4 1.4L9 4v8.2a1 1 0 1 1-2 0V4L5.7 5.3a1 1 0 0 1-1.4-1.4l3-3A1 1 0 0 1 8 .6z",
        "M2 8.6a1 1 0 0 1 1 1v7.3h10V9.6a1 1 0 1 1 2 0v8a1.3 1.3 0 0 1-1.3 1.3H2.3A1.3 1.3 0 0 1 1 17.6v-8a1 1 0 0 1 1-1z")

    private fun icon(name: String, w: Float, h: Float, vararg paths: String): ImageVector =
        ImageVector.Builder(name, w.dp, h.dp, w, h).apply {
            paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()
}

// ---------------------------------------------------------------- grouped lists

@Composable
fun GroupHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = Apple.type.footnote,
        color = Apple.colors.secondaryLabel,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 7.dp)
            .semantics { heading() },
    )
}

@Composable
fun GroupFooter(text: String, warning: Boolean = false, center: Boolean = false) {
    Text(
        text = text,
        style = Apple.type.footnote,
        color = if (warning) Apple.colors.orange else Apple.colors.secondaryLabel,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 7.dp),
    )
}

/** An inset grouped section: rounded card, rows stacked inside. */
@Composable
fun InsetGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Apple.colors.elevated),
        content = content,
    )
}

/** Hairline separator, inset to where the next row's text starts, as UIKit does. */
@Composable
fun Hairline(startInset: Dp = 16.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Apple.colors.elevated)
            .padding(start = startInset)
            .height(0.5.dp)
            .background(Apple.colors.separator),
    )
}

/**
 * A 44 dp list row. Tappable rows highlight while pressed instead of rippling.
 * [role] and [selected] carry the row's meaning to TalkBack.
 */
@Composable
fun ListRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    role: Role? = null,
    selected: Boolean? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Apple.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val base = modifier
        .fillMaxWidth()
        .background(if (pressed && onClick != null) colors.pressed else colors.elevated)
    val interactive = when {
        onClick == null -> base
        selected != null -> base.selectable(
            selected = selected,
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
        )
        else -> base.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
        )
    }
    Row(
        modifier = interactive
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), content = content)
        trailing?.invoke()
    }
}

@Composable
fun RowTitle(text: String, strong: Boolean = false, color: Color = Apple.colors.label) {
    Text(
        text = text,
        style = if (strong) Apple.type.headline else Apple.type.body,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun RowSubtitle(text: String, maxLines: Int = 2) {
    Text(
        text = text,
        style = Apple.type.footnote,
        color = Apple.colors.secondaryLabel,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** Leading checkmark slot for single-choice rows; keeps its width when empty. */
@Composable
fun CheckSlot(checked: Boolean) {
    val alpha by animateFloatAsState(if (checked) 1f else 0f, tween(180))
    val scale by animateFloatAsState(
        if (checked) 1f else 0.6f,
        spring(dampingRatio = 0.5f, stiffness = 600f),
    )
    Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = AppleIcons.Check,
            contentDescription = null,
            tint = Apple.colors.tint,
            modifier = Modifier
                .size(17.dp)
                .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale },
        )
    }
}

@Composable
fun Chevron() {
    Icon(
        imageVector = AppleIcons.Chevron,
        contentDescription = null,
        tint = Apple.colors.tertiaryLabel,
        modifier = Modifier.size(width = 8.dp, height = 13.dp),
    )
}

@Composable
fun Badge(text: String, highlighted: Boolean = false) {
    val colors = Apple.colors
    Text(
        text = text,
        style = Apple.type.caption,
        color = if (highlighted) colors.tint else colors.secondaryLabel,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (highlighted) colors.tint.copy(alpha = 0.14f) else colors.fill)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

// ---------------------------------------------------------------- controls

/** UISwitch: 51×31, spring-loaded thumb, tint fill when on. */
@Composable
fun AppleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    enabled: Boolean = true,
) {
    val colors = Apple.colors
    val track by animateColorAsState(if (checked) colors.tintFill else colors.fill, tween(220))
    val thumbX by animateDpAsState(
        if (checked) 22.dp else 2.dp,
        spring(dampingRatio = 0.78f, stiffness = 520f),
    )
    Box(
        Modifier
            .size(width = 51.dp, height = 31.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .background(track)
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics { contentDescription = label },
    ) {
        Box(
            Modifier
                .offset(x = thumbX, y = 2.dp)
                .size(27.dp)
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/** UISegmentedControl: the lifted thumb slides to the chosen segment. */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    val colors = Apple.colors
    val index = options.indexOf(selected).coerceAtLeast(0)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(9.dp))
            .background(colors.fill)
            .padding(2.dp),
    ) {
        val segment = maxWidth / options.size
        val thumbX by animateDpAsState(
            segment * index,
            spring(dampingRatio = 0.86f, stiffness = 420f),
        )
        Box(
            Modifier
                .offset(x = thumbX)
                .width(segment)
                .height(32.dp)
                .shadow(2.dp, RoundedCornerShape(7.dp))
                .background(colors.segmentThumb, RoundedCornerShape(7.dp)),
        )
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEachIndexed { i, option ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(32.dp)
                        .selectable(
                            selected = i == index,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = enabled,
                            role = Role.Tab,
                            onClick = { onSelect(option) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(option),
                        style = Apple.type.footnote.copy(
                            fontWeight = if (i == index) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = colors.label,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- buttons

/** Full-width prominent button: 50 dp, 12 dp corners, shrinks slightly on press. */
@Composable
fun FilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Apple.colors
    PressableButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        background = if (enabled) colors.tintFill else colors.fill,
        content = if (enabled) colors.onTint else colors.tertiaryLabel,
    )
}

/** The quieter companion: tint-coloured label on a 16 % tint wash. */
@Composable
fun TintedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val colors = Apple.colors
    val accent = if (destructive) colors.red else colors.tint
    PressableButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = true,
        background = accent.copy(alpha = 0.16f),
        content = accent,
    )
}

@Composable
private fun PressableButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    background: Color,
    content: Color,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, tween(120))
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (pressed) 0.88f else 1f
            }
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = Apple.type.headline, color = content)
    }
}

/** Plain tint-coloured text button, 44 dp tall, for trailing row actions. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, destructive: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .alpha(if (pressed) 0.4f else 1f)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = Apple.type.body,
            color = if (destructive) Apple.colors.red else Apple.colors.tint,
        )
    }
}

// ---------------------------------------------------------------- progress

/** 4 dp rounded bar. `null` progress runs the indeterminate sweep. */
@Composable
fun ThinProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    val colors = Apple.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(colors.fill),
    ) {
        if (progress != null) {
            val animated by animateFloatAsState(
                progress.coerceIn(0f, 1f),
                tween(300, easing = LinearEasing),
            )
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(colors.tintFill),
            )
        } else {
            val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
                initialValue = -0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart),
                label = "sweep",
            )
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .offset(x = maxWidth * sweep)
                        .width(maxWidth * 0.4f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(colors.tintFill),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- waveform

/** Rounded bars from a normalised peak envelope, as in the web preview. */
@Composable
fun Waveform(peaks: FloatArray, modifier: Modifier = Modifier) {
    val color = Apple.colors.tintFill
    Canvas(modifier.fillMaxWidth().height(48.dp)) {
        val bar = 2.dp.toPx()
        val gap = 1.5.dp.toPx()
        val count = (size.width / (bar + gap)).toInt()
        if (count <= 0 || peaks.isEmpty()) return@Canvas
        for (i in 0 until count) {
            val peak = peaks[(i.toFloat() / count * peaks.size).toInt().coerceIn(0, peaks.lastIndex)]
            val h = maxOf(2.dp.toPx(), peak * (size.height - 2.dp.toPx()))
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (bar + gap), (size.height - h) / 2f),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2f),
            )
        }
    }
}

@Composable
fun SectionSpacer() = Spacer(Modifier.height(32.dp))
