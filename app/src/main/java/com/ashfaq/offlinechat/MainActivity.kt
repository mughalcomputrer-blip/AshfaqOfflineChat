package com.ashfaq.offlinechat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity(), ChatManager.Listener {

    private lateinit var chatManager: ChatManager
    private lateinit var adapter: MessageAdapter
    private lateinit var statusText: TextView
    private lateinit var recyclerMessages: RecyclerView
    private lateinit var editMessage: EditText
    private lateinit var buttonMic: ImageButton
    private lateinit var buttonFile: ImageButton
    private lateinit var quoteBar: View
    private lateinit var quoteBarSender: TextView
    private lateinit var quoteBarText: TextView
    private lateinit var buttonSearch: TextView
    private lateinit var buttonOverflow: TextView
    private lateinit var searchBar: View
    private lateinit var editSearch: EditText
    private lateinit var buttonCloseSearch: ImageButton
    private lateinit var buttonFind: MaterialButton
    private lateinit var buttonDisconnect: MaterialButton
    private lateinit var buttonSend: MaterialButton
    private lateinit var buttonCancelQuote: ImageButton
    private lateinit var lockOverlay: View
    private lateinit var lockTitle: TextView
    private lateinit var lockHint: TextView
    private lateinit var editPin: EditText
    private lateinit var textPinError: TextView
    private lateinit var buttonUnlock: MaterialButton

    // True once startApp() has run. The normal startup has to happen
    // exactly once — either straight away in onCreate, or after the
    // PIN gate lets the user through — and this is the guard.
    private var appStarted = false

    private var requestDialog: AlertDialog? = null
    private var closing = false

    // The quote chosen with a long press ("Reply"), waiting to ride
    // along with the next message the user sends — text, voice, photo
    // or file alike. Both stay null when no reply is pending; sending
    // (or the ✕ on the quote bar) clears them again.
    private var pendingQuotedSender: String? = null
    private var pendingQuotedText: String? = null

    // Ids for our own sent messages, handed out one per message, so a
    // finished transfer can be matched back to its chat row (the
    // "delivered" ticks). Continued after the loaded history in
    // startApp, so ids never repeat within the saved chat.
    private var nextLocalId = 1L

    // Files from other phones that are still arriving, while their
    // bubble is already on screen as a "receiving" placeholder:
    // Nearby payload id -> the placeholder row's local id. The
    // placeholder borrows an id from the same counter (its own sends
    // and placeholders can then never share one, so the adapter's
    // progress state stays unambiguous); the finished message drops
    // it again (received messages carry no local id). A placeholder
    // is never written to the history — only the finished message
    // is, when its transfer completes.
    private val receivingPlaceholders = mutableMapOf<Long, Long>()

    // Voice recording state.
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartedAt: Long = 0L
    private var isRecording = false
    private val recordHandler = Handler(Looper.getMainLooper())
    private val autoStopRecording = Runnable { stopRecordingAndSend() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            chatManager.start()
        } else {
            setStatus(getString(R.string.permissions_denied), R.color.status_error)
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    // The walkie-talkie's own microphone ask: granting it does not
    // start a talk by itself (the user holds the button again — the
    // dialog stays open underneath), it only puts the permission
    // right and says so.
    private val talkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, R.string.walkie_mic_ready, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    // Asking for the location permission only puts the permission
    // right; the position itself is fetched when the user taps the
    // location button again (the same "tap again after granting"
    // shape as the rest of the app). A full refusal is explained.
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.none { it }) {
            Toast.makeText(
                this, R.string.location_permission_needed, Toast.LENGTH_LONG
            ).show()
        }
    }

    // Walkie-talkie state: the dialog and its two working parts,
    // non-null only while the dialog is open; whether our own talk
    // button is currently held; and who is talking to us right now,
    // as last reported by ChatManager (kept across the dialog, so
    // reopening it mid-talk shows the right state).
    private var walkieDialog: AlertDialog? = null
    private var walkieStatus: TextView? = null
    private var walkieButton: MaterialButton? = null
    private var isTalkingNow = false
    private var peerTalking = false
    private var peerTalkingName: String? = null

    // Typing indicator state. Sending side: whether our last
    // signal said "typing" (so the stop signal goes out exactly
    // once, when the text is emptied or sent) and when the last
    // start signal went out (the throttle — steady typing repeats
    // its signal every couple of seconds, not per keystroke).
    private var typingSignalSent = false
    private var lastTypingSignalAt = 0L

    // Receiving side: who is typing — their name, and the moment
    // (uptimeMillis) their indicator expires. A typer who never
    // sends a stop is forgotten when their time runs out; the one
    // runnable below wakes up for the earliest expiry and tidies
    // the map (see refreshTypingIndicator).
    private val typingPeers = LinkedHashMap<String, Long>()
    private val typingHandler = Handler(Looper.getMainLooper())
    private val typingExpiryCheck = Runnable {
        if (!closing) refreshTypingIndicator()
    }

    // The status line's real content — the last status set through
    // setStatus — kept aside while a typing line borrows the line,
    // so what comes back afterwards is the current status
    // (connected, hosting, reconnecting…), never a stale copy.
    private var currentStatusText: String = ""
    private var currentStatusColorRes: Int = R.color.status_idle
    private var typingStatusShown = false

    // Waiting-for-a-fix state: the manager and listener of the one
    // single update that may be in flight, and the providers still
    // left to try when it times out. All null/empty when idle.
    private var pendingLocationManager: LocationManager? = null
    private var pendingLocationListener: LocationListener? = null
    private var pendingLocationProviders: List<String> = emptyList()
    private val locationHandler = Handler(Looper.getMainLooper())
    private val locationTimeout = Runnable { onLocationTimeout() }

    // The system media picker (photos and videos): no storage
    // permission is needed, and a cancelled pick simply gives us
    // nothing (not an error). The picked item's type decides which
    // send flow it takes.
    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val mimeType = try {
                contentResolver.getType(uri)
            } catch (e: Exception) {
                null
            }
            if (mimeType != null && mimeType.startsWith("video/")) {
                prepareAndSendVideo(uri)
            } else {
                prepareAndSendPhoto(uri)
            }
        }
    }

    // The system document picker, for files of any kind: again no storage
    // permission is needed, and a cancelled pick stays silent.
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            prepareAndSendFile(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Version 1 stays in portrait so a rotation cannot drop the connection.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        recyclerMessages = findViewById(R.id.recyclerMessages)
        editMessage = findViewById(R.id.editMessage)
        buttonMic = findViewById(R.id.buttonMic)
        buttonFile = findViewById(R.id.buttonFile)
        quoteBar = findViewById(R.id.quoteBar)
        quoteBarSender = findViewById(R.id.quoteBarSender)
        quoteBarText = findViewById(R.id.quoteBarText)
        buttonSearch = findViewById(R.id.buttonSearch)
        buttonOverflow = findViewById(R.id.buttonOverflow)
        searchBar = findViewById(R.id.searchBar)
        editSearch = findViewById(R.id.editSearch)
        buttonCloseSearch = findViewById(R.id.buttonCloseSearch)
        buttonFind = findViewById(R.id.buttonFind)
        buttonDisconnect = findViewById(R.id.buttonDisconnect)
        buttonSend = findViewById(R.id.buttonSend)
        buttonCancelQuote = findViewById(R.id.buttonCancelQuote)
        lockOverlay = findViewById(R.id.lockOverlay)
        lockTitle = findViewById(R.id.lockTitle)
        lockHint = findViewById(R.id.lockHint)
        editPin = findViewById(R.id.editPin)
        textPinError = findViewById(R.id.textPinError)
        buttonUnlock = findViewById(R.id.buttonUnlock)

        // The theme is painted first and always: when a PIN is set,
        // the lock screen is the first thing seen, and it must already
        // wear the right palette before anything else happens.
        applyThemeColors()

        // The PIN gate. While the lock screen is up, none of the
        // normal startup runs: no chat manager, no saved chat on
        // screen, no permission prompts, no searching for phones.
        // A correct PIN runs startApp() then, exactly once.
        if (PinLock.isSet(this) && !sessionUnlocked) {
            lockOverlay.visibility = View.VISIBLE
            buttonUnlock.setOnClickListener { tryUnlock() }
            editPin.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    tryUnlock()
                    true
                } else {
                    false
                }
            }
            editPin.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
            imm.showSoftInput(editPin, InputMethodManager.SHOW_IMPLICIT)
        } else {
            // No PIN to pass — or already passed earlier in this
            // session, which is where the dark-mode recreate lands.
            sessionUnlocked = true
            startApp()
        }
    }

    /**
     * The app's normal startup: the adapter, the saved chat, the chat
     * manager and every button that works with them. It used to live
     * in onCreate; the PIN gate now holds it back until the user
     * unlocks, and the [appStarted] guard keeps it to one run.
     */
    private fun startApp() {
        if (appStarted) return
        appStarted = true

        adapter = MessageAdapter()
        adapter.onMessageMenuRequested = { message -> showMessageMenu(message) }
        recyclerMessages.layoutManager = LinearLayoutManager(this)
        recyclerMessages.adapter = adapter

        // Show the chat that was saved on this phone, if any.
        val history = ChatHistoryStore.load(this)
        if (history.isNotEmpty()) {
            adapter.setMessages(history)
            recyclerMessages.scrollToPosition(history.size - 1)
        }
        // New messages we send get fresh local ids, continuing after
        // the highest id the loaded history already uses.
        nextLocalId = (history.maxOfOrNull { it.localId } ?: 0L) + 1L

        chatManager = ChatManager(this, this)

        buttonFind.setOnClickListener { onFindClicked() }
        buttonDisconnect.setOnClickListener { chatManager.disconnect() }
        buttonSend.setOnClickListener { sendCurrentMessage() }
        editMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?, start: Int, count: Int, after: Int
            ) {
            }

            override fun onTextChanged(
                s: CharSequence?, start: Int, before: Int, count: Int
            ) {
                onMessageTextChanged(s?.toString() ?: "")
            }

            override fun afterTextChanged(s: Editable?) {}
        })
        buttonMic.setOnClickListener { onMicClicked() }
        buttonFile.setOnClickListener { showAttachMenu(it) }
        buttonCancelQuote.setOnClickListener { clearPendingQuote() }
        buttonSearch.setOnClickListener { toggleSearchBar() }
        buttonOverflow.setOnClickListener { showOverflowMenu(it) }
        buttonCloseSearch.setOnClickListener { closeSearch() }
        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?, start: Int, count: Int, after: Int
            ) {
            }

            override fun onTextChanged(
                s: CharSequence?, start: Int, before: Int, count: Int
            ) {
                adapter.setFilterQuery(s?.toString() ?: "")
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    /**
     * The Unlock button on the lock screen. A correct PIN hides the
     * lock, clears the field and runs the held-back startup; a wrong
     * one shows the error, clears the field and stays locked. There
     * is deliberately no way around this screen — pressing Back just
     * leaves the app, as usual.
     */
    private fun tryUnlock() {
        if (PinLock.verify(this, editPin.text.toString())) {
            sessionUnlocked = true
            textPinError.visibility = View.GONE
            editPin.text.clear()
            lockOverlay.visibility = View.GONE
            startApp()
        } else {
            textPinError.visibility = View.VISIBLE
            editPin.text.clear()
        }
    }

    /**
     * The ⋮ button in the header: a WhatsApp-style overflow menu.
     * Everything that once crowded the header as its own button —
     * Name, Friends, dark mode, PIN lock, color themes, Clear chat —
     * lives here now, each item calling the very same handler or
     * dialog its old button called. The Walkie-Talkie item (update
     * #14) opens its own dialog, described below.
     */
    private fun showOverflowMenu(anchor: View) {
        val itemName = 1
        val itemFriends = 2
        val itemDarkMode = 3
        val itemPinLock = 4
        val itemThemes = 5
        val itemClear = 6
        val itemWalkie = 7
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, itemName, 0, R.string.your_name)
        popup.menu.add(0, itemFriends, 1, R.string.friends)
        popup.menu.add(0, itemDarkMode, 2, R.string.dark_mode)
        popup.menu.add(0, itemPinLock, 3, R.string.pin_lock_title)
        popup.menu.add(0, itemThemes, 4, R.string.color_themes)
        popup.menu.add(0, itemWalkie, 5, R.string.walkie_talkie)
        popup.menu.add(0, itemClear, 6, R.string.clear_chat)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                itemName -> showNameDialog()
                itemFriends -> showFriendsDialog()
                itemDarkMode -> toggleDarkMode()
                itemPinLock -> showPinLockMenu()
                itemThemes -> showThemesDialog()
                itemWalkie -> showWalkieTalkieDialog()
                itemClear -> confirmClearChat()
            }
            true
        }
        popup.show()
    }

    /**
     * The ➕ attach button in the input row: a WhatsApp-style menu of
     * the three things that can be attached — a photo or video, a
     * file, or the current location — each calling the same flow its
     * own button used to call before the row was decluttered.
     */
    private fun showAttachMenu(anchor: View) {
        val itemMedia = 1
        val itemFile = 2
        val itemLocation = 3
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, itemMedia, 0, R.string.pick_media)
        popup.menu.add(0, itemFile, 1, R.string.send_file)
        popup.menu.add(0, itemLocation, 2, R.string.share_location)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                itemMedia -> onPhotoClicked()
                itemFile -> onFileClicked()
                itemLocation -> onLocationClicked()
            }
            true
        }
        popup.show()
    }

    // ---------- Walkie-talkie ----------

    /**
     * The Walkie-Talkie overflow-menu item: a simple dialog with a
     * status line and one BIG hold-to-talk button, built in code
     * like the display-name dialog. Holding the button streams this
     * phone's microphone live to everyone connected (see
     * [ChatManager.startTalk]); letting go ends the talk. While
     * someone else is talking, the button is disabled — half-duplex,
     * like a real walkie-talkie — and the status line says who it
     * is. A standard dialog, so it keeps the light dialog theme in
     * dark mode too, like the app's other dialogs.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showWalkieTalkieDialog() {
        if (closing) return
        if (!chatManager.isConnected()) {
            Toast.makeText(
                this, R.string.walkie_not_connected, Toast.LENGTH_SHORT
            ).show()
            return
        }
        // A talk may already be on the channel (it started while
        // the dialog was closed): pick its state up, so the dialog
        // opens showing the truth.
        if (chatManager.isChannelBusy()) {
            peerTalking = true
            peerTalkingName = chatManager.currentTalkerName()
        }
        val density = resources.displayMetrics.density
        val side = (20 * density).toInt()
        val status = TextView(this).apply {
            textSize = 15f
            setTextColor(AppColors.textPrimary(this@MainActivity))
        }
        val button = MaterialButton(this).apply {
            text = getString(R.string.walkie_hold_to_talk)
            textSize = 20f
            minHeight = (80 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (16 * density).toInt() }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, side / 2, side, 0)
            addView(status)
            addView(button)
        }
        walkieStatus = status
        walkieButton = button
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    onTalkButtonPressed()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onTalkButtonReleased()
                    true
                }
                else -> false
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.walkie_talkie)
            .setView(container)
            .setNegativeButton(R.string.friends_close, null)
            .create()
        dialog.setOnDismissListener {
            // A talk must never outlive its dialog: a hot microphone
            // nobody can see would be a nasty surprise. (A talk that
            // is playing TO us keeps playing — ChatManager owns it,
            // and it ends when the talker lets go.)
            onTalkButtonReleased()
            if (walkieDialog === dialog) {
                walkieDialog = null
                walkieStatus = null
                walkieButton = null
            }
        }
        walkieDialog = dialog
        refreshWalkieDialog()
        dialog.show()
    }

    /**
     * The talk button was pressed down. Talking needs the
     * microphone permission — the same one voice messages use; when
     * it is missing, it is asked for and this press ends there (the
     * user holds the button again afterwards, the dialog still
     * open). A busy channel and a failed start are explained, never
     * silent.
     */
    private fun onTalkButtonPressed() {
        if (isTalkingNow) return
        if (peerTalking) return // the button is disabled then, too
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            talkPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (chatManager.startTalk()) {
            isTalkingNow = true
            refreshWalkieDialog()
        } else {
            Toast.makeText(
                this, R.string.walkie_start_failed, Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** The talk button was released (or the dialog went away):
     *  the talk ends. Safe to call when no talk is running. */
    private fun onTalkButtonReleased() {
        if (!isTalkingNow) return
        isTalkingNow = false
        chatManager.stopTalk()
        refreshWalkieDialog()
    }

    /**
     * Paints the walkie-talkie dialog from the current state: our
     * own talk in progress, someone else talking (button disabled,
     * their name on the status line), or a free channel. Does
     * nothing while the dialog is closed.
     */
    private fun refreshWalkieDialog() {
        val status = walkieStatus ?: return
        val button = walkieButton ?: return
        when {
            isTalkingNow -> {
                status.text = getString(R.string.walkie_status_talking_you)
                button.text = getString(R.string.walkie_release_to_stop)
                button.isEnabled = true
            }
            peerTalking -> {
                status.text = getString(
                    R.string.walkie_status_talking_other,
                    peerTalkingName ?: getString(R.string.quote_friend)
                )
                button.text = getString(R.string.walkie_hold_to_talk)
                button.isEnabled = false
            }
            else -> {
                status.text = if (chatManager.isConnected()) {
                    getString(R.string.walkie_status_ready)
                } else {
                    getString(R.string.walkie_not_connected)
                }
                button.text = getString(R.string.walkie_hold_to_talk)
                button.isEnabled = chatManager.isConnected()
            }
        }
    }

    /** Flips the dark-mode switch and rebuilds the screen in the new palette. */
    private fun toggleDarkMode() {
        AppColors.setDark(this, !AppColors.isDark(this))
        recreate()
    }

    /**
     * The Color themes overflow-menu item: a single-choice list of the color themes
     * (update #12), the current one checked. Picking one saves it and
     * rebuilds the screen in the new skin, exactly like the dark-mode
     * toggle does. A standard dialog, so it keeps the light dialog
     * theme in dark mode too — the same accepted limitation as the
     * app's other dialogs.
     */
    private fun showThemesDialog() {
        val ids = AppColors.THEME_IDS
        val names = ids.map { getString(AppColors.themeNameRes(it)) }
            .toTypedArray()
        val current = ids.indexOf(AppColors.themeId(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.color_themes)
            .setSingleChoiceItems(names, current) { dialog, which ->
                dialog.dismiss()
                if (which != current) {
                    AppColors.setThemeId(this, ids[which])
                    recreate()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Paints the whole screen — background, header, quote bar, input
     * row, status bar — from AppColors. Called once in onCreate; with
     * dark mode off it sets exactly the colors the layouts name, so the
     * light screen is unchanged.
     */
    private fun applyThemeColors() {
        val dark = AppColors.isDark(this)
        findViewById<View>(R.id.rootLayout)
            .setBackgroundColor(AppColors.screenBackground(this))
        findViewById<View>(R.id.headerBar)
            .setBackgroundColor(AppColors.headerBackground(this))
        findViewById<TextView>(R.id.titleText)
            .setTextColor(AppColors.headerText(this))
        window.statusBarColor = AppColors.statusBar(this)
        statusText.setTextColor(AppColors.statusColor(this, R.color.status_idle))

        quoteBar.setBackgroundColor(AppColors.inputBarBackground(this))
        findViewById<View>(R.id.quoteBarStrip)
            .setBackgroundColor(AppColors.accent(this))
        quoteBarSender.setTextColor(AppColors.accent(this))
        quoteBarText.setTextColor(AppColors.textSecondary(this))
        findViewById<ImageButton>(R.id.buttonCancelQuote)
            .setColorFilter(AppColors.textSecondary(this))

        findViewById<View>(R.id.inputBar)
            .setBackgroundColor(AppColors.inputBarBackground(this))
        editMessage.setBackgroundResource(AppColors.inputFieldDrawable(this))
        editMessage.setTextColor(AppColors.textPrimary(this))
        editMessage.setHintTextColor(AppColors.textSecondary(this))
        buttonFile.setColorFilter(AppColors.iconTint(this))
        buttonMic.setColorFilter(AppColors.iconTint(this))

        buttonSearch.setTextColor(AppColors.headerText(this))
        buttonOverflow.setTextColor(AppColors.headerText(this))
        searchBar.setBackgroundColor(AppColors.inputBarBackground(this))
        editSearch.setBackgroundResource(AppColors.inputFieldDrawable(this))
        editSearch.setTextColor(AppColors.textPrimary(this))
        editSearch.setHintTextColor(AppColors.textSecondary(this))
        buttonCloseSearch.setColorFilter(AppColors.textSecondary(this))

        // The lock screen may be the first thing the user sees, so it
        // is painted here with everything else: same background, input
        // field and text colors as the chat behind it.
        lockOverlay.setBackgroundColor(AppColors.screenBackground(this))
        lockTitle.setTextColor(AppColors.textPrimary(this))
        lockHint.setTextColor(AppColors.textSecondary(this))
        editPin.setBackgroundResource(AppColors.inputFieldDrawable(this))
        editPin.setTextColor(AppColors.textPrimary(this))
        editPin.setHintTextColor(AppColors.textSecondary(this))
        textPinError.setTextColor(
            AppColors.statusColor(this, R.color.status_error)
        )

        // The Send and Unlock buttons wear the accent. Their layouts
        // name @color/accent directly — Ocean's accent — so the
        // palette repaints them here in every mode, and the chosen
        // theme's accent reaches them too. With Ocean selected the
        // value set is the very color the layouts name.
        buttonSend.backgroundTintList =
            ColorStateList.valueOf(AppColors.accent(this))
        buttonUnlock.backgroundTintList =
            ColorStateList.valueOf(AppColors.accent(this))

        // The Find / Disconnect buttons take their colors from the
        // (light) app theme, which would leave the outlined
        // Disconnect button nearly invisible on the dark background —
        // in dark mode they get readable colors by hand instead.
        if (dark) {
            buttonFind.backgroundTintList =
                ColorStateList.valueOf(AppColors.headerBackground(this))
            buttonFind.setTextColor(AppColors.headerText(this))
            buttonDisconnect.setTextColor(AppColors.headerText(this))
            buttonDisconnect.strokeColor =
                ColorStateList.valueOf(AppColors.textSecondary(this))
        }
    }

    override fun onDestroy() {
        closing = true
        requestDialog?.dismiss()
        // Dismissing the walkie-talkie dialog also ends a talk in
        // progress, through its dismiss listener.
        walkieDialog?.dismiss()
        discardRecording()
        stopLocationWait()
        // If the app is closed from the PIN lock screen, startApp()
        // never ran and there is no chat manager to stop.
        if (::chatManager.isInitialized) chatManager.disconnect()
        super.onDestroy()
    }

    // ---------- Buttons ----------

    private fun onFindClicked() {
        if (chatManager.isConnected()) {
            Toast.makeText(this, R.string.status_connected_already, Toast.LENGTH_SHORT).show()
            return
        }
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            chatManager.start()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ---------- Friends ----------

    /**
     * The Friends overflow-menu item: the people this phone has connected with
     * before, newest first, each with the last time they connected.
     * Tapping one looks for that person and asks them to connect
     * (see [onFriendClicked]). Nobody remembered yet gets a short
     * explanation instead of an empty list.
     */
    private fun showFriendsDialog() {
        if (closing) return
        val friends = FriendsStore.list(this)
        if (friends.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.friends_title)
                .setMessage(R.string.friends_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val labels = friends.map { (name, lastConnected) ->
            getString(
                R.string.friend_last_connected,
                name,
                formatFriendTime(lastConnected)
            )
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.friends_title)
            .setItems(labels) { _, which -> onFriendClicked(friends[which].first) }
            .setNegativeButton(R.string.friends_close, null)
            .show()
    }

    /**
     * A friend was tapped: look for them and let ChatManager ask
     * them to connect as soon as they are found. Like the Find
     * button, the Nearby permissions must be in place first — when
     * they are missing, the permission ask runs and the user taps
     * the friend again afterwards (the same shape as location
     * sharing). ChatManager's search reports "Searching..." through
     * onSearching while it starts; the "Looking for..." line below
     * is set after that, so it is the one that stays on screen.
     */
    private fun onFriendClicked(name: String) {
        if (chatManager.isConnected()) {
            Toast.makeText(this, R.string.status_connected_already, Toast.LENGTH_SHORT).show()
            return
        }
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        chatManager.findFriend(name)
        setStatus(getString(R.string.friend_looking, name), R.color.status_idle)
    }

    /** "5 Oct, 3:42 PM" — when a friend was last connected with. */
    private fun formatFriendTime(epochMillis: Long): String =
        SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
            .format(Date(epochMillis))

    private fun sendCurrentMessage() {
        val text = editMessage.text.toString().trim()
        if (text.isEmpty()) return
        val localId = nextLocalId++
        // The id the other phones will know this message by, decided
        // here so the chat row below can carry the same id (reactions
        // refer to it).
        val wireId = UUID.randomUUID().toString()
        if (!chatManager.sendMessage(
                text, localId, pendingQuotedSender, pendingQuotedText,
                wireId = wireId
            )
        ) {
            Toast.makeText(this, R.string.not_connected_cannot_send, Toast.LENGTH_SHORT).show()
            return
        }
        addMessage(
            ChatMessage(
                text = text,
                isSent = true,
                localId = localId,
                quotedSender = pendingQuotedSender,
                quotedText = pendingQuotedText,
                wireId = wireId
            )
        )
        editMessage.text.clear()
        clearPendingQuote()
    }

    // ---------- Typing indicator ----------

    /**
     * The message box changed. While it holds any text, the other
     * phones hear that we are typing — throttled, so a burst of
     * keystrokes is one signal and steady typing repeats it every
     * couple of seconds (each repeat also refreshes the display
     * on the other side). An emptied box takes the signal back at
     * once; sending does the same, because [sendCurrentMessage]
     * clears the box. While nobody is connected the signal simply
     * goes nowhere and is tried again on the next change.
     */
    private fun onMessageTextChanged(text: String) {
        if (text.isBlank()) {
            stopTypingSignal()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (!typingSignalSent ||
            now - lastTypingSignalAt >= TYPING_SIGNAL_THROTTLE_MS
        ) {
            if (chatManager.sendTypingSignal(true)) {
                typingSignalSent = true
                lastTypingSignalAt = now
            }
        }
    }

    /** Takes our "typing" back, once — see [onMessageTextChanged]. */
    private fun stopTypingSignal() {
        if (!typingSignalSent) return
        typingSignalSent = false
        chatManager.sendTypingSignal(false)
    }

    /**
     * Forgets everyone who was typing (a connection change makes
     * their state meaningless) and stops the expiry wake-up. The
     * status line itself is the caller's business: the callbacks
     * that call this set a fresh status right after.
     */
    private fun forgetTypingPeers() {
        typingPeers.clear()
        typingHandler.removeCallbacks(typingExpiryCheck)
    }

    /**
     * Paints the typing line — or hands the line back to the real
     * status. Expired typers are pruned first; the one named is
     * the most recent (a refresh re-inserts at the end of the
     * map, see onTypingChanged). While anyone is left, a wake-up
     * is set for the earliest expiry, so the line also clears
     * itself about three seconds after the last signal when no
     * stop ever arrives.
     */
    private fun refreshTypingIndicator() {
        val now = SystemClock.uptimeMillis()
        val iterator = typingPeers.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value <= now) iterator.remove()
        }
        typingHandler.removeCallbacks(typingExpiryCheck)
        if (typingPeers.isEmpty()) {
            if (typingStatusShown) showRealStatus()
            return
        }
        typingStatusShown = true
        statusText.text = getString(
            R.string.typing_indicator, typingPeers.keys.last()
        )
        statusText.setTextColor(
            AppColors.statusColor(this, R.color.status_idle)
        )
        val earliest = typingPeers.values.minOrNull() ?: now
        typingHandler.postDelayed(
            typingExpiryCheck, maxOf(0L, earliest - now)
        )
    }

    /** Puts the status line back to the last real status. */
    private fun showRealStatus() {
        typingStatusShown = false
        statusText.text = currentStatusText
        statusText.setTextColor(
            AppColors.statusColor(this, currentStatusColorRes)
        )
    }

    // ---------- Replies & reactions ----------

    /**
     * The menu shown when a message is long-pressed: the six reaction
     * emojis, then "Reply" and "Forward". Our own messages also offer
     * "Edit" (text messages only) and "Delete for everyone". A message
     * without a wire id (media saved by an older app version, which
     * announced none) cannot be reacted to — the other phones would not
     * know which message the reaction means — so it only offers "Reply"
     * and "Forward"; for the same reason such a message cannot be
     * edited or deleted for everyone either (a forward needs no id:
     * the copy is a new message with a new id of its own). A deleted
     * message is just a placeholder now and has no menu at all.
     */
    private fun showMessageMenu(message: ChatMessage) {
        if (closing) return
        if (message.deleted) return
        if (message.wireId == null) {
            AlertDialog.Builder(this)
                .setItems(
                    arrayOf(
                        getString(R.string.reply),
                        getString(R.string.forward)
                    )
                ) { _, which ->
                    if (which == 0) {
                        setPendingQuote(message)
                    } else {
                        confirmForwardMessage(message)
                    }
                }
                .show()
            return
        }
        val emojis = ChatManager.REACTION_EMOJIS
        val canEdit = message.isSent && message.type == ChatMessage.Type.TEXT
        val items = mutableListOf<String>()
        items.addAll(emojis)
        items.add(getString(R.string.reply))
        items.add(getString(R.string.forward))
        if (canEdit) items.add(getString(R.string.edit_message))
        if (message.isSent) items.add(getString(R.string.delete_for_everyone))
        AlertDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which < emojis.size ->
                        applyOwnReaction(message, emojis[which])
                    which == emojis.size -> setPendingQuote(message)
                    which == emojis.size + 1 ->
                        confirmForwardMessage(message)
                    canEdit && which == emojis.size + 2 ->
                        showEditMessageDialog(message)
                    else -> confirmDeleteMessage(message)
                }
            }
            .show()
    }

    /**
     * Reacts to [message] with [emoji] as this phone: the reaction is
     * shown on our own screen at once (under our stamped wire name,
     * so it means the same person here as on the other phones) and
     * broadcast for the others to apply. Reactions toggle, so doing
     * this twice with the same emoji takes the reaction back.
     */
    private fun applyOwnReaction(message: ChatMessage, emoji: String) {
        val wireId = message.wireId ?: return
        if (adapter.applyReaction(wireId, emoji, chatManager.wireSenderName())) {
            ChatHistoryStore.save(this, adapter.snapshot())
        }
        chatManager.sendReaction(wireId, emoji)
    }

    /**
     * Remembers [message] as the one being replied to and shows the
     * quote bar above the input row. The quote goes out with the next
     * message that is actually sent, whichever kind it is; a send that
     * fails (for example while not connected) leaves it in place.
     */
    private fun setPendingQuote(message: ChatMessage) {
        pendingQuotedSender = quoteAuthorOf(message)
        pendingQuotedText = quotePreviewOf(message)
        quoteBarSender.text = pendingQuotedSender
        quoteBarText.text = pendingQuotedText
        quoteBar.visibility = View.VISIBLE
    }

    /** Drops the pending reply and hides the quote bar. */
    private fun clearPendingQuote() {
        pendingQuotedSender = null
        pendingQuotedText = null
        quoteBar.visibility = View.GONE
    }

    // ---------- Edits & deletes ----------

    /**
     * The Edit flow for one of our own text messages: a dialog with
     * the current text prefilled (the display-name dialog's shape).
     * On Save the edit command goes out first — while not connected
     * nothing changes anywhere and a toast says so — then our own
     * bubble updates in place and the chat history follows. Saving
     * the unchanged text, or an empty one, does nothing.
     */
    private fun showEditMessageDialog(message: ChatMessage) {
        val wireId = message.wireId ?: return
        val input = EditText(this).apply {
            setText(message.text)
            setSelection(text.length)
            hint = getString(R.string.message_hint)
        }
        // Give the text field some room inside the dialog.
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = (20 * resources.displayMetrics.density).toInt()
            setPadding(side, 0, side, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_message_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isNotEmpty() && newText != message.text) {
                    if (chatManager.sendEditMessage(wireId, newText)) {
                        if (adapter.editOwnMessage(wireId, newText)) {
                            ChatHistoryStore.save(this, adapter.snapshot())
                        }
                    } else {
                        Toast.makeText(
                            this, R.string.not_connected_edit,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The Delete-for-everyone flow for one of our own messages:
     * asks first, then the command goes out and our own bubble
     * becomes the deleted placeholder, like it does on the other
     * phones (the history follows). While not connected nothing
     * changes and a toast says so.
     */
    private fun confirmDeleteMessage(message: ChatMessage) {
        val wireId = message.wireId ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_message_title)
            .setMessage(R.string.delete_message_text)
            .setPositiveButton(R.string.delete_message_confirm) { _, _ ->
                if (chatManager.sendDeleteMessage(wireId)) {
                    if (adapter.deleteOwnMessage(wireId)) {
                        ChatHistoryStore.save(this, adapter.snapshot())
                    }
                } else {
                    Toast.makeText(
                        this, R.string.not_connected_delete,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- Forwarding ----------

    /**
     * The Forward flow for any message that is not deleted: asks
     * first, then the message goes out again as a new message from
     * this phone, marked as forwarded (see [forwardMessage]).
     */
    private fun confirmForwardMessage(message: ChatMessage) {
        AlertDialog.Builder(this)
            .setTitle(R.string.forward_message_title)
            .setMessage(R.string.forward_message_text)
            .setPositiveButton(R.string.forward) { _, _ ->
                forwardMessage(message)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Who a forward of [message] names as the original author: the
     * author a forward of it already names (so forwarding a forward
     * keeps pointing at the person who wrote it first); for a
     * message we received, its sender; for our own message, nobody
     * — we are the author, and the caption is then just
     * "↪ Forwarded".
     */
    private fun forwardAuthorOf(message: ChatMessage): String? =
        message.forwardedFrom
            ?: if (message.isSent) {
                null
            } else {
                message.senderName?.takeIf { it.isNotBlank() }
            }

    /**
     * Sends [message] again as a new message from this phone,
     * marked as a forward. The copy is fresh in every respect: a
     * new local id and wire id, no quote (a pending reply is left
     * untouched — it belongs to the next composed message), no
     * reactions. While not connected nothing is sent and a toast
     * says so. A media message whose file is no longer on this
     * phone cannot be forwarded and says so instead.
     */
    private fun forwardMessage(message: ChatMessage) {
        val author = forwardAuthorOf(message)
        val localId = nextLocalId++
        val wireId = UUID.randomUUID().toString()
        when (message.type) {
            ChatMessage.Type.TEXT -> {
                if (!chatManager.sendForwardedText(
                        message.text, author, localId, wireId
                    )
                ) {
                    toastNotConnectedForward()
                    return
                }
                addMessage(
                    ChatMessage(
                        text = message.text,
                        isSent = true,
                        localId = localId,
                        wireId = wireId,
                        forwarded = true,
                        forwardedFrom = author
                    )
                )
            }
            ChatMessage.Type.LOCATION -> {
                val latitude = message.latitude
                val longitude = message.longitude
                if (latitude == null || longitude == null) return
                if (!chatManager.sendForwardedLocation(
                        latitude, longitude, author, localId, wireId
                    )
                ) {
                    toastNotConnectedForward()
                    return
                }
                addMessage(
                    ChatMessage(
                        text = "",
                        isSent = true,
                        type = ChatMessage.Type.LOCATION,
                        latitude = latitude,
                        longitude = longitude,
                        localId = localId,
                        wireId = wireId,
                        forwarded = true,
                        forwardedFrom = author
                    )
                )
            }
            ChatMessage.Type.VOICE -> {
                val copy = mediaCopyForForward(message.audioPath) ?: return
                if (!chatManager.sendVoiceMessage(
                        copy, message.durationSeconds, localId,
                        wireId = wireId,
                        forwarded = true, forwardedFrom = author
                    )
                ) {
                    toastNotConnectedForward()
                    return
                }
                addMessage(
                    ChatMessage(
                        text = "",
                        isSent = true,
                        type = ChatMessage.Type.VOICE,
                        audioPath = copy.absolutePath,
                        durationSeconds = message.durationSeconds,
                        localId = localId,
                        wireId = wireId,
                        forwarded = true,
                        forwardedFrom = author
                    )
                )
            }
            ChatMessage.Type.PHOTO -> {
                val copy = mediaCopyForForward(message.imagePath) ?: return
                if (!chatManager.sendPhotoMessage(
                        copy, localId,
                        wireId = wireId,
                        forwarded = true, forwardedFrom = author
                    )
                ) {
                    toastNotConnectedForward()
                    return
                }
                addMessage(
                    ChatMessage(
                        text = "",
                        isSent = true,
                        type = ChatMessage.Type.PHOTO,
                        imagePath = copy.absolutePath,
                        localId = localId,
                        wireId = wireId,
                        forwarded = true,
                        forwardedFrom = author
                    )
                )
            }
            ChatMessage.Type.VIDEO -> {
                val copy = mediaCopyForForward(message.videoPath) ?: return
                if (!chatManager.sendVideoMessage(
                        copy, localId,
                        wireId = wireId,
                        forwarded = true, forwardedFrom = author
                    )
                ) {
                    toastNotConnectedForward()
                    return
                }
                addMessage(
                    ChatMessage(
                        text = "",
                        isSent = true,
                        type = ChatMessage.Type.VIDEO,
                        videoPath = copy.absolutePath,
                        durationSeconds = message.durationSeconds,
                        localId = localId,
                        wireId = wireId,
                        forwarded = true,
                        forwardedFrom = author
                    )
                )
            }
            ChatMessage.Type.FILE -> {
                val copy = mediaCopyForForward(message.filePath) ?: return
                if (!chatManager.sendFileMessage(
                        copy, localId,
                        wireId = wireId,
                        forwarded = true, forwardedFrom = author
                    )
                ) {
                    toastNotConnectedForward()
                    return
                }
                addMessage(
                    ChatMessage(
                        text = "",
                        isSent = true,
                        type = ChatMessage.Type.FILE,
                        filePath = copy.absolutePath,
                        fileName = copy.name,
                        fileSizeBytes = copy.length(),
                        localId = localId,
                        wireId = wireId,
                        forwarded = true,
                        forwardedFrom = author
                    )
                )
            }
        }
    }

    /** The toast every failed forward shows: nothing was sent. */
    private fun toastNotConnectedForward() {
        Toast.makeText(
            this, R.string.not_connected_forward, Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * The file a media forward sends: our own sends keep their
     * files in the cache folder, and a forward is a new send, so
     * it gets its own cache copy of the file it re-sends — the
     * forwarded row then stands on its own, even if the original
     * message is later deleted (which removes the original's
     * file). A file that is already in the cache folder (one of
     * our own earlier sends) is used as it is. Null — with the
     * "file gone" toast shown — when the file is no longer on
     * this phone or the copy fails.
     */
    private fun mediaCopyForForward(path: String?): File? {
        val source = path?.let { File(it) }
        if (source == null || !source.exists() || source.length() == 0L) {
            Toast.makeText(this, R.string.file_gone, Toast.LENGTH_SHORT).show()
            return null
        }
        if (source.absoluteFile.parentFile?.absolutePath ==
            cacheDir.absolutePath
        ) {
            return source
        }
        val dest = uniqueCacheFile(source.name)
        val copy = try {
            source.copyTo(dest, overwrite = false)
            dest.takeIf { it.exists() && it.length() > 0 }
        } catch (e: Exception) {
            null
        }
        if (copy == null) {
            try {
                dest.delete()
            } catch (ignored: Exception) {
                // A half copy that will not delete is harmless.
            }
            Toast.makeText(this, R.string.file_gone, Toast.LENGTH_SHORT).show()
        }
        return copy
    }

    /**
     * A free file name in the cache folder: the name itself, or
     * "name (1)", "name (2)", ... — the same rule the received
     * files follow, so a forward never overwrites another file.
     */
    private fun uniqueCacheFile(name: String): File {
        var candidate = File(cacheDir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(cacheDir, "$base ($counter)$extension")
            counter++
        }
        return candidate
    }

    // ---------- Chat search ----------

    /**
     * The 🔍 header button: opens the search bar under the header
     * (and focuses its field), or closes it again. While the bar is
     * open, typing narrows the chat list live — the adapter filters
     * its visible slice; the full chat, and the history saved from
     * it, stay untouched.
     */
    private fun toggleSearchBar() {
        if (searchBar.visibility == View.VISIBLE) {
            closeSearch()
            return
        }
        searchBar.visibility = View.VISIBLE
        editSearch.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
            as InputMethodManager
        imm.showSoftInput(editSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Closes the search bar: clearing the field makes the watcher
     * restore the full list, then the bar and keyboard go away.
     * Sending a message calls this too (see addMessage), so the user
     * always lands back on the full chat and sees their own bubble.
     */
    private fun closeSearch() {
        if (searchBar.visibility != View.VISIBLE) return
        editSearch.text.clear()
        searchBar.visibility = View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
            as InputMethodManager
        imm.hideSoftInputFromWindow(editSearch.windowToken, 0)
    }

    /**
     * The name a quote of [message] carries: the original author's
     * display name. Our own messages are quoted under our own name (the
     * other phones stamp it into the quote, exactly like the sender
     * name of a normal message); a received message whose author is
     * unknown (saved by an older app version) is quoted as "Friend".
     */
    private fun quoteAuthorOf(message: ChatMessage): String = when {
        message.isSent -> chatManager.displayBaseName()
        !message.senderName.isNullOrBlank() -> message.senderName
        else -> getString(R.string.quote_friend)
    }

    /** The one-line preview a quote of [message] shows. */
    private fun quotePreviewOf(message: ChatMessage): String = when (message.type) {
        ChatMessage.Type.TEXT -> {
            val oneLine = message.text.replace('\n', ' ').trim()
            if (oneLine.length > QUOTE_PREVIEW_MAX_CHARS) {
                oneLine.substring(0, QUOTE_PREVIEW_MAX_CHARS) + "…"
            } else {
                oneLine
            }
        }
        ChatMessage.Type.VOICE ->
            getString(R.string.quote_voice_preview, formatDuration(message.durationSeconds))
        ChatMessage.Type.PHOTO -> getString(R.string.quote_photo_preview)
        ChatMessage.Type.VIDEO ->
            getString(R.string.quote_video_preview, formatDuration(message.durationSeconds))
        ChatMessage.Type.FILE -> getString(
            R.string.quote_file_preview,
            message.fileName ?: message.filePath?.let { File(it).name } ?: ""
        )
        ChatMessage.Type.LOCATION -> getString(R.string.quote_location_preview)
    }

    /** "0:07" style, like the voice rows in the chat show it. */
    private fun formatDuration(totalSeconds: Int): String =
        String.format(
            java.util.Locale.getDefault(), "%d:%02d",
            totalSeconds / 60, totalSeconds % 60
        )

    // ---------- Voice messages ----------

    /** First tap starts recording, second tap stops and sends it. */
    private fun onMicClicked() {
        if (isRecording) {
            stopRecordingAndSend()
            return
        }
        if (!chatManager.isConnected()) {
            Toast.makeText(this, R.string.not_connected_voice, Toast.LENGTH_SHORT).show()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startRecording()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        val file = File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val newRecorder: MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setAudioEncodingBitRate(64_000)
            newRecorder.setAudioSamplingRate(44_100)
            newRecorder.setOutputFile(file.absolutePath)
            newRecorder.prepare()
            newRecorder.start()
        } catch (e: Exception) {
            try {
                newRecorder.release()
            } catch (ignored: Exception) {
                // Recorder already gone.
            }
            file.delete()
            Toast.makeText(this, R.string.voice_recording_failed, Toast.LENGTH_SHORT).show()
            return
        }
        recorder = newRecorder
        recordingFile = file
        recordingStartedAt = SystemClock.elapsedRealtime()
        isRecording = true
        buttonMic.setColorFilter(AppColors.statusColor(this, R.color.status_error))
        setStatus(getString(R.string.recording_status), R.color.status_error)
        recordHandler.removeCallbacks(autoStopRecording)
        recordHandler.postDelayed(autoStopRecording, MAX_RECORDING_MS)
    }

    private fun stopRecordingAndSend() {
        val activeRecorder = recorder ?: return
        recordHandler.removeCallbacks(autoStopRecording)
        val file = recordingFile
        val durationSeconds = (
            (SystemClock.elapsedRealtime() - recordingStartedAt) / 1000
            ).toInt().coerceIn(1, MAX_RECORDING_SECONDS)

        var stoppedOk = true
        try {
            activeRecorder.stop()
        } catch (e: RuntimeException) {
            // Stopping too early makes the recorder refuse; the file is
            // then unusable, so we throw it away below.
            stoppedOk = false
        }
        try {
            activeRecorder.release()
        } catch (ignored: Exception) {
            // Recorder already gone.
        }
        recorder = null
        recordingFile = null
        isRecording = false
        buttonMic.setColorFilter(AppColors.iconTint(this))

        if (!stoppedOk || file == null || !file.exists() || file.length() == 0L) {
            file?.delete()
            Toast.makeText(this, R.string.voice_recording_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val localId = nextLocalId++
        val wireId = UUID.randomUUID().toString()
        if (!chatManager.sendVoiceMessage(
                file, durationSeconds, localId,
                pendingQuotedSender, pendingQuotedText,
                wireId = wireId
            )
        ) {
            Toast.makeText(this, R.string.not_connected_cannot_send, Toast.LENGTH_SHORT).show()
            return
        }
        addMessage(
            ChatMessage(
                text = "",
                isSent = true,
                type = ChatMessage.Type.VOICE,
                audioPath = file.absolutePath,
                durationSeconds = durationSeconds,
                localId = localId,
                quotedSender = pendingQuotedSender,
                quotedText = pendingQuotedText,
                wireId = wireId
            )
        )
        clearPendingQuote()
        setStatus(getString(R.string.voice_sent), R.color.status_connected)
    }

    /** Stops a recording without sending it (used when the screen closes). */
    private fun discardRecording() {
        recordHandler.removeCallbacks(autoStopRecording)
        val activeRecorder = recorder ?: return
        try {
            activeRecorder.stop()
        } catch (ignored: RuntimeException) {
            // Nothing worth keeping anyway.
        }
        try {
            activeRecorder.release()
        } catch (ignored: Exception) {
            // Recorder already gone.
        }
        recorder = null
        recordingFile?.delete()
        recordingFile = null
        isRecording = false
    }

    // ---------- Photo and video messages ----------

    private fun onPhotoClicked() {
        if (!chatManager.isConnected()) {
            Toast.makeText(this, R.string.not_connected_media, Toast.LENGTH_SHORT).show()
            return
        }
        photoPickerLauncher.launch(
            PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                .build()
        )
    }

    /**
     * Shrinks the picked photo (longest side at most [MAX_PHOTO_DIMENSION]
     * pixels), saves it as a JPEG in the cache folder and sends it. Working
     * on a small copy keeps the offline transfer fast.
     */
    private fun prepareAndSendPhoto(uri: Uri) {
        val file = try {
            createPreparedPhotoFile(uri)
        } catch (e: Exception) {
            null
        }
        if (file == null) {
            Toast.makeText(this, R.string.photo_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val localId = nextLocalId++
        val wireId = UUID.randomUUID().toString()
        if (!chatManager.sendPhotoMessage(
                file, localId, pendingQuotedSender, pendingQuotedText,
                wireId = wireId
            )
        ) {
            Toast.makeText(this, R.string.not_connected_cannot_send, Toast.LENGTH_SHORT).show()
            return
        }
        addMessage(
            ChatMessage(
                text = "",
                isSent = true,
                type = ChatMessage.Type.PHOTO,
                imagePath = file.absolutePath,
                localId = localId,
                quotedSender = pendingQuotedSender,
                quotedText = pendingQuotedText,
                wireId = wireId
            )
        )
        clearPendingQuote()
    }

    private fun createPreparedPhotoFile(uri: Uri): File? {
        // Read only the size first, without loading the whole picture.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Decode a sampled-down copy (about twice the final size at most),
        // then scale exactly; this keeps memory use modest.
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                bounds.outWidth, bounds.outHeight,
                MAX_PHOTO_DIMENSION * 2, MAX_PHOTO_DIMENSION * 2
            )
        }
        var bitmap = contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: return null

        // Turn the picture the right way up, using the rotation stored in
        // the photo itself (framework EXIF reader, Android 7 and newer;
        // older phones simply keep the picture as it comes).
        val rotationDegrees = readExifRotationDegrees(uri)
        if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            if (rotated !== bitmap) {
                bitmap.recycle()
                bitmap = rotated
            }
        }

        val largestSide = maxOf(bitmap.width, bitmap.height)
        if (largestSide > MAX_PHOTO_DIMENSION) {
            val scale = MAX_PHOTO_DIMENSION.toFloat() / largestSide
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== bitmap) {
                bitmap.recycle()
                bitmap = scaled
            }
        }

        val file = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val saved = file.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, PHOTO_JPEG_QUALITY, output)
        }
        bitmap.recycle()
        if (!saved || !file.exists() || file.length() == 0L) {
            file.delete()
            return null
        }
        return file
    }

    /** Rotation stored in the photo (0, 90, 180 or 270). 0 when unknown. */
    private fun readExifRotationDegrees(uri: Uri): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return 0
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                when (
                    ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (
                halfHeight / inSampleSize >= reqHeight &&
                halfWidth / inSampleSize >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    // ---------- Video messages ----------

    /**
     * Copies the picked video into our cache folder (keeping its name)
     * and sends it as it is — videos are not shrunk or re-encoded
     * (that would need extra libraries). The same 100 MB cap as for
     * files applies, checked up front when the picker reports a size
     * and again during the copy.
     */
    private fun prepareAndSendVideo(uri: Uri) {
        val originalName = queryDisplayName(uri)
            ?: "video_${System.currentTimeMillis()}"
        var cleanName = ChatManager.sanitizeFileName(originalName)
            .ifEmpty { "video_${System.currentTimeMillis()}" }
        // The player on the other phone (and our own history) is
        // happier with a proper ending; add the picker's one when the
        // name has none.
        if (!cleanName.contains('.')) {
            val extension = try {
                contentResolver.getType(uri)?.let {
                    MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
                }
            } catch (e: Exception) {
                null
            }
            if (!extension.isNullOrEmpty()) {
                cleanName = "$cleanName.$extension"
            }
        }

        // When the picker can tell us the size up front, refuse a video
        // that is too large without copying it first.
        val knownSize = queryFileSize(uri)
        if (knownSize != null && knownSize > MAX_FILE_SIZE_BYTES) {
            onError(getString(R.string.file_too_large))
            return
        }

        val file = when (val result = copyPickedFile(uri, cleanName)) {
            is CopyResult.Done -> result.file
            CopyResult.TooLarge -> {
                onError(getString(R.string.file_too_large))
                return
            }
            CopyResult.Failed -> {
                Toast.makeText(this, R.string.video_failed, Toast.LENGTH_SHORT).show()
                return
            }
        }
        val durationSeconds = pickedVideoDurationSeconds(file)
        val localId = nextLocalId++
        val wireId = UUID.randomUUID().toString()
        if (!chatManager.sendVideoMessage(
                file, localId, pendingQuotedSender, pendingQuotedText,
                wireId = wireId
            )
        ) {
            Toast.makeText(this, R.string.not_connected_cannot_send, Toast.LENGTH_SHORT).show()
            return
        }
        addMessage(
            ChatMessage(
                text = "",
                isSent = true,
                type = ChatMessage.Type.VIDEO,
                videoPath = file.absolutePath,
                durationSeconds = durationSeconds,
                localId = localId,
                quotedSender = pendingQuotedSender,
                quotedText = pendingQuotedText,
                wireId = wireId
            )
        )
        clearPendingQuote()
    }

    /** Reads the length of a video file, in seconds. 0 when unknown. */
    private fun pickedVideoDurationSeconds(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val millis = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            (millis / 1000).toInt()
        } catch (e: Exception) {
            0
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
                // Nothing to clean up then.
            }
        }
    }

    // ---------- File messages ----------

    private fun onFileClicked() {
        if (!chatManager.isConnected()) {
            Toast.makeText(this, R.string.not_connected_file, Toast.LENGTH_SHORT).show()
            return
        }
        filePickerLauncher.launch(arrayOf("*/*"))
    }

    /**
     * Copies the picked file into our cache folder (keeping its original
     * name) and sends it. Files over [MAX_FILE_SIZE_BYTES] are refused
     * with the normal error path, before anything is sent.
     */
    private fun prepareAndSendFile(uri: Uri) {
        val originalName = queryDisplayName(uri)
            ?: "file_${System.currentTimeMillis()}"
        val cleanName = ChatManager.sanitizeFileName(originalName)
            .ifEmpty { "file_${System.currentTimeMillis()}" }

        // When the picker can tell us the size up front, refuse a file
        // that is too large without copying it first.
        val knownSize = queryFileSize(uri)
        if (knownSize != null && knownSize > MAX_FILE_SIZE_BYTES) {
            onError(getString(R.string.file_too_large))
            return
        }

        val file = when (val result = copyPickedFile(uri, cleanName)) {
            is CopyResult.Done -> result.file
            CopyResult.TooLarge -> {
                onError(getString(R.string.file_too_large))
                return
            }
            CopyResult.Failed -> {
                Toast.makeText(this, R.string.file_failed, Toast.LENGTH_SHORT).show()
                return
            }
        }
        val localId = nextLocalId++
        val wireId = UUID.randomUUID().toString()
        if (!chatManager.sendFileMessage(
                file, localId, pendingQuotedSender, pendingQuotedText,
                wireId = wireId
            )
        ) {
            Toast.makeText(this, R.string.not_connected_cannot_send, Toast.LENGTH_SHORT).show()
            return
        }
        addMessage(
            ChatMessage(
                text = "",
                isSent = true,
                type = ChatMessage.Type.FILE,
                filePath = file.absolutePath,
                fileName = file.name,
                fileSizeBytes = file.length(),
                localId = localId,
                quotedSender = pendingQuotedSender,
                quotedText = pendingQuotedText,
                wireId = wireId
            )
        )
        clearPendingQuote()
    }

    /** How copying a picked file into the cache folder went. */
    private sealed class CopyResult {
        class Done(val file: File) : CopyResult()
        object TooLarge : CopyResult()
        object Failed : CopyResult()
    }

    /**
     * Streams the picked content into a cache file with the given name.
     * The copy stops (and the half-written file is removed) as soon as
     * the size passes [MAX_FILE_SIZE_BYTES], for pickers that do not
     * report a size up front.
     */
    private fun copyPickedFile(uri: Uri, name: String): CopyResult {
        val dest = File(cacheDir, name)
        return try {
            val input = contentResolver.openInputStream(uri) ?: return CopyResult.Failed
            var total = 0L
            var tooLarge = false
            input.use { stream ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FILE_SIZE_BYTES) {
                            tooLarge = true
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            when {
                tooLarge -> {
                    dest.delete()
                    CopyResult.TooLarge
                }
                !dest.exists() || dest.length() == 0L -> {
                    dest.delete()
                    CopyResult.Failed
                }
                else -> CopyResult.Done(dest)
            }
        } catch (e: Exception) {
            dest.delete()
            CopyResult.Failed
        }
    }

    /** The name the picker shows for this file. Null when unknown. */
    private fun queryDisplayName(uri: Uri): String? =
        queryOpenableColumn(uri, OpenableColumns.DISPLAY_NAME) { cursor, index ->
            cursor.getString(index)
        }

    /** The size the picker reports for this file. Null when unknown. */
    private fun queryFileSize(uri: Uri): Long? =
        queryOpenableColumn(uri, OpenableColumns.SIZE) { cursor, index ->
            if (cursor.isNull(index)) null else cursor.getLong(index)
        }

    private fun <T> queryOpenableColumn(
        uri: Uri,
        column: String,
        read: (android.database.Cursor, Int) -> T?
    ): T? {
        return try {
            contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(column)
                if (index >= 0 && cursor.moveToFirst()) read(cursor, index) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Location messages ----------

    /**
     * The Location attach-menu item: shares this phone's current position. Without the
     * location permission there is nothing to share, so it is asked
     * for first and the tap ends there (after granting, the user taps
     * again). The position comes from the framework LocationManager —
     * the app has no Google location library and gains none.
     */
    private fun onLocationClicked() {
        if (!chatManager.isConnected()) {
            Toast.makeText(this, R.string.not_connected_location, Toast.LENGTH_SHORT).show()
            return
        }
        if (pendingLocationListener != null) {
            // A fix is already on its way; extra taps change nothing.
            return
        }
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        fetchAndSendLocation()
    }

    /**
     * Finds the position and shares it. A last-known position that is
     * still fresh (at most [LOCATION_FRESH_MS] old) is shared at once;
     * otherwise one fresh fix is requested — GPS first, the network
     * provider as fallback — with a timeout per provider. When no
     * provider is on, or none answers in time, the normal error path
     * explains what to do.
     */
    @SuppressLint("MissingPermission") // checked in onLocationClicked
    private fun fetchAndSendLocation() {
        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            onError(getString(R.string.location_unavailable))
            return
        }
        freshLastKnownLocation(manager)?.let { fresh ->
            sendLocation(fresh)
            return
        }
        val providers = enabledLocationProviders(manager)
        if (providers.isEmpty()) {
            onError(getString(R.string.location_unavailable))
            return
        }
        setStatus(getString(R.string.location_finding), R.color.status_idle)
        requestSingleFix(manager, providers)
    }

    /**
     * The newest last-known position from GPS or the network provider,
     * when it is at most [LOCATION_FRESH_MS] old; older (or none) is
     * not worth sharing, so null then.
     */
    @SuppressLint("MissingPermission") // checked in onLocationClicked
    private fun freshLastKnownLocation(manager: LocationManager): Location? {
        val now = System.currentTimeMillis()
        var best: Location? = null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val candidate = try {
                manager.getLastKnownLocation(provider)
            } catch (e: Exception) {
                null // no such provider on this phone, or no permission
            } ?: continue
            val currentBest = best
            if (currentBest == null || candidate.time > currentBest.time) {
                best = candidate
            }
        }
        val fresh = best ?: return null
        return if (now - fresh.time <= LOCATION_FRESH_MS) fresh else null
    }

    /** The providers that are switched on, GPS before network. */
    private fun enabledLocationProviders(manager: LocationManager): List<String> =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider ->
                try {
                    manager.isProviderEnabled(provider)
                } catch (e: Exception) {
                    false // no such provider on this phone
                }
            }

    /**
     * Asks the first of [providers] for one fresh fix and waits at
     * most [LOCATION_TIMEOUT_MS] for it. The answer is delivered on
     * the main thread (the looper given to the request), where the
     * chat lives.
     */
    @SuppressLint("MissingPermission") // checked in onLocationClicked
    private fun requestSingleFix(manager: LocationManager, providers: List<String>) {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                stopLocationWait()
                sendLocation(location)
            }

            override fun onProviderDisabled(provider: String) {
                // The timeout that is already running ends this wait.
            }

            override fun onProviderEnabled(provider: String) = Unit

            @Deprecated("Only called on old Android versions.")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        pendingLocationManager = manager
        pendingLocationListener = listener
        pendingLocationProviders = providers.drop(1)
        try {
            manager.requestSingleUpdate(providers.first(), listener, Looper.getMainLooper())
        } catch (e: Exception) {
            // The request itself refused (provider just went away, ...):
            // treat it like a timeout for this provider.
            onLocationTimeout()
            return
        }
        locationHandler.removeCallbacks(locationTimeout)
        locationHandler.postDelayed(locationTimeout, LOCATION_TIMEOUT_MS)
    }

    /**
     * One provider did not answer in time: try the next one that is
     * left, or give up through the normal error path.
     */
    private fun onLocationTimeout() {
        val manager = pendingLocationManager
        val remaining = pendingLocationProviders
        stopLocationWait()
        if (manager != null && remaining.isNotEmpty()) {
            requestSingleFix(manager, remaining)
        } else {
            onError(getString(R.string.location_unavailable))
        }
    }

    /** Ends a wait for a fix, whatever stage it reached. */
    private fun stopLocationWait() {
        locationHandler.removeCallbacks(locationTimeout)
        val manager = pendingLocationManager
        val listener = pendingLocationListener
        if (manager != null && listener != null) {
            try {
                manager.removeUpdates(listener)
            } catch (ignored: Exception) {
                // The listener was never registered, or is already gone.
            }
        }
        pendingLocationManager = null
        pendingLocationListener = null
        pendingLocationProviders = emptyList()
    }

    /** Puts the position on its way and shows our own bubble at once. */
    private fun sendLocation(location: Location) {
        val localId = nextLocalId++
        val wireId = UUID.randomUUID().toString()
        if (!chatManager.sendLocationMessage(
                location.latitude, location.longitude, localId, wireId
            )
        ) {
            Toast.makeText(this, R.string.not_connected_cannot_send, Toast.LENGTH_SHORT).show()
            return
        }
        addMessage(
            ChatMessage(
                text = "",
                isSent = true,
                type = ChatMessage.Type.LOCATION,
                latitude = location.latitude,
                longitude = location.longitude,
                localId = localId,
                wireId = wireId
            )
        )
        setStatus(getString(R.string.location_sent), R.color.status_connected)
    }

    // ---------- Permissions ----------

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return list.toTypedArray()
    }

    // ---------- Helpers ----------

    /**
     * Sets the status line — and remembers it as the line's real
     * content. A typing line may be borrowing the line when this
     * is called; a real status always wins it back at once (the
     * typing state itself is kept until its own timeout — it
     * just stops being what the line shows), and when the typing
     * line later ends, [showRealStatus] restores this status,
     * never an older one.
     */
    private fun setStatus(text: String, colorRes: Int) {
        currentStatusText = text
        currentStatusColorRes = colorRes
        showRealStatus()
    }

    private fun addMessage(message: ChatMessage) {
        // Our own sends close an open chat search first, so the user
        // lands back on the full list and sees the bubble they just
        // sent. Incoming messages leave the search alone: they join
        // the full list and show in the results only if they match.
        if (message.isSent) closeSearch()
        adapter.addMessage(message)
        recyclerMessages.scrollToPosition(adapter.itemCount - 1)
        // Keep the on-phone history in step, so the chat survives a restart.
        ChatHistoryStore.save(this, adapter.snapshot())
    }

    // ---------- Display name & chat history ----------

    /** Lets the user change the name other phones see for this phone. */
    private fun showNameDialog() {
        val input = EditText(this).apply {
            setText(chatManager.displayBaseName())
            setSelection(text.length)
            setSingleLine(true)
            hint = getString(R.string.name_dialog_hint)
        }
        // Give the text field some room inside the dialog.
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = (20 * resources.displayMetrics.density).toInt()
            setPadding(side, 0, side, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.name_dialog_title)
            .setMessage(R.string.name_dialog_message)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                chatManager.updateDisplayName(input.text.toString())
                Toast.makeText(
                    this,
                    getString(R.string.name_saved, chatManager.displayBaseName()),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Asks first, then wipes the saved history and the on-screen chat. */
    private fun confirmClearChat() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_chat_title)
            .setMessage(R.string.clear_chat_message)
            .setPositiveButton(R.string.clear_chat_confirm) { _, _ ->
                adapter.clearMessages()
                ChatHistoryStore.clear(this)
                Toast.makeText(this, R.string.chat_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- PIN lock ----------

    /**
     * The PIN lock overflow-menu item. With no PIN set it goes straight to the
     * set flow; with one set it offers Change or Remove, and both of
     * those first ask for the current PIN. This only ever runs while
     * the app is unlocked — at the lock screen the header sits under
     * the overlay, out of reach.
     */
    private fun showPinLockMenu() {
        if (!PinLock.isSet(this)) {
            promptNewPin(R.string.pin_set)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pin_lock_title)
            .setItems(
                arrayOf(
                    getString(R.string.pin_change),
                    getString(R.string.pin_remove)
                )
            ) { _, which ->
                when (which) {
                    0 -> promptCurrentPin { promptNewPin(R.string.pin_updated) }
                    1 -> promptCurrentPin {
                        PinLock.remove(this)
                        Toast.makeText(
                            this, R.string.pin_removed, Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Asks for the current PIN; runs [action] only when it checks out. */
    private fun promptCurrentPin(action: () -> Unit) {
        promptPin(R.string.pin_enter_current) { pin ->
            if (PinLock.verify(this, pin)) {
                action()
            } else {
                Toast.makeText(this, R.string.pin_wrong, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * The set/change flow: a new PIN, then the same PIN once more to
     * be sure. Only a matching pair is saved; [doneMessage] is the
     * toast shown on success ("PIN set" the first time, "PIN
     * changed" afterwards).
     */
    private fun promptNewPin(doneMessage: Int) {
        promptPin(R.string.pin_enter_new) { newPin ->
            promptPin(R.string.pin_confirm_new) { confirmed ->
                if (confirmed == newPin) {
                    PinLock.setPin(this, newPin)
                    Toast.makeText(this, doneMessage, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this, R.string.pin_mismatch, Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * One dialog with a single numeric-password field, in the same
     * shape as the display-name dialog. The entered text goes to
     * [onEntered] — but only when it is exactly 4 digits; anything
     * else gets an explanation and the flow stops there.
     */
    private fun promptPin(title: Int, onEntered: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setSingleLine(true)
            hint = getString(R.string.pin_hint)
        }
        // Give the text field some room inside the dialog.
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = (20 * resources.displayMetrics.density).toInt()
            setPadding(side, 0, side, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pin = input.text.toString()
                if (pin.length == 4 && pin.all { it.isDigit() }) {
                    onEntered(pin)
                } else {
                    Toast.makeText(
                        this, R.string.pin_must_be_4, Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- ChatManager.Listener ----------

    override fun onSearching() {
        if (closing) return
        setStatus(getString(R.string.status_searching), R.color.status_idle)
    }

    override fun onConnectionRequest(
        endpointName: String,
        authDigits: String,
        accept: () -> Unit,
        reject: () -> Unit
    ) {
        if (closing) return
        requestDialog?.dismiss()
        requestDialog = AlertDialog.Builder(this)
            .setTitle(R.string.confirm_title)
            .setMessage(getString(R.string.confirm_message, endpointName, authDigits))
            .setCancelable(false)
            .setPositiveButton(R.string.accept) { _, _ -> accept() }
            .setNegativeButton(R.string.reject) { _, _ ->
                // ChatManager reports the right status for our role
                // (searching again, or still hosting the group).
                reject()
            }
            .show()
    }

    override fun onConnecting(endpointName: String) {
        if (closing) return
        setStatus(getString(R.string.status_connecting, endpointName), R.color.status_idle)
    }

    override fun onConnected(endpointName: String) {
        if (closing) return
        requestDialog?.dismiss()
        setStatus(getString(R.string.status_connected, endpointName), R.color.status_connected)
    }

    override fun onGroupHostUpdate(connectedCount: Int) {
        if (closing) return
        // The group changed shape: the one who left may be the
        // one who was typing. Their state is forgotten — a member
        // who is still typing re-announces within a couple of
        // seconds anyway.
        forgetTypingPeers()
        setStatus(
            getString(R.string.status_hosting, connectedCount),
            R.color.status_connected
        )
    }

    override fun onDisconnected() {
        if (closing) return
        requestDialog?.dismiss()
        // Transfers died with the connection: a "receiving"
        // placeholder can never be filled now, so its row goes away
        // (ChatManager also ends each one through onReceiveFailed —
        // whichever runs first wins, the other finds nothing), and
        // no send is still in flight, so every progress bar goes.
        for (localId in receivingPlaceholders.values.toList()) {
            adapter.removeMessage(localId)
        }
        receivingPlaceholders.clear()
        adapter.clearTransferProgress()
        // Any walkie-talkie talk died with the connection; an open
        // dialog just loses its button (refreshWalkieDialog shows
        // the not-connected line), it is not closed from under the
        // user. (ChatManager stops the capture itself.)
        isTalkingNow = false
        peerTalking = false
        peerTalkingName = null
        refreshWalkieDialog()
        // Typing states died with the connection too: ours (the
        // flag resets, so a later reconnect re-announces on the
        // next keystroke) and theirs (forgotten at once; the
        // setStatus below repaints the line anyway).
        typingSignalSent = false
        forgetTypingPeers()
        setStatus(getString(R.string.status_disconnected), R.color.status_idle)
    }

    override fun onReconnecting(attempt: Int, maxAttempts: Int) {
        if (closing) return
        setStatus(
            getString(R.string.status_reconnecting, attempt, maxAttempts),
            R.color.status_idle
        )
    }

    override fun onFriendNotFound(friendName: String) {
        if (closing) return
        val message = getString(R.string.friend_not_found, friendName)
        setStatus(message, R.color.status_idle)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onMessageReceived(
        text: String,
        senderName: String?,
        quotedSender: String?,
        quotedText: String?,
        wireId: String?,
        forwarded: Boolean,
        forwardedFrom: String?
    ) {
        if (closing) return
        addMessage(
            ChatMessage(
                text = text,
                isSent = false,
                senderName = senderName,
                quotedSender = quotedSender,
                quotedText = quotedText,
                wireId = wireId,
                forwarded = forwarded,
                forwardedFrom = forwardedFrom
            )
        )
    }

    override fun onReactionReceived(
        targetWireId: String,
        emoji: String,
        reactorName: String?
    ) {
        if (closing) return
        // A reaction without a name cannot be attributed to anyone;
        // one for a message this phone does not have (yet, or any
        // more) is dropped by the adapter the same way.
        val reactor = reactorName?.takeIf { it.isNotBlank() } ?: return
        if (adapter.applyReaction(targetWireId, emoji, reactor)) {
            ChatHistoryStore.save(this, adapter.snapshot())
        }
    }

    override fun onMessageEdited(
        targetWireId: String,
        newText: String,
        editorName: String?
    ) {
        if (closing) return
        // The adapter applies the edit only when the stamped name is
        // the message's author; an edit for a message this phone
        // does not have, or from anybody else, changes nothing.
        if (adapter.applyEdit(targetWireId, newText, editorName)) {
            ChatHistoryStore.save(this, adapter.snapshot())
        }
    }

    override fun onMessageDeleted(targetWireId: String, deleterName: String?) {
        if (closing) return
        // Same authorship rule as for an edit; the adapter also
        // removes the message's media file from this phone.
        if (adapter.applyDelete(targetWireId, deleterName)) {
            ChatHistoryStore.save(this, adapter.snapshot())
        }
    }

    override fun onMessageDelivered(localId: Long) {
        if (closing) return
        // The transfer is done, so its progress bar is done too.
        adapter.clearProgress(localId)
        // Put the second tick on the message, and save the history so
        // the ticks are still right after the app is reopened.
        if (adapter.markDelivered(localId)) {
            ChatHistoryStore.save(this, adapter.snapshot())
        }
    }

    override fun onSendProgress(localId: Long, percent: Int) {
        if (closing) return
        adapter.setProgress(localId, percent)
    }

    override fun onSendProgressEnded(localId: Long) {
        if (closing) return
        // The send failed or was cancelled (the error itself comes
        // through onError, as before): its bar simply goes away.
        adapter.clearProgress(localId)
    }

    override fun onReceiveStarted(
        payloadId: Long,
        type: ChatMessage.Type,
        senderName: String?,
        fileName: String?,
        voiceDurationSeconds: Int,
        quotedSender: String?,
        quotedText: String?,
        wireId: String?,
        forwarded: Boolean,
        forwardedFrom: String?
    ) {
        if (closing) return
        // The file is on its way and its info was already in, so its
        // bubble goes up at once — as a placeholder with no file yet,
        // wearing the progress bar the transfer updates fill. It is
        // deliberately NOT saved to the history: only the finished
        // message is, in onReceiveCompleted. (If a later save for
        // some other message sweeps the placeholder into a snapshot
        // anyway, the history load drops it again — a media entry
        // whose file does not exist is skipped there.)
        val localId = nextLocalId++
        receivingPlaceholders[payloadId] = localId
        val placeholder = ChatMessage(
            text = "",
            isSent = false,
            type = type,
            durationSeconds = if (type == ChatMessage.Type.VOICE) {
                voiceDurationSeconds
            } else {
                0
            },
            fileName = if (type == ChatMessage.Type.FILE) fileName else null,
            senderName = senderName,
            quotedSender = quotedSender,
            quotedText = quotedText,
            wireId = wireId,
            forwarded = forwarded,
            forwardedFrom = forwardedFrom,
            localId = localId
        )
        adapter.addMessage(placeholder)
        recyclerMessages.scrollToPosition(adapter.itemCount - 1)
        adapter.setProgress(localId, 0)
    }

    override fun onReceiveProgress(payloadId: Long, percent: Int) {
        if (closing) return
        val localId = receivingPlaceholders[payloadId] ?: return
        adapter.setProgress(localId, percent)
    }

    override fun onReceiveCompleted(
        payloadId: Long,
        filePath: String,
        fileName: String,
        fileSizeBytes: Long,
        durationSeconds: Int
    ) {
        if (closing) return
        val localId = receivingPlaceholders.remove(payloadId) ?: return
        val placeholder = adapter.messageByLocalId(localId)
        adapter.clearProgress(localId)
        if (placeholder == null) return
        if (placeholder.deleted) {
            // The author deleted this message for everyone while it
            // was still arriving here: the placeholder row (already
            // a "deleted" bubble) stands, and the file that just
            // landed goes away again instead of filling it.
            try {
                File(filePath).delete()
            } catch (ignored: Exception) {
                // Best effort, like every other media delete.
            }
            ChatHistoryStore.save(this, adapter.snapshot())
            return
        }
        // The finished message is the placeholder plus its file —
        // built by copy, so the sender, quote, message id and any
        // reactions the row collected while the file was on its way
        // all carry over. The borrowed local id is dropped again:
        // received messages carry none.
        val finished = when (placeholder.type) {
            ChatMessage.Type.VOICE -> placeholder.copy(
                audioPath = filePath,
                durationSeconds = durationSeconds,
                localId = 0
            )
            ChatMessage.Type.PHOTO -> placeholder.copy(
                imagePath = filePath,
                localId = 0
            )
            ChatMessage.Type.VIDEO -> placeholder.copy(
                videoPath = filePath,
                durationSeconds = durationSeconds,
                localId = 0
            )
            ChatMessage.Type.FILE -> placeholder.copy(
                filePath = filePath,
                fileName = fileName,
                fileSizeBytes = fileSizeBytes,
                localId = 0
            )
            else -> placeholder
        }
        adapter.replaceMessage(localId, finished)
        // Only now — the message is whole — does it reach the history.
        ChatHistoryStore.save(this, adapter.snapshot())
    }

    override fun onReceiveFailed(payloadId: Long) {
        if (closing) return
        // The file will never arrive: its placeholder row goes away
        // again. It was never saved, so nothing else needs undoing.
        val localId = receivingPlaceholders.remove(payloadId) ?: return
        adapter.removeMessage(localId)
    }

    override fun onVoiceMessageReceived(
        filePath: String,
        durationSeconds: Int,
        senderName: String?,
        quotedSender: String?,
        quotedText: String?,
        wireId: String?,
        forwarded: Boolean,
        forwardedFrom: String?
    ) {
        if (closing) return
        addMessage(
            ChatMessage(
                text = "",
                isSent = false,
                type = ChatMessage.Type.VOICE,
                audioPath = filePath,
                durationSeconds = durationSeconds,
                senderName = senderName,
                quotedSender = quotedSender,
                quotedText = quotedText,
                wireId = wireId,
                forwarded = forwarded,
                forwardedFrom = forwardedFrom
            )
        )
    }

    override fun onPhotoMessageReceived(
        filePath: String,
        senderName: String?,
        quotedSender: String?,
        quotedText: String?,
        wireId: String?,
        forwarded: Boolean,
        forwardedFrom: String?
    ) {
        if (closing) return
        addMessage(
            ChatMessage(
                text = "",
                isSent = false,
                type = ChatMessage.Type.PHOTO,
                imagePath = filePath,
                senderName = senderName,
                quotedSender = quotedSender,
                quotedText = quotedText,
                wireId = wireId,
                forwarded = forwarded,
                forwardedFrom = forwardedFrom
            )
        )
    }

    override fun onVideoMessageReceived(
        filePath: String,
        durationSeconds: Int,
        senderName: String?,
        quotedSender: String?,
        quotedText: String?,
        wireId: String?,
        forwarded: Boolean,
        forwardedFrom: String?
    ) {
        if (closing) return
        addMessage(
            ChatMessage(
                text = "",
                isSent = false,
                type = ChatMessage.Type.VIDEO,
                videoPath = filePath,
                durationSeconds = durationSeconds,
                senderName = senderName,
                quotedSender = quotedSender,
                quotedText = quotedText,
                wireId = wireId,
                forwarded = forwarded,
                forwardedFrom = forwardedFrom
            )
        )
    }

    override fun onFileMessageReceived(
        filePath: String,
        fileName: String,
        fileSizeBytes: Long,
        senderName: String?,
        quotedSender: String?,
        quotedText: String?,
        wireId: String?,
        forwarded: Boolean,
        forwardedFrom: String?
    ) {
        if (closing) return
        addMessage(
            ChatMessage(
                text = "",
                isSent = false,
                type = ChatMessage.Type.FILE,
                filePath = filePath,
                fileName = fileName,
                fileSizeBytes = fileSizeBytes,
                senderName = senderName,
                quotedSender = quotedSender,
                quotedText = quotedText,
                wireId = wireId,
                forwarded = forwarded,
                forwardedFrom = forwardedFrom
            )
        )
    }

    override fun onLocationMessageReceived(
        latitude: Double,
        longitude: Double,
        senderName: String?,
        wireId: String?,
        forwarded: Boolean,
        forwardedFrom: String?
    ) {
        if (closing) return
        addMessage(
            ChatMessage(
                text = "",
                isSent = false,
                type = ChatMessage.Type.LOCATION,
                latitude = latitude,
                longitude = longitude,
                senderName = senderName,
                wireId = wireId,
                forwarded = forwarded,
                forwardedFrom = forwardedFrom
            )
        )
    }

    /**
     * Someone started talking on the walkie-talkie channel. The
     * audio itself plays through ChatManager; here we only keep the
     * state the dialog paints from — or, while the dialog is closed,
     * let the user know with a toast that a talk is on (they hear
     * it either way).
     */
    override fun onTalkStarted(senderName: String?) {
        if (closing) return
        peerTalking = true
        peerTalkingName = senderName
        if (walkieDialog != null) {
            refreshWalkieDialog()
        } else {
            Toast.makeText(
                this,
                getString(
                    R.string.walkie_status_talking_other,
                    senderName ?: getString(R.string.quote_friend)
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** The walkie-talkie talk ended; the channel is free again. */
    override fun onTalkEnded() {
        if (closing) return
        peerTalking = false
        peerTalkingName = null
        refreshWalkieDialog()
    }

    /**
     * Our own talk ended by itself (time cap, dead pipes, a
     * microphone problem) while the button may still be held:
     * forget that we are talking, so the dialog paints the free
     * channel and the next press starts cleanly.
     */
    override fun onOwnTalkEnded() {
        if (closing) return
        isTalkingNow = false
        refreshWalkieDialog()
    }

    /**
     * Someone's typing state changed (see [ChatManager]). The
     * name is the typer's own — in a group it names whoever is
     * typing, not the host who relayed the signal. A typer
     * without a name cannot be announced and is ignored, like an
     * unattributed reaction. The display and its timeout live
     * in [refreshTypingIndicator].
     */
    override fun onTypingChanged(senderName: String?, isTyping: Boolean) {
        if (closing) return
        val name = senderName?.takeIf { it.isNotBlank() } ?: return
        if (isTyping) {
            // Re-inserted at the end of the map, so the name the
            // status line shows is the most recent typer.
            typingPeers.remove(name)
            typingPeers[name] =
                SystemClock.uptimeMillis() + TYPING_DISPLAY_MS
        } else {
            typingPeers.remove(name)
        }
        refreshTypingIndicator()
    }

    override fun onError(message: String) {
        if (closing) return
        requestDialog?.dismiss()
        setStatus(getString(R.string.error_generic, message), R.color.status_error)
        Toast.makeText(this, R.string.error_hint, Toast.LENGTH_LONG).show()
    }

    companion object {
        /**
         * Whether this process has already passed the PIN gate.
         * Static on purpose: recreate() for the dark-mode toggle
         * builds a new activity in the same process and must not
         * demand the PIN again mid-session, while a genuinely fresh
         * app start (a new process — including after Android killed
         * the backgrounded app) starts locked again when a PIN is set.
         */
        private var sessionUnlocked = false

        private const val MAX_RECORDING_MS = 120_000L // 2 minutes
        private const val MAX_RECORDING_SECONDS = 120
        private const val MAX_PHOTO_DIMENSION = 1600 // pixels, longest side
        private const val PHOTO_JPEG_QUALITY = 80
        private const val MAX_FILE_SIZE_BYTES = 100L * 1024 * 1024 // 100 MB
        private const val QUOTE_PREVIEW_MAX_CHARS = 60
        // A last-known position older than this is not shared; a fresh
        // fix is asked for instead, and each provider gets this long
        // to answer before the next one is tried.
        private const val LOCATION_FRESH_MS = 2 * 60_000L // 2 minutes
        private const val LOCATION_TIMEOUT_MS = 15_000L // 15 seconds
        // Typing indicator: while the user types, the "typing"
        // signal repeats at most this often; a peer's indicator
        // is forgotten this long after their last signal.
        private const val TYPING_SIGNAL_THROTTLE_MS = 2_000L // 2 seconds
        private const val TYPING_DISPLAY_MS = 3_000L // 3 seconds
    }
}
