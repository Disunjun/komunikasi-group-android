package com.disunjun.komunikasigroup.communication

import com.disunjun.komunikasigroup.BuildConfig

/** A1.5 backend configuration. URL comes from BuildConfig, never hard-coded at runtime. */
object A15Config {
    val baseUrl: String = BuildConfig.BACKEND_URL.trimEnd('/')
}