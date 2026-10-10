package com.alpdroid.proxy

/**
 * A snapshot of everything the Settings screen exposes. Lives here (not in :app, where the
 * real SharedPreferences-backed SettingsStore is) so agent-mcp — which can't depend on :app —
 * can still read/write settings through plain get/set lambdas RoninApplication supplies, the
 * same pattern already used for the workspace directory supplier.
 */
data class AppSettings(
    val browserHomeUrl: String,
    val terminalProxyingEnabled: Boolean,
    val scopePatterns: List<String>,
)
