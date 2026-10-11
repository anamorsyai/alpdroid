package com.alpdroid.proxy

import java.util.concurrent.atomic.AtomicLong

/**
 * What the proxy has really carried since it started: bytes sent to servers ([bytesUp]), bytes brought back from them
 * ([bytesDown]) and requests handled. Shown in the app so the numbers can be compared with an external speed meter —
 * while traffic goes through the proxy a meter that also reads the loopback interface sees the same bytes again on the
 * browser-to-proxy leg, so it can show roughly double (or more) of this.
 */
class ProxyStats {
    val bytesUp = AtomicLong()
    val bytesDown = AtomicLong()
    val requests = AtomicLong()
}
