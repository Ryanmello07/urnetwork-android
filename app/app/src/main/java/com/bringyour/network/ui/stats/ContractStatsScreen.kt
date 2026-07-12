package com.bringyour.network.ui.stats

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
private data class ContractCircleState(
    val id: String,
    val used: Long,
    val total: Long,
)

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

        // a replaced contract (new id) slides out to the side and fades while the
        // new one fades into its place; within a contract the disc just resizes.
        // the id/used/total travel through the animated state (with contentKey on
        // the id) so the outgoing circle keeps its own data and only an id change
        // triggers a swap.
        AnimatedContent(
            targetState = ContractCircleState(row.contractId, row.contractUsedByteCount, row.contractByteCount),
            contentKey = { it.id },
            transitionSpec = {
                (fadeIn(tween(500)) togetherWith
                    (slideOutHorizontally(tween(500)) { -it } + fadeOut(tween(500))))
                    .apply { targetContentZIndex = 1f }
            },
            label = "contractCircleSwap"
        ) { state ->
            ContractCircle(
                used = state.used,
                total = state.total,
                color = contractColor,
                label = stringResource(id = R.string.contract),
            )
        }

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

        AnimatedContent(
            targetState = ContractCircleState(row.companionContractId, row.companionContractUsedByteCount, row.companionContractByteCount),
            contentKey = { it.id },
            transitionSpec = {
                (fadeIn(tween(500)) togetherWith
                    (slideOutHorizontally(tween(500)) { it } + fadeOut(tween(500))))
                    .apply { targetContentZIndex = 1f }
            },
            label = "companionCircleSwap"
        ) { state ->
            ContractCircle(
                used = state.used,
                total = state.total,
                color = companionColor,
                label = stringResource(id = R.string.companion),
            )
        }

    }
}

@Composable
private fun ContractCircle(
    used: Long,
    total: Long,
    color: Color,
    label: String,
) {

    // area-proportional inner disc, with a minimum visible size
    val fraction = if (0 < total) min(1.0, used.toDouble() / total.toDouble()) else 0.0
    // grow/shrink the disc smoothly, matching the 0.5s transfer-chart transition
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
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                drawCircle(
                    color = color.copy(alpha = 0.8f),
                    radius = radius - 1.dp.toPx(),
                    style = Stroke(width = 1.dp.toPx())
                )
                if (0 < animatedFraction) {
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
