package net.appstorefr.perfectdnsmanager.data

import net.appstorefr.perfectdnsmanager.R

data class DnsProfile(
    val id: Long = System.currentTimeMillis(),
    val providerName: String,
    val name: String,
    val type: DnsType,
    val primary: String,
    val secondary: String? = null,
    val primaryV6: String? = null,
    val secondaryV6: String? = null,
    val description: String? = null,
    val isCustom: Boolean = false,
    val isFavorite: Boolean = false,
    val testUrl: String? = null,
    val isOperatorDns: Boolean = false
) {
    companion object {
        data class ProviderRating(val speed: Int, val privacy: Int)

        val providerRatings = mapOf(
            "ControlD"    to ProviderRating(speed = 4, privacy = 5),
            "dns.sb"      to ProviderRating(speed = 4, privacy = 5),
            "Surfshark"   to ProviderRating(speed = 4, privacy = 4),
            "Mullvad"     to ProviderRating(speed = 3, privacy = 5),
            "Quad9"       to ProviderRating(speed = 4, privacy = 5),
            "AdGuard"     to ProviderRating(speed = 4, privacy = 4),
            "Cloudflare"  to ProviderRating(speed = 5, privacy = 3),
            "NextDNS"     to ProviderRating(speed = 3, privacy = 3),
            "Yandex"      to ProviderRating(speed = 3, privacy = 2),
            "Google"      to ProviderRating(speed = 5, privacy = 1)
        )
        fun getProviderIcon(providerName: String): Int = when {
            providerName.contains("AdGuard", true) -> R.drawable.ic_adguard
            providerName.contains("Cloudflare", true) -> R.drawable.ic_cloudflare
            providerName.contains("ControlD", true) -> R.drawable.ic_controld
            providerName.contains("dns.sb", true) -> R.drawable.ic_dnssb
            providerName.contains("Google", true) -> R.drawable.ic_google
            providerName.contains("Mullvad", true) -> R.drawable.ic_mullvad
            providerName.contains("NextDNS", true) -> R.drawable.ic_nextdns
            providerName.contains("Quad9", true) -> R.drawable.ic_quad9
            providerName.contains("Surfshark", true) -> R.drawable.ic_surfshark
            providerName.contains("Orange", true) -> R.drawable.ic_orange
            providerName.contains("Free", true) -> R.drawable.ic_free
            providerName.contains("SFR", true) -> R.drawable.ic_sfr
            providerName.contains("Bouygues", true) -> R.drawable.ic_bouygues
            providerName.contains("Yandex", true) -> R.drawable.ic_yandex
            else -> R.drawable.ic_dns_custom
        }

        fun getDefaultPresets(): List<DnsProfile> = listOf(

            // ══════════════════════════════════════════════════════
            //  AdGuard
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1000, providerName = "AdGuard", name = "Unfiltered", type = DnsType.DOH,
                primary = "https://unfiltered.adguard-dns.com/dns-query",
                description = "Sans filtre (DoH)", testUrl = "https://adguard.com/test.html"),
            DnsProfile(id = 1001, providerName = "AdGuard", name = "Standard", type = DnsType.DOH,
                primary = "https://dns.adguard-dns.com/dns-query",
                description = "Bloque pubs et traqueurs (DoH)", testUrl = "https://adguard.com/test.html"),
            DnsProfile(id = 1002, providerName = "AdGuard", name = "Standard", type = DnsType.DEFAULT,
                primary = "94.140.14.14", secondary = "94.140.15.15",
                primaryV6 = "2a10:50c0::ad1:ff", secondaryV6 = "2a10:50c0::ad2:ff",
                description = "Bloque pubs et traqueurs", testUrl = "https://adguard.com/test.html"),
            DnsProfile(id = 1003, providerName = "AdGuard", name = "Standard", type = DnsType.DOT,
                primary = "dns.adguard-dns.com",
                description = "Bloque pubs et traqueurs (DoT)", testUrl = "https://adguard.com/test.html"),
            DnsProfile(id = 1004, providerName = "AdGuard", name = "Unfiltered", type = DnsType.DEFAULT,
                primary = "94.140.14.140", secondary = "94.140.14.141",
                primaryV6 = "2a10:50c0::1:ff", secondaryV6 = "2a10:50c0::2:ff",
                description = "Sans filtre"),
            DnsProfile(id = 1005, providerName = "AdGuard", name = "Family", type = DnsType.DEFAULT,
                primary = "94.140.14.15", secondary = "94.140.15.16",
                primaryV6 = "2a10:50c0::bad1:ff", secondaryV6 = "2a10:50c0::bad2:ff",
                description = "Protection famille"),

            // ══════════════════════════════════════════════════════
            //  Cloudflare
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1010, providerName = "Cloudflare", name = "Standard", type = DnsType.DOH,
                primary = "https://cloudflare-dns.com/dns-query",
                description = "Rapide et privé (DoH)", testUrl = "https://one.one.one.one/help/"),
            DnsProfile(id = 1011, providerName = "Cloudflare", name = "Standard", type = DnsType.DEFAULT,
                primary = "1.1.1.1", secondary = "1.0.0.1",
                primaryV6 = "2606:4700:4700::1111", secondaryV6 = "2606:4700:4700::1001",
                description = "Rapide et privé", testUrl = "https://one.one.one.one/help/"),
            DnsProfile(id = 1012, providerName = "Cloudflare", name = "Standard", type = DnsType.DOT,
                primary = "one.one.one.one",
                description = "Rapide et privé (DoT)", testUrl = "https://one.one.one.one/help/"),
            DnsProfile(id = 1013, providerName = "Cloudflare", name = "Malware Blocking", type = DnsType.DEFAULT,
                primary = "1.1.1.2", secondary = "1.0.0.2",
                primaryV6 = "2606:4700:4700::1112", secondaryV6 = "2606:4700:4700::1002",
                description = "Sécurité renforcée"),
            DnsProfile(id = 1014, providerName = "Cloudflare", name = "Family", type = DnsType.DEFAULT,
                primary = "1.1.1.3", secondary = "1.0.0.3",
                primaryV6 = "2606:4700:4700::1113", secondaryV6 = "2606:4700:4700::1003",
                description = "Blocage contenu adulte"),

            // ══════════════════════════════════════════════════════
            //  ControlD
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1033, providerName = "ControlD", name = "Unfiltered", type = DnsType.DOH,
                primary = "https://freedns.controld.com/p0", description = "Sans filtre (DoH)"),
            DnsProfile(id = 1034, providerName = "ControlD", name = "Malware", type = DnsType.DOH,
                primary = "https://freedns.controld.com/p1", description = "Anti-Malware (DoH)"),
            DnsProfile(id = 1035, providerName = "ControlD", name = "Ads & Tracking", type = DnsType.DOH,
                primary = "https://freedns.controld.com/p2", description = "Anti-Pub (DoH)"),
            DnsProfile(id = 1036, providerName = "ControlD", name = "Unfiltered", type = DnsType.DEFAULT,
                primary = "76.76.2.0", secondary = "76.76.10.0",
                primaryV6 = "2606:1a40::0", secondaryV6 = "2606:1a40:1::0",
                description = "Sans filtre"),
            DnsProfile(id = 1037, providerName = "ControlD", name = "Malware", type = DnsType.DEFAULT,
                primary = "76.76.2.1", secondary = "76.76.10.1",
                primaryV6 = "2606:1a40::1", secondaryV6 = "2606:1a40:1::1",
                description = "Anti-Malware"),
            DnsProfile(id = 1038, providerName = "ControlD", name = "Ads & Tracking", type = DnsType.DEFAULT,
                primary = "76.76.2.2", secondary = "76.76.10.2",
                primaryV6 = "2606:1a40::2", secondaryV6 = "2606:1a40:1::2",
                description = "Anti-Pub"),
            DnsProfile(id = 1030, providerName = "ControlD", name = "Unfiltered", type = DnsType.DOT,
                primary = "p0.freedns.controld.com", description = "Sans filtre (DoT)"),
            DnsProfile(id = 1031, providerName = "ControlD", name = "Malware", type = DnsType.DOT,
                primary = "p1.freedns.controld.com", description = "Anti-Malware (DoT)"),
            DnsProfile(id = 1032, providerName = "ControlD", name = "Ads & Tracking", type = DnsType.DOT,
                primary = "p2.freedns.controld.com", description = "Anti-Pub (DoT)"),

            // ══════════════════════════════════════════════════════
            //  dns.sb
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1040, providerName = "dns.sb", name = "Standard", type = DnsType.DOH,
                primary = "https://doh.dns.sb/dns-query", description = "No logs (DoH)"),
            DnsProfile(id = 1041, providerName = "dns.sb", name = "Standard", type = DnsType.DEFAULT,
                primary = "185.222.222.222", secondary = "45.11.45.11",
                primaryV6 = "2a09::", secondaryV6 = "2a11::",
                description = "No logs"),
            DnsProfile(id = 1042, providerName = "dns.sb", name = "Standard", type = DnsType.DOT,
                primary = "dot.sb", description = "No logs (DoT)"),

            // ══════════════════════════════════════════════════════
            //  Google
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1050, providerName = "Google", name = "Standard", type = DnsType.DOH,
                primary = "https://dns.google/dns-query", description = "Rapide (DoH)"),
            DnsProfile(id = 1051, providerName = "Google", name = "Standard", type = DnsType.DEFAULT,
                primary = "8.8.8.8", secondary = "8.8.4.4",
                primaryV6 = "2001:4860:4860::8888", secondaryV6 = "2001:4860:4860::8844",
                description = "Rapide"),
            DnsProfile(id = 1052, providerName = "Google", name = "Standard", type = DnsType.DOT,
                primary = "dns.google", description = "Rapide (DoT)"),

            // ══════════════════════════════════════════════════════
            //  Mullvad  (DoH / DoT uniquement — pas de DNS standard UDP)
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1060, providerName = "Mullvad", name = "Standard", type = DnsType.DOH,
                primary = "https://dns.mullvad.net/dns-query",
                description = "Confidentialité (DoH)"),
            DnsProfile(id = 1063, providerName = "Mullvad", name = "Adblock", type = DnsType.DOH,
                primary = "https://adblock.dns.mullvad.net/dns-query",
                description = "Anti-pub (DoH)"),
            DnsProfile(id = 1064, providerName = "Mullvad", name = "Base", type = DnsType.DOH,
                primary = "https://base.dns.mullvad.net/dns-query",
                description = "Anti-pub + malware (DoH)"),
            DnsProfile(id = 1062, providerName = "Mullvad", name = "Standard", type = DnsType.DOT,
                primary = "dns.mullvad.net", description = "Confidentialité (DoT)"),
            DnsProfile(id = 1065, providerName = "Mullvad", name = "Base", type = DnsType.DOT,
                primary = "base.dns.mullvad.net", description = "Anti-pub + malware (DoT)"),

            // ══════════════════════════════════════════════════════
            //  NextDNS
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1070, providerName = "NextDNS", name = "Standard", type = DnsType.DOH,
                primary = "https://dns.nextdns.io",
                description = "Sans profil perso (DoH)", testUrl = "https://test.nextdns.io/"),
            DnsProfile(id = 1071, providerName = "NextDNS", name = "Standard", type = DnsType.DOT,
                primary = "dns.nextdns.io",
                description = "Sans profil perso (DoT)", testUrl = "https://test.nextdns.io/"),

            // ══════════════════════════════════════════════════════
            //  Quad9
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1080, providerName = "Quad9", name = "Standard", type = DnsType.DOH,
                primary = "https://dns.quad9.net/dns-query",
                description = "Anti-Malware (DoH)"),
            DnsProfile(id = 1081, providerName = "Quad9", name = "Standard", type = DnsType.DEFAULT,
                primary = "9.9.9.9", secondary = "149.112.112.112",
                primaryV6 = "2620:fe::fe", secondaryV6 = "2620:fe::9",
                description = "Anti-Malware"),
            DnsProfile(id = 1082, providerName = "Quad9", name = "Unsecured", type = DnsType.DEFAULT,
                primary = "9.9.9.10", secondary = "149.112.112.10",
                primaryV6 = "2620:fe::10", secondaryV6 = "2620:fe::fe:10",
                description = "Sans filtre"),
            DnsProfile(id = 1083, providerName = "Quad9", name = "Standard", type = DnsType.DOT,
                primary = "dns.quad9.net", description = "Anti-Malware (DoT)"),

            // ══════════════════════════════════════════════════════
            //  Surfshark
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1101, providerName = "Surfshark", name = "Standard", type = DnsType.DOH,
                primary = "https://dns.surfsharkdns.com/dns-query",
                description = "No logs (DoH)"),
            DnsProfile(id = 1100, providerName = "Surfshark", name = "Standard", type = DnsType.DEFAULT,
                primary = "194.169.169.169",
                primaryV6 = "2a09:a707:169::",
                description = "No logs"),
            DnsProfile(id = 1102, providerName = "Surfshark", name = "Standard", type = DnsType.DOT,
                primary = "dns.surfsharkdns.com", description = "No logs (DoT)"),

            // ══════════════════════════════════════════════════════
            //  Yandex DNS
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 1110, providerName = "Yandex", name = "Basic", type = DnsType.DOH,
                primary = "https://common.dot.dns.yandex.net/dns-query",
                description = "Rapide (DoH)"),
            DnsProfile(id = 1111, providerName = "Yandex", name = "Safe", type = DnsType.DOH,
                primary = "https://safe.dot.dns.yandex.net/dns-query",
                description = "Anti-Malware (DoH)"),
            DnsProfile(id = 1112, providerName = "Yandex", name = "Family", type = DnsType.DOH,
                primary = "https://family.dot.dns.yandex.net/dns-query",
                description = "Protection famille (DoH)"),
            DnsProfile(id = 1113, providerName = "Yandex", name = "Basic", type = DnsType.DEFAULT,
                primary = "77.88.8.8", secondary = "77.88.8.1",
                primaryV6 = "2a02:6b8::feed:0ff", secondaryV6 = "2a02:6b8:0:1::feed:0ff",
                description = "Rapide"),
            DnsProfile(id = 1114, providerName = "Yandex", name = "Safe", type = DnsType.DEFAULT,
                primary = "77.88.8.88", secondary = "77.88.8.2",
                primaryV6 = "2a02:6b8::feed:bad", secondaryV6 = "2a02:6b8:0:1::feed:bad",
                description = "Anti-Malware"),
            DnsProfile(id = 1115, providerName = "Yandex", name = "Family", type = DnsType.DEFAULT,
                primary = "77.88.8.7", secondary = "77.88.8.3",
                primaryV6 = "2a02:6b8::feed:a11", secondaryV6 = "2a02:6b8:0:1::feed:a11",
                description = "Protection famille"),
            DnsProfile(id = 1116, providerName = "Yandex", name = "Basic", type = DnsType.DOT,
                primary = "common.dot.dns.yandex.net",
                description = "Rapide (DoT)"),
            DnsProfile(id = 1117, providerName = "Yandex", name = "Safe", type = DnsType.DOT,
                primary = "safe.dot.dns.yandex.net",
                description = "Anti-Malware (DoT)"),
            DnsProfile(id = 1118, providerName = "Yandex", name = "Family", type = DnsType.DOT,
                primary = "family.dot.dns.yandex.net",
                description = "Protection famille (DoT)"),

            // ══════════════════════════════════════════════════════
            //  DNS Opérateur FR  (cachés sauf toggle dédié)
            // ══════════════════════════════════════════════════════
            DnsProfile(id = 2001, providerName = "🇫🇷 Orange", name = "DNS Orange", type = DnsType.DEFAULT,
                primary = "80.10.246.2", secondary = "80.10.246.129",
                description = "DNS opérateur Orange", isOperatorDns = true),
            DnsProfile(id = 2002, providerName = "🇫🇷 Free", name = "DNS Free", type = DnsType.DEFAULT,
                primary = "212.27.40.240", secondary = "212.27.40.241",
                description = "DNS opérateur Free", isOperatorDns = true),
            DnsProfile(id = 2003, providerName = "🇫🇷 SFR", name = "DNS SFR", type = DnsType.DEFAULT,
                primary = "109.0.66.10", secondary = "109.0.66.20",
                description = "DNS opérateur SFR", isOperatorDns = true),
            DnsProfile(id = 2004, providerName = "🇫🇷 Bouygues", name = "DNS Bouygues", type = DnsType.DEFAULT,
                primary = "194.158.122.10", secondary = "194.158.122.15",
                description = "DNS opérateur Bouygues Telecom", isOperatorDns = true)
        )
    }
}
