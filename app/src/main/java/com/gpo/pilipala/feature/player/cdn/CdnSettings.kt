package com.gpo.pilipala.feature.player.cdn

import com.gpo.pilipala.core.settings.AppSettings

/** Line (CDN) selection settings, shown on the 网络与线路 settings page. */
object CdnSettings {
    /** "auto" = measure and switch when a line can't keep up; "fixed" = always [fixedHost]; "native" = as issued. */
    val mode = AppSettings.register("cdn_mode", "auto")
    val fixedHost = AppSettings.register("cdn_fixed_host", CdnHosts.DEFAULT_TARGET)
    /** "proxy" = MCDN through the official proxy, "replace" = prefer an issued UPOS URL, "keep" = leave it. */
    val mcdn = AppSettings.register("cdn_mcdn", "proxy")
    /** Replace P2P / PCDN endpoints (bare IPs, odd ports) with a UPOS mirror. */
    val avoidPcdn = AppSettings.register("cdn_avoid_pcdn", true)
    val autoSwitch = AppSettings.register("cdn_auto_switch", true)
}
