package com.alpdroid.app

import android.content.Context

/**
 * GitHub-token access for the agent bridge that deliberately does NOT go through the UI host:
 * the bridge's [AgentBridge.Host] is attached only while a MainActivity exists, so every
 * UI-routed call (including the old githubToken()) returned null whenever the screen host was
 * detached — after an app update, a process kill, or simply no Activity being alive. The token
 * itself lives in the app Keystore via [GitHubAuth], reachable from the Application context
 * alone, so these readers keep `alpctl github token` (and git push/pull with it) working
 * across restarts while the user's "Let agents use my GitHub token" option is on.
 */
object GitHubTokenReader {
    fun allowed(context: Context): Boolean = SettingsStore(context).agentGithubToken

    fun signedIn(context: Context): Boolean = GitHubAuth.token(context) != null

    /** May refresh silently (blocking, network) — bridge threads tolerate that; never call on UI. */
    fun token(context: Context): String? =
        if (allowed(context)) GitHubAuth.validToken(context) else null
}
