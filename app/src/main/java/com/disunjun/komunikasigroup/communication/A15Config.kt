package com.disunjun.komunikasigroup.communication

import com.disunjun.komunikasigroup.BuildConfig

/** A1.5 + v3 backend configuration. URLs come from BuildConfig, never hard-coded at runtime. */
object A15Config {
    val baseUrl: String = BuildConfig.BACKEND_URL.trimEnd('/')

    /** v3 media signaling backend (dev). Only carries the media:* contract. */
    val signalingUrl: String = BuildConfig.SIGNALING_URL.trimEnd('/')
}