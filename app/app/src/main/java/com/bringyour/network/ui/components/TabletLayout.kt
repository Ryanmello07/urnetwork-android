package com.bringyour.network.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tablet layout convention (mmm/DESIGNSTYLE.md "Tablet layouts"): on a tablet a
 * full-screen view lays its content in one readable centered column the width of
 * one of the app's sheets, an auth screen in a narrower form column, and the
 * connect drawer wraps its content in a centered panel. Both clamps are no-ops on
 * phone widths, so one view serves phones, tablets and tablet minis in both
 * orientations.
 */
object TabletLayout {

    /**
     * The readable content column. It equals the width of the app's modal
     * bottom sheets on a tablet (Material 3 `BottomSheetDefaults.SheetMaxWidth`, 640dp): the
     * referral network editor, add sign-in method, redeem code and emoji tag
     * sheets all render at this width, so a full-screen view reads like a sheet.
     */
    val contentWidth: Dp = 640.dp

    /** The auth form column (sign in, create network, instant account, seed phrase). */
    val formWidth: Dp = 400.dp

    /** Windows at least this wide (dp) get the tablet clamps; phones never do. */
    const val minTabletWidthDp: Int = 600

    /** Gap between a floating tablet drawer's bottom edge and the bar below it. */
    val drawerFloatGap: Dp = 12.dp

    /** Corner radius of the floating tablet drawer (rounded on every corner). */
    val drawerCornerRadius: Dp = 20.dp

    /** Side inset that keeps the floating drawer off the screen edges on a tablet mini. */
    val drawerSideInset: Dp = 16.dp
}

/** True when the current window is tablet-wide (portrait tablet mini and up). */
@Composable
@ReadOnlyComposable
fun isTabletWidth(): Boolean =
    LocalConfiguration.current.screenWidthDp >= TabletLayout.minTabletWidthDp

/**
 * Centers the content in a column of [TabletLayout.contentWidth] on tablet
 * widths; a no-op on phones. Apply outside the view's own padding, before its
 * `fillMaxWidth`/`fillMaxSize`.
 */
fun Modifier.tabletReadableColumn(): Modifier = tabletColumn(TabletLayout.contentWidth)

/**
 * Centers an auth form in a column of [TabletLayout.formWidth] on tablet widths;
 * a no-op on phones.
 */
fun Modifier.tabletForm(): Modifier = tabletColumn(TabletLayout.formWidth)

private fun Modifier.tabletColumn(maxWidth: Dp): Modifier = composed {
    if (isTabletWidth()) {
        this
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = maxWidth)
    } else {
        this
    }
}

/**
 * Width of the floating connect drawer panel on tablet widths: the readable
 * column, or narrower so a tablet mini in portrait (600dp) still shows a side
 * margin on both sides instead of an edge-to-edge sheet.
 */
@Composable
@ReadOnlyComposable
fun tabletDrawerWidth(): Dp {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val inset = screenWidth - TabletLayout.drawerSideInset * 2
    return if (inset < TabletLayout.contentWidth) inset else TabletLayout.contentWidth
}
