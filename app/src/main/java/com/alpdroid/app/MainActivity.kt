package com.alpdroid.app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import org.json.JSONArray
import android.content.res.ColorStateList
import android.util.Log
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.alpdroid.app.files.FileBrowserPanel
import com.alpdroid.app.files.FileOps
import com.alpdroid.app.terminal.TerminalColors
import com.alpdroid.app.terminal.TerminalEmulator
import com.alpdroid.app.terminal.TerminalView
import com.alpdroid.app.terminal.Themes
import java.io.File
import java.io.IOException

class MainActivity : Activity() {
    private lateinit var terminalView: TerminalView
    private lateinit var setupContainer: LinearLayout
    private lateinit var setupStatus: TextView
    private lateinit var setupProgress: LiquidFillView
    private lateinit var grantStorageButton: Button
    private lateinit var startTerminalButton: Button
    private lateinit var retryButton: Button
    private lateinit var storageStatusText: TextView
    private lateinit var extraKeysScroll: HorizontalScrollView
    private lateinit var tabBar: LinearLayout
    private lateinit var extraKeysRow: LinearLayout
    private lateinit var fileBrowserPanel: FileBrowserPanel
    private lateinit var fbSessionsButton: Button
    private lateinit var fbAndroidButton: Button
    private lateinit var fbAlpineButton: Button
    private lateinit var fbUpButton: ImageButton
    private lateinit var fbPathText: TextView
    private lateinit var fbFileList: ListView
    private lateinit var fbSessionsContainer: LinearLayout
    private lateinit var fbSessionsList: LinearLayout
    private lateinit var settingsPanel: LinearLayout
    private lateinit var rootFrame: FrameLayout
    private lateinit var terminalContainer: FrameLayout
    private lateinit var tabBarScroll: HorizontalScrollView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var settingsStore: SettingsStore

    // Owned by the Application, not this Activity instance — see AlpineTermApp.tabs for why:
    // in short, so a shell surviving in the background across an Activity recreation isn't torn
    // down just because the (unrelated) Activity object holding the UI was.
    private val tabs get() = (application as AlpineTermApp).tabs
    private var activeTabIndex: Int
        get() = (application as AlpineTermApp).activeTabIndex
        set(value) { (application as AlpineTermApp).activeTabIndex = value }
    private var nextTabId: Int
        get() = (application as AlpineTermApp).nextTabId
        set(value) { (application as AlpineTermApp).nextTabId = value }

    /** Set only right before this Activity's own finish() call below, when every tab's shell has
     *  already exited on its own — the one case onDestroy() should actually tear sessions down
     *  for. isFinishing alone can't be used for that: it's also true when the *system* destroys
     *  this Activity because the user swiped the app away from Recents, which does NOT mean the
     *  shells should die too (task removal doesn't kill this process on its own, and the whole
     *  point of the foreground keep-alive service is to survive exactly that). */
    private var deliberateExit = false

    // Delegated to the Application for the same reason tabs/activeTabIndex above are — see
    // AlpineTermApp.pendingSessionStarts.
    private var pendingSessionStarts: Int
        get() = (application as AlpineTermApp).pendingSessionStarts
        set(value) { (application as AlpineTermApp).pendingSessionStarts = value }

    // Known only once the view has been laid out at least once (TerminalView.onGridSize) —
    // every new tab and every tab switch resizes to whatever this currently is.
    private var lastRows = 24
    private var lastCols = 80
    private var gridKnown = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate() — this is what actually swaps MainActivity's manifest
        // theme (Theme.App.Starting, the splash) for its real one (Theme.App.Starting's own
        // postSplashScreenTheme, Theme.AlpDroid) once the splash has been shown.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        registerDeviceEvents()
        // Plugin counting walks every plugin dir (reads + hashes) — off the main thread;
        // enabledCount is @Volatile so readers never see a torn value, just 0 briefly.
        (application as AlpineTermApp).pluginJobs.apply {
            paused = false
            (application as AlpineTermApp).backgroundExecutor.execute { refreshCount() }
        }
        agentBridge.host = agentHost
        if (settingsStore.agentAccessEnabled) syncAgentBridge()
        // System-kill detector: a stale heartbeat means the OS killed the process (battery
        // saver / memory pressure), not an app crash — there is no crash dialog for those,
        // so without this the user can never tell the two apart. Rotation recreates within
        // seconds (onPause stamps fresh), so only a gap counts. A clean onDestroy (swipe
        // from Recents, Back, rotation) clears suspicion — only a death with no lifecycle
        // at all reports. Deliberate exits zero the stamp.
        val lastAlive = settingsStore.lastAliveMs
        val cleanGone = settingsStore.destroyWasClean
        settingsStore.destroyWasClean = false
        settingsStore.lastAliveMs = System.currentTimeMillis()
        if (lastAlive != 0L && !cleanGone && System.currentTimeMillis() - lastAlive > 90_000L) {
            mainHandler.post { showSystemKillNotice() }
        }
        mainHandler.postDelayed({ if (!isFinishing && !isDestroyed) maybeAutoBackup() }, 30_000)
        mainHandler.postDelayed({ if (!isFinishing && !isDestroyed) maybeAutoUpdateCheck() }, 10_000)
        startPeriodicUpdateChecks()
        TerminalColors.applyTheme(Themes.byId(settingsStore.themeId))

        setContentView(R.layout.activity_main)
        // Taking over inset handling entirely (paired with windowSoftInputMode="adjustNothing" in
        // the manifest) is the actual fix for the keyboard-toggle line-loss saga: the previous
        // "adjustResize" design shrank the terminal's real pixel size — and therefore its row/col
        // count, and therefore the PTY's window size the shell reacts to — every time the keyboard
        // so much as twitched, and no amount of debouncing or hysteresis on that resize path was
        // ever going to be provably correct across every device's keyboard animation and IME
        // quirks. With decorFitsSystemWindows off, the window keeps its full size regardless of
        // the keyboard, and applyInsets() below (not a resize at all — a pure visual translationY)
        // is the only thing that reacts to the keyboard now. The terminal's own row/col count only
        // ever changes for a genuine width/height change again: rotation or an explicit font-size
        // change — both real, deliberate, and nothing like the keyboard's own animation noise.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        drawerLayout = findViewById(R.id.drawerLayout)
        setupDrawerGestures()
        rootFrame = findViewById(R.id.rootFrame)
        setupContainer = findViewById(R.id.setupContainer)
        setupStatus = findViewById(R.id.setupStatus)
        setupProgress = findViewById(R.id.setupProgress)
        grantStorageButton = findViewById(R.id.grantStorageButton)
        startTerminalButton = findViewById(R.id.startTerminalButton)
        retryButton = findViewById(R.id.retryButton)
        extraKeysScroll = findViewById(R.id.extraKeysScroll)
        tabBarScroll = findViewById(R.id.tabBarScroll)
        tabBar = findViewById(R.id.tabBar)
        terminalContainer = findViewById(R.id.terminalContainer)
        extraKeysRow = findViewById(R.id.extraKeysRow)
        settingsPanel = findViewById(R.id.settingsPanel)
        applyInsetsManually()

        terminalView = TerminalView(this, null)
        terminalContainer.addView(terminalView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        terminalView.onInput = { bytes -> writeToActiveSession(bytes) }
        terminalView.onGridSize = { rows, cols -> onGridSize(rows, cols) }
        terminalView.onFontScaleChanged = { sp -> settingsStore.fontSizeSp = sp }
        terminalView.onSwipeTab = { next -> switchToTab(activeTabIndex + if (next) 1 else -1) }
        terminalView.onSaveSelection = { text -> saveSelectionToFile(text) }
        terminalView.setTextSizePx(spToPx(settingsStore.fontSizeSp))
        terminalView.setTypeface(typefaceFor(settingsStore.fontFamily))
        terminalView.setLigaturesEnabled(settingsStore.ligaturesEnabled)

        terminalContainer.addView(buildSearchBar(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        buildExtraKeysRow(extraKeysRow)
        showSettingsCategoryList()
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerOpened(drawerView: View) {
                // Either drawer sliding open over the terminal is exactly the moment the keyboard
                // is most likely still up from typing — leaving it up just eats space behind the
                // drawer content until the user thinks to dismiss it themselves.
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(drawerView.windowToken, 0)
                // Deliberately does NOT reset the settings panel back to its category list here —
                // a swipe that closes the drawer by accident (or on purpose, to glance at the
                // terminal) should land back on whatever sub-screen was open, not lose the user's
                // place. showSettingsCategoryList() is still called once up front (see above) and
                // from each sub-screen's own back arrow, which is the only deliberate way back.
            }
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
            override fun onDrawerClosed(drawerView: View) {}
            override fun onDrawerStateChanged(newState: Int) {}
        })
        fbSessionsButton = findViewById(R.id.fbSessionsButton)
        fbAndroidButton = findViewById(R.id.fbAndroidButton)
        fbAlpineButton = findViewById(R.id.fbAlpineButton)
        fbUpButton = findViewById(R.id.fbUpButton)
        fbPathText = findViewById(R.id.fbPathText)
        fbFileList = findViewById(R.id.fbFileList)
        fbSessionsContainer = findViewById(R.id.fbSessionsContainer)
        fbSessionsList = findViewById(R.id.fbSessionsList)
        fileBrowserPanel = FileBrowserPanel(
            this,
            fbAndroidButton,
            fbAlpineButton,
            fbUpButton,
            fbPathText,
            fbFileList,
            findViewById(R.id.fbClipboardBar),
            findViewById(R.id.fbClipboardText),
            findViewById(R.id.fbPasteButton),
            findViewById(R.id.fbCancelClipboardButton),
            findViewById(R.id.fbSelectionBarScroll),
            findViewById(R.id.fbSelectionBar),
            onFileRootSelected = { hideSessionsMode() },
            onOpenTerminalHere = { guestPath ->
                drawerLayout.closeDrawer(GravityCompat.START)
                addTab(onStarted = { runShortcutCommandOnReady("cd '" + guestPath.replace("'", "'\\''") + "'\n") })
            },
        )
        fbSessionsButton.setOnClickListener { showSessionsMode() }
        // The left drawer's layout starts in file-browser mode (fbSessionsContainer defaults to
        // gone in the XML) — flip it to Sessions once, right up front, so the very first time the
        // drawer opens it lands on the sessions list rather than a file browser nobody asked for.
        // After this, hideSessionsMode()/showSessionsMode() are only ever called from the
        // Sessions/Android/Alpine tab taps themselves, so whichever one the user picks stays put
        // across drawer closes and reopens instead of snapping back here.
        showSessionsMode()
        findViewById<Button>(R.id.fbNewSessionButton).apply {
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
            stateListAnimator = null
            elevation = 0f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(ContextCompat.getColor(this@MainActivity, R.color.accent_wash))
            }
            setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.START)
                addTab()
            }
        }
        rebuildTabBar()
        applyChromeColors()

        grantStorageButton.setOnClickListener { startActivity(StorageAccess.requestIntent(this)) }
        updateStorageBanner()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Re-tapping the home-screen widget while the app is already running should behave like
        // tapping "+" in the tab bar — a fresh session, not just bringing the same one to front
        // (which is all singleTop + FLAG_ACTIVITY_SINGLE_TOP would otherwise do on their own).
        // On a cold start, tabs is still empty and the normal first-tab flow in onGridSize()
        // handles it, so this only fires for the "already running" case.
        if (intent.action == AlpineWidgetProvider.ACTION_NEW_SESSION && tabs.isNotEmpty() && gridKnown) {
            addTab()
        }
    }

    /** Newest downloaded APK newer than the installed app, if any — the resume-install source. */
    private fun findPendingUpdateApk(): java.io.File? {
        val dir = getExternalFilesDir(null)?.let { java.io.File(it, "updates") } ?: return null
        val (_, currentCode) = AppUpdater.currentVersion(this)
        return dir.listFiles { f -> f.isFile && f.name.startsWith("AlpineTerm-") && f.name.endsWith(".apk") }
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull { apk -> (runCatching { AppUpdater.apkVersionCode(this, apk) }.getOrNull() ?: 0) > currentCode }
    }

    private fun showPendingInstallDialog(apk: java.io.File) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Ready to install")
            .setMessage("The update is downloaded (${apk.name}). Install now? The app will close — tabs and files are untouched.")
            .setPositiveButton("Install") { _, _ -> AppUpdater.installApk(this, apk) }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateStorageBanner()
        refreshStorageRow()
        // Back from the install-permission Settings screen: re-offer the waiting install
        // instead of stranding the user with no way to continue.
        if (sentToInstallSettings) {
            sentToInstallSettings = false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
                findPendingUpdateApk()?.let { showPendingInstallDialog(it) }
            } else {
                android.widget.Toast.makeText(this, "Install permission still off — tap Update to try again", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        // Returning from the installer / browser / Settings is exactly when an update may
        // have appeared — re-check here (throttled) so no restart is ever needed to see it.
        maybeAutoUpdateCheck()
        // A foreground start refused while we were backgrounded (API 31+) is retried here, the
        // first moment we are foreground again — otherwise the process stays killable.
        if (tabs.isNotEmpty()) updateKeepAliveService()
        maybeAskBatteryExemption()
        terminalView.setTextSizePx(spToPx(settingsStore.fontSizeSp))
        extraKeysScroll.visibility = if (settingsStore.showExtraKeys) View.VISIBLE else View.GONE
        terminalView.requestFocus()
        terminalView.resumeBlink()
        // Keyboard only when explicitly asked (fresh widget session): popping it on every resume
        // (back from installer, browser, Settings) used to cover the screen uninvited.
        if (intent.action == AlpineWidgetProvider.ACTION_NEW_SESSION) {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(terminalView, 0)
        }
    }

    /** Opens the system's battery-exemption prompt, falling back to the battery settings
     *  list when the direct prompt can't open (missing permission on older builds, OEM
     *  quirks) — never silently nothing. Returns true if exemption already held. */
    private fun openBatteryExemption(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            android.widget.Toast.makeText(this, "Already ignoring battery optimizations", android.widget.Toast.LENGTH_SHORT).show()
            return true
        }
        val direct = Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:$packageName"),
        )
        val opened = runCatching { startActivity(direct); true }.getOrDefault(false)
        if (!opened) {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            android.widget.Toast.makeText(this, "Find AlpDroid in the list and set it to Unrestricted", android.widget.Toast.LENGTH_LONG).show()
        }
        return false
    }

    /** One-time explanation + system prompt for the battery exemption: the keep-alive service
     *  and wake lock only work reliably when the app is also exempt from Doze/standby limits.
     *  Shown once, and only once a session exists (so it is clearly tied to background work). */
    private fun maybeAskBatteryExemption() {
        if (isFinishing || isDestroyed || tabs.isEmpty() || settingsStore.batteryPromptShown) return
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { settingsStore.batteryPromptShown = true; return }
        settingsStore.batteryPromptShown = true
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Keep sessions running in the background?")
            .setMessage("Servers and long commands stop when Android puts the app to sleep. Allow AlpDroid to ignore battery optimizations so they keep running with the screen off. You can change this later in Settings.")
            .setPositiveButton("Allow") { _, _ -> openBatteryExemption() }
            .setNegativeButton("Not now", null)
            .show()
    }

    /** The previous process died to a system kill (not a crash, not an exit): explain and
     *  point at the battery exemption, which is the actual fix. Sessions can't survive it —
     *  proot and every shell were children of the dead process. */
    private fun showSystemKillNotice() {
        if (isFinishing || isDestroyed) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Stopped by the system")
            .setMessage(
                "Android closed the app to save battery/memory, killing the terminal sessions with it. " +
                    "This isn't a crash — nothing in the app did it. To stop it happening:\n\n" +
                    "• Tap Battery settings below and allow Unrestricted / Ignore optimizations\n" +
                    "• Lock AlpDroid in the Recents screen so swipes don't clear it\n" +
                    "• Keep the keep-alive notification on",
            )
            .setPositiveButton("Battery settings") { _, _ -> openBatteryExemption() }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        // Heartbeat for the system-kill detector (commit: must hit disk before any kill).
        settingsStore.lastAliveMs = System.currentTimeMillis()
        // The view stays attached to its window the whole time the app just sits backgrounded
        // (Home button, switching apps) — only actually finishing the Activity detaches it — so
        // without this, the cursor-blink Handler would keep firing every 530ms indefinitely with
        // nothing ever visibly drawn, a small but needless wakeup for as long as the process
        // (kept alive on purpose for background CLI work) happens to stay running.
        terminalView.pauseBlink()
    }

    private fun spToPx(sp: Float): Float =
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    // Font asset inflate/parse used to run synchronously on every cold start and every
    // theme/agent change that rebuilds settings — cached process-wide after first load.
    private var cachedTypefaceId: String? = null
    private var cachedTypeface: Typeface? = null
    private fun typefaceFor(fontId: String): Typeface {
        if (fontId == cachedTypefaceId) return cachedTypeface ?: Typeface.MONOSPACE
        val tf = when (fontId) {
            "jetbrains_mono" -> ResourcesCompat.getFont(this, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
            "fira_code" -> ResourcesCompat.getFont(this, R.font.fira_code) ?: Typeface.MONOSPACE
            else -> Typeface.MONOSPACE
        }
        cachedTypefaceId = fontId
        cachedTypeface = tf
        return tf
    }

    /**
     * Before this, only the terminal's own text area followed the chosen theme — the tab bar,
     * extra-keys row, and setup screen were all a hardcoded dark gray, so switching themes
     * looked like it only half-applied. Everything but the settings panel itself (kept a fixed
     * neutral color so its controls stay legible regardless of the terminal theme) now follows
     * [TerminalColors.DEFAULT_BG], including the system status/nav bars for a true full-screen feel.
     */
    private fun applyChromeColors() {
        val bg = TerminalColors.DEFAULT_BG
        // The theme XML's windowBackground is a static, hardcoded black (needed as *something*
        // for the instant before this code runs) that nothing ever updated to match the chosen
        // theme — it's the window's own base layer, underneath every view, so it shows through
        // any gap or moment where views above it don't fully opaquely repaint (most visibly:
        // the crossfade in fadeOutSetup(), where both the outgoing and incoming views are
        // briefly semi-transparent at once). That's almost certainly the "black block with hard
        // boundaries" this is fixing — every *view's own* background already matched the theme.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        window.statusBarColor = bg
        window.navigationBarColor = bg
        rootFrame.setBackgroundColor(bg)
        terminalContainer.setBackgroundColor(bg)
        tabBarScroll.setBackgroundColor(bg)
        extraKeysScroll.setBackgroundColor(bg)
        setupContainer.setBackgroundColor(bg)
    }

    // --- Settings: drill-down categories -------------------------------------------------------

    private data class SettingsCategory(val title: String, val subtitle: String, val icon: Int, val build: (LinearLayout) -> Unit)

    private val settingsCategories: List<SettingsCategory> by lazy {
        listOf(
            SettingsCategory("Guide", "How every feature works — read this first", R.drawable.ic_info, ::buildGuideCategory),
            SettingsCategory("Display & Theme", "Theme, font, ligatures, extra keys row", R.drawable.ic_palette, ::buildDisplayCategory),
            SettingsCategory("Sessions & Background", "Survive backgrounding, battery, bell", R.drawable.ic_sync, ::buildSessionsCategory),
            SettingsCategory("Packages & Toolchains", "Install tools, search Alpine's package index", R.drawable.ic_download, ::buildPackagesCategory),
            SettingsCategory("Backup & Storage", "Backup/restore Alpine, shared storage, reinstall", R.drawable.ic_backup, ::buildBackupCategory),
            SettingsCategory("Network & SSH", "SSH, DNS, mirrors, local web preview", R.drawable.ic_wifi, ::buildNetworkCategory),
            SettingsCategory("Agent access & GitHub", "Let terminal agents control the app; sign in to GitHub", R.drawable.ic_link, ::buildAgentCategory),
            SettingsCategory("Plugins", "Custom panels: fields, buttons and scripts you or an agent add", R.drawable.ic_star, ::buildPluginsCategory),
            SettingsCategory("Devices", "SD cards, USB drives & devices, network adapters, Wi-Fi", R.drawable.ic_folder, ::buildDevicesCategory),
            SettingsCategory("About", "Version", R.drawable.ic_info, ::buildAboutCategory),
        )
    }

    private fun showSettingsCategoryList() {
        devicesPanel = null
        settingsPanel.removeAllViews()
        settingsPanel.addView(
            TextView(this).apply {
                text = "Settings"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFE7ECEF.toInt())
                setPadding(dp(4), 0, 0, dp(14))
            },
        )
        settingsCategories.forEach { category ->
            settingsPanel.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    background = ResourcesCompat.getDrawable(resources, android.R.drawable.list_selector_background, theme)
                    setPadding(dp(4), dp(9), dp(4), dp(9))
                    addView(iconTile(category.icon), LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(14) })
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            addView(
                                TextView(this@MainActivity).apply {
                                    text = category.title
                                    textSize = 15f
                                    typeface = Typeface.DEFAULT_BOLD
                                    setTextColor(0xFFE7ECEF.toInt())
                                },
                            )
                            addView(
                                TextView(this@MainActivity).apply {
                                    text = category.subtitle
                                    textSize = 12.5f
                                    setTextColor(0xFF8B93A1.toInt())
                                    setPadding(0, dp(2), 0, 0)
                                },
                            )
                        },
                    )
                    addView(
                        ImageView(this@MainActivity).apply {
                            setImageResource(R.drawable.ic_chevron_right)
                            imageTintList = ColorStateList.valueOf(0xFF8B93A1.toInt())
                            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                        },
                    )
                    setOnClickListener { showSettingsCategory(category) }
                },
            )
        }
    }

    private fun showSettingsCategory(category: SettingsCategory) {
        devicesPanel = null
        settingsPanel.removeAllViews()
        settingsPanel.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                background = ResourcesCompat.getDrawable(resources, android.R.drawable.list_selector_background, theme)
                setPadding(dp(4), dp(6), dp(4), dp(6))
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_arrow_left)
                        imageTintList = ColorStateList.valueOf(0xFF3ED0B8.toInt())
                        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) }
                    },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = category.title
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(0xFF3ED0B8.toInt())
                    },
                )
                setOnClickListener { showSettingsCategoryList() }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) },
        )
        // Its own container, so a screen that rebuilds itself (Devices, Plugins, Agent access call
        // removeAllViews() on refresh) clears only its content — not the back header above.
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsPanel.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        category.build(content)
    }

    private fun buildDisplayCategory(panel: LinearLayout) {
        panel.addView(guideLink("Display, themes & keys"))
        panel.addView(sectionLabel("Theme"))
        val radioGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        Themes.ALL.forEach { theme ->
            radioGroup.addView(
                RadioButton(this).apply {
                    text = theme.label
                    setTextColor(0xFFD4D4D4.toInt())
                    buttonTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.accent))
                    id = View.generateViewId()
                    isChecked = theme.id == settingsStore.themeId
                    setOnClickListener {
                        settingsStore.themeId = theme.id
                        TerminalColors.applyTheme(theme)
                        // TerminalColors.applyTheme() only swaps the *palette* (DEFAULT_FG/BG,
                        // ANSI16) — every cell already on screen or in scrollback had its color
                        // resolved to a concrete RGB at write time, so without this, only text
                        // written from this point on picked up the new theme, leaving old output
                        // stuck in whatever theme was active when it was printed (a visibly
                        // "mixed themes" screen). applyPalette() re-resolves every tab's cells
                        // (not just the active one — switching tabs later must show the new
                        // theme too) against the palette that was just swapped in.
                        tabs.forEach { it.emulator.applyPalette() }
                        applyChromeColors()
                        // Every chrome view above just redrew itself as a side effect of
                        // setBackgroundColor(), but TerminalView only repaints when something
                        // actually calls invalidate() on it — nothing here did, so the terminal
                        // kept showing the old theme's colors until some unrelated event (the
                        // keyboard closing, a resize) forced it to redraw anyway.
                        terminalView.invalidate()
                        drawerLayout.closeDrawer(GravityCompat.END)
                    }
                },
            )
        }
        panel.addView(radioGroup)

        panel.addView(sectionLabel("Font size"))
        val sizeLabel = TextView(this).apply {
            text = "${settingsStore.fontSizeSp.toInt()}sp"
            setTextColor(0xFFD4D4D4.toInt())
        }
        panel.addView(sizeLabel)
        panel.addView(
            SeekBar(this).apply {
                max = 16 // maps to 10sp..26sp
                progress = (settingsStore.fontSizeSp.toInt() - 10).coerceIn(0, 16)
                val accent = ContextCompat.getColor(this@MainActivity, R.color.accent)
                progressTintList = ColorStateList.valueOf(accent)
                thumbTintList = ColorStateList.valueOf(accent)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                        val sp = (10 + value).toFloat()
                        sizeLabel.text = "${sp.toInt()}sp"
                        if (fromUser) {
                            settingsStore.fontSizeSp = sp
                            terminalView.setTextSizePx(spToPx(sp))
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            },
        )

        panel.addView(sectionLabel("Font"))
        val fontOptions = listOf("monospace" to "System monospace", "jetbrains_mono" to "JetBrains Mono", "fira_code" to "Fira Code")
        val fontGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        fontOptions.forEach { (id, label) ->
            fontGroup.addView(
                RadioButton(this).apply {
                    text = label
                    setTextColor(0xFFD4D4D4.toInt())
                    buttonTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.accent))
                    this.id = View.generateViewId()
                    isChecked = id == settingsStore.fontFamily
                    setOnClickListener {
                        settingsStore.fontFamily = id
                        terminalView.setTypeface(typefaceFor(id))
                    }
                },
            )
        }
        panel.addView(fontGroup)
        panel.addView(
            MaterialSwitch(this).apply {
                text = "Ligatures (e.g. Fira Code's -> and ==)"
                setTextColor(0xFFD4D4D4.toInt())
                isChecked = settingsStore.ligaturesEnabled
                setOnCheckedChangeListener { _, checked ->
                    settingsStore.ligaturesEnabled = checked
                    terminalView.setLigaturesEnabled(checked)
                }
            },
        )

        panel.addView(sectionLabel("Extra keys row"))
        panel.addView(
            MaterialSwitch(this).apply {
                text = "Show ESC / TAB / CTRL / arrow row"
                setTextColor(0xFFD4D4D4.toInt())
                isChecked = settingsStore.showExtraKeys
                setOnCheckedChangeListener { _, checked ->
                    settingsStore.showExtraKeys = checked
                    extraKeysScroll.visibility = if (checked) View.VISIBLE else View.GONE
                }
            },
        )

        panel.addView(sectionLabel("Custom shortcuts"))
        panel.addView(
            TextView(this).apply {
                text = "Add your own one-tap command buttons to the key row."
                setTextColor(0xFF8B93A1.toInt())
                textSize = 11f
                setPadding(0, 0, 0, dp(6))
            },
        )
        val snippetListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(snippetListContainer)
        fun refreshSnippetList() {
            snippetListContainer.removeAllViews()
            settingsStore.customSnippets.forEachIndexed { index, (label, cmd) ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(
                    TextView(this).apply {
                        text = "$label  ·  $cmd"
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                        textSize = 13f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
                row.addView(
                    pillButton().apply {
                        text = "✕"
                        setOnClickListener {
                            val updated = settingsStore.customSnippets.toMutableList()
                            updated.removeAt(index)
                            settingsStore.customSnippets = updated
                            refreshSnippetList()
                            buildExtraKeysRow(extraKeysRow)
                        }
                    },
                )
                snippetListContainer.addView(row)
            }
        }
        refreshSnippetList()
        panel.addView(
            pillButton().apply {
                text = "Add shortcut"
                setOnClickListener {
                    val labelInput = android.widget.EditText(this@MainActivity).apply { hint = "Button label, e.g. \"status\"" }
                    val cmdInput = android.widget.EditText(this@MainActivity).apply { hint = "Command, e.g. \"git status\"" }
                    val form = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(20), dp(8), dp(20), 0)
                        addView(labelInput); addView(cmdInput)
                    }
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Add shortcut")
                        .setView(form)
                        .setPositiveButton("Save") { _, _ ->
                            val label = labelInput.text.toString().trim()
                            val cmd = cmdInput.text.toString().trim()
                            if (label.isEmpty() || cmd.isEmpty()) return@setPositiveButton
                            settingsStore.customSnippets = settingsStore.customSnippets + (label to "$cmd\n")
                            refreshSnippetList()
                            buildExtraKeysRow(extraKeysRow)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            },
        )
    }

    private fun buildSessionsCategory(panel: LinearLayout) {
        panel.addView(guideLink("Sessions & keep-alive"))
        panel.addView(sectionLabel("Background sessions"))
        panel.addView(
            MaterialSwitch(this).apply {
                text = "Keep sessions alive in background"
                setTextColor(0xFFD4D4D4.toInt())
                isChecked = settingsStore.keepAliveEnabled
                setOnCheckedChangeListener { _, checked ->
                    settingsStore.keepAliveEnabled = checked
                    updateKeepAliveService()
                }
            },
        )
        panel.addView(
            TextView(this).apply {
                text = "Recommended for coding agents and long builds."
                setTextColor(0xFF8B93A1.toInt())
                textSize = 12f
                setPadding(0, dp(4), 0, dp(8))
            },
        )
        panel.addView(
            MaterialSwitch(this).apply {
                text = "Also hold a wake lock (uses more battery)"
                setTextColor(0xFFD4D4D4.toInt())
                isChecked = settingsStore.wakeLockEnabled
                setOnCheckedChangeListener { _, checked ->
                    settingsStore.wakeLockEnabled = checked
                    updateKeepAliveService()
                }
            },
        )

        // Even a running foreground service can be throttled by an OEM's own aggressive battery
        // manager (Xiaomi/MIUI, Huawei, Samsung's "sleeping apps", ...) — this is the other half
        // of actually surviving in the background on those devices, not a duplicate of keep-alive.
        panel.addView(
            pillButton().apply {
                text = "Ignore battery optimizations"
                setOnClickListener { openBatteryExemption() }
            },
        )

        panel.addView(sectionLabel("Terminal bell"))
        panel.addView(
            MaterialSwitch(this).apply {
                text = "Play a sound (works even on silent)"
                setTextColor(0xFFD4D4D4.toInt())
                isChecked = settingsStore.bellSoundEnabled
                setOnCheckedChangeListener { _, checked -> settingsStore.bellSoundEnabled = checked }
            },
        )
    }

    private fun buildPackagesCategory(panel: LinearLayout) {
        panel.addView(guideLink("Packages & Quick Install"))
        panel.addView(sectionLabel("Toolchains & packages"))
        panel.addView(
            TextView(this).apply {
                text = "One tap runs the install command in the active session, same as typing it — nothing " +
                    "downloads or executes until you tap. ★ marks the most commonly used ones."
                setTextColor(0xFF8B93A1.toInt())
                textSize = 12f
                setPadding(0, 0, 0, dp(6))
            },
        )
        val popularPackages = setOf(
            "Node.js + npm", "Python", "git + curl", "opencode (v1)", "opencode (v2)",
            "alphacode (musl)", "Claude Code CLI", "Claude hunting rig", "nano", "vim", "tmux", "nmap",
        )

        panel.addView(
            pillButton().apply {
                text = "Update package index (apk update)"
                setOnClickListener {
                    drawerLayout.closeDrawer(GravityCompat.END)
                    runShortcutCommand("apk update\n")
                }
            },
        )

        panel.addView(
            pillButton().apply {
                text = "Repair tool dependencies (libstdc++, bash, curl)"
                setOnClickListener {
                    drawerLayout.closeDrawer(GravityCompat.END)
                    runShortcutCommand("apk add --no-cache libstdc++ libgcc bash curl\n")
                }
            },
        )

        panel.addView(sectionLabel("Search Alpine's package index"))
        panel.addView(
            TextView(this).apply {
                text = "Searches all of Alpine's live package repositories."
                setTextColor(0xFF8B93A1.toInt())
                textSize = 12f
                setPadding(0, 0, 0, dp(6))
            },
        )
        val searchInput = android.widget.EditText(this).apply {
            hint = "Package name, e.g. \"ffmpeg\""
            setTextColor(0xFFE7ECEF.toInt())
            setHintTextColor(0xFF8B93A1.toInt())
            background = null
            setPadding(0, 0, 0, 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setSingleLine()
        }
        val searchResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val searchStatus = TextView(this).apply {
            setTextColor(0xFF8B93A1.toInt())
            textSize = 12f
            setPadding(0, dp(6), 0, dp(4))
        }
        fun runPackageSearch(query: String) {
            searchResults.removeAllViews()
            searchStatus.text = "Searching…"
            val app = application as AlpineTermApp
            app.searchExecutor.execute {
                val names = runCatching { AlpineSession.searchPackages(this@MainActivity, query) }.getOrDefault(emptyList())
                val parsed = names.mapNotNull { line ->
                    PKG_VERSION_RE.find(line)?.groupValues?.get(1) ?: line.takeIf { it.isNotBlank() }
                }.distinct().filter { it.matches(PKG_NAME_RE) }
                mainHandler.post {
                    searchStatus.text = if (parsed.isEmpty()) "No matches (or Alpine isn't set up yet)" else "${parsed.size} result(s)"
                    parsed.forEach { name ->
                        val row = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(2), dp(10), dp(2), dp(10))
                        }
                        if (name in popularPackages) {
                            row.addView(
                                ImageView(this@MainActivity).apply {
                                    setImageResource(R.drawable.ic_star)
                                    imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.star))
                                    layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(6) }
                                },
                            )
                        }
                        row.addView(
                            TextView(this@MainActivity).apply {
                                text = name
                                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                                textSize = 13.5f
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            },
                        )
                        row.addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                isClickable = true
                                isFocusable = true
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.RECTANGLE
                                    cornerRadius = dp(12).toFloat()
                                    setColor(ContextCompat.getColor(this@MainActivity, R.color.accent_wash))
                                }
                                setPadding(dp(12), dp(7), dp(14), dp(7))
                                addView(
                                    ImageView(this@MainActivity).apply {
                                        setImageResource(R.drawable.ic_download)
                                        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.accent))
                                        layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(5) }
                                    },
                                )
                                addView(
                                    TextView(this@MainActivity).apply {
                                        text = "Install"
                                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                                        textSize = 12.5f
                                        typeface = Typeface.DEFAULT_BOLD
                                    },
                                )
                                setOnClickListener {
                                    drawerLayout.closeDrawer(GravityCompat.END)
                                    // Quoted: $name comes from the live network package index —
                                    // validated by PKG_NAME_RE above, quoted here as defense in depth.
                                    runShortcutCommand("apk add --no-cache '" + name.replace("'", "'\\''") + "'\n")
                                }
                            },
                        )
                        searchResults.addView(row)
                    }
                }
            }
        }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val query = searchInput.text.toString().trim()
                if (query.isNotEmpty()) runPackageSearch(query)
                true
            } else {
                false
            }
        }
        panel.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(ContextCompat.getColor(this@MainActivity, R.color.panel_2))
                }
                setPadding(dp(14), dp(10), dp(14), dp(10))
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_search)
                        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
                        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) }
                    },
                )
                addView(searchInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) },
        )
        panel.addView(searchStatus)
        panel.addView(searchResults)

        panel.addView(sectionLabel("Shortcuts"))
        fun packageGroup(title: String, entries: List<Pair<String, String>>) {
            panel.addView(
                TextView(this).apply {
                    text = title
                    setTextColor(0xFFD4D4D4.toInt())
                    textSize = 13f
                    setPadding(0, dp(8), 0, dp(4))
                },
            )
            entries.forEach { (label, cmd) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(14).toFloat()
                        setColor(ContextCompat.getColor(this@MainActivity, R.color.panel_2))
                    }
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    if (label in popularPackages) {
                        addView(
                            ImageView(this@MainActivity).apply {
                                setImageResource(R.drawable.ic_star)
                                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.star))
                                layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(8) }
                            },
                        )
                    }
                    addView(
                        TextView(this@MainActivity).apply {
                            text = label
                            setTextColor(0xFFE7ECEF.toInt())
                            textSize = 13.5f
                            typeface = Typeface.DEFAULT_BOLD
                        },
                    )
                    setOnClickListener {
                        drawerLayout.closeDrawer(GravityCompat.END)
                        runShortcutCommand(cmd)
                    }
                }
                panel.addView(
                    row,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) },
                )
            }
        }
        packageGroup(
            "Runtimes",
            listOf(
                "Node.js + npm" to "apk add --no-cache nodejs npm\n",
                "Python" to "apk add --no-cache python3 py3-pip\n",
                "git + curl" to "apk add --no-cache git curl\n",
            ),
        )
        packageGroup(
            "AI coding agents",
            listOf(
                // Alpine's minirootfs ships neither curl nor bash by default (only busybox's own
                // wget/ash) — both installers pipe a script through curl, so curl has to be
                // installed first or the whole command is just "curl: not found". opencode's own
                // binary is a Bun build, dynamically linked against libstdc++/libgcc — libraries
                // Alpine's musl-based minirootfs doesn't ship at all (nothing else here needs
                // them), so without libstdc++ installed too, opencode itself runs but immediately
                // fails with "Error relocating ...: undefined symbol" / "cannot open shared
                // object file" the first time it's actually launched, long after the installer
                // itself already reported success.
                // alphacode ships a fully static musl binary built by CI (see its
                // build-alpine.yml): no libstdc++/gcompat needed at all.
                // The installer comes from the GitHub contents API (Accept: raw),
                // not raw.githubusercontent: the raw CDN serves stale blobs for
                // up to hours, which broke this button with phantom errors.
                // busybox wget fetches it — no apk add in the chain: apk index
                // fetches fail on some networks and the && then skipped the
                // install entirely (v1.7.31 bug). wget is always present here.
                // Claude hunting rig: the installer lives in the PUBLIC sanitized repo
                // (rig-portable — no keys), piped straight into sh, same one-liner shape
                // as alphacode. Every push to its main branch installs automatically.
                // Go is installed first: it rebuilds the aarch64 shim seamlessly on any
                // arch without a second code path. Keys come from the user's own
                // /etc/conf.d/anthropic-shim (kept/asked-for by the installer, never here).
                "Claude hunting rig" to "apk add --no-cache go >/dev/null 2>&1; wget -qO- --header=\"Accept: application/vnd.github.raw\" https://api.github.com/repos/anamorsyai/rig-portable/contents/scripts/install-rig.sh | sh\n",
                "alphacode (musl)" to "wget -qO- --header=\"Accept: application/vnd.github.raw\" https://api.github.com/repos/anamorsyai/alphacode/contents/scripts/install-musl.sh | sh\n",
                "opencode (v1)" to "apk add --no-cache curl libstdc++ && curl -fsSL https://opencode.ai/install | sh; export PATH=\"\$PATH:/root/.opencode/bin\"\n",
                "opencode (v2)" to "apk add --no-cache bash curl libstdc++ gcompat && curl -fsSL https://opencode.ai/v2/install | bash; export PATH=\"\$PATH:/root/.opencode/bin\"\n",
                "Claude Code CLI" to "apk add --no-cache nodejs npm && npm install -g @anthropic-ai/claude-code\n",
            ),
        )
        panel.addView(
            TextView(this).apply {
                text = "opencode installs to /root/.opencode/bin (commands `opencode` and `opencode2`), which is added to PATH automatically. alphacode installs a fully static musl binary to /root/.local/bin with a symlink in /usr/local/bin. Claude hunting rig restores the full ~/.claude setup (skills/commands/agents), builds its own arm64 shim proxy (go), and starts it on 127.0.0.1:9086 for the claude CLI."
                setTextColor(0xFF8B93A1.toInt())
                textSize = 11f
                setPadding(0, 0, 0, dp(6))
            },
        )
        packageGroup(
            "Editors & shell tools",
            listOf(
                "nano" to "apk add --no-cache nano\n",
                "vim" to "apk add --no-cache vim\n",
                "micro" to "apk add --no-cache micro\n",
                "tmux" to "apk add --no-cache tmux\n",
                "htop" to "apk add --no-cache htop\n",
                "jq" to "apk add --no-cache jq\n",
                "ripgrep" to "apk add --no-cache ripgrep\n",
                "fzf" to "apk add --no-cache fzf\n",
            ),
        )
        packageGroup(
            "Security & networking",
            listOf(
                "nmap" to "apk add --no-cache nmap nmap-scripts\n",
                "tcpdump" to "apk add --no-cache tcpdump\n",
                "netcat" to "apk add --no-cache netcat-openbsd\n",
                "tshark" to "apk add --no-cache tshark\n",
                "john the ripper" to "apk add --no-cache john\n",
                "hydra" to "apk add --no-cache hydra\n",
                "aircrack-ng" to "apk add --no-cache aircrack-ng\n",
                "sqlmap" to "apk add --no-cache sqlmap\n",
            ),
        )
    }

    private val backupCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val restoreCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    private var backupCancelBtn: Button? = null
    private var restoreCancelBtn: Button? = null

    private fun buildBackupCategory(panel: LinearLayout) {
        panel.addView(sectionLabel("Backup"))
        panel.addView(guideLink("Backup & restore"))
        panel.addView(
            TextView(this).apply {
                text = "App-folder backups are deleted if the app is uninstalled — use \"chosen file\" to keep one in Downloads, on an SD card, or in the cloud."
                setTextColor(0xFF8B93A1.toInt())
                textSize = 12f
                setPadding(0, 0, 0, dp(6))
            },
        )
        panel.addView(
            pillButton().apply {
                text = "Backup Alpine (app folder)"
                setOnClickListener { backupAlpine() }
            },
        )
        panel.addView(
            pillButton().apply {
                text = "Backup Alpine to chosen file…"
                setOnClickListener { backupAlpineToChosenFile() }
            },
        )
        backupCancelBtn = pillButton().apply {
            text = "Cancel backup"
            visibility = View.GONE
            setOnClickListener { backupCancelled.set(true) }
        }
        panel.addView(backupCancelBtn)
        panel.addView(
            MaterialSwitch(this).apply {
                text = "Automatic weekly backup (keeps the newest 3)"
                setTextColor(0xFFD4D4D4.toInt())
                isChecked = settingsStore.autoBackupEnabled
                setOnCheckedChangeListener { _, checked -> settingsStore.autoBackupEnabled = checked }
            },
        )
        panel.addView(
            pillButton().apply {
                text = "Restore Alpine from backup"
                setOnClickListener { restoreAlpinePicker() }
            },
        )
        panel.addView(
            pillButton().apply {
                text = "Restore Alpine from chosen file…"
                setOnClickListener { restoreAlpineFromChosenFile() }
            },
        )
        restoreCancelBtn = pillButton().apply {
            text = "Cancel restore"
            visibility = View.GONE
            setOnClickListener { restoreCancelled.set(true) }
        }
        panel.addView(restoreCancelBtn)
        panel.addView(
            pillButton().apply {
                text = "Export settings"
                setOnClickListener { exportSettings() }
            },
        )
        panel.addView(
            pillButton().apply {
                text = "Import settings"
                setOnClickListener { importSettingsPicker() }
            },
        )

        // The setup screen's own "Grant shared-storage access" banner only exists briefly,
        // during first-run setup — if the moment is missed there (or storage is revoked later),
        // this is the only other way back to it, always reachable regardless of setup state.
        panel.addView(sectionLabel("Shared storage"))
        storageStatusText = TextView(this).apply {
            setTextColor(0xFFD4D4D4.toInt())
            textSize = 13f
        }
        panel.addView(storageStatusText)
        refreshStorageRow()
        panel.addView(
            pillButton().apply {
                text = "Grant / manage access"
                setOnClickListener { startActivity(StorageAccess.requestIntent(this@MainActivity)) }
            },
        )
        // A running session's proot already made its bind-mount decision at launch — granting
        // storage afterward can't change an already-running shell, only a new one. This is the
        // "hot reload" for that: closes the current tab and opens a fresh one in its place,
        // rather than making the user hunt for a brand new tab number just to pick it up.
        panel.addView(
            pillButton().apply {
                text = "Restart current session"
                setOnClickListener {
                    drawerLayout.closeDrawer(GravityCompat.END)
                    // Bridges the gap between destroy() below and addTab() actually being called
                    // right after it — addTab() increments pendingSessionStarts itself once it
                    // runs, so this hands off to that rather than double-counting.
                    pendingSessionStarts++
                    tabs.getOrNull(activeTabIndex)?.session?.destroy()
                    addTab()
                    pendingSessionStarts--
                }
            },
        )
        // The only way an already-set-up install picks up a change to which Alpine release the
        // app downloads (e.g. this build pinning to v3.22 instead of "latest-stable" to avoid a
        // newer apk-tools 3.x incompatibility) is a fresh download — short of a full app
        // uninstall/reinstall, this is that: wipes the current rootfs and closes every open tab,
        // so the very next one triggers the normal first-run setup flow again from scratch.
        panel.addView(
            pillButton().apply {
                text = "Reinstall Alpine (fresh download)"
                setOnClickListener {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Reinstall Alpine?")
                        .setMessage("Deletes the current Alpine installation and closes all sessions. The next session will download a fresh copy. Anything you saved inside Alpine's own filesystem (not /sdcard) will be lost.")
                        .setPositiveButton("Reinstall") { _, _ ->
                            drawerLayout.closeDrawer(GravityCompat.END)
                            // Held across the whole async wipe (unlike "Restart current session"'s
                            // handoff, addTab() here doesn't run until well after this returns) and
                            // handed off to addTab()'s own increment right before calling it.
                            pendingSessionStarts++
                            val closingSessions = tabs.toList().onEach { it.session.destroy() }
                            val app = application as AlpineTermApp
                            app.backgroundExecutor.execute {
                                // See confirmRestore()'s equivalent wait: destroy() only requests
                                // the process die, and wiping the rootfs right away risks racing
                                // one that's still exiting and still touching files underneath it.
                                closingSessions.forEach { it.session.awaitExit(2000) }
                                runCatching { AlpineRootfs.wipeForReinstall(this@MainActivity) }
                                mainHandler.post {
                                    pendingSessionStarts--
                                    addTab()
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            },
        )
    }

    private fun buildNetworkCategory(panel: LinearLayout) {
        panel.addView(sectionLabel("Network"))
        panel.addView(guideLink("Network, SSH & opencode web"))
        panel.addView(
            TextView(this).apply {
                val ips = NetworkInfo.localIpv4Addresses()
                text = if (ips.isEmpty()) "No local network address found." else "Reachable on your network at: ${ips.joinToString(", ")}"
                setTextColor(0xFFD4D4D4.toInt())
                textSize = 13f
            },
        )
        panel.addView(
            pillButton().apply {
                text = "Start opencode web server (0.0.0.0:4096)"
                setOnClickListener { confirmOpencodeWeb() }
            },
        )
        panel.addView(
            pillButton().apply {
                text = "Copy SSH setup command"
                setOnClickListener {
                    val cmd = "apk add openssh && ssh-keygen -A && passwd root && /usr/sbin/sshd -D"
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ssh setup", cmd))
                    android.widget.Toast.makeText(this@MainActivity, "Paste it into the terminal to enable SSH", android.widget.Toast.LENGTH_LONG).show()
                }
            },
        )
        // resolv.conf inside the shared rootfs is only (re)written when a session starts, so a
        // network switch (wifi <-> mobile data) or a carrier DNS server that's stopped
        // responding — "DNS: transient error"/"I/O error" from apk mid-session — stays broken
        // until the user thinks to open a new tab. This re-writes it immediately, live, without
        // needing to restart anything.
        panel.addView(
            pillButton().apply {
                text = "Refresh network / DNS"
                setOnClickListener {
                    val app = application as AlpineTermApp
                    app.backgroundExecutor.execute {
                        runCatching { AlpineSession.refreshNetwork(this@MainActivity) }
                        mainHandler.post {
                            android.widget.Toast.makeText(this@MainActivity, "Network settings refreshed", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
        )
        // A fresh install picks up the fallback mirror list automatically (AlpineRootfs runs
        // this once right after extraction) — this button is for an installation that predates
        // the feature, so a repeatedly blocked/failing dl-cdn.alpinelinux.org (a "Permission
        // denied"/"DNS: transient error" from apk that a plain wget to the same host doesn't
        // hit) has somewhere else to actually resolve packages from without re-downloading Alpine.
        panel.addView(
            pillButton().apply {
                text = "Add fallback apk mirrors"
                setOnClickListener {
                    val app = application as AlpineTermApp
                    app.backgroundExecutor.execute {
                        val added = runCatching { AlpineRootfs.addFallbackMirrors(this@MainActivity) }.getOrDefault(false)
                        mainHandler.post {
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                if (added) "Fallback mirrors added — try apk update again" else "Already up to date (or Alpine isn't set up yet)",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            },
        )
        panel.addView(sectionLabel("SSH quick-connect"))
        val sshListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(sshListContainer)
        fun refreshSshList() {
            sshListContainer.removeAllViews()
            SshProfiles.list(this).forEach { profile ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(
                    TextView(this).apply {
                        val target = if (profile.user.isNotBlank()) "${profile.user}@${profile.host}" else profile.host
                        text = "${profile.name} ($target)"
                        setTextColor(0xFFD4D4D4.toInt())
                        textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
                row.addView(
                    pillButton().apply {
                        text = "Connect"
                        setOnClickListener {
                            drawerLayout.closeDrawer(GravityCompat.END)
                            tabs.getOrNull(activeTabIndex)?.lastSshCommand = profile.connectCommand()
                            runShortcutCommand(profile.connectCommand())
                        }
                    },
                )
                row.addView(
                    pillButton().apply {
                        text = "✕"
                        setOnClickListener { SshProfiles.remove(this@MainActivity, profile); refreshSshList() }
                    },
                )
                sshListContainer.addView(row)
            }
        }
        refreshSshList()
        panel.addView(
            pillButton().apply {
                text = "Add SSH profile"
                setOnClickListener {
                    val nameInput = android.widget.EditText(this@MainActivity).apply { hint = "Name (e.g. \"home server\")" }
                    val hostInput = android.widget.EditText(this@MainActivity).apply { hint = "Host or IP" }
                    val portInput = android.widget.EditText(this@MainActivity).apply { hint = "Port (default 22)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
                    val userInput = android.widget.EditText(this@MainActivity).apply { hint = "Username" }
                    val form = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(20), dp(8), dp(20), 0)
                        addView(nameInput); addView(hostInput); addView(portInput); addView(userInput)
                    }
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Add SSH profile")
                        .setView(form)
                        .setPositiveButton("Save") { _, _ ->
                            val host = hostInput.text.toString().trim()
                            if (host.isEmpty()) return@setPositiveButton
                            val name = nameInput.text.toString().trim().ifEmpty { host }
                            SshProfiles.add(this@MainActivity, SshProfile(name, host, portInput.text.toString().trim(), userInput.text.toString().trim()))
                            refreshSshList()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            },
        )

        panel.addView(sectionLabel("Local web preview"))
        panel.addView(
            TextView(this).apply {
                text = "Dev servers in a tab are reachable at 127.0.0.1 — no forwarding needed."
                setTextColor(0xFF8B93A1.toInt())
                textSize = 12f
                setPadding(0, 0, 0, dp(6))
            },
        )
        val portInput = android.widget.EditText(this).apply { hint = "Port"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        panel.addView(portInput)
        panel.addView(
            pillButton().apply {
                text = "Open in browser"
                setOnClickListener {
                    val port = portInput.text.toString().toIntOrNull()
                    if (port == null) {
                        android.widget.Toast.makeText(this@MainActivity, "Enter a port number", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        openLocalPort(port)
                    }
                }
            },
        )
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(3000, 5173, 8080, 5000).forEach { port ->
            presetRow.addView(
                pillButton().apply {
                    text = "$port"
                    // Plain Button doesn't force single-line by default — four of these packed
                    // into a 280dp settings drawer left the last one's measured width too tight
                    // for its own text, wrapping "5000" onto two lines instead of just staying
                    // one line and letting the row scroll horizontally past the drawer's edge.
                    setSingleLine(true)
                    setOnClickListener { openLocalPort(port) }
                },
            )
        }
        panel.addView(
            android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(presetRow)
            },
        )
    }

    // --- Guide -------------------------------------------------------------------------------

    private var guideOpenSection: String? = null

    /** Jumps to Settings → Guide with [title]'s section expanded. */
    private fun openGuide(title: String) {
        guideOpenSection = title
        settingsCategories.firstOrNull { it.title == "Guide" }?.let { showSettingsCategory(it) }
    }

    private fun guideLink(title: String, label: String = "Read the guide: $title"): Button =
        pillButton().apply { text = label; setOnClickListener { openGuide(title) } }

    private fun buildGuideCategory(panel: LinearLayout) {
        panel.addView(devNote("Tap a section to expand it. Other settings screens stay short and link here for the full explanation."))
        val open = guideOpenSection
        GuideContent.sections.forEach { sec ->
            val body = TextView(this).apply {
                text = sec.body; textSize = 12.5f; setTextColor(0xFFD4D4D4.toInt()); setTextIsSelectable(true)
                setPadding(0, dp(8), 0, 0)
                setLineSpacing(0f, 1.15f)
                visibility = if (sec.title == open) View.VISIBLE else View.GONE
            }
            val arrow = TextView(this).apply { text = if (sec.title == open) "▾" else "▸"; textSize = 16f; setTextColor(0xFF3ED0B8.toInt()); setPadding(0, 0, dp(10), 0) }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(arrow)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply { text = sec.title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFE7ECEF.toInt()) })
                    addView(TextView(this@MainActivity).apply { text = sec.summary; textSize = 12f; setTextColor(0xFF8B93A1.toInt()) })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            panel.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBg()
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
                addView(header); addView(body)
                setOnClickListener {
                    val show = body.visibility != View.VISIBLE
                    body.visibility = if (show) View.VISIBLE else View.GONE
                    arrow.text = if (show) "▾" else "▸"
                }
            })
        }
        guideOpenSection = null
    }


    // --- Plugins: dynamic panels built from plugin.json, logic in guest scripts ---------------

    private var pluginRun: PtySession? = null
    private var pluginWatchdog: Thread? = null
    private val ansiRe = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007")
    private val PKG_VERSION_RE = Regex("^(.*)-[^-]+-r[0-9]+$")
    /** apk package names: lowercase alnum plus . + _ - (validated because search results come
     *  from the live network index and are typed into a live shell — never trust them raw). */
    private val PKG_NAME_RE = Regex("^[A-Za-z0-9][A-Za-z0-9.+_\\-]*$")

    /** Stops a foreground one-shot run including its watchdog — destroying the session alone
     *  left the 120s watchdog thread sleeping to the end, one zombie per Stop tap. */
    private fun stopPluginRun() {
        pluginRun?.let { runCatching { it.destroy() } }
        pluginRun = null
        pluginWatchdog?.interrupt()
        pluginWatchdog = null
    }

    private fun buildPluginsCategory(panel: LinearLayout) { fillPlugins(panel) }

    private fun fillPlugins(panel: LinearLayout) {
        panel.removeAllViews()
        if (!AlpineRootfs.isReady(this)) { panel.addView(devNote("Open a terminal tab once first so Alpine is set up.")); return }
        panel.addView(sectionLabel("Plugins"))
        panel.addView(devNote("Custom panels built from a plugin.json plus scripts, kept inside Alpine so backups include them. You review scripts before anything runs."))
        panel.addView(guideLink("Plugins"))
        panel.addView(guideLink("Scheduled & background scripts", "Guide: scheduled & background scripts"))
        panel.addView(pillButton().apply { text = "Rescan"; setOnClickListener { fillPlugins(panel) } })
        panel.addView(pillButton().apply {
            text = "Create sample plugin"
            setOnClickListener {
                android.widget.Toast.makeText(this@MainActivity, if (Plugins.createSample(this@MainActivity)) "Sample created" else "Couldn't create sample", android.widget.Toast.LENGTH_SHORT).show()
                fillPlugins(panel)
            }
        })
        val plugins = Plugins.list(this)
        if (plugins.isEmpty()) panel.addView(devNote("No plugins yet. Create the sample to see the format, or ask an agent to build one."))
        plugins.forEach { p ->
            panel.addView(devCard(p.title, chips = if (Plugins.isApproved(this, p)) listOf("approved" to 0xFF3ED0B8.toInt()) else listOf("needs review" to 0xFFE5A94B.toInt()),
                lines = listOfNotNull(p.description.takeIf { it.isNotBlank() }?.let { "About" to it }, "Folder" to "~/.alpdroid/plugins/${p.id}", "Buttons" to (p.buttons.joinToString { it.label }.ifEmpty { "none" }))))
            panel.addView(pillButton().apply { text = "Open ${p.title}"; setOnClickListener { showPlugin(panel, p) } })
            panel.addView(pillButton().apply { text = "Delete"; setOnClickListener {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Delete plugin \"${p.title}\"?").setMessage("Removes its folder, scripts and saved values.")
                    .setPositiveButton("Delete") { _, _ -> Plugins.delete(p); fillPlugins(panel) }
                    .setNegativeButton("Cancel", null).show()
            } })
        }
    }

    private fun showPlugin(panel: LinearLayout, plugin: Plugins.Plugin) {
        panel.removeAllViews()
        stopPluginRun()
        panel.addView(pillButton().apply { text = "← All plugins"; setOnClickListener { fillPlugins(panel) } })
        panel.addView(guideLink("Plugins"))
        panel.addView(sectionLabel(plugin.title))
        if (plugin.description.isNotBlank()) panel.addView(devNote(plugin.description))

        val values = Plugins.loadState(plugin).toMutableMap()
        fun save(id: String, v: String) { values[id] = v; Plugins.saveValue(plugin, id, v) }
        plugin.fields.forEach { f ->
            panel.addView(TextView(this).apply { text = f.label; textSize = 12.5f; setTextColor(0xFF8B93A1.toInt()); setPadding(0, dp(10), 0, dp(2)) })
            when (f.type) {
                "toggle" -> panel.addView(MaterialSwitch(this).apply {
                    text = f.label; setTextColor(0xFFD4D4D4.toInt())
                    isChecked = values[f.id] in setOf("1", "true")
                    setOnCheckedChangeListener { _, c -> save(f.id, if (c) "1" else "0") }
                })
                "select" -> {
                    val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
                    f.options.forEach { o -> group.addView(RadioButton(this).apply {
                        text = o; setTextColor(0xFFD4D4D4.toInt()); id = View.generateViewId()
                        buttonTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.accent))
                        isChecked = values[f.id] == o
                        setOnClickListener { save(f.id, o) }
                    }) }
                    panel.addView(group)
                }
                else -> panel.addView(android.widget.EditText(this).apply {
                    setText(values[f.id] ?: "")
                    setTextColor(0xFFD4D4D4.toInt()); setHintTextColor(0xFF5A6270.toInt()); hint = f.label
                    inputType = if (f.type == "number") android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED else android.text.InputType.TYPE_CLASS_TEXT
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun afterTextChanged(e: android.text.Editable?) { save(f.id, e?.toString() ?: "") }
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    })
                })
            }
        }

        val output = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(0xFFD4D4D4.toInt()); setTextIsSelectable(true)
            background = cardBg(); setPadding(dp(12), dp(10), dp(12), dp(10))
            text = "Output appears here."
        }
        val stop = pillButton().apply { text = "Stop"; visibility = View.GONE; setOnClickListener { stopPluginRun(); (it as View).visibility = View.GONE } }

        fun runButton(b: Plugins.Button) {
            output.text = "▶ ${b.label}\n"
            stop.visibility = View.VISIBLE
            val app = application as AlpineTermApp
            app.backgroundExecutor.execute {
                val session = runCatching { Plugins.run(this, plugin, b, values) }.getOrNull()
                if (session == null) { mainHandler.post { output.append("Couldn't start (is Alpine ready?)\n"); stop.visibility = View.GONE }; return@execute }
                pluginRun = session
                val watchdog = Thread({ try { Thread.sleep(120_000); runCatching { session.destroy() } } catch (_: InterruptedException) {} }, "plugin-watchdog").apply { isDaemon = true; start() }
                pluginWatchdog = watchdog
                var total = 0L
                runCatching {
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = session.stdout.read(buf); if (n <= 0) break
                        val chunk = ansiRe.replace(String(buf, 0, n, Charsets.UTF_8).replace("\r", ""), "")
                        total += chunk.length
                        if (total <= 200_000) mainHandler.post { output.append(chunk) }
                    }
                }
                watchdog.interrupt()
                if (pluginWatchdog === watchdog) pluginWatchdog = null
                runCatching { session.destroy() }
                if (pluginRun === session) pluginRun = null
                mainHandler.post { output.append("\n■ finished\n"); stop.visibility = View.GONE }
            }
        }

        panel.addView(sectionLabel("Actions"))
        val plain = plugin.buttons.filter { !it.background }
        if (plain.isEmpty()) panel.addView(devNote("This plugin has no one-shot buttons."))
        plain.forEach { b ->
            panel.addView(pillButton().apply { text = b.label; setOnClickListener {
                if (Plugins.isApproved(this@MainActivity, plugin)) runButton(b)
                else com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Allow \"${plugin.title}\" to run?")
                    .setMessage("These scripts will run inside Alpine with access to everything a terminal there can reach (files, network, and the app's control API if agent access is on). Review them:\n\n" + Plugins.reviewText(plugin))
                    .setPositiveButton("Allow & run") { _, _ -> Plugins.approve(this@MainActivity, plugin); runButton(b) }
                    .setNegativeButton("Cancel", null).show()
            } })
        }
        val jobs = (application as AlpineTermApp).pluginJobs
        val allJobs = jobs.jobsOf(plugin)
        if (allJobs.isNotEmpty()) {
            panel.addView(sectionLabel("Automation"))
            panel.addView(devNote("Jobs run in the background while the keep-alive notification shows."))
            allJobs.forEach { j ->
                val status = TextView(this).apply { textSize = 12f; setTextColor(0xFF8B93A1.toInt()); text = jobs.status(j) }
                val title = if (j.everyMinutes != null) "${j.label} — every ${j.everyMinutes} min" else "${j.label} — keep running"
                panel.addView(MaterialSwitch(this).apply {
                    text = title; setTextColor(0xFFD4D4D4.toInt())
                    isChecked = Plugins.isEnabled(plugin, j.id)
                    setOnCheckedChangeListener { btn, on ->
                        fun apply(on: Boolean) {
                            Plugins.setEnabled(plugin, j.id, on)
                            if (!on) jobs.stop(j)
                            jobs.refreshCount()
                            updateKeepAliveService()
                            status.text = jobs.status(j)
                        }
                        if (on && !Plugins.isApproved(this@MainActivity, plugin)) {
                            btn.isChecked = false
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                                .setTitle("Allow \"${plugin.title}\" to run in the background?")
                                .setMessage("These scripts will run unattended inside Alpine, repeatedly, with access to everything a terminal there can reach. Review them:\n\n" + Plugins.reviewText(plugin))
                                .setPositiveButton("Allow & enable") { _, _ -> Plugins.approve(this@MainActivity, plugin); apply(true); btn.isChecked = true }
                                .setNegativeButton("Cancel", null).show()
                        } else apply(on)
                    }
                })
                panel.addView(status)
                panel.addView(pillButton().apply { text = "Run now"; setOnClickListener {
                    if (!Plugins.isApproved(this@MainActivity, plugin)) android.widget.Toast.makeText(this@MainActivity, "Approve the scripts first (turn the switch on)", android.widget.Toast.LENGTH_SHORT).show()
                    else { jobs.launch(j); updateKeepAliveService(); status.text = "starting…" }
                } })
                panel.addView(pillButton().apply { text = "View log"; setOnClickListener {
                    // tailLog reads + decodes the whole (up to 200KB) log file — off the UI thread.
                    output.text = "── ${j.label} log ──\n(loading…)"
                    (application as AlpineTermApp).backgroundExecutor.execute {
                        val text = "── ${j.label} log ──\n" + jobs.tailLog(j)
                        val st = jobs.status(j)
                        mainHandler.post {
                            output.text = text
                            status.text = st
                        }
                    }
                } })
            }
        }
        panel.addView(stop)
        panel.addView(output)
    }


    // --- Agent access (local control API), GitHub sign-in, opencode web server ---------------

    private val agentBridge get() = (application as AlpineTermApp).agentBridge

    private val agentHost = object : AgentBridge.Host {
        override fun <T> onMain(block: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()
            val latch = java.util.concurrent.CountDownLatch(1)
            val out = java.util.concurrent.atomic.AtomicReference<Result<T>>()
            runOnUiThread { out.set(runCatching(block)); latch.countDown() }
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw IllegalStateException("app is busy")
            return out.get().getOrThrow()
        }

        private fun confirm(message: String): Boolean {
            val latch = java.util.concurrent.CountDownLatch(1)
            val answer = java.util.concurrent.atomic.AtomicBoolean(false)
            runOnUiThread {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("An agent wants to change a setting")
                    .setMessage(message)
                    .setPositiveButton("Allow") { _, _ -> answer.set(true); latch.countDown() }
                    .setNegativeButton("Deny") { _, _ -> latch.countDown() }
                    .setOnCancelListener { latch.countDown() }
                    .show()
            }
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
            return answer.get()
        }

        override fun settingsJson(): JSONObject = onMain {
            JSONObject()
                .put("theme", settingsStore.themeId).put("themes", JSONArray(Themes.ALL.map { it.id }))
                .put("font_size", settingsStore.fontSizeSp).put("font_family", settingsStore.fontFamily)
                .put("show_extra_keys", settingsStore.showExtraKeys).put("ligatures", settingsStore.ligaturesEnabled)
                .put("bell_sound", settingsStore.bellSoundEnabled).put("keep_alive", settingsStore.keepAliveEnabled)
                .put("wake_lock", settingsStore.wakeLockEnabled).put("auto_backup", settingsStore.autoBackupEnabled)
                .put("shortcuts", JSONArray(settingsStore.customSnippets.map { JSONObject().put("label", it.first).put("cmd", it.second) }))
        }

        override fun setSetting(key: String, value: String): String {
            val on = value.lowercase() in setOf("1", "true", "on", "yes")
            val sensitive = mapOf(
                "keep_alive" to "Keep sessions alive in the background: ${if (on) "ON" else "OFF"}",
                "wake_lock" to "Hold a wake lock (uses battery): ${if (on) "ON" else "OFF"}",
                "auto_backup" to "Automatic weekly backup: ${if (on) "ON" else "OFF"}",
            )
            sensitive[key]?.let { if (!confirm(it)) return "the user denied this change" }
            return onMain {
                when (key) {
                    "theme" -> Themes.ALL.firstOrNull { it.id == value }?.let { settingsStore.themeId = it.id; applyAllSettings(); "ok" }
                        ?: "unknown theme; options: ${Themes.ALL.joinToString { it.id }}"
                    "font_size" -> value.toFloatOrNull()?.takeIf { it in 8f..40f }?.let { settingsStore.fontSizeSp = it; applyAllSettings(); "ok" } ?: "font_size must be 8-40"
                    "font_family" -> if (value in setOf("monospace", "jetbrains_mono", "fira_code")) { settingsStore.fontFamily = value; applyAllSettings(); "ok" } else "options: monospace, jetbrains_mono, fira_code"
                    "show_extra_keys" -> { settingsStore.showExtraKeys = on; applyAllSettings(); "ok" }
                    "ligatures" -> { settingsStore.ligaturesEnabled = on; applyAllSettings(); "ok" }
                    "bell_sound" -> { settingsStore.bellSoundEnabled = on; "ok" }
                    "keep_alive" -> { settingsStore.keepAliveEnabled = on; updateKeepAliveService(); "ok" }
                    "wake_lock" -> { settingsStore.wakeLockEnabled = on; updateKeepAliveService(); "ok" }
                    "auto_backup" -> { settingsStore.autoBackupEnabled = on; "ok" }
                    else -> "unknown setting '$key'"
                }
            }
        }

        override fun addShortcut(label: String, cmd: String): String {
            if (label.isBlank() || cmd.isBlank() || label.length > 40 || cmd.length > 500) return "need a label (<=40 chars) and cmd (<=500 chars)"
            return onMain { settingsStore.customSnippets = settingsStore.customSnippets + (label to cmd); "ok" }
        }

        override fun newTab(label: String?): String { runOnUiThread { addTab(label) }; return "starting" }
        override fun selectTab(id: Int): Boolean = onMain { tabs.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { switchToTab(it); true } ?: false }
        override fun closeTab(id: Int): Boolean = onMain { tabs.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { closeTab(it); true } ?: false }

        override fun clipboardGet(): String = onMain {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
        }
        override fun clipboardSet(text: String) = onMain {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("AlpDroid", text))
        }
        override fun notify(title: String, text: String) = OperationNotifications.alert(this@MainActivity, OperationNotifications.newId(), title.take(80), text.take(300))
        override fun toast(text: String) = runOnUiThread { android.widget.Toast.makeText(this@MainActivity, text.take(300), android.widget.Toast.LENGTH_LONG).show() }
        override fun openUrl(url: String): Boolean {
            if (!url.startsWith("https://") && !url.startsWith("http://")) return false
            runOnUiThread { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            return true
        }

        override fun devicesJson(): JSONObject {
            val drives = runCatching { DeviceInfo.removableDrives(this@MainActivity) }.getOrDefault(emptyList())
            val guestPaths = DeviceInfo.guestPaths(drives)
            return JSONObject()
                .put("drives", JSONArray(drives.map { JSONObject().put("label", it.label).put("mounted", it.mounted).put("guest_path", guestPaths[it] ?: "/mnt/${it.mountName}").put("total", it.totalBytes).put("free", it.freeBytes) }))
                .put("usb", JSONArray(DeviceInfo.usbDevices(this@MainActivity).map { JSONObject().put("id", it.id).put("title", it.title).put("kind", it.kind) }))
                .put("interfaces", JSONArray(DeviceInfo.interfaces().map { JSONObject().put("name", it.name).put("kind", it.kind).put("up", it.up).put("addresses", JSONArray(it.addresses)) }))
                .put("network", DeviceInfo.activeNetwork(this@MainActivity)?.let { JSONObject().put("transport", it.transport).put("internet", it.validated).put("dns", JSONArray(it.dns)).put("gateway", it.gateway ?: JSONObject.NULL) } ?: JSONObject.NULL)
        }

        override fun githubStatus(): JSONObject = JSONObject()
            .put("signed_in", GitHubAuth.token(this@MainActivity) != null)
            .put("login", GitHubAuth.login(this@MainActivity) ?: JSONObject.NULL)
            .put("agents_may_use_token", settingsStore.agentGithubToken)

        override fun githubToken(): String? = if (settingsStore.agentGithubToken) {
            // Runs on the bridge pool thread (not UI): Keystore decrypt + possible silent
            // refresh are allowed to block here, so the token survives app updates and
            // process kills — previously this raced the UI host lifecycle.
            GitHubAuth.validToken(this@MainActivity)
        } else null
    }

    /** Re-applies every user-visible setting after one was changed programmatically. */
    private fun applyAllSettings() {
        TerminalColors.applyTheme(Themes.byId(settingsStore.themeId))
        tabs.forEach { it.emulator.applyPalette() }
        terminalView.setTypeface(typefaceFor(settingsStore.fontFamily))
        terminalView.setTextSizePx(spToPx(settingsStore.fontSizeSp))
        terminalView.setLigaturesEnabled(settingsStore.ligaturesEnabled)
        extraKeysScroll.visibility = if (settingsStore.showExtraKeys) View.VISIBLE else View.GONE
        buildExtraKeysRow(extraKeysRow)
        applyChromeColors()
        terminalView.invalidate()
    }

    private fun syncAgentBridge() {
        if (settingsStore.agentAccessEnabled) agentBridge.start(AlpineSession.BRIDGE_PORT, settingsStore.agentToken) else agentBridge.stop()
        val root = AlpineRootfs.rootDir(this)
        if (root.isDirectory) (application as AlpineTermApp).backgroundExecutor.execute { AlpineSession.writeAgentFiles(this, root) }
    }

    // Read by the device-flow poll thread, written on UI: volatile so cancel is seen.
    @Volatile private var githubCancelled = false

    /** AlpDroidx27s own OAuth App ID (res/values/github.xml); the user-pasted one is only a fallback while that's blank. */
    private fun githubClientId(): String = getString(R.string.github_client_id).trim().ifBlank { settingsStore.githubClientId }
    private fun hasBuiltInGithubClient() = getString(R.string.github_client_id).isNotBlank()

    private fun buildAgentCategory(panel: LinearLayout) { fillAgent(panel) }

    private fun fillAgent(panel: LinearLayout) {
        panel.removeAllViews()
        panel.addView(sectionLabel("Agent access (local control API)"))
        panel.addView(
            MaterialSwitch(this).apply {
                text = "Let programs in the terminal control AlpDroid"
                setTextColor(0xFFD4D4D4.toInt())
                isChecked = settingsStore.agentAccessEnabled
                setOnCheckedChangeListener { _, checked ->
                    settingsStore.agentAccessEnabled = checked
                    syncAgentBridge()
                }
            },
        )
        panel.addView(devNote("Off by default. When on, programs in the terminal can use `alpctl` to control the app — anything running there, including installed packages, gets that power: tabs, screen content, clipboard, browser, and your GitHub token if enabled. Only switch on for sessions you trust."))
        panel.addView(guideLink("Agent access"))
        panel.addView(
            MaterialSwitch(this).apply {
                text = "Tell coding agents about AlpDroid (AGENTS.md note)"
                setTextColor(0xFFD4D4D4.toInt())
                isChecked = settingsStore.agentContextFiles
                setOnCheckedChangeListener { _, checked ->
                    settingsStore.agentContextFiles = checked
                    val root = AlpineRootfs.rootDir(this@MainActivity)
                    if (root.isDirectory) (application as AlpineTermApp).backgroundExecutor.execute {
                        AgentContext.sync(root, AgentContext.appVersion(this@MainActivity), settingsStore.agentAccessEnabled, checked)
                    }
                }
            },
        )
        panel.addView(pillButton().apply { text = "Copy access token"; setOnClickListener {
            val clip = android.content.ClipData.newPlainText("token", settingsStore.agentToken)
            // A long-lived bearer token on the system clipboard is readable by any app —
            // mark it sensitive (API 33+) so the system hides it from clipboard history.
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
            android.widget.Toast.makeText(this@MainActivity, "Token copied", android.widget.Toast.LENGTH_SHORT).show()
        } })
        panel.addView(pillButton().apply { text = "Regenerate token (locks out old ones)"; setOnClickListener {
            val t = settingsStore.regenerateAgentToken()
            agentBridge.updateToken(t)
            syncAgentBridge()
            android.widget.Toast.makeText(this@MainActivity, "New token issued", android.widget.Toast.LENGTH_SHORT).show()
        } })

        panel.addView(sectionLabel("GitHub"))
        panel.addView(guideLink("GitHub sign-in"))
        val login = GitHubAuth.login(this)
        val signedIn = GitHubAuth.token(this) != null
        panel.addView(devNote(if (signedIn) "Signed in as ${login ?: "(unknown)"}. Token is stored encrypted in the Android Keystore." else (if (hasBuiltInGithubClient()) "Not signed in. Tap the button — GitHub opens in your browser and asks you to authorize." else "Not signed in. This build has no GitHub OAuth App built in yet, so paste one's Client ID below (see the guide). Once one is built in, sign-in is a single tap.")))
        if (!signedIn) {
            val input = if (hasBuiltInGithubClient()) null else android.widget.EditText(this).apply {
                hint = "OAuth App Client ID (Ov23li…)"
                setText(settingsStore.githubClientId)
                setTextColor(0xFFD4D4D4.toInt())
                setHintTextColor(0xFF5A6270.toInt())
                isSingleLine = true
            }
            input?.let { panel.addView(it) }
            panel.addView(pillButton().apply { text = "Sign in with GitHub"; setOnClickListener {
                input?.let { settingsStore.githubClientId = it.text.toString() }
                startGithubSignIn(panel)
            } })
        } else {
            panel.addView(
                MaterialSwitch(this).apply {
                    text = "Let agents use my GitHub token (git push/pull, gh)"
                    setTextColor(0xFFD4D4D4.toInt())
                    isChecked = settingsStore.agentGithubToken
                    setOnCheckedChangeListener { _, checked -> settingsStore.agentGithubToken = checked }
                },
            )
            panel.addView(devNote("With this on (and Agent access on), git in the terminal signs in to github.com automatically."))
            panel.addView(pillButton().apply { text = "Sign out"; setOnClickListener {
                GitHubAuth.signOut(this@MainActivity)
                settingsStore.agentGithubToken = false
                fillAgent(panel)
            } })
        }
    }

    private fun startGithubSignIn(panel: LinearLayout) {
        val clientId = githubClientId()
        if (clientId.isBlank()) { android.widget.Toast.makeText(this, "Enter the Client ID first", android.widget.Toast.LENGTH_SHORT).show(); return }
        githubCancelled = false
        Thread {
            val code = GitHubAuth.requestDeviceCode(clientId)
            runOnUiThread {
                if (code == null) { android.widget.Toast.makeText(this, "Couldn't reach GitHub — check the Client ID (Device Flow must be enabled) and network.", android.widget.Toast.LENGTH_LONG).show(); return@runOnUiThread }
                // One tap: the browser opens straight to GitHub's authorize page with the code already
                // filled in (it's also copied, in case GitHub doesn't pre-fill it), showing whichever
                // GitHub account is signed in there. Approving in the browser is all that's left.
                val url = Uri.parse(code.verificationUri).buildUpon().appendQueryParameter("user_code", code.userCode).build()
                fun openBrowser() {
                    // Marked sensitive like the agent token below: a one-time auth code on the
                    // clipboard is readable by any app (API 33+ hides it from history).
                    val clip = android.content.ClipData.newPlainText("code", code.userCode)
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        clip.description.extras = android.os.PersistableBundle().apply {
                            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                    }
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Authorize on GitHub")
                    .setMessage("GitHub is opening in your browser. Check the account shown, then tap Authorize.\n\nYour code: ${code.userCode}\n(copied — paste it if the page asks)\n\nWaiting for approval…")
                    .setPositiveButton("Open GitHub again", null)
                    .setNegativeButton("Cancel") { _, _ -> githubCancelled = true }
                    .setCancelable(false)
                    .show()
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { openBrowser() }
                openBrowser()
                Thread {
                    val result = GitHubAuth.pollToken(clientId, code) { githubCancelled }
                    val login = (result as? GitHubAuth.Poll.Granted)?.let { GitHubAuth.fetchLogin(it.token) }
                    runOnUiThread {
                        // Rotation may have destroyed this Activity while polling: persist a
                        // granted token regardless (re-auth must never be lost), but touch no
                        // dead UI beyond a guarded dismiss.
                        if (result is GitHubAuth.Poll.Granted) GitHubAuth.saveToken(this, result.token, login, result.refreshToken, result.expiresInSec, clientId)
                        runCatching { dialog.dismiss() }
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        when (result) {
                            is GitHubAuth.Poll.Granted -> {
                                // Token already persisted above (even across rotation).
                                android.widget.Toast.makeText(this, "Signed in to GitHub as ${login ?: "?"}", android.widget.Toast.LENGTH_LONG).show()
                                // Bring the app back after the browser step. Android may refuse a launch from the
                                // background, so a tap-to-return notification covers that case.
                                OperationNotifications.finish(this, OperationNotifications.newId(), "Signed in to GitHub", "Signed in as ${login ?: "your account"} — tap to return", true)
                                runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
                            }
                            is GitHubAuth.Poll.Failed -> if (!githubCancelled) android.widget.Toast.makeText(this, result.reason, android.widget.Toast.LENGTH_LONG).show()
                        }
                        fillAgent(panel)
                    }
                }.start()
            }
        }.start()
    }

    /**
     * One-click opencode server: a single Start button opens a tab whose session directly
     * execs the server command — nothing is typed, so no proot-startup race. v2 has no `web`
     * subcommand (that was v1); `serve` starts the API + web server. The binary is resolved
     * in-guest (opencode, else opencode2, else a hint to Quick install). Stop with Ctrl+C
     * in that tab.
     */
    private fun confirmOpencodeWeb() {
        val port = 4096
        val ips = NetworkInfo.localIpv4Addresses()
        val urls = if (ips.isEmpty()) "(no network address found)" else ips.joinToString("\n") { "http://$it:$port" }
        fun start() {
            drawerLayout.closeDrawer(GravityCompat.END)
            // Direct-exec session: the server command is argv from spawn, nothing is typed —
            // typing into a just-spawned tab lost bytes while proot was still starting.
            // LD_PRELOAD gcompat: bun's FFI stub needs a glibc symbol musl lacks (fixes the
            // "gnu_get_libc_version: symbol not found" dlopen crash on Alpine).
            addTab(
                "opencode serve",
                onStarted = {
                    tabs.getOrNull(activeTabIndex)?.let { catchServePassword(it, 5) }
                },
                directCommand = "[ -f /lib/libgcompat.so.0 ] && export LD_PRELOAD=/lib/libgcompat.so.0; " +
                    "BIN=\$(command -v opencode || command -v opencode2); " +
                    "if [ -z \"\$BIN\" ]; then echo 'opencode not installed — Settings > Quick install first'; exit 1; fi; " +
                    "\"\$BIN\" serve --hostname 0.0.0.0 --port $port",
            )
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Start opencode server?")
            .setMessage(
                "Runs `opencode serve --hostname 0.0.0.0 --port $port` in a new tab. It prints a generated password — the app catches it and shows it to you for the browser login. Anyone on this network with that password gets full access: only use on networks you trust.\n\n" +
                    "URLs:\n$urls\n\n" +
                    "Stop with Ctrl+C in that tab.",
            )
            .setPositiveButton("Start server") { _, _ -> start() }
            .setNegativeButton("Cancel", null)
            .show()
    }


    /** Watches a just-started serve tab for its printed `server password X` line, then
     *  hands the URL + password to the user (serve always generates one; there is no
     *  passwordless mode). Retries a few times — bun under proot can take a while. */
    private fun catchServePassword(tab: TerminalTab, attemptsLeft: Int) {
        if (attemptsLeft <= 0) return
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (!tabs.contains(tab)) return@postDelayed // tab closed meanwhile
            val hit = Regex("server password (\\S+)").find(tab.emulator.tailText(60))
            if (hit != null) showServeReadyDialog(tab, hit.groupValues[1])
            else catchServePassword(tab, attemptsLeft - 1)
        }, 8000)
    }

    private fun showServeReadyDialog(tab: TerminalTab, password: String) {
        if (isFinishing || isDestroyed || !tabs.contains(tab)) return
        val port = 4096
        val ips = NetworkInfo.localIpv4Addresses()
        val urls = if (ips.isEmpty()) "http://<phone>:$port" else ips.joinToString("\n") { "http://$it:$port" }
        // Auto-open this phone's browser on the local URL; password is copied so the
        // login page is one paste away (the page has no URL param to pre-fill).
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("opencode password", password))
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$port")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("opencode server running")
            .setMessage("Browser opened on this phone (127.0.0.1:$port) and the password is copied — paste it in. From another device on this Wi-Fi open:\n\n$urls\n\nPassword: $password\n\nStop with Ctrl+C in the \"opencode serve\" tab.")
            .setPositiveButton("Copy password again") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("opencode password", password))
                android.widget.Toast.makeText(this, "Password copied", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Done", null)
            .show()
    }

    // --- Devices: drives, USB, network interfaces, Wi-Fi -------------------------------------

    private var devicesPanel: LinearLayout? = null

    private val deviceEventReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_MEDIA_MOUNTED ->
                    android.widget.Toast.makeText(this@MainActivity, "Drive mounted — open a new tab to use it under /mnt", android.widget.Toast.LENGTH_LONG).show()
                Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL ->
                    android.widget.Toast.makeText(this@MainActivity, "Drive removed — tabs still using it may error until it's back", android.widget.Toast.LENGTH_LONG).show()
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                    android.widget.Toast.makeText(this@MainActivity, "USB device attached", android.widget.Toast.LENGTH_SHORT).show()
                android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED ->
                    android.widget.Toast.makeText(this@MainActivity, "USB device detached", android.widget.Toast.LENGTH_SHORT).show()
            }
            // Only while the Devices screen is actually showing — otherwise there's nothing to update.
            devicesPanel?.takeIf { it.isShown }?.let { fillDevices(it) }
        }
    }

    private fun registerDeviceEvents() {
        val filter = android.content.IntentFilter().apply {
            listOf(Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL).forEach { addAction(it) }
            addDataScheme("file")
        }
        val usb = android.content.IntentFilter().apply {
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(DeviceInfo.ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(this, deviceEventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, deviceEventReceiver, usb, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun buildDevicesCategory(panel: LinearLayout) {
        devicesPanel = panel
        fillDevices(panel, rescan = true)
    }


    private fun cardBg(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(ContextCompat.getColor(this@MainActivity, R.color.panel_2))
    }

    private fun chip(text: String, color: Int = 0xFF3ED0B8.toInt()): TextView = TextView(this).apply {
        this.text = text
        textSize = 10.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color)
        setPadding(dp(8), dp(2), dp(8), dp(2))
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setStroke(dp(1), color) }
    }

    /** One rounded card: bold title, optional status chips, aligned key/value lines, optional
     *  usage bar (0..100). Values are selectable so an IP/DNS can be copied out. */
    private fun devCard(
        title: String,
        chips: List<Pair<String, Int>> = emptyList(),
        lines: List<Pair<String, String>> = emptyList(),
        barPercent: Int? = null,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBg()
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFFE7ECEF.toInt())
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                chips.forEach { (t, c) -> addView(chip(t, c), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }) }
            },
        )
        if (barPercent != null) {
            addView(
                android.widget.ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = barPercent.coerceIn(0, 100)
                    progressTintList = ColorStateList.valueOf(if (barPercent >= 90) 0xFFE5534B.toInt() else 0xFF3ED0B8.toInt())
                    progressBackgroundTintList = ColorStateList.valueOf(0xFF2A303C.toInt())
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(8); bottomMargin = dp(2) },
            )
        }
        lines.forEach { (k, v) ->
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(4), 0, 0)
                    addView(TextView(this@MainActivity).apply {
                        text = k
                        textSize = 12f
                        setTextColor(0xFF8B93A1.toInt())
                    }, LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT))
                    addView(TextView(this@MainActivity).apply {
                        text = v
                        textSize = 12.5f
                        typeface = Typeface.MONOSPACE
                        setTextColor(0xFFD4D4D4.toInt())
                        setTextIsSelectable(true)
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                },
            )
        }
    }

    private fun devNote(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xFF8B93A1.toInt())
        setPadding(dp(2), dp(2), dp(2), dp(10))
    }

    private fun signalLabel(dbm: Int) = when {
        dbm >= -55 -> "Excellent"
        dbm >= -67 -> "Good"
        dbm >= -78 -> "Fair"
        else -> "Weak"
    }

    /** Snapshot of everything the Devices screen shows — collected off the main thread
     *  (Wi-Fi scans, sysfs reads, storage stats), rendered on it. Views can't cross threads,
     *  so only plain data travels between the two halves. */
    private data class DevicesData(
        val summary: List<Pair<String, String>>,
        val net: DeviceInfo.ActiveNet?,
        val link: DeviceInfo.WifiLink?,
        val ifaces: List<DeviceInfo.Iface>,
        val drives: List<DeviceInfo.Drive>,
        val guestPaths: Map<DeviceInfo.Drive, String>,
        val storageGranted: Boolean,
        val usb: List<DeviceInfo.Usb>,
        val hasLocation: Boolean,
        val locationEnabled: Boolean,
        val wifi: List<DeviceInfo.Wifi>,
    )

    private var devicesTicket = 0

    private fun fillDevices(panel: LinearLayout, rescan: Boolean = false) {
        panel.removeAllViews()
        panel.addView(pillButton().apply { text = "Refresh"; setOnClickListener { fillDevices(panel, rescan = true) } })
        panel.addView(guideLink("Devices"))
        panel.addView(devNote("Scanning hardware…"))
        val ticket = ++devicesTicket
        val appCtx = applicationContext
        (application as AlpineTermApp).backgroundExecutor.execute {
            val drives = runCatching { DeviceInfo.removableDrives(appCtx) }.getOrDefault(emptyList())
            val data = DevicesData(
                summary = DeviceInfo.deviceSummary(appCtx),
                net = DeviceInfo.activeNetwork(appCtx),
                link = DeviceInfo.wifiLink(appCtx),
                ifaces = DeviceInfo.interfaces(),
                drives = drives,
                guestPaths = DeviceInfo.guestPaths(drives),
                storageGranted = StorageAccess.isGranted(appCtx),
                usb = DeviceInfo.usbDevices(appCtx),
                hasLocation = DeviceInfo.hasLocationPermission(this@MainActivity),
                locationEnabled = DeviceInfo.locationEnabled(appCtx),
                wifi = if (DeviceInfo.hasLocationPermission(this@MainActivity)) DeviceInfo.wifiScan(appCtx, rescan) else emptyList(),
            )
            mainHandler.post {
                // The panel may have been rebuilt (another Refresh, category switch) while the
                // scan was in flight — never render into a panel that isn't current anymore.
                if (isFinishing || isDestroyed || devicesPanel !== panel || ticket != devicesTicket) return@post
                renderDevices(panel, data)
            }
        }
    }

    private fun renderDevices(panel: LinearLayout, data: DevicesData) {
        panel.removeAllViews()
        val ok = 0xFF3ED0B8.toInt()
        val warn = 0xFFE5A94B.toInt()
        val bad = 0xFFE5534B.toInt()

        panel.addView(pillButton().apply { text = "Refresh"; setOnClickListener { fillDevices(panel, rescan = true) } })
        panel.addView(guideLink("Devices"))


        panel.addView(sectionLabel("This phone"))
        panel.addView(devCard("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}", lines = data.summary))

        panel.addView(sectionLabel("Active connection"))
        val net = data.net
        if (net == null) {
            panel.addView(devCard("Offline", chips = listOf("no network" to bad)))
        } else {
            val link = if (net.transport == "Wi-Fi") data.link else null
            panel.addView(
                devCard(
                    net.transport,
                    chips = listOf(
                        (if (net.validated) "internet OK" else "no internet") to (if (net.validated) ok else warn),
                    ) + (if (net.metered) listOf("metered" to warn) else emptyList()),
                    lines = buildList {
                        link?.let {
                            add("Network" to it.ssid)
                            add("Signal" to "${it.rssi} dBm (${signalLabel(it.rssi)})")
                            add("Link speed" to "${it.speedMbps} Mbps • ${it.band}")
                        }
                        net.gateway?.let { add("Gateway" to it) }
                        if (net.dns.isNotEmpty()) add("DNS" to net.dns.joinToString("\n"))
                        if (net.downKbps > 0) add("Est. speed" to "↓ ${net.downKbps / 1000} Mbps  ↑ ${net.upKbps / 1000} Mbps")
                    },
                ),
            )
        }

        panel.addView(sectionLabel("Network adapters"))
        val ifaces = data.ifaces
        if (ifaces.isEmpty()) panel.addView(devNote("No adapters reported."))
        ifaces.forEach { i ->
            panel.addView(
                devCard(
                    "${i.name}  •  ${i.kind}",
                    chips = listOf((if (i.up) "up" else "down") to (if (i.up) ok else 0xFF8B93A1.toInt())),
                    lines = buildList {
                        i.addresses.forEach { a -> add((if (a.contains(':')) "IPv6" else "IPv4") to a) }
                        if (i.mtu > 0) add("MTU" to "${i.mtu}")
                        if (i.rxBytes >= 0) add("Traffic" to "↓ ${DeviceInfo.humanBytes(i.rxBytes)}   ↑ ${DeviceInfo.humanBytes(i.txBytes.coerceAtLeast(0))}")
                    },
                ),
            )
        }

        panel.addView(sectionLabel("Drives (SD card / USB flash)"))
        val drives = data.drives
        val guestPaths = data.guestPaths
        if (drives.isEmpty()) panel.addView(devNote("None detected. Plug in an SD card or USB drive (OTG); only drives Android itself can mount appear here."))
        drives.forEach { d ->
            val used = d.totalBytes - d.freeBytes
            panel.addView(
                devCard(
                    d.label,
                    chips = listOf((if (d.mounted) "mounted" else "not mounted") to (if (d.mounted) ok else warn)),
                    barPercent = if (d.mounted && d.totalBytes > 0) (used * 100 / d.totalBytes).toInt() else null,
                    lines = buildList {
                        if (d.mounted) {
                            add("Space" to "${DeviceInfo.humanBytes(d.freeBytes)} free of ${DeviceInfo.humanBytes(d.totalBytes)}")
                            add("In terminal" to "${guestPaths[d] ?: "/mnt/${d.mountName}"}  (new tabs)")
                            add("Android path" to "${d.path}")
                        } else add("Status" to "Unavailable to the terminal")
                        d.uuid?.let { add("UUID" to it) }
                    },
                ),
            )
        }
        if (drives.isNotEmpty() && !data.storageGranted) panel.addView(devNote("Grant all-files access (Backup & Storage) to use drives inside the terminal."))

        panel.addView(sectionLabel("USB devices"))
        val usb = data.usb
        if (usb.isEmpty()) panel.addView(devNote("None attached (needs an OTG cable/adapter)."))
        usb.forEach { u ->
            panel.addView(
                devCard(
                    u.title,
                    chips = listOf(u.kind to ok, (if (u.hasPermission) "access granted" else "no access") to (if (u.hasPermission) ok else warn)),
                    lines = u.details,
                ),
            )
            if (!u.hasPermission) panel.addView(pillButton().apply { text = "Grant access to ${u.id}"; setOnClickListener { DeviceInfo.requestUsbPermission(this@MainActivity, u.device) } })
        }
        if (usb.isNotEmpty()) panel.addView(devNote("Lists devices and requests Android's per-device access. Formatting and flashing aren't supported yet."))

        panel.addView(sectionLabel("Wi-Fi networks nearby"))
        if (!data.hasLocation) {
            panel.addView(devNote("Android requires the location permission to list Wi-Fi networks."))
            panel.addView(pillButton().apply {
                text = "Allow & scan"
                setOnClickListener { ActivityCompat.requestPermissions(this@MainActivity, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE) }
            })
        } else {
            val wifi = data.wifi
            if (wifi.isEmpty()) {
                panel.addView(
                    devNote(
                        if (!data.locationEnabled) "Location services are switched off — Android returns no Wi-Fi results until they're on (Settings → Location)."
                        else "No results yet — Android throttles scans to a few every couple of minutes. This refreshes on its own.",
                    ),
                )
            }
            wifi.take(20).forEach { w ->
                panel.addView(
                    devCard(
                        w.ssid,
                        chips = (if (w.connected) listOf("connected" to ok) else emptyList()) +
                            listOf(w.security to (if (w.security == "Open") warn else 0xFF8B93A1.toInt())),
                        lines = listOf(
                            "Signal" to "${w.level} dBm (${signalLabel(w.level)})",
                            "Band" to "${w.band}${if (w.channel > 0) " • channel ${w.channel}" else ""}",
                        ),
                    ),
                )
            }
            panel.addView(pillButton().apply { text = "Connect… (Android Wi-Fi settings)"; setOnClickListener { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) } })
            panel.addView(devNote("Connections are made in Android's Wi-Fi settings."))
        }
    }

    private fun buildAboutCategory(panel: LinearLayout) {
        panel.addView(sectionLabel("About"))
        panel.addView(
            TextView(this).apply {
                text = "AlpDroid $appVersionLabel"
                textSize = 12f
                setTextColor(0xFF5A6270.toInt())
            },
        )
        updateStatusText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF8B93A1.toInt())
            text = lastUpdateStatus()
        }
        panel.addView(updateStatusText)
        // Opening About re-checks when stale — opening Settings is exactly when a
        // user wonders about updates, so don't make them hunt the manual button.
        if (System.currentTimeMillis() - settingsStore.lastUpdateCheckMs > updateCheckThrottleMs) {
            checkForAppUpdate(manual = false)
        }
        panel.addView(
            pillButton().apply {
                text = "Check for updates"
                setOnClickListener { checkForAppUpdate(manual = true) }
            },
        )
    }

    private var updateStatusText: TextView? = null
    private var updateCheckInFlight = false

    private fun lastUpdateStatus(): String {
        val last = settingsStore.lastUpdateCheckMs
        if (last == 0L) return "Never checked for updates."
        val agoMin = (System.currentTimeMillis() - last) / 60000
        val ago = if (agoMin < 1) "just now" else if (agoMin < 60) "$agoMin min ago" else "${agoMin / 60} h ago"
        return "Last checked $ago — you're on AlpDroid $appVersionLabel."
    }

    /**
     * Compares this APK against the newest GitHub release with an APK asset. Silent unless
     * [manual] or an update is actually found — the daily auto-check never nags on "none".
     */
    private fun checkForAppUpdate(manual: Boolean) {
        if (updateCheckInFlight) return
        updateCheckInFlight = true
        if (manual) updateStatusText?.text = "Checking…"
        (application as AlpineTermApp).backgroundExecutor.execute {
            val update = AppUpdater.checkForUpdate(this)
            mainHandler.post {
                updateCheckInFlight = false
                if (isFinishing || isDestroyed) return@post
                settingsStore.lastUpdateCheckMs = System.currentTimeMillis()
                if (update == null) {
                    updateStatusText?.text = lastUpdateStatus()
                    if (manual) {
                        android.widget.Toast.makeText(this, "No updates found", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@post
                }
                showUpdateDialog(update)
            }
        }
    }

    /**
     * Periodic auto-check: 10s after start, on every resume, and every 2h while running —
     * an update pops whenever it appears, no restart needed. Throttled to one check per
     * 6h; only dialogs when something newer actually exists.
     */
    private val updateCheckIntervalMs = 15L * 60 * 1000
    // Short on purpose: one tiny API call, and it makes updates pop within minutes —
    // a daily throttle is what silently swallowed the very first auto-check in testing.
    private val updateCheckThrottleMs = 15L * 60 * 1000
    private fun maybeAutoUpdateCheck() {
        if (System.currentTimeMillis() - settingsStore.lastUpdateCheckMs < updateCheckThrottleMs) return
        checkForAppUpdate(manual = false)
    }

    private val periodicUpdateCheck = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                // Heartbeat for the system-kill detector (covers foreground kills; onPause
                // only stamps when leaving).
                settingsStore.lastAliveMs = System.currentTimeMillis()
                maybeAutoUpdateCheck()
                mainHandler.postDelayed(this, updateCheckIntervalMs)
            }
        }
    }

    private fun startPeriodicUpdateChecks() {
        mainHandler.removeCallbacks(periodicUpdateCheck)
        mainHandler.postDelayed(periodicUpdateCheck, updateCheckIntervalMs)
    }

    private var updateDownloadCancelled = false
    // Set when installApk() detoured to Settings for the install permission: the downloaded
    // APK waits on disk, and onResume() below re-offers the install on return.
    private var sentToInstallSettings = false

    private fun showUpdateDialog(update: AppUpdater.Update) {
        val notes = update.notes.take(1500)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Update available: ${update.name}")
            .setMessage(
                "You're on AlpDroid $appVersionLabel.${if (notes.isNotBlank()) "\n\nWhat's new:\n$notes" else ""}" +
                    "\n\nThe app will close to install — your tabs and files are untouched (sessions don't survive any app update, same as a Play Store one).",
            )
            .setPositiveButton("Update now") { _, _ -> downloadAndInstallUpdate(update) }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }

    private fun downloadAndInstallUpdate(update: AppUpdater.Update) {
        updateDownloadCancelled = false
        // Same filling-logo treatment as the Alpine setup screen: a LiquidFillView fed by
        // real bytes, indeterminate until the total is known.
        val density = resources.displayMetrics.density
        val fill = LiquidFillView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams((120 * density).toInt(), (120 * density).toInt()).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
            isIndeterminate = true
        }
        val label = android.widget.TextView(this).apply {
            text = "Starting…"
            setTextColor(0xFFD4D4D4.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        val body = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
            addView(fill)
            addView(label)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Downloading ${update.name}")
            .setView(body)
            .setNegativeButton("Cancel") { _, _ -> updateDownloadCancelled = true }
            .setCancelable(false)
            .show()
        (application as AlpineTermApp).backgroundExecutor.execute {
            var lastMs = 0L
            val apk = runCatching {
                AppUpdater.download(this, update, { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastMs >= 300) {
                        lastMs = now
                        val text = if (total > 0) "${FileOps.humanSize(done)} of ${FileOps.humanSize(total)}" else FileOps.humanSize(done)
                        val percent = if (total > 0) ((done * 100 / total).toInt().coerceIn(0, 100)) else -1
                        mainHandler.post {
                            if (!dialog.isShowing) return@post
                            label.text = text
                            if (percent >= 0) { fill.isIndeterminate = false; fill.progress = percent }
                        }
                    }
                }, { updateDownloadCancelled })
            }.getOrNull()
            mainHandler.post {
                runCatching { dialog.dismiss() }
                if (isFinishing || isDestroyed) return@post
                if (apk == null) {
                    if (!updateDownloadCancelled) {
                        updateStatusText?.text = "Update check failed — try again later."
                        android.widget.Toast.makeText(this, "Couldn't download the update", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@post
                }
                updateStatusText?.text = "Ready to install ${update.name}."
                val (_, currentCode) = AppUpdater.currentVersion(this)
                val apkCode = runCatching { AppUpdater.apkVersionCode(this, apk) }.getOrNull()
                if (apkCode == null || apkCode <= currentCode) {
                    updateStatusText?.text = "Download rejected: not newer than installed."
                    android.widget.Toast.makeText(this, "Downloaded file isn't a newer app version — aborted", android.widget.Toast.LENGTH_LONG).show()
                    runCatching { apk.delete() }
                    return@post
                }
                // Returns false only when install permission is missing — the user is already
                // on their way to Settings then. Remember it: onResume() re-offers the install
                // when they return (the old flow stranded them with no way back).
                if (!AppUpdater.installApk(this, apk)) sentToInstallSettings = true
            }
        }
    }

    /** "1.2.3 (45)" from the APK's own versionName/versionCode — read once rather than on every
     *  settings-panel rebuild (a theme or font change tears down and rebuilds the whole panel). */
    private val appVersionLabel: String by lazy {
        runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            "${info.versionName} ($code)"
        }.getOrDefault("unknown")
    }

    private lateinit var searchBar: LinearLayout
    private lateinit var searchCounter: TextView

    /** A `less`-of-the-scrollback search bar, hidden until the extra-keys row's search button
     *  toggles it — jumps the view to matches via TerminalView.search() rather than duplicating
     *  any of its scrollback-addressing logic here. */
    private fun buildSearchBar(): LinearLayout {
        // A plain Button's platform-default style reserves a large minimum width/height
        // (min ~88dp wide, its own elevated background) meant for a standalone tap target — three
        // of those next to the input squeezed it down to almost nothing, wrapping its hint text
        // across several lines instead of showing it on one. These use the same compact,
        // no-minimum-size TextView-as-button style as the extra-keys row instead, so the input
        // actually gets the space its layout_weight asks for.
        fun iconButton(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(0xFFD4D4D4.toInt())
            gravity = Gravity.CENTER
            minWidth = dp(40)
            minHeight = dp(40)
            isClickable = true
            isFocusable = false
            background = ResourcesCompat.getDrawable(resources, android.R.drawable.list_selector_background, theme)
            setOnClickListener { action() }
        }
        val input = android.widget.EditText(this).apply {
            hint = "Search scrollback"
            setTextColor(0xFFD4D4D4.toInt())
            setHintTextColor(0xFF8B93A1.toInt())
            maxLines = 1
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            setOnEditorActionListener { _, _, _ -> doSearch(text.toString(), forward = true); true }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xEE12161C.toInt())
            setPadding(dp(12), dp(6), dp(4), dp(6))
            visibility = View.GONE
            addView(input)
            addView(iconButton("▲") { doSearch(input.text.toString(), forward = false) })
            addView(iconButton("▼") { doSearch(input.text.toString(), forward = true) })
            val counter = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(0xFF8B93A1.toInt())
                minWidth = dp(48)
                gravity = Gravity.CENTER
            }
            searchCounter = counter
            addView(counter)
            addView(
                iconButton("✕") {
                    visibility = View.GONE
                    input.setText("")
                    searchCounter.text = ""
                    terminalView.clearSearch()
                    terminalView.requestFocus()
                },
            )
        }
        searchBar = bar
        return bar
    }

    private fun doSearch(query: String, forward: Boolean) {
        if (query.isEmpty()) return
        terminalView.searchAsync(query, forward) { found, position, total ->
            searchCounter.text = if (found) "$position/$total" else ""
            if (!found) {
                android.widget.Toast.makeText(this, "No matches", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleSearchBar() {
        if (searchBar.visibility == View.VISIBLE) {
            searchBar.visibility = View.GONE
            terminalView.clearSearch()
            terminalView.requestFocus()
        } else {
            searchBar.visibility = View.VISIBLE
            searchBar.getChildAt(0).requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(searchBar.getChildAt(0), 0)
        }
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 11.5f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
        setPadding(dp(2), dp(20), 0, dp(8))
        setTextColor(0xFF8B93A1.toInt())
    }

    /** The redesign's standard flat, rounded action button — replacing this app's original plain
     *  system Buttons (a gray rectangle with a drop shadow) everywhere a settings screen needs
     *  one. Every call site only ever sets `text` and `setOnClickListener` after this, so a single
     *  shared style here is safe to apply uniformly. */
    private fun pillButton(): Button = Button(this).apply {
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        textSize = 13.5f
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
        setPadding(dp(16), dp(11), dp(16), dp(11))
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        stateListAnimator = null
        elevation = 0f
        val base = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(ContextCompat.getColor(this@MainActivity, R.color.panel_2))
        }
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, null)
        // Baked in here rather than at each call site: pillButton() is used both stacked
        // vertically in a settings panel (bottomMargin is what gives them breathing room instead
        // of touching edge-to-edge) and side-by-side in a horizontal row like the SSH profile
        // list or the local-preview port presets (marginEnd does the same job there) — wrap
        // content in both directions so this never fights either layout's own sizing.
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
            marginEnd = dp(8)
        }
    }

    /** A rounded, tinted icon tile — the redesign's standard way of leading a settings/file row
     *  with an icon, replacing the plain untinted text-only rows this app started with. */
    private fun iconTile(iconRes: Int, sizeDp: Int = 42, iconSizeDp: Int = 22, tint: Int = ContextCompat.getColor(this, R.color.accent)): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(sizeDp * 3 / 10).toFloat()
                setColor(ContextCompat.getColor(this@MainActivity, R.color.panel_2))
            }
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(tint)
                    layoutParams = FrameLayout.LayoutParams(dp(iconSizeDp), dp(iconSizeDp), Gravity.CENTER)
                },
            )
        }
    }

    private fun fadeOutSetup() {
        setupContainer.animate()
            .alpha(0f)
            .setDuration(220)
            .withEndAction { setupContainer.visibility = View.GONE }
            .start()
        terminalView.alpha = 0f
        terminalView.animate().alpha(1f).setDuration(220).start()
    }

    /** A BEL byte (0x07) — a shell/build/agent signaling it wants attention. Fires from the
     *  pty-reader thread (via TerminalEmulator.feed), so this hops to the UI thread itself. */
    private fun onTerminalBell(tabId: Int) {
        mainHandler.post {
            // Vibrate follows the sound switch: silent-mode users who never opted in must not
            // get buzzed by every background BEL.
            if (settingsStore.bellSoundEnabled) {
                val vibrator = getSystemService(android.os.Vibrator::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(80, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION") vibrator?.vibrate(80)
                }
                playBellSound()
            }
            val index = tabs.indexOfFirst { it.id == tabId }
            if (index >= 0 && index != activeTabIndex) {
                android.widget.Toast.makeText(this, "Session ${index + 1} wants attention", android.widget.Toast.LENGTH_SHORT).show()
                notifyBell(tabId, index)
            }
        }
    }

    /** STREAM_MUSIC rather than the notification/ringer stream: those are exactly what silent
     *  mode mutes, but someone who explicitly opts into a bell sound (off by default) wants a
     *  cue they'll actually notice even with the phone silenced — the same reason a media-stream
     *  alarm-clock sound still plays when ringtones don't. A plain synthesized beep rather than
     *  a bundled audio asset — nothing elaborate is needed for "something happened". */
    private fun playBellSound() {
        runCatching {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 90)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
            mainHandler.postDelayed({ tone.release() }, 300)
        }
    }

    /** A build finishing, a CLI agent (opencode/Claude Code/Cline) waiting for input, a `read`
     *  prompt — the whole point of BEL is "look at this even if you've moved on", so the
     *  notification carries a tail of what's actually on screen rather than just "something
     *  happened". Separate channel from the keep-alive service's silent ongoing one, since this
     *  one should actually make a sound/heads-up like a normal alert. */
    private fun notifyBell(tabId: Int, index: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(BELL_CHANNEL_ID, "Session alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "A session's shell rang the terminal bell while you were elsewhere"
                    },
                )
            }
            val tab = tabs.getOrNull(tabs.indexOfFirst { it.id == tabId })
            val preview = tab?.let { tabOutputPreview(it) }.orEmpty()
            val openApp = PendingIntent.getActivity(
                this, tabId, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = androidx.core.app.NotificationCompat.Builder(this, BELL_CHANNEL_ID)
                .setContentTitle("Session ${index + 1} wants attention")
                .setContentText(preview.ifEmpty { "Tap to view" })
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(preview.ifEmpty { "Tap to view" }))
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
            nm.notify(BELL_NOTIFICATION_BASE_ID + tabId, notification)
        }
    }

    private fun tabOutputPreview(tab: TerminalTab, maxLines: Int = 4): String {
        val emulator = tab.emulator
        val total = emulator.combinedRowCount()
        if (total == 0) return ""
        val start = maxOf(0, total - maxLines)
        return (start until total).joinToString("\n") { row ->
            emulator.textInRange(row, 0, row, Int.MAX_VALUE)
        }.trimEnd()
    }

    private fun updateKeepAliveService() {
        val intent = Intent(this, TerminalKeepAliveService::class.java)
        if (settingsStore.keepAliveEnabled && (tabs.isNotEmpty() || (application as AlpineTermApp).pluginJobs.hasActive())) {
            requestNotificationPermissionIfNeeded()
            intent.putExtra(TerminalKeepAliveService.EXTRA_WAKE_LOCK, settingsStore.wakeLockEnabled)
            // This can run well after a long first-run Alpine download finishes (posted from a
            // background executor callback) — if the user has already left the app by then,
            // Android (API 31+) refuses a startForegroundService() call made while backgrounded
            // and throws ForegroundServiceStartNotAllowedException, uncaught, on the main thread.
            // A session that's already running doesn't need the keep-alive notification to exist
            // right this instant — it'll simply retry the next time this is called (e.g. the next
            // tab open, or the user returning to a Settings toggle) — so swallowing this specific
            // failure is safe, unlike letting it crash the whole app over a notification.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            }
        } else {
            stopService(intent)
        }
    }

    /** Without this, the foreground service still runs (it doesn't need the permission to keep
     *  the process alive) but its notification never shows on API 33+, so there's no visible
     *  sign the keep-alive is actually active. Asked for right when it's first actually needed
     *  (the first session starting), not upfront at launch. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), NOTIFICATION_PERMISSION_REQUEST_CODE)
        }
    }

    private fun updateStorageBanner() {
        grantStorageButton.visibility = if (StorageAccess.isGranted(this)) View.GONE else View.VISIBLE
    }

    private fun refreshStorageRow() {
        // storageStatusText only exists once the Backup & Storage settings category has been
        // opened at least once (categories build their views lazily on first visit) — nothing
        // to refresh yet if it hasn't.
        if (!::storageStatusText.isInitialized) return
        storageStatusText.text = if (StorageAccess.isGranted(this)) {
            "Granted — /sdcard is available in new sessions."
        } else {
            "Not granted — sessions won't see /sdcard until you grant this, then open a new session."
        }
    }

    /**
     * With decorFitsSystemWindows off, nothing pads or resizes anything for the status bar, the
     * navigation bar, or the soft keyboard automatically — this used to happen for free. System-bar
     * space becomes real padding on rootFrame (so content never sits under the notch/nav bar).
     *
     * The keyboard's own inset drives two things, and — this is the important part — neither of
     * them is a resize of terminalContainer or the terminal's row/col count:
     *
     * 1. extraKeysScroll.translationY, floating it up above the keyboard. A transform, not a
     *    margin: extraKeysScroll stays a plain LinearLayout child right after terminalContainer
     *    in the XML, always reserving its own (keyboard-independent) height out of
     *    terminalContainer's share, same as before the keyboard ever enters the picture — a
     *    margin driven by the keyboard's height would shrink terminalContainer for the keyboard
     *    again, exactly the mistake this whole rewrite undoes. translationY moves it purely
     *    visually, with no effect on layout or on any sibling's size.
     * 2. terminalView.imeCoverPx, a pure render-time value — see TerminalView for what it does.
     *
     * This app went through several rounds of "lines disappear on keyboard toggle" believing the
     * cause was in how resizePrimaryScreen() shuffled rows to/from scrollback across a real
     * resize, and fixed real bugs there — but a diagnostic capture eventually caught the actual
     * root cause: a real resize means a real SIGWINCH to the shell, and most shells react to
     * *every* SIGWINCH (needed or not) by moving their cursor and reprinting the prompt in place
     * (`ESC[1A` followed by a full redraw was what Alpine's ash did in the capture). On a tab with
     * real scrollback, that unconditional cursor-up drags the visible prompt up by one row on
     * every single keyboard toggle, compounding over repeated toggles into exactly "content
     * disappearing" — a shell-side reaction our own resize math was never going to be able to
     * out-think, however carefully it preserved rows on its own side.
     *
     * The actual fix is upstream of all that: never send the shell a resize for the keyboard in
     * the first place. terminalContainer's size — and therefore the emulator's row/col count and
     * the PTY's own winsize — now stays completely fixed across every keyboard show/hide; only a
     * real rotation or a deliberate font-size change still resizes it. What used to be solved by
     * resizing (keeping the cursor visible above the keyboard rather than letting it hide behind
     * it) is now solved by TerminalView shifting its own rendering up by just enough to keep the
     * cursor in view — see imeCoverPx/renderShiftPx there — which was the one problem that made
     * an earlier, similar "just don't resize" attempt get abandoned for the resize-based approach
     * this replaces.
     */
    private fun applyInsetsManually() {
        ViewCompat.setOnApplyWindowInsetsListener(rootFrame) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            // ime().bottom is measured from the true screen edge, including whatever the
            // navigation bar itself occupies — but rootFrame's own bottom padding (just above,
            // from systemBars()) already pushed all of this content up above the nav bar. Without
            // subtracting the nav bar's own height back out, everything below floats the nav
            // bar's height too far above the keyboard, opening a visible gap between the extra
            // keys row and the keyboard (and an equal gap of true blank terminal above it).
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val imeOverlap = (imeBottom - navBarBottom).coerceAtLeast(0)
            extraKeysScroll.translationY = -imeOverlap.toFloat()
            // Floating extraKeysScroll up by imeOverlap lands its bottom edge exactly at the
            // visible boundary (terminalContainer's own bottom minus imeOverlap) — the row's own
            // height doesn't add to how much of the terminal's content is actually obstructed,
            // it cancels out (the row eats into the last imeOverlap-tall band of the terminal at
            // the same rate the keyboard gives that band back at its own bottom edge). imeOverlap
            // alone is the right amount to clear both the keyboard and the floated row.
            terminalView.imeCoverPx = imeOverlap
            insets
        }
        rootFrame.requestApplyInsets()
    }

    private fun onGridSize(rows: Int, cols: Int) {
        lastRows = rows
        lastCols = cols
        if (!gridKnown) {
            gridKnown = true
            if (tabs.isNotEmpty()) {
                // Sessions already running — this Activity instance was (re)created while the
                // process, and every tab's reader thread, kept going underneath it (see
                // AlpineTermApp.tabs and the isFinishing guard in onDestroy). Re-attach to them
                // instead of asking to "resume": nothing was actually lost.
                setupContainer.visibility = View.GONE
                rebindTabOutputs()
                switchToTab(activeTabIndex.coerceIn(0, tabs.size - 1))
                // A widget tap that arrives as a normal launch Intent (the process was killed and
                // this is a fresh Activity, not an already-running one reusing itself via
                // onNewIntent()'s singleTop path) used to be silently ignored — only onNewIntent()
                // ever checked for ACTION_NEW_SESSION, so "already running in the background,
                // Activity recreated" was the one case a widget tap did nothing at all.
                if (intent.action == AlpineWidgetProvider.ACTION_NEW_SESSION) addTab()
            } else {
                maybeOfferSessionResume() // first tab(s), once the view actually knows how big it is
            }
        } else {
            tabs.getOrNull(activeTabIndex)?.session?.resize(rows, cols)
        }
    }

    /**
     * Only meaningful right after Android has fully killed and relaunched the process — a saved
     * count of more than one tab is the signal, since a normal single-tab session isn't worth
     * asking about. Deliberately doesn't try to restore working directory or history: neither
     * survives the actual shell process dying, so promising more than "the same number of named
     * tabs, freshly started" would be a lie the user discovers the first time they cd somewhere.
     */
    private fun maybeOfferSessionResume() {
        val labels = SessionPersistence.load(this)
        if (labels.size <= 1) {
            addTab(labels.firstOrNull())
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Resume previous sessions?")
            .setMessage(
                "You had ${labels.size} sessions open before AlpDroid was closed " +
                    "(${labels.joinToString(", ") { it ?: "unnamed" }}). Reopen that many now? " +
                    "These start fresh shells — working directory and history from before aren't restored.",
            )
            .setPositiveButton("Resume all") { _, _ -> resumeSequentially(labels, 0) }
            .setNegativeButton("Just one") { _, _ -> addTab() }
            // Back or a tap outside used to just dismiss this with neither button ever pressed —
            // leaving no tab open at all and the setup screen's spinner running with nothing
            // actually happening behind it, no way forward except force-closing and reopening.
            .setCancelable(false)
            .show()
    }

    /** Firing addTab() once per saved label all at once would queue every download/setup step
     *  onto the same background executor together, with each tab's status text overwriting the
     *  last on the shared setup screen — confusing to watch even though nothing would actually
     *  be wrong underneath. Starting the next one only once the previous tab has actually been
     *  added keeps the setup screen showing one coherent story at a time. */
    private fun resumeSequentially(labels: List<String?>, index: Int) {
        if (index >= labels.size) return
        // A failed tab no longer aborts the chain silently: the remaining labels still
        // resume, and the Retry button from the failed attempt stays available.
        val next = { resumeSequentially(labels, index + 1) }
        addTab(labels[index], onStarted = { next() }, onFailed = {
            android.widget.Toast.makeText(this, "Couldn't reopen \"" + (labels[index] ?: "unnamed") + "\" \u2014 continuing with the rest", android.widget.Toast.LENGTH_SHORT).show()
            next()
        })
    }

    private fun persistTabLabels() {
        SessionPersistence.save(this, tabs.map { it.label })
        rebuildSessionsList()
    }

    /**
     * [onFailed] fires (once) whenever this attempt ends without a tab — setup failure or
     * shell-start failure — alongside the usual Retry UI. Lets chained callers (session
     * resume) keep going instead of stalling silently on one bad tab. */
    private fun addTab(initialLabel: String? = null, onStarted: (() -> Unit)? = null, onFailed: (() -> Unit)? = null, directCommand: String? = null) {
        // Balanced by exactly one decrement wherever this particular attempt's flow actually
        // ends: startSessionNow()'s completion (success or failure) below, or showSetupFailure()
        // if ensureReady() itself fails first. Covers every path that can ever call addTab() — a
        // plain "+", the widget, a saved-session resume adding several tabs in a row, and restart/
        // reinstall/restore's own manual pre-increment (see those call sites) handing off to this
        // one once addTab() actually runs.
        pendingSessionStarts++
        val id = nextTabId++
        // A still-running fadeOutSetup() from a previous tab's successful start (resumeSequentially
        // calling addTab() again immediately after the previous one) would otherwise finish this
        // setup screen's very first frame by setting it GONE out from under it, hiding the status
        // text — and the Retry button, if this attempt then fails — until whatever's animating
        // happens to invalidate the view again.
        setupContainer.animate().cancel()
        setupContainer.alpha = 1f
        setupContainer.visibility = View.VISIBLE
        setupProgress.visibility = View.VISIBLE
        setupProgress.isIndeterminate = true
        grantStorageButton.visibility = View.GONE
        startTerminalButton.visibility = View.GONE
        retryButton.visibility = View.GONE
        setupStatus.text = AlpineRootfs.lastStatus.ifEmpty { "Setting up Alpine…" }

        val statusPoller = object : Runnable {
            override fun run() {
                val status = AlpineRootfs.lastStatus.ifEmpty { "Setting up Alpine…" }
                val total = AlpineRootfs.totalBytes
                if (total > 0) {
                    val percent = ((AlpineRootfs.downloadedBytes * 100) / total).toInt().coerceIn(0, 100)
                    setupProgress.isIndeterminate = false
                    setupProgress.progress = percent
                    setupStatus.text = "$status ($percent%)"
                } else {
                    setupProgress.isIndeterminate = true
                    setupStatus.text = status
                }
                mainHandler.postDelayed(this, 200)
            }
        }
        mainHandler.post(statusPoller)

        val app = application as AlpineTermApp
        app.backgroundExecutor.execute {
            // Distinguishing "was already ready" from "just finished downloading" is what lets
            // repeat tab opens skip straight to the shell (isReady() is then an instant no-op)
            // while a genuine first-ever run pauses to let the user grant storage before the
            // proot bind-mount decision below gets made — that decision can't be changed for an
            // already-running session, only for the next one.
            val wasReadyBefore = AlpineRootfs.isReady(this)
            val rootfsReady = AlpineRootfs.ensureReady(this)
            mainHandler.post {
                mainHandler.removeCallbacks(statusPoller)
                setupProgress.isIndeterminate = true
                when {
                    // A download/extraction failure (lost connection, interrupted transfer, etc.)
                    // must never silently drop into the system-shell fallback — that fallback
                    // exists for devices that genuinely can't run Alpine at all (unsupported CPU
                    // arch, proot missing), not for "the network hiccuped." Stopping here and
                    // making the user explicitly retry is what guarantees a session only ever
                    // starts once Alpine is actually installed and verified.
                    !rootfsReady -> showSetupFailure(initialLabel, onStarted, onFailed)
                    !wasReadyBefore && !StorageAccess.isGranted(this) -> showReadyGate(id, initialLabel, onStarted, onFailed, directCommand)
                    else -> startSessionNow(id, initialLabel, onStarted, onFailed, directCommand)
                }
            }
        }
    }

    /**
     * Shown once, only on a genuine first-ever setup, and only if storage isn't already
     * granted — the download normally finishes fast enough that there was never a real chance
     * to tap "Grant shared-storage access" on the old auto-continuing setup screen before it
     * faded into the terminal. Pausing here for an explicit "Start terminal" tap is what
     * actually gives that button a moment to be seen and used, not just flash past.
     */
    /** Alpine's download/extraction failed (network loss, interrupted transfer, unsupported CPU
     *  arch, etc.) — stop and make the user explicitly retry rather than ever silently starting a
     *  degraded system-shell session instead of the Alpine one they asked for. */
    private fun showSetupFailure(initialLabel: String? = null, onStarted: (() -> Unit)? = null, onFailed: (() -> Unit)? = null) {
        // This addTab() attempt's flow ends right here (never reaches startSessionNow(), which is
        // the only other place this decrements) — Retry below calls addTab() fresh, with its own
        // new increment/decrement pair, so this one must close out now or it'd leak upward by one
        // every time setup fails.
        pendingSessionStarts--
        val reason = AlpineRootfs.lastFailure.ifEmpty { "unknown error" }
        setupStatus.text = "Alpine setup failed: $reason\n\nCheck your internet connection and try again."
        setupProgress.visibility = View.GONE
        retryButton.visibility = View.VISIBLE
        retryButton.setOnClickListener { addTab(initialLabel, onStarted, onFailed) }
        onFailed?.invoke()
    }

    private fun showReadyGate(id: Int, initialLabel: String? = null, onStarted: (() -> Unit)? = null, onFailed: (() -> Unit)? = null, directCommand: String? = null) {
        setupStatus.text = "Alpine is ready. Grant shared storage now so it's available in the shell (or skip — you can grant it later from Settings)."
        grantStorageButton.visibility = View.VISIBLE
        startTerminalButton.visibility = View.VISIBLE
        startTerminalButton.setOnClickListener {
            startTerminalButton.visibility = View.GONE
            grantStorageButton.visibility = View.GONE
            startSessionNow(id, initialLabel, onStarted, onFailed, directCommand)
        }
    }

    private fun startSessionNow(id: Int, initialLabel: String? = null, onStarted: (() -> Unit)? = null, onFailed: (() -> Unit)? = null, directCommand: String? = null) {
        setupStatus.text = "Starting shell…"
        val app = application as AlpineTermApp
        app.backgroundExecutor.execute {
            val result = runCatching { AlpineSession.start(this, lastRows, lastCols, id, directCommand) }
            mainHandler.post {
                // Decremented here (success or failure) rather than at the top of addTab() —
                // addTab() can run synchronously right after the old tab's destroy() call, well
                // before that tab's own reader thread notices EOF and calls onTabExited();
                // decrementing any earlier would leave the guard already off by the time
                // onTabExited() actually needs it.
                pendingSessionStarts--
                result.onSuccess { started ->
                    val emulator = TerminalEmulator(
                        lastRows, lastCols,
                        respond = { text -> writeToSession(started.session, text) },
                    )
                    val tab = TerminalTab(id, started.session, emulator, started.backendLabel, initialLabel)
                    if (!tab.backendLabel.startsWith("Alpine")) {
                        val banner = "[AlpDroid] backend: ${tab.backendLabel}\r\n".toByteArray(Charsets.UTF_8)
                        emulator.feed(banner, banner.size)
                    }
                    tabs.add(tab)
                    tab.onOutput = { if (tabs.getOrNull(activeTabIndex) === tab) terminalView.onPtyOutput() }
                    tab.onExit = { onTabExited(tab) }
                    emulator.onBell = { onTerminalBell(tab.id) }
                    persistTabLabels()
                    startReaderThread(tab)
                    fadeOutSetup()
                    switchToTab(tabs.size - 1)
                    updateKeepAliveService()
                    onStarted?.invoke()
                }.onFailure { e ->
                    // Previously left the user stuck here with no way forward but to leave the
                    // app and come back — ensureReady() succeeding but the shell itself then
                    // failing to start (a proot launch error, the pty bridge missing) is rare but
                    // not impossible, and it deserves the same Retry affordance as an ensureReady()
                    // failure already gets in showSetupFailure().
                    setupStatus.text = "Failed to start a shell: ${e.message}"
                    setupProgress.visibility = View.GONE
                    retryButton.visibility = View.VISIBLE
                    retryButton.setOnClickListener { addTab(initialLabel, onStarted, onFailed, directCommand) }
                    onFailed?.invoke()
                }
            }
        }
    }

    private fun startReaderThread(tab: TerminalTab) {
        Thread({
            val buf = ByteArray(32 * 1024)
            // Wrapping the whole loop, not just the read() above — any *other* uncaught exception
            // here (a bug in TerminalEmulator.feed() processing some byte sequence, in particular)
            // would otherwise kill this thread right there, skipping the mainHandler.post below
            // entirely: no exception was ever visibly thrown at the user, nothing crashed, but the
            // tab's shell had, from that moment on, no way left to ever report its own exit — every
            // future EOF on this same pty would go completely unnoticed. From the outside that's
            // indistinguishable from "the tab hangs and never closes," permanently, for the rest of
            // this process's life, over a bug that has nothing to do with why the shell actually
            // exited. Catching here guarantees onExit still fires — and the tab still gets torn
            // down — no matter what actually went wrong feeding this thread's own output through.
            try {
                while (true) {
                    val n = try { tab.session.stdout.read(buf) } catch (e: IOException) { -1 }
                    if (n <= 0) break
                    tab.emulator.feed(buf, n)
                    // Not a direct `terminalView.onPtyOutput()` closure: this thread outlives any one
                    // MainActivity instance, so it goes through the tab's own callback instead, kept
                    // pointed at whichever Activity (and TerminalView) currently exists — see
                    // TerminalTab.onOutput and rebindTabOutputs().
                    tab.onOutput?.invoke()
                }
            } catch (e: Exception) {
                Log.e("AlpDroid/Reader", "pty-reader-${tab.id} crashed; closing tab instead of hanging it", e)
            }
            mainHandler.post { tab.onExit?.invoke() }
        }, "pty-reader-${tab.id}").apply { isDaemon = true }.start()
    }

    /** Points every tab's output/exit/bell callbacks at *this* Activity instance — called once
     *  for a freshly started tab, and for every existing tab right after a fresh MainActivity
     *  re-attaches to sessions that were already running (see onCreate). Without re-binding
     *  onExit/onBell here too (onOutput alone used to be the only one), a shell that exited or
     *  rang its bell after the system destroyed and recreated the Activity (backgrounding, a
     *  config change — the process and its reader threads outlive that) would still call back
     *  into the dead instance: it would update a tab list and views nobody can see, and — if it
     *  was the last tab — call finish() on an Activity that was never actually going to be shown
     *  again anyway, while the live, current Activity's UI just sits frozen on the exited tab. */
    private fun rebindTabOutputs() {
        tabs.forEach { tab ->
            tab.onOutput = { if (tabs.getOrNull(activeTabIndex) === tab) terminalView.onPtyOutput() }
            tab.onExit = { onTabExited(tab) }
            tab.emulator.onBell = { onTerminalBell(tab.id) }
        }
    }

    /** A tab's shell exited (typed "exit"/Ctrl-D, or crashed) — drop it and switch to a
     *  neighbor; closing the very last tab closes the app, same as the old single-tab behavior. */
    private fun onTabExited(tab: TerminalTab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        // This path fires when the shell process exited on its own (typed "exit"/Ctrl-D) — the
        // pty itself is already gone, but nothing else was: destroy() is what actually joins the
        // resize thread, closes the control-fifo file descriptor, and deletes the fifo file on
        // disk. closeTab() (an explicit tab-close from the UI) already calls this; this path never
        // did, leaking one thread + one fd + one fifo file per tab a user ever typed "exit" in
        // instead of swiping it closed. Safe to call unconditionally — destroy() is idempotent.
        tab.session.destroy()
        // Captured before removal, by identity rather than index — the exited tab isn't
        // necessarily the active one (a background tab can exit on its own too), and re-deriving
        // "the next active tab" from the EXITED tab's own index used to switch the user away from
        // whatever they were actually looking at whenever a *different*, background tab happened
        // to exit at the same time.
        val wasActive = idx == activeTabIndex
        val activeTab = tabs.getOrNull(activeTabIndex)
        tabs.removeAt(idx)
        if (tabs.isEmpty()) {
            // A restart/reinstall/restore/another still-in-flight addTab() is already expected to
            // add a replacement — never auto-finish() here just because some session's own exit
            // made tabs momentarily empty in between.
            if (pendingSessionStarts > 0) return
            // Every tab exited deliberately (typed "exit"/Ctrl-D) rather than the process being
            // killed out from under them — nothing to offer resuming next launch.
            SessionPersistence.clear(this)
            updateKeepAliveService()
            deliberateExit = true
            // Re-checked: a new tab started in the 400ms window (widget, bridge, fast tap)
            // used to be killed along with the exit it replaced.
            mainHandler.postDelayed({ if (tabs.isEmpty() && pendingSessionStarts == 0) finish() }, 400)
            return
        }
        persistTabLabels()
        // The keep-alive notification's session count (see TerminalKeepAliveService) is only ever
        // refreshed by re-posting it here and at the other call sites below — without this, closing
        // one tab out of several left the notification showing the count from before the close
        // until something else happened to touch it (adding another tab, the app restarting).
        updateKeepAliveService()
        if (wasActive) {
            switchToTab(idx.coerceAtMost(tabs.size - 1))
        } else {
            // The tab the user was actually looking at is still right there, unchanged — only its
            // index shifted from the removal, so just fix that up and redraw the tab bar's
            // numbering, without switchToTab()'s resize/resetViewState() (which would needlessly
            // disturb the active tab's scroll position/selection for a tab switch that never
            // actually happened).
            val newActiveIndex = tabs.indexOf(activeTab)
            activeTabIndex = if (newActiveIndex >= 0) newActiveIndex else idx.coerceAtMost(tabs.size - 1)
            rebuildTabBar()
        }
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        activeTabIndex = index
        val tab = tabs[index]
        // The newly-active tab may have gone stale in size while backgrounded (a pinch-zoom or
        // rotation only resizes whichever emulator is currently attached to the view).
        tab.session.resize(lastRows, lastCols)
        tab.emulator.resize(lastRows, lastCols)
        // scrollOffset and any active text selection are TerminalView's own state, not the
        // emulator's — swapping emulator alone would leave Tab B rendered from wherever Tab A's
        // scrollback view happened to be scrolled to (and possibly still show Tab A's selection).
        terminalView.resetViewState()
        terminalView.emulator = tab.emulator
        rebuildTabBar()
        rebuildSessionsList()
    }

    /** Closing a tab (its own or another's) just kills that session — [onTabExited], on that
     *  tab's own reader thread, is what actually removes it and picks the next active tab. */
    private fun closeTab(index: Int) {
        tabs.getOrNull(index)?.session?.destroy()
    }

    private fun showRenameTabDialog(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        val input = android.widget.EditText(this).apply {
            hint = "Session name"
            setText(tab.label.orEmpty())
            setSelection(text.length)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Rename session ${index + 1}")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                tab.label = input.text.toString().trim().ifEmpty { null }
                persistTabLabels()
                rebuildTabBar()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** "Save to file" from the selection context menu — for shuttling a long agent run's output
     *  or a diff off the device without piping through `tee` first. */
    private fun saveSelectionToFile(text: String) {
        val base = if (StorageAccess.isGranted(this)) StorageAccess.sharedStorageRoot() else getExternalFilesDir(null) ?: filesDir
        val dir = File(base, "AlpDroidExports").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val dest = File(dir, "selection-$stamp.txt")
        runCatching { dest.writeText(text) }
            .onSuccess { android.widget.Toast.makeText(this, "Saved to ${dest.absolutePath}", android.widget.Toast.LENGTH_LONG).show() }
            .onFailure { android.widget.Toast.makeText(this, "Save failed: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
    }

    /** Alpine runs under `proot`, which doesn't isolate the network namespace — a dev server
     *  bound inside a session is already reachable at Android's own 127.0.0.1, no forwarding
     *  needed. This just saves typing the URL by hand. */
    private fun openLocalPort(port: Int) {
        drawerLayout.closeDrawer(GravityCompat.END)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$port"))) }
            .onFailure { android.widget.Toast.makeText(this, "No browser available", android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun writeToActiveSession(bytes: ByteArray) {
        tabs.getOrNull(activeTabIndex)?.let { writeToSession(it.session, bytes) }
    }

    /** For every one-tap shortcut that injects a whole command (package installs, "Update package
     *  index", SSH quick-connect/reconnect, custom shortcuts) — prefixes it with Ctrl+E (move to
     *  end of line) then Ctrl+U (kill line before cursor), both honored by ash/bash/etc., so
     *  whatever the user already had typed but not yet submitted at the prompt is cleared first
     *  regardless of where their cursor happened to be left. Ctrl+U alone only kills text *before*
     *  the cursor — if the cursor had been moved left, whatever came after it would still get
     *  appended onto the injected command. Also drops any armed CTRL/ALT extra-key modifier: this
     *  bypasses sendControlAware() (a literal command string, not a control character), which is
     *  the one thing that otherwise clears them — left armed, the *next* key the user types
     *  anywhere would wrongly get treated as that modifier's target instead of a plain keystroke,
     *  exactly what sendControlAware() exists to prevent for every other extra key. */
    private fun runShortcutCommand(cmd: String) {
        tabs.getOrNull(activeTabIndex)?.let { runShortcutCommand(it.session, cmd) }
    }

    private fun runShortcutCommand(session: PtySession, cmd: String) {
        terminalView.ctrlArmed = false
        terminalView.altArmed = false
        writeToSession(session, byteArrayOf(0x05, 0x15) + cmd.toByteArray())
    }

    /** Types [cmd] the moment the tab's shell first produces output. Writing straight into a
     *  just-spawned session loses bytes: proot takes seconds to start on a phone and master
     *  writes with no slave open yet fail instead of queueing — the tab opened with nothing
     *  typed. First output proves the shell is alive and reading. */
    private fun runShortcutCommandOnReady(cmd: String) {
        val tab = tabs.getOrNull(activeTabIndex) ?: return
        val prev = tab.onOutput
        var sent = false
        tab.onOutput = {
            prev?.invoke()
            if (!sent) {
                sent = true
                tab.onOutput = prev
                runShortcutCommand(tab.session, cmd)
            }
        }
    }

    private fun writeToSession(session: PtySession, bytes: ByteArray) {
        session.writeAsync(bytes)
    }

    private fun writeToSession(session: PtySession, text: String) = writeToSession(session, text.toByteArray(Charsets.UTF_8))

    private fun rebuildTabBar() {
        tabBar.removeAllViews()
        tabs.forEachIndexed { index, tab -> tabBar.addView(buildTabButton(index, tab)) }
        tabBar.addView(buildAddTabButton())
    }

    // --- Session manager (left drawer) --------------------------------------------------------

    /** Switches the left drawer's file browser over to the session list — a sibling view sharing
     *  the same drawer rather than a separate screen, same reasoning FileBrowserPanel's own class
     *  doc gives for embedding the file browser directly instead of linking out to one. */
    private fun showSessionsMode() {
        fbUpButton.visibility = View.GONE
        fbPathText.visibility = View.GONE
        fbFileList.visibility = View.GONE
        fbSessionsContainer.visibility = View.VISIBLE
        rebuildSessionsList()
    }

    private fun hideSessionsMode() {
        fbUpButton.visibility = View.VISIBLE
        fbPathText.visibility = View.VISIBLE
        fbFileList.visibility = View.VISIBLE
        fbSessionsContainer.visibility = View.GONE
    }

    /** Rebuilt on every tab add/remove/rename/switch (see persistTabLabels() and switchToTab()) —
     *  cheap enough for the handful of tabs a session list actually has, and far simpler than
     *  diffing a ListView adapter for something this size (the same reasoning the Settings
     *  panel's own SSH profile list already uses). A no-op while the session list isn't the
     *  visible drawer content, so tab activity elsewhere doesn't do pointless layout work. */
    private fun rebuildSessionsList() {
        if (fbSessionsContainer.visibility != View.VISIBLE) return
        fbSessionsList.removeAllViews()
        tabs.forEachIndexed { index, tab ->
            val active = index == activeTabIndex
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(if (active) ContextCompat.getColor(this@MainActivity, R.color.accent_wash) else Color.TRANSPARENT)
                }
                setPadding(dp(10), dp(4), dp(4), dp(4))
                setOnClickListener {
                    switchToTab(index)
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
            }
            row.addView(
                iconTile(R.drawable.ic_description, sizeDp = 36, iconSizeDp = 18, tint = ContextCompat.getColor(this, if (active) R.color.accent else R.color.text_dim)),
                LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) },
            )
            row.addView(
                TextView(this).apply {
                    text = tab.label?.let { "${index + 1}: $it" } ?: "Session ${index + 1}"
                    setTextColor(if (active) ContextCompat.getColor(this@MainActivity, R.color.accent) else ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            row.addView(
                ImageButton(this).apply {
                    setImageResource(R.drawable.ic_more_vert)
                    imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
                    background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setOnClickListener { showSessionRowMenu(tab.id, this) }
                },
            )
            fbSessionsList.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2) })
        }
    }

    private fun showSessionRowMenu(tabId: Int, anchor: View) {
        android.widget.PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Rename")
            menu.add(0, 2, 1, "Close")
            setOnMenuItemClickListener { item ->
                // Resolve at click time: the row was built earlier, indices may have shifted.
                val index = tabs.indexOfFirst { it.id == tabId }
                if (index < 0) return@setOnMenuItemClickListener true
                when (item.itemId) {
                    1 -> showRenameTabDialog(index)
                    2 -> closeTab(index)
                }
                true
            }
            show()
        }
    }

    private fun buildTabButton(index: Int, tab: TerminalTab): View {
        val active = index == activeTabIndex
        // Resolve by stable id at gesture time: a background tab exit re-indexes `tabs`, so a
        // pill built earlier would otherwise close/switch/rename the wrong session.
        val tabId = tab.id
        fun liveIndex(): Int? = tabs.indexOfFirst { it.id == tabId }.takeIf { it >= 0 }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(if (active) 0x243ED0B8 else Color.TRANSPARENT)
                if (active) setStroke(dp(1), 0x593ED0B8)
            }
            setPadding(dp(16), dp(7), dp(16), dp(7))
        }
        val label = TextView(this).apply {
            text = tab.label?.let { "${index + 1}: $it" } ?: "${index + 1}"
            setTextColor(if (active) 0xFF3ED0B8.toInt() else 0xFF8B93A1.toInt())
            typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textSize = 13f
        }
        // A single manual touch state machine rather than separate click/long-click listeners:
        // the tab bar is itself a HorizontalScrollView, so a *horizontal* swipe-to-close would
        // fight that view's own horizontal scroll gesture — this uses a vertical swipe-up
        // instead (tap to switch, long-press for the menu, swipe up to close), and a
        // GestureDetector's onTouchEvent() returning true would swallow every event and break
        // plain taps, so this tracks the gesture by hand instead.
        //
        // Attached to the whole pill (container), not just the text label — the label is only
        // as wide as its own text (e.g. a single "1"), while the visible/tappable-looking pill
        // is that plus 16dp of padding on each side. A listener on the label alone left most of
        // the pill's own surface completely unresponsive, which is exactly what made switching
        // tabs feel like it "required many taps": most taps were landing in that dead padding.
        container.isClickable = true
        container.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var longPressFired = false
            private val longPressRunnable = Runnable {
                longPressFired = true
                container.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                liveIndex()?.let { showTabMenu(it, container) }
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY
                        longPressFired = false
                        mainHandler.postDelayed(longPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(event.rawX - downX) > dp(16) || kotlin.math.abs(event.rawY - downY) > dp(16)) {
                            mainHandler.removeCallbacks(longPressRunnable)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        mainHandler.removeCallbacks(longPressRunnable)
                        if (!longPressFired) {
                            val dx = event.rawX - downX
                            val dy = event.rawY - downY
                            when {
                                dy < -dp(40) && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f -> liveIndex()?.let { closeTab(it) }
                                kotlin.math.abs(dx) < dp(16) && kotlin.math.abs(dy) < dp(16) -> liveIndex()?.let { switchToTab(it) }
                            }
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> mainHandler.removeCallbacks(longPressRunnable)
                }
                return true
            }
        })
        container.addView(label)
        return container
    }

    /** Scrollback + screen as a plain .txt in the same folder backups use — for pasting an agent's
     *  output somewhere else without fighting long-press selection across thousands of lines. */
    private fun saveTabOutput(tab: TerminalTab) {
        // fullText() walks ~2000 scrollback rows into a ~400KB+ string — built here on the
        // background thread, never on the UI thread that calls this from the tab menu.
        (application as AlpineTermApp).backgroundExecutor.execute {
            val text = tab.emulator.fullText()
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val dest = File(AlpineBackup.backupsDir(this), "alpineterm-output-$stamp.txt")
            val ok = runCatching { dest.writeText(text) }.isSuccess
            mainHandler.post {
                android.widget.Toast.makeText(
                    this,
                    if (ok) "Saved: ${dest.absolutePath}" else "Couldn't save output",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun showTabMenu(index: Int, anchor: View) {
        val tab = tabs.getOrNull(index)
        val tabId = tab?.id
        android.widget.PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "New session")
            menu.add(0, 3, 1, "Rename")
            // Only offered once this tab has actually used an SSH quick-connect profile — a
            // dropped connection (the network flaking, a phone sleeping) drops back to the local
            // shell prompt rather than closing the tab, so re-running the same ssh command is
            // exactly "reconnect" without needing to go find the profile again in Settings.
            if (tab?.lastSshCommand != null) menu.add(0, 4, 2, "Reconnect SSH")
            menu.add(0, 5, 3, "Save output to file")
            menu.add(0, 2, 4, "Close")
            setOnMenuItemClickListener { item ->
                // Resolve by id at click time: a background exit while the menu is open shifts indices.
                val liveTab = tabId?.let { id -> tabs.getOrNull(tabs.indexOfFirst { it.id == id }) }
                when (item.itemId) {
                    1 -> addTab()
                    2 -> liveTab?.let { tabs.indexOfFirst { t -> t.id == it.id }.takeIf { i -> i >= 0 }?.let { closeTab(it) } }
                    5 -> liveTab?.let { saveTabOutput(it) }
                    3 -> liveTab?.let { tabs.indexOfFirst { t -> t.id == it.id }.takeIf { i -> i >= 0 }?.let { showRenameTabDialog(it) } }
                    4 -> liveTab?.lastSshCommand?.let { runShortcutCommand(liveTab.session, it) }
                }
                true
            }
            show()
        }
    }

    private fun buildAddTabButton(): View = FrameLayout(this).apply {
        val size = dp(34)
        layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(4) }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(this@MainActivity, R.color.panel_2))
        }
        addView(
            ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_add)
                imageTintList = ColorStateList.valueOf(0xFF3ED0B8.toInt())
                layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
            },
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { addTab() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildExtraKeysRow(row: LinearLayout) {
        row.removeAllViews()
        fun keyBackground() = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(0xFF1D232C.toInt())
        }
        fun addKey(label: String, action: () -> Unit) {
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFE7ECEF.toInt())
                background = keyBackground()
                minWidth = dp(38)
                minHeight = dp(38)
                setPadding(dp(12), 0, dp(12), 0)
                // A plain Button is focusable by default, and tapping one moves Android's view
                // focus onto it — away from terminalView, which owns the IME connection. That
                // focus change can make the soft keyboard briefly detach and reattach, which
                // means every tap of an arrow/ESC/TAB/etc. button here could itself trigger a
                // spurious keyboard hide/show (and the resize that goes with it) despite doing
                // nothing to the actual view layout — exactly the "arrow key eats a line" report
                // that had nothing to do with any real resize the user asked for. Never letting
                // these buttons take focus in the first place removes that path entirely.
                isFocusable = false
                isFocusableInTouchMode = false
                setOnClickListener { action() }
            }
            row.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(6) })
        }
        // CTRL/ALT are momentary modifiers, not plain actions — addKey() above always ran its
        // action and never reflected any state, so tapping CTRL always (re-)armed it with no
        // visual sign it had, and tapping it again did nothing (still just set the same field to
        // true) rather than canceling it. This toggles the armed flag and keeps the button's own
        // color in sync with it via refresh(), which TerminalView.onModifierStateChanged also
        // calls — covering the OTHER way the armed state changes: sendControlAware()/onKeyDown()
        // clearing it once the next keystroke actually consumes it.
        val modifierRefreshers = mutableListOf<() -> Unit>()
        fun addToggleKey(label: String, isArmed: () -> Boolean, toggle: () -> Unit) {
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                minWidth = dp(38)
                minHeight = dp(38)
                setPadding(dp(12), 0, dp(12), 0)
                isFocusable = false
                isFocusableInTouchMode = false
            }
            fun refresh() {
                val armed = isArmed()
                button.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(if (armed) ContextCompat.getColor(this@MainActivity, R.color.accent_wash) else 0xFF1D232C.toInt())
                }
                button.setTextColor(if (armed) ContextCompat.getColor(this@MainActivity, R.color.accent) else 0xFFE7ECEF.toInt())
            }
            button.setOnClickListener { toggle(); refresh() }
            refresh()
            modifierRefreshers += ::refresh
            row.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(6) })
        }
        // Same non-focusable requirement as addKey() above, for the icon-only keys (search,
        // arrows) — a FrameLayout+ImageView rather than a real ImageButton so that requirement
        // is enforced the identical way instead of relying on ImageButton's own focus defaults.
        fun addIconKey(iconRes: Int, tint: Int = 0xFFE7ECEF.toInt(), action: () -> Unit) {
            val button = FrameLayout(this).apply {
                background = keyBackground()
                isClickable = true
                isFocusable = false
                isFocusableInTouchMode = false
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(iconRes)
                        imageTintList = ColorStateList.valueOf(tint)
                        layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
                    },
                )
                setOnClickListener { action() }
            }
            row.addView(button, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(6) })
        }
        // sendControlAware() rather than send()/sendText() directly for every one of these:
        // otherwise, tapping CTRL then any button OTHER than a soft-keyboard letter (which alone
        // used to check the armed state) fired that button completely normally and left CTRL
        // still armed afterward, so it would then wrongly apply itself to whatever unrelated
        // letter got typed next instead of the key the user actually meant it for.
        // Once a running program turns on mouse click reporting (opencode and most other modern
        // TUIs do, for their whole UI including their own text input box), every tap on the
        // terminal reports as a click instead of opening the keyboard — correct for the parts of
        // the UI that are genuinely clickable, but with no other way to type at all. This key is
        // the explicit, always-works alternative: it isn't a tap on the terminal itself, so it
        // never gets reinterpreted as a click no matter what the running program has enabled.
        addIconKey(R.drawable.ic_keyboard) { terminalView.toggleKeyboard() }
        addIconKey(R.drawable.ic_search, 0xFF3ED0B8.toInt()) { toggleSearchBar() }
        addKey("ESC") { terminalView.sendControlAware(byteArrayOf(0x1B)) }
        addKey("TAB") { terminalView.sendControlAware(byteArrayOf(0x09)) }
        addToggleKey("CTRL", { terminalView.ctrlArmed }, { terminalView.ctrlArmed = !terminalView.ctrlArmed })
        addToggleKey("ALT", { terminalView.altArmed }, { terminalView.altArmed = !terminalView.altArmed })
        // Respect DECCKM like onKeyDown() and the alt-scroll fallback do: programs that
        // switched cursor keys to application mode (vim, fzf-style pickers) expect SS3.
        fun arrow(app: String, normal: String) = if (terminalView.emulator?.applicationCursorKeys == true) app else normal
        addIconKey(R.drawable.ic_arrow_up) { terminalView.sendControlAware(arrow("\u001BOA", "\u001B[A")) }
        addIconKey(R.drawable.ic_arrow_down) { terminalView.sendControlAware(arrow("\u001BOB", "\u001B[B")) }
        addIconKey(R.drawable.ic_arrow_left) { terminalView.sendControlAware(arrow("\u001BOD", "\u001B[D")) }
        addIconKey(R.drawable.ic_arrow_right) { terminalView.sendControlAware(arrow("\u001BOC", "\u001B[C")) }
        addKey("HOME") { terminalView.sendControlAware("\u001B[H") }
        addKey("END") { terminalView.sendControlAware("\u001B[F") }
        addKey("/") { terminalView.sendControlAware("/") }
        addKey("-") { terminalView.sendControlAware("-") }
        addKey("|") { terminalView.sendControlAware("|") }
        // User-defined shortcuts (Settings -> Display -> Custom shortcuts) — a literal command
        // string rather than a control character, so it goes straight to the active session
        // exactly like typing it, not through sendControlAware()'s CTRL/ALT-arming logic.
        settingsStore.customSnippets.forEach { (label, cmd) ->
            addKey(label) { runShortcutCommand(cmd) }
        }
        terminalView.onModifierStateChanged = { modifierRefreshers.forEach { it() } }
    }

    /**
     * Gesture-nav phones eat edge swipes as system Back before the drawers ever see them, so
     * edge-dragging a drawer open never works and every swipe just exits. Two-part fix, no
     * extra buttons anywhere:
     * 1. System-gesture exclusion strips on both vertical edges hand those swipes to
     *    DrawerLayout, which opens the drawer with its own native animation. 32dp matches
     *    roughly the drawer's own edge zone; the rest of the screen still backs normally.
     * 2. Predictive-back dispatcher (with enableOnBackInvokedCallback in the manifest):
     *    Back with a drawer open closes it (file browser goes up a level first), Back
     *    otherwise exits as before — same logic the old onBackPressed override had.
     */
    private fun setupDrawerGestures() {
        drawerLayout.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            // systemGestureExclusionRects is API 29+; older devices have button navigation
            // with no edge-swipe conflict, so there is nothing to exclude there.
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return@addOnLayoutChangeListener
            val strip = dp(32)
            val w = v.width
            val h = v.height
            if (w > 0 && h > 0) {
                v.systemGestureExclusionRects = listOf(
                    android.graphics.Rect(0, 0, strip, h),
                    android.graphics.Rect(w - strip, 0, w, h),
                )
            }
        }
        // Back behavior lives in the onBackPressed() override below (this Activity
        // deliberately avoids AppCompat, which owns OnBackPressedDispatcher) — kept next to
        // the gesture setup since they solve the same swipe-vs-exit problem together.
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        when {
            drawerLayout.isDrawerOpen(GravityCompat.END) -> drawerLayout.closeDrawer(GravityCompat.END)
            // Inside a subdirectory: back navigates up a level first, same as tapping the file
            // browser's own Up button — only closes the drawer once already at a root.
            drawerLayout.isDrawerOpen(GravityCompat.START) ->
                if (!fileBrowserPanel.onBackPressed()) drawerLayout.closeDrawer(GravityCompat.START)
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // A deliberate exit isn't a kill: zero the heartbeat so the next launch stays quiet.
        if (deliberateExit) settingsStore.lastAliveMs = 0L
        // Any onDestroy at all (swipe-away, Back, rotation) proves no kill happened —
        // kills run zero lifecycle. Read as "clean gone" on next launch.
        settingsStore.destroyWasClean = true
        runCatching { unregisterReceiver(deviceEventReceiver) }
        // One-shot plugin runs are owned by this Activity's panel: recreation must not
        // orphan their session + watchdog (the output view is gone either way).
        stopPluginRun()
        // Drop tab callbacks closing over this instance: the reader threads outlive it and
        // the tabs list (app-scoped) would otherwise pin the dead Activity until rebind.
        // Harmless across rotation — onCreate rebinds via rebindTabOutputs().
        (application as AlpineTermApp).tabs.forEach { it.onOutput = null; it.onExit = null }
        // Stop the GitHub device-flow poll promptly instead of delivering its result +
        // dialog.dismiss() to a destroyed instance (window leak on rotation).
        githubCancelled = true
        if (agentBridge.host === agentHost) agentBridge.host = null
        // Without this, a pending statusPoller tick or a delayed finish() from onTabExited could
        // still fire after the activity is gone and touch now-destroyed views.
        mainHandler.removeCallbacksAndMessages(null)
        fileBrowserPanel.shutdown()
        // isFinishing alone isn't the right guard here — it's also true when the *system*
        // destroys this Activity because the user swiped the app away from Recents, which is
        // very likely the exact thing being tested as "does the session persist" and does NOT by
        // itself kill this process or mean the shells should die (task removal doesn't stop a
        // foreground service on its own; that's the whole point of running one). Killing every
        // session and stopping the service on plain isFinishing used to undo the service's entire
        // purpose in exactly that case, and made it look like tabs "didn't persist" because this
        // method was the one destroying them a moment after the recreation flow even had a chance
        // to run. Only deliberateExit (set right before the finish() call above, once every tab's
        // shell has already exited on its own) tears anything down here; every other destruction —
        // task removal, the system reclaiming a backgrounded Activity's memory — leaves tabs
        // running for the next onCreate to re-attach to via rebindTabOutputs()/switchToTab().
        if (deliberateExit) {
            if (!(application as AlpineTermApp).pluginJobs.hasActive()) stopService(Intent(this, TerminalKeepAliveService::class.java))
            tabs.forEach { it.session.destroy() }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // The service is already running by the time this answers (it doesn't need the
        // permission to keep the process alive) — re-running updateKeepAliveService() just calls
        // startForeground() again on the same running service, which is what makes the
        // notification actually appear now that permission exists, without restarting anything.
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) updateKeepAliveService()
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) devicesPanel?.let { fillDevices(it) }
    }

    @Deprecated("SAF file pickers still use startActivityForResult (Activity, not ComponentActivity)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            BACKUP_CREATE_REQUEST_CODE -> doBackupToUri(uri)
            RESTORE_OPEN_REQUEST_CODE -> confirmRestoreUri(uri)
        }
    }

    /** User-chosen backup destination (Downloads, SD card, cloud provider): survives uninstall,
     *  unlike the app-folder backups which Android deletes with the app. */
    private fun backupAlpineToChosenFile() {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/gzip"
            putExtra(Intent.EXTRA_TITLE, "alpineterm-backup-$stamp.tar.gz")
        }
        runCatching { startActivityForResult(intent, BACKUP_CREATE_REQUEST_CODE) }
            .onFailure { android.widget.Toast.makeText(this, "No file picker found", android.widget.Toast.LENGTH_LONG).show() }
    }

    private fun restoreAlpineFromChosenFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/gzip", "application/x-gzip", "application/x-tar"))
        }
        runCatching { startActivityForResult(intent, RESTORE_OPEN_REQUEST_CODE) }
            .onFailure { android.widget.Toast.makeText(this, "No file picker found", android.widget.Toast.LENGTH_LONG).show() }
    }

    private fun uriDisplayName(uri: Uri, fallback: String): String {
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return fallback
    }

    // --- Backup / restore & settings export/import ------------------------------------------

    private fun backupAlpine() {
        val root = AlpineRootfs.rootDir(this)
        val destDir = AlpineBackup.backupsDir(this)
        val app = application as AlpineTermApp
        // Walking the whole rootfs to size it is itself not free for a large install — run it on
        // the background executor rather than blocking the tap that triggered this, same as every
        // other filesystem-touching action in this Activity already does.
        app.backgroundExecutor.execute {
            // A gzip'd tar of the rootfs is typically well under its uncompressed size, but
            // "typical" isn't a guarantee (already-compressed files — node_modules' own
            // .whl/.tar.gz caches, anything downloaded through the guest — barely shrink further)
            // — checked against the UNcompressed size instead of guessing a ratio, so this only
            // ever under-warns, never fails to warn about a backup that's actually going to run
            // out of room. Catches a full device up front with a clear message instead of a write
            // failing deep inside GZIPOutputStream partway through, which used to just look like
            // the truncated-archive bug fixed earlier — "backup failed" or a corrupt file, with
            // no indication *why* it failed at all.
            val available = runCatching { android.os.StatFs(destDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
            val estimatedSize = runCatching { dirSize(root) }.getOrDefault(0L)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (estimatedSize > 0 && available < estimatedSize) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Low storage")
                        .setMessage(
                            "This backup needs roughly ${FileOps.humanSize(estimatedSize)}, but only " +
                                "${FileOps.humanSize(available)} is free. It'll likely fail partway through. " +
                                "Free up space first, or continue anyway?",
                        )
                        .setPositiveButton("Continue anyway") { _, _ -> doBackupAlpine(root, destDir) }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    doBackupAlpine(root, destDir)
                }
            }
        }
    }

    /** Once per app start, well after the first tab has begun opening (so it doesn't compete for
     *  the single background executor with session startup). */
    private fun maybeAutoBackup() {
        if (!settingsStore.autoBackupEnabled || !AlpineRootfs.isReady(this)) return
        val week = 7L * 24 * 60 * 60 * 1000
        if (System.currentTimeMillis() - settingsStore.lastAutoBackupMs < week) return
        doBackupAlpine(AlpineRootfs.rootDir(this), AlpineBackup.backupsDir(this), keepLast = 3)
    }

    private fun dirSize(dir: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>().apply { add(dir) }
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
            if (f.isDirectory) f.listFiles()?.forEach { stack.add(it) } else total += f.length()
        }
        return total
    }

    private fun doBackupAlpine(root: File, destDir: File, keepLast: Int = 0) {
        val auto = keepLast > 0
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        // Automatic ones get their own prefix so the "keep the newest 3" cleanup can never touch a
        // backup the user made by hand.
        val dest = File(destDir, (if (auto) "alpineterm-auto-" else "alpineterm-backup-") + "$stamp.tar.gz")
        // Written under a name restoreAlpinePicker() doesn't recognize (it only lists ".tar.gz"),
        // then renamed to the real name only once the archive is fully, successfully written. A
        // backup that never gets to finish — an exception partway through, but just as easily the
        // whole process getting killed outright (backgrounded during a large rootfs backup, low
        // memory; nothing here holds foreground priority the way a live session does), which no
        // amount of runCatching/finally on this side can ever run cleanup for — used to leave
        // whatever partial bytes had been written sitting at the final .tar.gz name, i.e. exactly
        // what restore lists as a real backup and what a third-party archiver (reported: ZArchiver,
        // "broken pipe") chokes on decompressing. A half-written .part file is never mistaken for
        // one, killed process included, since the rename to the real name is the last thing that
        // happens and only after backup() already returned normally.
        val partial = File(destDir, "${dest.name}.part")
        android.widget.Toast.makeText(this, "Backing up Alpine to ${dest.name}…", android.widget.Toast.LENGTH_SHORT).show()
        val app = application as AlpineTermApp
        val notifId = OperationNotifications.newId()
        backupCancelled.set(false)
        runOnUiThread { backupCancelBtn?.visibility = View.VISIBLE }
        app.backupRestoreExecutor.execute {
            // Sweep up any .part left behind by a previous backup that got killed outright rather
            // than throwing — restoreAlpinePicker() already ignores these (it only lists
            // ".tar.gz"), but they'd otherwise sit in the backups folder forever.
            destDir.listFiles { f -> f.name.endsWith(".tar.gz.part") }?.forEach { it.delete() }
            // Total file count isn't known up front (that would mean walking the whole tree
            // twice), so this shows a running count rather than a percentage — still far more
            // useful than a plain "backing up" spinner for a large rootfs. Throttled to avoid
            // hammering the notification manager once per file in a tree with thousands of them.
            var lastUpdateMs = 0L
            OperationNotifications.progress(this, notifId, "Backing up Alpine", "Starting…")
            var cancelled = false
            val ok = runCatching {
                AlpineBackup.backup(root, partial, { count ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateMs >= 300) {
                        lastUpdateMs = now
                        OperationNotifications.progress(this, notifId, "Backing up Alpine", "$count files backed up…")
                    }
                }, { backupCancelled.get() })
            }.onFailure { if (it is java.util.concurrent.CancellationException) cancelled = true }.isSuccess && partial.renameTo(dest)
            if (!ok) partial.delete()
            if (ok && auto) {
                // Only counted as done once it really finished, so a failed or killed run retries at the next app start.
                settingsStore.lastAutoBackupMs = System.currentTimeMillis()
                destDir.listFiles { f -> f.name.startsWith("alpineterm-auto-") && f.name.endsWith(".tar.gz") }
                    ?.sortedByDescending { it.lastModified() }?.drop(keepLast)?.forEach { it.delete() }
            }
            OperationNotifications.finish(
                this,
                notifId,
                "Backing up Alpine",
                if (ok) "Backup saved: ${dest.name}" else if (cancelled) "Backup cancelled" else "Backup failed",
                ok,
            )
            mainHandler.post {
                backupCancelBtn?.visibility = View.GONE
                android.widget.Toast.makeText(
                    this,
                    if (ok) "Backup saved: ${dest.absolutePath}" else if (cancelled) "Backup cancelled" else "Backup failed",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun restoreAlpinePicker() {
        val dirs = listOfNotNull(AlpineBackup.backupsDir(this), AlpineBackup.legacyBackupsDir(this)).distinct()
        val backups = dirs.flatMap { d -> d.listFiles { f -> f.name.endsWith(".tar.gz") }?.toList() ?: emptyList() }
            .sortedByDescending { it.lastModified() }
        if (backups.isEmpty()) {
            android.widget.Toast.makeText(this, "No backups found in ${AlpineBackup.backupsDir(this)}", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val labels = backups.map { it.name }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Restore Alpine backup")
            .setItems(labels) { _, i -> confirmRestore(backups[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRestore(backupFile: File) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Restore ${backupFile.name}?")
            .setMessage("This replaces the entire current Alpine installation. All open sessions will be closed. This can't be undone.")
            .setPositiveButton("Restore") { _, _ -> startRestore(backupFile) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Free-space preflight for restores (backups already had one): a gzip'd rootfs typically
     * expands ~3-4x, so anything under 4x the archive size warns instead of wiping the live
     * install and then dying to ENOSPC halfway through extraction.
     */
    private fun startRestore(backupFile: File) {
        val destRoot = AlpineRootfs.rootDir(this)
        val free = runCatching { android.os.StatFs(destRoot.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        val need = backupFile.length() * 4
        if (need > 0 && free < need) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Low storage")
                .setMessage(
                    "This backup (${FileOps.humanSize(backupFile.length())}) may need roughly " +
                        "${FileOps.humanSize(need)} unpacked, but only ${FileOps.humanSize(free)} is free. " +
                        "It'll likely fail partway — leaving Alpine broken until a fresh setup. Continue anyway?",
                )
                .setPositiveButton("Continue anyway") { _, _ -> doRestore(backupFile) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            doRestore(backupFile)
        }
    }

    private fun doRestore(backupFile: File) {
                // closeTab() only requests destruction — actual removal from `tabs` happens later,
                // asynchronously, via onTabExited() on each session's own reader thread — so a
                // snapshot is destroyed here rather than looping on tabs.isNotEmpty(), which
                // would never observe the list shrinking in time and spin forever.
                // Held across the whole async restore and handed off to addTab()'s own increment
                // right before calling it, same pattern as "Reinstall Alpine" above.
                pendingSessionStarts++
                val closingSessions = tabs.toList().onEach { it.session.destroy() }
                android.widget.Toast.makeText(this, "Restoring…", android.widget.Toast.LENGTH_SHORT).show()
                val app = application as AlpineTermApp
                val notifId = OperationNotifications.newId()
                restoreCancelled.set(false)
                runOnUiThread { restoreCancelBtn?.visibility = View.VISIBLE }
                OperationNotifications.progress(this, notifId, "Restoring ${backupFile.name}", "Starting…")
                app.backupRestoreExecutor.execute {
                    // destroy() only requests the process die — starting to overwrite the whole
                    // rootfs right away risks racing a process that's still exiting and still
                    // touching files underneath it. Bounded (2s/session) so one stuck process
                    // can't hang a restore forever.
                    closingSessions.forEach { it.session.awaitExit(2000) }
                    // No upfront clearReadyMarker(): restore() extracts into a staging sibling
                    // and only swaps it over the live tree on full success — a failed restore
                    // leaves the current install (and its marker) untouched, so clearing first
                    // would only discard a good marker for an install that is still fine.
                    // markReady() below refreshes the label on success.
                    var lastUpdateMs = 0L
                    var cancelled = false
                    val ok = runCatching {
                        AlpineBackup.restore(backupFile, AlpineRootfs.rootDir(this), { count ->
                            val now = System.currentTimeMillis()
                            if (now - lastUpdateMs >= 300) {
                                lastUpdateMs = now
                                OperationNotifications.progress(this, notifId, "Restoring ${backupFile.name}", "$count entries restored…")
                            }
                        }, { restoreCancelled.get() })
                    }.onFailure { if (it is java.util.concurrent.CancellationException) cancelled = true }.isSuccess
                    if (ok) AlpineRootfs.markReady(this, backupFile.name)
                    OperationNotifications.finish(
                        this,
                        notifId,
                        "Restoring ${backupFile.name}",
                        if (ok) "Restore complete" else if (cancelled) "Restore cancelled" else "Restore failed",
                        ok,
                    )
                    mainHandler.post {
                        restoreCancelBtn?.visibility = View.GONE
                        android.widget.Toast.makeText(this, if (ok) "Restore complete" else if (cancelled) "Restore cancelled — starting a fresh Alpine setup instead" else "Restore failed — starting a fresh Alpine setup instead", android.widget.Toast.LENGTH_LONG).show()
                        drawerLayout.closeDrawer(GravityCompat.END)
                        pendingSessionStarts--
                        // Always add a replacement, restore failure included — AlpineBackup.restore()
                        // already deletes the old rootfs before extracting the new one, so a failed
                        // restore leaves nothing usable behind either way; addTab()'s own
                        // ensureReady() will just re-download a clean install rather than stranding
                        // the user on a blank screen with every tab gone and nothing to recover it.
                        addTab()
                    }
                }
    }

    private fun doBackupToUri(uri: Uri) {
        val name = uriDisplayName(uri, "chosen backup")
        val root = AlpineRootfs.rootDir(this)
        android.widget.Toast.makeText(this, "Backing up Alpine to $name…", android.widget.Toast.LENGTH_SHORT).show()
        val app = application as AlpineTermApp
        val notifId = OperationNotifications.newId()
        backupCancelled.set(false)
        runOnUiThread { backupCancelBtn?.visibility = View.VISIBLE }
        app.backupRestoreExecutor.execute {
            var lastUpdateMs = 0L
            OperationNotifications.progress(this, notifId, "Backing up Alpine", "Starting…")
            var cancelled = false
            val ok = runCatching {
                contentResolver.openOutputStream(uri, "w")?.use { out ->
                    AlpineBackup.backupToStream(root, out, { count ->
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateMs >= 300) {
                            lastUpdateMs = now
                            OperationNotifications.progress(this, notifId, "Backing up Alpine", "$count files backed up…")
                        }
                    }, { backupCancelled.get() })
                } ?: throw IllegalStateException("could not open $name for writing")
            }.onFailure { if (it is java.util.concurrent.CancellationException) cancelled = true }.isSuccess
            OperationNotifications.finish(
                this,
                notifId,
                "Backing up Alpine",
                if (ok) "Backup saved: $name" else if (cancelled) "Backup cancelled" else "Backup failed",
                ok,
            )
            mainHandler.post {
                backupCancelBtn?.visibility = View.GONE
                android.widget.Toast.makeText(
                    this,
                    if (ok) "Backup saved: $name" else if (cancelled) "Backup cancelled" else "Backup failed",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun confirmRestoreUri(uri: Uri) {
        val name = uriDisplayName(uri, "chosen file")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Restore $name?")
            .setMessage("This replaces the entire current Alpine installation. All open sessions will be closed. This can't be undone.")
            .setPositiveButton("Restore") { _, _ -> doRestoreFromUri(uri, name) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doRestoreFromUri(uri: Uri, displayName: String) {
        pendingSessionStarts++
        val closingSessions = tabs.toList().onEach { it.session.destroy() }
        android.widget.Toast.makeText(this, "Restoring…", android.widget.Toast.LENGTH_SHORT).show()
        val app = application as AlpineTermApp
        val notifId = OperationNotifications.newId()
        restoreCancelled.set(false)
        runOnUiThread { restoreCancelBtn?.visibility = View.VISIBLE }
        OperationNotifications.progress(this, notifId, "Restoring $displayName", "Starting…")
        app.backupRestoreExecutor.execute {
            closingSessions.forEach { it.session.awaitExit(2000) }
            // Same as doRestore(): no upfront clear — a failed stream restore leaves the live
            // tree and its marker intact.
            var lastUpdateMs = 0L
            var cancelled = false
            val ok = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    AlpineBackup.restoreFromStream(input, AlpineRootfs.rootDir(this), { count ->
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateMs >= 300) {
                            lastUpdateMs = now
                            OperationNotifications.progress(this, notifId, "Restoring $displayName", "$count entries restored…")
                        }
                    }, { restoreCancelled.get() })
                } ?: throw IllegalStateException("could not open $displayName")
            }.onFailure { if (it is java.util.concurrent.CancellationException) cancelled = true }.isSuccess
            if (ok) AlpineRootfs.markReady(this, displayName)
            OperationNotifications.finish(
                this,
                notifId,
                "Restoring $displayName",
                if (ok) "Restore complete" else if (cancelled) "Restore cancelled" else "Restore failed",
                ok,
            )
            mainHandler.post {
                restoreCancelBtn?.visibility = View.GONE
                android.widget.Toast.makeText(this, if (ok) "Restore complete" else if (cancelled) "Restore cancelled — starting a fresh Alpine setup instead" else "Restore failed — starting a fresh Alpine setup instead", android.widget.Toast.LENGTH_LONG).show()
                drawerLayout.closeDrawer(GravityCompat.END)
                pendingSessionStarts--
                addTab()
            }
        }
    }

    private fun exportSettings() {
        val json = org.json.JSONObject().apply {
            put("theme", settingsStore.themeId)
            put("font_size_sp", settingsStore.fontSizeSp)
            put("font_family", settingsStore.fontFamily)
            put("show_extra_keys", settingsStore.showExtraKeys)
            put("keep_alive_enabled", settingsStore.keepAliveEnabled)
            put("wake_lock_enabled", settingsStore.wakeLockEnabled)
            put("ligatures_enabled", settingsStore.ligaturesEnabled)
            put("bell_sound_enabled", settingsStore.bellSoundEnabled)
            put(
                "custom_snippets",
                org.json.JSONArray().apply {
                    settingsStore.customSnippets.forEach { (label, cmd) ->
                        put(org.json.JSONObject().apply { put("label", label); put("cmd", cmd) })
                    }
                },
            )
        }
        val dest = File(AlpineBackup.backupsDir(this), "alpineterm-settings.json")
        runCatching { dest.writeText(json.toString(2)) }
            .onSuccess { android.widget.Toast.makeText(this, "Settings exported to ${dest.absolutePath}", android.widget.Toast.LENGTH_LONG).show() }
            .onFailure { android.widget.Toast.makeText(this, "Export failed: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
    }

    private fun importSettingsPicker() {
        val dest = File(AlpineBackup.backupsDir(this), "alpineterm-settings.json")
        if (!dest.exists()) {
            android.widget.Toast.makeText(this, "No exported settings file found (${dest.absolutePath})", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val json = runCatching { org.json.JSONObject(dest.readText()) }.getOrNull()
        if (json == null) {
            android.widget.Toast.makeText(this, "Settings file is invalid", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // Same bounds the UI enforces when creating these by hand — the file lives where
        // the guest can plant it, so unbounded values must not flow straight into settings.
        // (Unknown theme/font ids already fall back safely in Themes.byId/typefaceFor.)
        settingsStore.themeId = json.optString("theme", settingsStore.themeId)
        settingsStore.fontSizeSp = json.optDouble("font_size_sp", settingsStore.fontSizeSp.toDouble()).toFloat().coerceIn(8f, 40f)
        settingsStore.fontFamily = json.optString("font_family", settingsStore.fontFamily)
        settingsStore.showExtraKeys = json.optBoolean("show_extra_keys", settingsStore.showExtraKeys)
        settingsStore.keepAliveEnabled = json.optBoolean("keep_alive_enabled", settingsStore.keepAliveEnabled)
        settingsStore.wakeLockEnabled = json.optBoolean("wake_lock_enabled", settingsStore.wakeLockEnabled)
        settingsStore.ligaturesEnabled = json.optBoolean("ligatures_enabled", settingsStore.ligaturesEnabled)
        settingsStore.bellSoundEnabled = json.optBoolean("bell_sound_enabled", settingsStore.bellSoundEnabled)
        json.optJSONArray("custom_snippets")?.let { arr ->
            settingsStore.customSnippets = (0 until minOf(arr.length(), 100)).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val label = obj.optString("label"); val cmd = obj.optString("cmd")
                if (label.isBlank() || cmd.isBlank() || label.length > 40 || cmd.length > 500) null else label to cmd
            }
        }
        TerminalColors.applyTheme(Themes.byId(settingsStore.themeId))
        tabs.forEach { it.emulator.applyPalette() }
        terminalView.setTypeface(typefaceFor(settingsStore.fontFamily))
        terminalView.setTextSizePx(spToPx(settingsStore.fontSizeSp))
        terminalView.setLigaturesEnabled(settingsStore.ligaturesEnabled)
        extraKeysScroll.visibility = if (settingsStore.showExtraKeys) View.VISIBLE else View.GONE
        buildExtraKeysRow(extraKeysRow)
        applyChromeColors()
        terminalView.invalidate()
        updateKeepAliveService()
        android.widget.Toast.makeText(this, "Settings imported — reopen Settings to see updated controls", android.widget.Toast.LENGTH_LONG).show()
        drawerLayout.closeDrawer(GravityCompat.END)
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1002
        private const val BACKUP_CREATE_REQUEST_CODE = 2001
        private const val RESTORE_OPEN_REQUEST_CODE = 2002
        private const val BELL_CHANNEL_ID = "alpineterm_bell"
        private const val BELL_NOTIFICATION_BASE_ID = 2000
    }
}
