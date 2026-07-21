package com.bringyour.network.ui.shared.models

import com.bringyour.network.R

enum class ProvideControlMode {
    AUTO,
    ALWAYS,
    // the private provider: always on, but provides only to same-network peers
    NETWORK,
    NEVER;

    companion object {
        fun fromString(value: String): ProvideControlMode? {
            return when (value.lowercase()) {
                "auto" -> AUTO
                "always" -> ALWAYS
                "network" -> NETWORK
                "never" -> NEVER
                else -> null
            }
        }

        fun toString(value: ProvideControlMode): String {
            return when (value) {
                AUTO -> "auto"
                ALWAYS -> "always"
                NETWORK -> "network"
                NEVER -> "never"
            }
        }

        fun toStringResourceId(value: ProvideControlMode): Int {
            return when (value) {
                AUTO -> R.string.auto
                ALWAYS -> R.string.always
                NETWORK -> R.string.network
                NEVER -> R.string.never
            }
        }
    }
}