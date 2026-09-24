package com.local.joybook

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * Home is the player for the open book. Everything about files — books, chapters, folder —
 * lives behind # (Menu), so day-to-day listening is just OK / ◂ ▸.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PAGE_PLAYER = 0
        private const val PAGE_MENU = 1
        private const val PAGE_BOOKS = 2
        private const val PAGE_CHAPTERS = 3
        /** One stick click = one step; ignore contact chatter. */
        private const val NAV_DEBOUNCE_MS = 140L
        /** Holding ◂ / ▸ keeps skipping at this pace. */
        private const val SKIP_REPEAT_MS = 300L
    }

    private lateinit var pager: ViewFlipper
    private lateinit var headerTitle: TextView
    private lateinit var speedBadge: TextView
    private lateinit var sleepBadge: TextView
    private lateinit var playerContent: View
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView
    private lateinit var emptyHint: TextView
    private lateinit var coverImage: ImageView
    private lateinit var bookTitle: TextView
    private lateinit var bookSub: TextView
    private lateinit var chapterText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timePos: TextView
    private lateinit var timeLeft: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var backStep: TextView
    private lateinit var fwdStep: TextView
    private lateinit var bookPercent: TextView
    private lateinit var bookProgress: ProgressBar
    private lateinit var bookLeft: TextView
    private lateinit var booksList: StickList
    private lateinit var chaptersList: StickList
    private lateinit var rowBooks: SettingRow
    private lateinit var rowChapters: SettingRow
    private lateinit var rowSpeed: SettingRow
    private lateinit var rowSleep: SettingRow
    private lateinit var rowSkip: SettingRow
    private lateinit var rowFolder: SettingRow
    private lateinit var rowRefresh: SettingRow
    private lateinit var rowTouch: SettingRow
    private lateinit var rowJoystick: SettingRow

    private val booksAdapter = BooksAdapter()
    private val chaptersAdapter = ChaptersAdapter()
    private var service: PlaybackService? = null
    private var bound = false
    private var scanning = false
    private var userSeeking = false
    private var lastNavAt = 0L
    private var lastSkipAt = 0L
    private var shownCover: Any? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scanExecutor = Executors.newSingleThreadExecutor()

    private val tick = object : Runnable {
        override fun run() {
            if (pager.displayedChild == PAGE_PLAYER) updateProgress()
            updateBadges()
            handler.postDelayed(this, 500)
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as PlaybackService.LocalBinder).service()
            updateAll()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    // Posted, not inline, so a rescan can finish swapping state before the UI re-reads it.
    private val changeListener: () -> Unit = { handler.post { updateAll() } }

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val name = DocumentFile.fromTreeUri(this, uri)?.name
            ?: uri.lastPathSegment?.substringAfterLast(':')
            ?: "folder"
        Prefs.setBooksFolder(this, uri.toString(), name)
        updateMenu()
        loadLibrary(announce = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pager = findViewById(R.id.pager)
        headerTitle = findViewById(R.id.headerTitle)
        speedBadge = findViewById(R.id.speedBadge)
        sleepBadge = findViewById(R.id.sleepBadge)
        playerContent = findViewById(R.id.playerContent)
        emptyView = findViewById(R.id.emptyView)
        emptyText = findViewById(R.id.emptyText)
        emptyHint = findViewById(R.id.emptyHint)
        coverImage = findViewById(R.id.coverImage)
        coverImage.clipToOutline = true
        bookTitle = findViewById(R.id.bookTitle)
        bookSub = findViewById(R.id.bookSub)
        chapterText = findViewById(R.id.chapterText)
        seekBar = findViewById(R.id.seekBar)
        timePos = findViewById(R.id.timePos)
        timeLeft = findViewById(R.id.timeLeft)
        btnPlay = findViewById(R.id.btnPlay)
        backStep = findViewById(R.id.backStep)
        fwdStep = findViewById(R.id.fwdStep)
        bookPercent = findViewById(R.id.bookPercent)
        bookProgress = findViewById(R.id.bookProgress)
        bookLeft = findViewById(R.id.bookLeft)

        val booksRv = findViewById<RecyclerView>(R.id.booksList)
        booksList = StickList(booksRv)
        booksRv.adapter = booksAdapter
        val chaptersRv = findViewById<RecyclerView>(R.id.chaptersList)
        chaptersList = StickList(chaptersRv)
        chaptersRv.adapter = chaptersAdapter

        setupControls()
        setupMenu()

        PlaybackService.ensureLoaded(this)
        showPage(PAGE_PLAYER)
        requestNotificationPermission()
        loadLibrary(announce = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showPage(PAGE_PLAYER)
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, PlaybackService::class.java), conn, Context.BIND_AUTO_CREATE)
        PlaybackService.addListener(changeListener)
        BookData.addListener(changeListener)
        handler.post(tick)
    }

    override fun onResume() {
        super.onResume()
        A11yBootstrap.ensureEnabled(this)
        updateAll()
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        PlaybackService.removeListener(changeListener)
        BookData.removeListener(changeListener)
        if (bound) {
            unbindService(conn)
            bound = false
        }
        service = null
        super.onStop()
    }

    override fun onDestroy() {
        scanExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (Prefs.ignoreTouch(this)) return true
        return super.dispatchTouchEvent(ev)
    }

    // ---- Keys --------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val page = pager.displayedChild

        // * = flashlight on this phone: never steal it.
        if (code == KeyEvent.KEYCODE_STAR) return super.dispatchKeyEvent(event)

        // # (or Menu): player <-> menu; from a list, back to the menu.
        if (code == KeyEvent.KEYCODE_POUND || code == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                showPage(if (page == PAGE_MENU) PAGE_PLAYER else PAGE_MENU)
            }
            return true
        }

        if (code == KeyEvent.KEYCODE_BACK && page != PAGE_PLAYER) {
            if (event.action == KeyEvent.ACTION_UP) showPage(if (page == PAGE_MENU) PAGE_PLAYER else PAGE_MENU)
            return true
        }

        if (page == PAGE_MENU) return super.dispatchKeyEvent(event) // DPAD moves focus between rows

        val handled = when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_8 -> true
            else -> false
        }
        if (!handled) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val now = SystemClock.uptimeMillis()

        // Holding ◂ / ▸ on the player keeps skipping.
        if (page == PAGE_PLAYER && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (event.repeatCount > 0 && now - lastSkipAt < SKIP_REPEAT_MS) return true
            if (event.repeatCount == 0 && now - lastNavAt < NAV_DEBOUNCE_MS) return true
            lastSkipAt = now
            lastNavAt = now
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) control(PlaybackService.ACTION_REWIND) { it.skipBack() }
            else control(PlaybackService.ACTION_FORWARD) { it.skipForward() }
            return true
        }

        if (event.repeatCount > 0 || now - lastNavAt < NAV_DEBOUNCE_MS) return true
        lastNavAt = now

        when (page) {
            PAGE_PLAYER -> when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> control(PlaybackService.ACTION_NEXT_CHAPTER) { it.nextChapter() }
                KeyEvent.KEYCODE_DPAD_DOWN -> control(PlaybackService.ACTION_PREV_CHAPTER) { it.prevChapter() }
                KeyEvent.KEYCODE_1 -> changeSpeed(-1)
                KeyEvent.KEYCODE_3 -> changeSpeed(1)
                KeyEvent.KEYCODE_0 -> cycleSleep()
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (PlaybackService.book == null) showPage(PAGE_MENU)
                    else control(PlaybackService.ACTION_TOGGLE) { it.toggle() }
                }
            }
            PAGE_BOOKS, PAGE_CHAPTERS -> {
                val list = if (page == PAGE_BOOKS) booksList else chaptersList
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> list.move(-1, wrap = true)
                    KeyEvent.KEYCODE_DPAD_DOWN -> list.move(1, wrap = true)
                    KeyEvent.KEYCODE_2 -> list.move(-list.pageSize(), wrap = false)
                    KeyEvent.KEYCODE_8 -> list.move(list.pageSize(), wrap = false)
                    KeyEvent.KEYCODE_5 -> list.setCursor(currentRow(page))
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (page == PAGE_BOOKS) openBookAt(list.cursor) else openChapterAt(list.cursor)
                    }
                }
            }
        }
        return true
    }

    private fun currentRow(page: Int): Int = if (page == PAGE_BOOKS) {
        BookData.books.indexOfFirst { it.id == PlaybackService.book?.id }.coerceAtLeast(0)
    } else {
        PlaybackService.chapterIndex
    }

    // ---- Pages -------------------------------------------------------------------------

    private fun showPage(page: Int) {
        if (pager.displayedChild != page) pager.displayedChild = page
        headerTitle.setText(
            when (page) {
                PAGE_MENU -> R.string.menu
                PAGE_BOOKS -> R.string.books
                PAGE_CHAPTERS -> R.string.chapters
                else -> R.string.app_name
            }
        )
        setHints(page)
        when (page) {
            PAGE_PLAYER -> {
                currentFocus?.clearFocus()
                updatePlayer()
            }
            PAGE_MENU -> {
                updateMenu()
                findViewById<View>(R.id.menuScroll).scrollTo(0, 0)
                rowBooks.root.requestFocus()
            }
            PAGE_BOOKS -> {
                currentFocus?.clearFocus()
                booksList.reset(currentRow(page))
            }
            PAGE_CHAPTERS -> {
                currentFocus?.clearFocus()
                chaptersList.reset(currentRow(page).coerceIn(0, (chaptersAdapter.itemCount - 1).coerceAtLeast(0)))
            }
        }
    }

    private fun setHints(page: Int) {
        val keys = listOf(R.id.keyA, R.id.keyB, R.id.keyC, R.id.keyD).map { findViewById<TextView>(it) }
        val labels = listOf(R.id.labelA, R.id.labelB, R.id.labelC, R.id.labelD).map { findViewById<TextView>(it) }
        val hints: List<Pair<String, String>> = when (page) {
            PAGE_PLAYER -> listOf(
                "◂ ▸" to getString(R.string.hint_skip, Prefs.skipSec(this)),
                "▴ ▾" to getString(R.string.hint_chapter),
                "OK" to getString(R.string.hint_play),
                "#" to getString(R.string.hint_menu),
            )
            PAGE_MENU -> listOf(
                "▴ ▾" to getString(R.string.hint_move),
                "OK" to getString(R.string.hint_select),
                "#" to getString(R.string.hint_back),
            )
            PAGE_BOOKS -> listOf(
                "▴ ▾" to getString(R.string.hint_move),
                "OK" to getString(R.string.hint_open),
                "#" to getString(R.string.hint_back),
            )
            else -> listOf(
                "▴ ▾" to getString(R.string.hint_move),
                "OK" to getString(R.string.hint_play),
                "#" to getString(R.string.hint_back),
            )
        }
        for (i in keys.indices) {
            val h = hints.getOrNull(i)
            keys[i].visibility = if (h == null) View.GONE else View.VISIBLE
            labels[i].visibility = if (h == null) View.GONE else View.VISIBLE
            if (h != null) {
                keys[i].text = h.first
                labels[i].text = h.second
            }
        }
        labels.last { it.visibility == View.VISIBLE }.let { last ->
            labels.forEach { l ->
                val lp = l.layoutParams as ViewGroup.MarginLayoutParams
                lp.marginEnd = if (l === last) 0 else (resources.displayMetrics.density * 10).toInt()
                l.layoutParams = lp
            }
        }
    }

    // ---- Player ------------------------------------------------------------------------

    private fun setupControls() {
        val prevCh = findViewById<ImageButton>(R.id.btnPrevCh)
        val nextCh = findViewById<ImageButton>(R.id.btnNextCh)
        prevCh.setOnClickListener { control(PlaybackService.ACTION_PREV_CHAPTER) { it.prevChapter() } }
        nextCh.setOnClickListener { control(PlaybackService.ACTION_NEXT_CHAPTER) { it.nextChapter() } }
        findViewById<View>(R.id.btnBack).setOnClickListener { control(PlaybackService.ACTION_REWIND) { it.skipBack() } }
        findViewById<View>(R.id.btnFwd).setOnClickListener { control(PlaybackService.ACTION_FORWARD) { it.skipForward() } }
        btnPlay.setOnClickListener { control(PlaybackService.ACTION_TOGGLE) { it.toggle() } }
        // Clickable views become focusable (and paint a focus ring); the stick is handled globally.
        for (b in listOf(prevCh, btnPlay, nextCh, findViewById(R.id.btnBack), findViewById<View>(R.id.btnFwd))) {
            b.isFocusable = false
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) timePos.text = fmtTime(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }

            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                service?.seekTo(sb.progress)
            }
        })
    }

    /** Run [direct] on the bound service, or deliver [action] by intent while binding is pending. */
    private fun control(action: String, direct: (PlaybackService) -> Unit) {
        if (PlaybackService.book == null) return
        val svc = service
        if (svc != null) {
            direct(svc)
        } else {
            ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java).setAction(action))
        }
    }

    private fun changeSpeed(dir: Int) {
        val svc = service ?: return
        svc.cycleSpeed(dir)
        toast(getString(R.string.speed_set, speedLabel(svc.speed())))
    }

    private fun cycleSleep() {
        val svc = service ?: return
        val choice = svc.cycleSleep()
        toast(getString(R.string.sleep_set, sleepLabel(choice)))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun updateAll() {
        updatePlayer()
        updateMenu()
        when (pager.displayedChild) {
            PAGE_BOOKS -> booksAdapter.notifyItemRangeChanged(0, booksAdapter.itemCount)
            PAGE_CHAPTERS -> chaptersAdapter.notifyItemRangeChanged(0, chaptersAdapter.itemCount)
        }
    }

    private fun updatePlayer() {
        val b = PlaybackService.book
        val svc = service
        if (b == null) {
            playerContent.visibility = View.INVISIBLE
            emptyView.visibility = View.VISIBLE
            val noFolder = Prefs.booksFolderUri(this).isBlank()
            emptyText.setText(
                when {
                    scanning -> R.string.scanning
                    noFolder -> R.string.no_folder
                    else -> R.string.no_books
                }
            )
            emptyHint.text = if (scanning) "" else getString(if (noFolder) R.string.no_folder_hint else R.string.no_books_hint)
            updateBadges()
            return
        }
        playerContent.visibility = View.VISIBLE
        emptyView.visibility = View.GONE

        bookTitle.text = b.title
        val info = BookData.info(this, b)
        val sub = listOfNotNull(info?.author, info?.album).joinToString("  ·  ")
        bookSub.text = sub
        bookSub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        chapterText.text = b.chapterLabel(this, PlaybackService.chapterIndex)

        BookData.load(this, b)
        BookData.fillDurations(this, b)
        val cover = BookData.cover(b)
        if (cover !== shownCover) {
            shownCover = cover
            coverImage.setImageBitmap(cover)
            coverImage.visibility = if (cover != null) View.VISIBLE else View.GONE
        }

        val playing = svc?.isPlaying() == true
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription = getString(if (playing) R.string.pause else R.string.play)
        val step = Prefs.skipSec(this).toString()
        backStep.text = step
        fwdStep.text = step
        updateBadges()
        updateProgress()
    }

    private fun updateBadges() {
        val svc = service
        val speed = Prefs.speed(this)
        val showSpeed = PlaybackService.book != null && kotlin.math.abs(speed - 1f) > 0.01f
        speedBadge.visibility = if (showSpeed) View.VISIBLE else View.GONE
        if (showSpeed) speedBadge.text = speedLabel(speed)
        val remaining = svc?.sleepRemainingMs() ?: 0L
        val eoc = svc?.sleepEndOfChapter == true
        val showSleep = remaining > 0 || eoc
        sleepBadge.visibility = if (showSleep) View.VISIBLE else View.GONE
        if (showSleep) {
            sleepBadge.text = if (eoc) getString(R.string.sleep_short_chapter)
            else getString(R.string.sleep_min, ((remaining + 59_999) / 60_000).toInt())
            if (sleepBadge.compoundDrawablesRelative[0] == null) {
                val d = ContextCompat.getDrawable(this, R.drawable.ic_bedtime)?.mutate()
                val px = (resources.displayMetrics.density * 11).toInt()
                d?.setBounds(0, 0, px, px)
                d?.setTint(ContextCompat.getColor(this, R.color.ja_accent))
                sleepBadge.setCompoundDrawablesRelative(d, null, null, null)
            }
        }
    }

    /** Chapter and whole-book progress; works before the service loaded anything (resume point). */
    private fun updateProgress() {
        val b = PlaybackService.book ?: return
        if (userSeeking) return
        val svc = service
        val idx = PlaybackService.chapterIndex
        val live = svc?.hasSession() == true
        val pos: Int
        val dur: Int
        if (live) {
            pos = svc!!.position()
            dur = svc.duration()
        } else {
            val (ch, p) = PlaybackService.resumePoint(this, b)
            pos = if (ch == idx) p else 0
            dur = b.chapters.getOrNull(idx)?.let { BookData.duration(this, it.uri).toInt() } ?: 0
        }
        seekBar.max = dur.coerceAtLeast(1)
        seekBar.progress = if (dur > 0) pos.coerceIn(0, dur) else 0
        timePos.text = fmtTime(pos)
        timeLeft.text = if (dur > 0) getString(R.string.time_left, fmtTime((dur - pos).coerceAtLeast(0))) else ""

        val total = BookData.total(this, b)
        val elapsed = BookData.elapsed(this, b, idx, pos)
        if (total != null && elapsed != null && total > 0) {
            val permille = (elapsed * 1000 / total).toInt().coerceIn(0, 1000)
            bookProgress.progress = permille
            bookPercent.text = getString(R.string.percent, permille / 10)
            val speed = Prefs.speed(this)
            val left = fmtLong(((total - elapsed) / speed).toLong())
            bookLeft.text = if (kotlin.math.abs(speed - 1f) > 0.01f) getString(R.string.left_at_speed, left, speedLabel(speed))
            else getString(R.string.left_fmt, left)
        } else {
            bookProgress.progress = if (b.chapters.isEmpty()) 0 else idx * 1000 / b.chapters.size
            bookPercent.text = getString(R.string.track_pos, idx + 1, b.chapters.size)
            bookLeft.text = getString(R.string.measuring)
        }
    }

    private fun fmtLong(ms: Long): String {
        val totalMin = (ms / 60_000).coerceAtLeast(0)
        return if (totalMin >= 60) getString(R.string.hours_min, (totalMin / 60).toInt(), (totalMin % 60).toInt())
        else getString(R.string.minutes, totalMin.toInt())
    }

    private fun speedLabel(v: Float): String =
        getString(R.string.speed_x, if (v * 10 % 1f == 0f) "%.1f".format(v) else "%.2f".format(v))

    private fun sleepLabel(minutes: Int): String = when {
        minutes < 0 -> getString(R.string.sleep_chapter)
        minutes == 0 -> getString(R.string.sleep_off)
        else -> getString(R.string.sleep_min, minutes)
    }

    // ---- Menu --------------------------------------------------------------------------

    private fun setupMenu() {
        rowBooks = SettingRow(findViewById(R.id.rowBooks), R.drawable.ic_book, R.string.row_books)
        rowChapters = SettingRow(findViewById(R.id.rowChapters), R.drawable.ic_list, R.string.row_chapters)
        rowSpeed = SettingRow(findViewById(R.id.rowSpeed), R.drawable.ic_speed, R.string.row_speed)
        rowSleep = SettingRow(findViewById(R.id.rowSleep), R.drawable.ic_bedtime, R.string.row_sleep)
        rowSkip = SettingRow(findViewById(R.id.rowSkip), R.drawable.ic_forward, R.string.row_skip)
        rowFolder = SettingRow(findViewById(R.id.rowFolder), R.drawable.ic_folder, R.string.row_folder)
        rowRefresh = SettingRow(findViewById(R.id.rowRefresh), R.drawable.ic_refresh, R.string.row_refresh)
        rowTouch = SettingRow(findViewById(R.id.rowTouch), R.drawable.ic_phone, R.string.row_touch)
        rowJoystick = SettingRow(findViewById(R.id.rowJoystick), R.drawable.ic_gamepad, R.string.row_joystick)

        // DPAD focus wraps from the last row to the first and back.
        rowBooks.root.nextFocusUpId = R.id.rowJoystick
        rowJoystick.root.nextFocusDownId = R.id.rowBooks

        rowBooks.chevron.visibility = View.VISIBLE
        rowBooks.root.setOnClickListener { if (BookData.books.isNotEmpty()) showPage(PAGE_BOOKS) }
        rowChapters.chevron.visibility = View.VISIBLE
        rowChapters.root.setOnClickListener { if (PlaybackService.book != null) showPage(PAGE_CHAPTERS) }
        rowSpeed.setSubtitle(getString(R.string.row_speed_sub))
        rowSpeed.root.setOnClickListener {
            val speeds = PlaybackService.SPEEDS
            val cur = speeds.indexOfFirst { it >= Prefs.speed(this) - 0.001f }
            val next = speeds[(cur + 1) % speeds.size]
            service?.setSpeed(next) ?: Prefs.setSpeed(this, next)
            updateAll()
        }
        rowSleep.setSubtitle(getString(R.string.row_sleep_sub))
        rowSleep.root.setOnClickListener { service?.cycleSleep(); updateAll() }
        rowSkip.setSubtitle(getString(R.string.row_skip_sub))
        rowSkip.root.setOnClickListener {
            val steps = PlaybackService.SKIP_STEPS
            val next = steps[(steps.indexOf(Prefs.skipSec(this)) + 1) % steps.size]
            Prefs.setSkipSec(this, next)
            updateAll()
        }
        rowFolder.chevron.visibility = View.VISIBLE
        rowFolder.root.setOnClickListener {
            try {
                openTree.launch(null)
            } catch (_: Exception) {
            }
        }
        rowFolder.root.setOnLongClickListener {
            Prefs.clearBooksFolder(this)
            loadLibrary(announce = false)
            toast(getString(R.string.folder_cleared))
            true
        }
        rowRefresh.root.setOnClickListener { loadLibrary(announce = true) }
        rowTouch.root.setOnClickListener {
            Prefs.setIgnoreTouch(this, !Prefs.ignoreTouch(this))
            updateMenu()
        }
        rowJoystick.setSubtitle(getString(R.string.row_joystick_sub))
        rowJoystick.chevron.visibility = View.VISIBLE
        rowJoystick.root.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
            }
            toast(getString(R.string.joystick_keys_hint))
        }
    }

    private fun updateMenu() {
        val b = PlaybackService.book
        rowBooks.setSubtitle(b?.title ?: getString(R.string.books_n, BookData.books.size))
        rowChapters.setSubtitle(b?.chapterLabel(this, PlaybackService.chapterIndex) ?: "")
        rowSpeed.setValue(speedLabel(Prefs.speed(this)))
        val svc = service
        val sleep = when {
            svc?.sleepEndOfChapter == true -> getString(R.string.sleep_chapter)
            (svc?.sleepRemainingMs() ?: 0L) > 0 -> getString(R.string.sleep_min, ((svc!!.sleepRemainingMs() + 59_999) / 60_000).toInt())
            else -> getString(R.string.sleep_off)
        }
        rowSleep.setValue(sleep)
        rowSkip.setValue(getString(R.string.seconds, Prefs.skipSec(this)))
        rowFolder.setSubtitle(Prefs.booksFolderName(this).ifBlank { getString(R.string.folder_none) })
        rowRefresh.setSubtitle(if (scanning) getString(R.string.scanning) else getString(R.string.books_n, BookData.books.size))
        val touch = !Prefs.ignoreTouch(this)
        rowTouch.setToggle(touch)
        rowTouch.setSubtitle(getString(if (touch) R.string.touch_on else R.string.touch_off))
        val joystickOn = A11yBootstrap.isEnabled(this)
        rowJoystick.setBadge(joystickOn)
        findViewById<View>(R.id.a11yWarning).visibility = if (joystickOn) View.GONE else View.VISIBLE
        if (pager.displayedChild == PAGE_PLAYER) setHints(PAGE_PLAYER) // skip step may have changed
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    // ---- Library -----------------------------------------------------------------------

    private fun loadLibrary(announce: Boolean) {
        if (scanning) return
        scanning = true
        updateAll()
        val app = applicationContext
        scanExecutor.execute {
            val result = try { BookLibrary.scan(app) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                scanning = false
                BookData.books = result
                // Fall back to the saved id: prefs from JoyBook 1.0 have no stored chapter list.
                val curId = PlaybackService.book?.id ?: Prefs.currentBookId(this)
                val match = result.firstOrNull { it.id == curId }
                // Keep the open book (with its fresh chapter list); otherwise open the first one.
                PlaybackService.selectBook(this, match ?: result.firstOrNull())
                // Current book first, then the rest: tags, covers and lengths for the lists.
                (listOfNotNull(match) + result).forEach {
                    BookData.load(this, it)
                    BookData.fillDurations(this, it)
                }
                @Suppress("NotifyDataSetChanged")
                booksAdapter.notifyDataSetChanged()
                updateAll()
                if (announce) toast(getString(R.string.refreshed, result.size))
            }
        }
    }

    private fun openBookAt(index: Int) {
        val b = BookData.books.getOrNull(index) ?: return
        val svc = service
        if (svc != null) {
            svc.openBook(b)
        } else {
            PlaybackService.selectBook(this, b)
            ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_PLAY))
        }
        @Suppress("NotifyDataSetChanged")
        chaptersAdapter.notifyDataSetChanged()
        showPage(PAGE_PLAYER)
    }

    private fun openChapterAt(index: Int) {
        val svc = service ?: return
        svc.jumpToChapter(index)
        showPage(PAGE_PLAYER)
    }

    // ---- Lists -------------------------------------------------------------------------

    private inner class BooksAdapter : RecyclerView.Adapter<BookHolder>() {
        override fun getItemCount(): Int = BookData.books.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            BookHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false))

        override fun onBindViewHolder(holder: BookHolder, position: Int) =
            holder.bind(BookData.books[position], position == booksList.cursor)
    }

    private inner class BookHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.bookCover)
        private val title: TextView = view.findViewById(R.id.bookTitle)
        private val meta: TextView = view.findViewById(R.id.bookMeta)
        private val bar: ProgressBar = view.findViewById(R.id.bookBar)
        private val eq: EqualizerView = view.findViewById(R.id.bookEq)

        init {
            cover.clipToOutline = true
            view.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) openBookAt(p)
            }
        }

        fun bind(b: Book, isCursor: Boolean) {
            val ctx = this@MainActivity
            val isCurrent = b.id == PlaybackService.book?.id
            itemView.isActivated = isCursor
            title.text = b.title
            title.maxLines = if (isCursor) 3 else 1
            title.setTextColor(ContextCompat.getColor(ctx, if (isCurrent) R.color.ja_accent else R.color.ja_text))
            title.typeface = if (isCursor || isCurrent) MEDIUM else Typeface.DEFAULT

            val art = BookData.cover(b)
            cover.setImageBitmap(art)
            cover.visibility = if (art != null) View.VISIBLE else View.GONE

            val (savedCh, savedPos) = Prefs.bookProgress(ctx, b.id)
            val n = b.chapters.size
            val live = isCurrent && service?.hasSession() == true
            val ch = if (live) PlaybackService.chapterIndex else savedCh
            val pos = if (live) service!!.position() else savedPos
            when {
                ch >= n -> {
                    meta.text = getString(R.string.book_finished, n)
                    bar.progress = 1000
                }
                ch == 0 && pos == 0 -> {
                    meta.text = getString(R.string.book_new, n)
                    bar.progress = 0
                }
                else -> {
                    val total = BookData.total(ctx, b)
                    val elapsed = BookData.elapsed(ctx, b, ch, pos)
                    if (total != null && elapsed != null && total > 0) {
                        val permille = (elapsed * 1000 / total).toInt().coerceIn(0, 1000)
                        meta.text = getString(R.string.book_meta_pct, ch + 1, n, permille / 10)
                        bar.progress = permille
                    } else {
                        meta.text = getString(R.string.book_meta, ch + 1, n)
                        bar.progress = ch * 1000 / n.coerceAtLeast(1)
                    }
                }
            }
            val showEq = isCurrent && service?.hasSession() == true
            eq.visibility = if (showEq) View.VISIBLE else View.GONE
            eq.setPlaying(showEq && service?.isPlaying() == true)
        }
    }

    private inner class ChaptersAdapter : RecyclerView.Adapter<ChapterHolder>() {
        override fun getItemCount(): Int = PlaybackService.book?.chapters?.size ?: 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ChapterHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_chapter, parent, false))

        override fun onBindViewHolder(holder: ChapterHolder, position: Int) =
            holder.bind(position, position == chaptersList.cursor)
    }

    private inner class ChapterHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.chTitle)
        private val dur: TextView = view.findViewById(R.id.chDur)
        private val eq: EqualizerView = view.findViewById(R.id.chEq)

        init {
            view.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) openChapterAt(p)
            }
        }

        fun bind(index: Int, isCursor: Boolean) {
            val ctx = this@MainActivity
            val b = PlaybackService.book ?: return
            val isCurrent = index == PlaybackService.chapterIndex
            itemView.isActivated = isCursor
            title.text = b.chapterName(ctx, index)
            title.maxLines = if (isCursor) 4 else 1
            val (savedCh, _) = Prefs.bookProgress(ctx, b.id)
            val done = index < savedCh && !isCurrent
            title.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    when {
                        isCurrent -> R.color.ja_accent
                        isCursor -> R.color.ja_text
                        done -> R.color.ja_text_3
                        else -> R.color.ja_text_2
                    }
                )
            )
            title.typeface = if (isCursor || isCurrent) MEDIUM else Typeface.DEFAULT
            val ms = BookData.duration(ctx, b.chapters[index].uri)
            dur.text = if (ms > 0) fmtTime(ms.toInt()) else ""
            val showEq = isCurrent && service?.hasSession() == true
            eq.visibility = if (showEq) View.VISIBLE else View.GONE
            eq.setPlaying(showEq && service?.isPlaying() == true)
        }
    }

    private class SettingRow(val root: View, iconRes: Int, titleRes: Int) {
        private val subtitle: TextView = root.findViewById(R.id.settingSubtitle)
        private val switch: SwitchCompat = root.findViewById(R.id.settingSwitch)
        private val value: TextView = root.findViewById(R.id.settingValue)
        val chevron: ImageView = root.findViewById(R.id.settingChevron)

        init {
            root.findViewById<ImageView>(R.id.settingIcon).setImageResource(iconRes)
            root.findViewById<TextView>(R.id.settingTitle).setText(titleRes)
        }

        fun setSubtitle(text: CharSequence?) {
            subtitle.text = text
            subtitle.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        fun setToggle(on: Boolean) {
            switch.visibility = View.VISIBLE
            if (switch.isChecked != on) switch.isChecked = on
        }

        /** Current choice for rows that cycle through values on OK. */
        fun setValue(text: String) {
            value.visibility = View.VISIBLE
            value.text = text
            value.setBackgroundResource(R.drawable.bg_pill_off)
            value.setTextColor(ContextCompat.getColor(root.context, R.color.ja_accent))
        }

        fun setBadge(on: Boolean) {
            val ctx = root.context
            value.visibility = View.VISIBLE
            value.setText(if (on) R.string.on else R.string.off)
            value.setBackgroundResource(if (on) R.drawable.bg_pill_on else R.drawable.bg_pill_off)
            value.setTextColor(ContextCompat.getColor(ctx, if (on) R.color.ja_green else R.color.ja_text_2))
        }
    }
}

private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
