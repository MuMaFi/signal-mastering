package app.signal.isolate.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * UINavigationController's large title, in Compose.
 *
 * The bar is pinned and transparent while the large title is on screen; once the title
 * has scrolled up under it, the bar fades to a translucent material with a hairline and
 * shows the compact title — iOS's scroll-edge behaviour. Content is capped at 720 dp and
 * centred, so a tablet gets a readable column rather than rows stretched edge to edge.
 */
@Composable
fun LargeTitleScaffold(
    title: String,
    subtitle: String,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Apple.colors
    val type = Apple.type
    val density = LocalDensity.current
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var titleHeight by remember { mutableIntStateOf(0) }
    val collapsed by remember {
        derivedStateOf {
            titleHeight > 0 && scrollState.value >= titleHeight - with(density) { 8.dp.toPx() }
        }
    }
    val barColor by animateColorAsState(
        if (collapsed) colors.bar else colors.bar.copy(alpha = 0f),
        tween(200),
    )
    val compactAlpha by animateFloatAsState(if (collapsed) 1f else 0f, tween(200))

    Box(Modifier.fillMaxSize().background(colors.groupedBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(top = statusBar + 44.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .onSizeChanged { titleHeight = it.height }
                    .padding(start = 16.dp, end = 16.dp, bottom = 22.dp),
            ) {
                Text(
                    text = title,
                    style = type.largeTitle,
                    color = colors.label,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = subtitle,
                    style = type.subheadline,
                    color = colors.secondaryLabel,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 64.dp),
                content = content,
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .background(barColor)
                .padding(top = statusBar)
                .height(44.dp),
        ) {
            Text(
                text = title,
                style = type.headline,
                color = colors.label,
                modifier = Modifier.align(Alignment.Center).alpha(compactAlpha),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .alpha(compactAlpha)
                    .background(colors.separator),
            )
        }
    }
}
