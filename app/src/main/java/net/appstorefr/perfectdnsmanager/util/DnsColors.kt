package net.appstorefr.perfectdnsmanager.util

import net.appstorefr.perfectdnsmanager.data.DnsType

object DnsColors {
    fun colorForType(type: DnsType): Int = when (type) {
        DnsType.DOH -> 0xFFBF00FF.toInt()  // Violet
        DnsType.DOQ -> 0xFF00D4FF.toInt()  // Blue/Cyan
        DnsType.DOT -> 0xFF00FF66.toInt()  // Green
        DnsType.DEFAULT -> 0xFF888888.toInt()  // Grey
    }

    fun labelForType(type: DnsType): String = when (type) {
        DnsType.DOH -> "DoH"
        DnsType.DOQ -> "DoQ"
        DnsType.DOT -> "DoT"
        DnsType.DEFAULT -> "Standard"
    }
}
