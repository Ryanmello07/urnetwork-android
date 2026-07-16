package com.bringyour.network.ui.stats

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.bringyour.network.R
import com.bringyour.network.ui.theme.Black
import com.bringyour.network.ui.theme.BlueMedium
import com.bringyour.network.ui.theme.Green
import com.bringyour.network.ui.theme.Pink
import com.bringyour.network.ui.theme.TextFaint
import com.bringyour.network.ui.theme.TextMuted
import com.bringyour.network.ui.theme.TopBarTitleTextStyle
import com.bringyour.network.utils.formatBitRate
import com.bringyour.network.utils.formatByteCountCompact
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Live contract details: a scrollable list with one row per peer client.
 * Each row visualizes the client contract (egress, green) and the
 * companion contract (ingress, pink) as two circles with transfer lines
 * between them. The inner disc of each circle grows as the contract
 * is used.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContractStatsScreen(
    navController: NavController,
    provider: Boolean,
    contractStatsViewModel: ContractStatsViewModel = hiltViewModel(),
) {

    LaunchedEffect(Unit) {
        contractStatsViewModel.start(provider)
    }

    val rows = contractStatsViewModel.rows
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // at the very top the list follows new contracts; scrolled away it holds
    // position (compose anchors by key) while new contracts collect behind a chip
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    // the newest client id seen while at the top; rows ahead of it are "new"
    var topSeenClientId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isAtTop, rows.firstOrNull()?.clientId) {
        if (isAtTop) {
            topSeenClientId = rows.firstOrNull()?.clientId
            // hold the very top as new contracts prepend
            if (0 < listState.firstVisibleItemIndex || 0 < listState.firstVisibleItemScrollOffset) {
                listState.scrollToItem(0)
            }
        }
    }

    val pendingCount = remember(rows, topSeenClientId) {
        val seenId = topSeenClientId
        if (seenId == null) {
            0
        } else {
            val index = rows.indexOfFirst { it.clientId == seenId }
            if (index < 0) rows.size else index
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(
                            id = if (provider) R.string.provider_contracts else R.string.client_contracts
                        ),
                        style = TopBarTitleTextStyle
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Black
                ),
            )
        },
        containerColor = Black
    ) { innerPadding ->

        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {

            if (rows.isEmpty()) {

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(id = R.string.no_open_contracts),
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(
                            id = if (provider) R.string.contracts_appear_providing else R.string.contracts_appear_connected
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextFaint
                    )
                }

            } else {

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(rows, key = { it.clientId }) { row ->
                        // a new contract fades in while the rest reflow down
                        Column(
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(durationMillis = 500),
                                placementSpec = tween(durationMillis = 500),
                                fadeOutSpec = tween(durationMillis = 500)
                            )
                        ) {
                            ContractClientRow(row = row)
                            HorizontalDivider()
                        }
                    }
                }

            }

            /**
             * new contracts chip
             */
            if (!isAtTop && 0 < pendingCount) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .background(BlueMedium, shape = RoundedCornerShape(16.dp))
                        .clickable {
                            scope.launch {
                                listState.animateScrollToItem(0)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        pluralStringResource(
                            id = R.plurals.new_items_count,
                            count = pendingCount,
                            pendingCount,
                        ),
                        style = TextStyle(fontSize = 12.sp),
                        color = Color.White
                    )
                }
            }

        }
    }
}

@Composable
fun ContractClientRow(
    row: ContractClientRowUi,
) {

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // the full client id, tappable to copy
            Text(
                row.clientId,
                style = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                color = Color.White,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        clipboardManager.setText(AnnotatedString(row.clientId))
                        Toast.makeText(context, R.string.client_id_copied, Toast.LENGTH_SHORT).show()
                    }
            )
            if (1 < row.pairCount) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    pluralStringResource(
                        id = R.plurals.contract_count,
                        count = row.pairCount,
                        row.pairCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        ContractPairViz(row = row)

    }
}

/**
 * Two circles representing the client contract and the companion
 * contract, with directional transfer lines between them
 */
@Composable
fun ContractPairViz(
    row: ContractClientRowUi,
) {

    val contractColor = Green
    val companionColor = Pink

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {

        // a replaced contract (new id) ejects its outer ring to the side and fades
        // while the new ring fades into the same fixed slot; a closing row ejects
        // the ring and shows nothing after. the inner disc persists across the swap
        // and just resizes.
        ContractCircle(
            contractId = row.contractId,
            used = row.contractUsedByteCount,
            total = row.contractByteCount,
            color = contractColor,
            label = stringResource(id = R.string.contract),
            slideLeft = true,
            closing = row.closing,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
            verticalArrangement = Arrangement.Center
        ) {
            TransferLine(
                bitRate = row.contractBitRate,
                color = contractColor,
                pointsRight = true,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TransferLine(
                bitRate = row.companionContractBitRate,
                color = companionColor,
                pointsRight = false,
            )
        }

        ContractCircle(
            contractId = row.companionContractId,
            used = row.companionContractUsedByteCount,
            total = row.companionContractByteCount,
            color = companionColor,
            label = stringResource(id = R.string.companion),
            slideLeft = false,
            closing = row.closing,
        )

    }
}

@Composable
private fun ContractCircle(
    contractId: String,
    used: Long,
    total: Long,
    color: Color,
    label: String,
    slideLeft: Boolean,
    closing: Boolean,
) {

    // area-proportional inner disc, with a minimum visible size
    val fraction = if (0 < total) min(1.0, used.toDouble() / total.toDouble()) else 0.0
    // grow/shrink the disc smoothly, matching the 0.5s transfer-chart transition.
    // this tracks the CURRENT contract's fill and, because the disc lives outside
    // the ring's swap below, persists across a replacement -- morphing to the new
    // level instead of popping.
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.toFloat(),
        animationSpec = tween(durationMillis = 500, easing = EaseOut),
        label = "contractInnerDisc"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(92.dp)
    ) {

        Box(
            modifier = Modifier.size(56.dp),
            contentAlignment = Alignment.Center
        ) {

            // the outer ring carries the contract identity: when a contract is
            // replaced (new id) the old ring is ejected -- it slides out to the
            // side and fades on its own fixed schedule and is never reversed, and
            // the new ring fades into the slot once the last ejecting ring has
            // left. a closing row ejects the ring and shows nothing after. only the
            // ring ejects, so the disc drawn over it resizes smoothly across a swap.
            ContractRing(
                contractId = contractId,
                color = color,
                slideLeft = slideLeft,
                circleSize = 56.dp,
                visible = !closing,
            )

            // the inner disc has no id and lives outside the swap, so it persists
            // across a contract replacement and just resizes. framing from zero
            // animates the grow-in / drain instead of an insert/remove pop.
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (0 < animatedFraction) {
                    val radius = size.minDimension / 2f
                    val innerRadius = kotlin.math.max(
                        3.dp.toPx(),
                        (radius - 1.dp.toPx()) * sqrt(animatedFraction)
                    )
                    drawCircle(
                        color = color.copy(alpha = 0.3f),
                        radius = innerRadius,
                    )
                    drawCircle(
                        color = color.copy(alpha = 0.6f),
                        radius = innerRadius,
                        style = Stroke(width = 0.5.dp.toPx())
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            formatByteCountCompact(used),
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
            color = Color.White
        )
        Text(
            stringResource(id = R.string.of_total, formatByteCountCompact(total)),
            style = TextStyle(fontSize = 10.sp),
            color = TextMuted
        )
        Text(
            label,
            style = TextStyle(fontSize = 10.sp),
            color = TextFaint
        )

    }
}

@Composable
private fun TransferLine(
    bitRate: Long,
    color: Color,
    pointsRight: Boolean,
) {
    val active = 0 < bitRate

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            if (active) formatBitRate(bitRate) else " ",
            style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium),
            color = if (active) color else Color.Transparent
        )

        Spacer(modifier = Modifier.height(2.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        ) {
            val alpha = if (active) 0.9f else 0.25f
            val y = size.height / 2f
            val arrowSize = 4.dp.toPx()
            // overlap the shaft into the arrowhead base so the join is solid
            // (a flat line cap meeting the tapering apex leaves a subpixel gap)
            val overlap = 1.dp.toPx()
            val path = androidx.compose.ui.graphics.Path()
            if (pointsRight) {
                drawLine(
                    color = color.copy(alpha = alpha),
                    start = Offset(0f, y),
                    end = Offset(size.width - arrowSize + overlap, y),
                    strokeWidth = 1.dp.toPx()
                )
                path.moveTo(size.width, y)
                path.lineTo(size.width - arrowSize, y - arrowSize / 2f)
                path.lineTo(size.width - arrowSize, y + arrowSize / 2f)
            } else {
                drawLine(
                    color = color.copy(alpha = alpha),
                    start = Offset(arrowSize - overlap, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx()
                )
                path.moveTo(0f, y)
                path.lineTo(arrowSize, y - arrowSize / 2f)
                path.lineTo(arrowSize, y + arrowSize / 2f)
            }
            path.close()
            drawPath(path, color = color.copy(alpha = alpha))
        }

    }
}

// eject timings for the identity ring, mirroring the shared design: the old ring
// slides out + fades over the slide window, the incoming ring fades in
private const val RING_SLIDE_MILLIS = 500
private const val RING_FADE_IN_MILLIS = 350

/**
 * One in-flight ejection: an independent slide-out + fade that runs once to
 * completion and is then removed. Its animatables live in this object (held by the
 * state list, outside composition) so a change landing mid-slide never reverses an
 * ejection already leaving.
 */
private class RingEjection {
    val id: java.util.UUID = java.util.UUID.randomUUID()
    val offset = Animatable(0f)
    val alpha = Animatable(1f)
}

/**
 * The outer identity ring of a contract circle.
 *
 * When [contractId] changes, the ring on screen is *ejected*: it slides out toward
 * the removal edge ([slideLeft]) and fades on its own fixed schedule, and is never
 * reversed even if further contracts change while it is still leaving. Each
 * ejection is an independent instance, so multiple rings can be leaving at once and
 * are all rendered; the incoming ring fades into the slot only once the LAST
 * ejecting ring has finished leaving. This mirrors the Apple ContractRing and
 * replaces the single interruptible cross-fade that slid a ring back in then out
 * again when another change landed mid-slide.
 *
 * When [visible] goes false (the client's last contract closed) the on-screen ring
 * ejects and nothing takes its place. The inner disc is drawn separately by the
 * caller and is not part of this eject -- it persists across a swap and resizes.
 */
@Composable
private fun ContractRing(
    contractId: String,
    color: Color,
    slideLeft: Boolean,
    circleSize: Dp,
    visible: Boolean,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // slide fully clear of the circle's slot, toward the removal edge
    val offscreenPx = with(density) { circleSize.toPx() } * 3f * if (slideLeft) -1f else 1f

    // the ring currently occupying the slot (the latest contract id)
    var currentId by remember { mutableStateOf(contractId) }
    // whether the current ring has faded in; false while ejections are leaving so a
    // not-yet-shown ring is never itself ejected (there is nothing to slide off)
    var currentVisible by remember { mutableStateOf(visible) }
    // mirrors `visible` in state so a deferred ejection completion reads the live
    // intent (the input param would be captured stale in the launched coroutine)
    var present by remember { mutableStateOf(visible) }
    // opacity of the settled/incoming ring: snapped to 0 on eject (the ejection copy
    // takes over the slide), animated to 1 on fade-in
    val currentAlpha = remember { Animatable(if (visible) 1f else 0f) }
    // in-flight ejections, each an independent slide-out + fade that runs once
    val ejections = remember { mutableStateListOf<RingEjection>() }

    fun fadeInCurrent() {
        currentVisible = true
        scope.launch {
            currentAlpha.animateTo(1f, tween(durationMillis = RING_FADE_IN_MILLIS))
        }
    }

    // spawn an independent slide-out of the on-screen ring; once started it always
    // runs to completion (never reverses), even if more changes land meanwhile
    fun ejectCurrentRing() {
        val ejection = RingEjection()
        ejections.add(ejection)
        scope.launch {
            val slide = launch {
                ejection.offset.animateTo(offscreenPx, tween(durationMillis = RING_SLIDE_MILLIS))
            }
            val fade = launch {
                ejection.alpha.animateTo(0f, tween(durationMillis = RING_SLIDE_MILLIS))
            }
            slide.join()
            fade.join()
            ejections.remove(ejection)
            // admit the waiting ring only once the last one has left and a ring is
            // still wanted in the slot
            if (ejections.isEmpty() && present && !currentVisible) {
                fadeInCurrent()
            }
        }
    }

    LaunchedEffect(contractId) {
        // fires on first composition too; the guard lets the initial ring settle
        // without an eject
        if (contractId == currentId) return@LaunchedEffect
        // eject the on-screen ring (if any), then admit the new id
        if (currentVisible) {
            ejectCurrentRing()
        }
        currentId = contractId
        currentVisible = false
        currentAlpha.snapTo(0f)
        // nothing leaving -> the new ring can fade in right away
        if (present && ejections.isEmpty()) {
            fadeInCurrent()
        }
    }

    LaunchedEffect(visible) {
        present = visible
        if (visible) {
            if (!currentVisible && ejections.isEmpty()) {
                fadeInCurrent()
            }
        } else if (currentVisible) {
            // the client is leaving: eject the ring and show nothing after
            ejectCurrentRing()
            currentVisible = false
            currentAlpha.snapTo(0f)
        }
    }

    Box(
        modifier = Modifier.size(circleSize),
        contentAlignment = Alignment.Center
    ) {
        // ejecting rings, each leaving on its own schedule
        ejections.forEach { ejection ->
            key(ejection.id) {
                RingStroke(
                    color = color,
                    modifier = Modifier.graphicsLayer {
                        translationX = ejection.offset.value
                        alpha = ejection.alpha.value
                    }
                )
            }
        }
        // the incoming/settled ring; hidden (alpha 0) until every ejection has left
        RingStroke(
            color = color,
            modifier = Modifier.graphicsLayer { alpha = currentAlpha.value }
        )
    }
}

/**
 * The stroked identity ring, filling its slot. Drawn once for the settled ring and
 * once per in-flight ejection.
 */
@Composable
private fun RingStroke(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val radius = size.minDimension / 2f
        drawCircle(
            color = color.copy(alpha = 0.8f),
            radius = radius - 1.dp.toPx(),
            style = Stroke(width = 1.dp.toPx())
        )
    }
}
