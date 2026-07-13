package com.bringyour.network.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bringyour.network.R
import com.bringyour.network.ui.theme.URNetworkTheme

@Composable
fun WalletChainIcon(
    blockchain: Blockchain?
) {

    val painterResourceId = when (blockchain) {
        Blockchain.SOLANA -> R.drawable.solana_logo
        Blockchain.BITTENSOR -> R.drawable.bittensor_logo
        else -> R.drawable.polygon_logo
    }

    val description = when (blockchain) {
        Blockchain.SOLANA -> "Solana Wallet"
        Blockchain.BITTENSOR -> "Bittensor Wallet"
        else -> "Polygon Wallet"
    }

    val padding = when (blockchain) {
        Blockchain.SOLANA, Blockchain.BITTENSOR -> 12.dp
        else -> 0.dp
    }

    val width = when (blockchain) {
        Blockchain.SOLANA, Blockchain.BITTENSOR -> 32.dp // solana, bittensor
        else -> 54.dp // polygon
    }

    val backgroundColor = when (blockchain) {
        Blockchain.SOLANA ->
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFF9945FF),
                    Color(0xFF14F195)
                )
            )
        Blockchain.BITTENSOR ->
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFF2A2A2A),
                    Color(0xFF000000)
                )
            )
        else ->
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFF8A46FF),
                    Color(0xFF6E38CC)
                )
            )
    }

    Box(
        modifier = Modifier
            .background(backgroundColor, shape = CircleShape)
            .padding(padding)
    ) {
        Icon(
            painter = painterResource(id = painterResourceId),
            tint = Color.White,
            contentDescription = description,
            modifier = Modifier.width(width).height(width)
        )
    }
}

@Preview
@Composable
private fun WalletChainIconPolygonPreview() {
    URNetworkTheme {
        WalletChainIcon(
            blockchain = Blockchain.POLYGON
        )
    }
}

@Preview
@Composable
private fun WalletChainIconSolanaPreview() {
    URNetworkTheme {
        WalletChainIcon(
            blockchain = Blockchain.SOLANA
        )
    }
}

@Preview
@Composable
private fun WalletChainIconBittensorPreview() {
    URNetworkTheme {
        WalletChainIcon(
            blockchain = Blockchain.BITTENSOR
        )
    }
}