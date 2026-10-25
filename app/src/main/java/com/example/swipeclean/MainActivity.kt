package com.example.swipeclean

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

/**
 * Swipe right = delete (goes to Android's system Trash, restorable for ~30 days)
 * Swipe left  = keep
 *
 * Speed design:
 *  - the next 3 photos are decoded in the background (2 threads) and kept in a memory cache
 *  - the next photo is already visible under the top card, so a swipe reveals it instantly
 *  - the swiped card flies away as a separate "ghost", so you can swipe again immediately
 *
 * Delete modes:
 *  - Instant (no popups): after you allow "Media management" (Menu > Delete without popups)
 *  - Batched (default): one Android confirmation per 25 photos / at the end / Trash button
 */
class MainActivity : Activity() {

    private class Photo(
        val id: Long,
        val uri: Uri,
        val size: Long,
        val date: Long,
        val name: String,
        val bucket: String
    )

    private class Action(val photo: Photo, val deleted: Boolean, val index: Int, val silent: Boolean) {
        var silentDone = false
        var undone = false
    }

    private companion object {
        const val REQ_PERM = 1
        const val REQ_TRASH = 2
        const val BATCH = 25
        const val BACK_SCALE = 0.94f
    }

    private val BG = Color.parseColor("#DFE6EC")
    private val TABLE = Color.parseColor("#CBD5DE")
    private val INK = Color.parseColor("#1E2A33")
    private val MUTED = Color.parseColor("#566673")
    private val RED = Color.parseColor("#D6303F")
    private val GREEN = Color.parseColor("#138A72")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var prefs: SharedPreferences
    private val kept = HashSet<Long>()
    private val deletedIds = HashSet<Long>()
    private var allPhotos = ArrayList<Photo>()
    private var photos = ArrayList<Photo>()
    private var album: String? = null
    private var index = 0
    private val pending = ArrayList<Photo>()
    private val history = ArrayList<Action>()
    private var keptCount = 0
    private var trashedCount = 0
    private var freedBytes = 0L
    private var trashingBatch: List<Photo> = emptyList()
    private var warnedFallback = false
    private var sortMode = 0
    private val sortNames = arrayOf("Newest first", "Oldest first", "Largest first", "Random")

    // file operations (trash / untrash) run in order on one thread; decoding uses two others
    private val io = Executors.newSingleThreadExecutor()
    private val decoder = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private var decodeLimit = 1440

    private val cache = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }
    private val waiting = HashMap<Long, ArrayList<(Bitmap?) -> Unit>>()

    private var loadToken = 0
    private var ready = false
    private var busy = false
    private var currentBmp: Bitmap? = null

    private lateinit var counter: TextView
    private lateinit var tally: TextView
    private lateinit var menuBtn: Button
    private lateinit var trashBtn: Button
    private lateinit var progressTrack: FrameLayout
    private lateinit var progressFill: View
    private lateinit var stage: FrameLayout
    private lateinit var backCard: ImageView
    private lateinit var card: ImageView
    private lateinit var stampDel: TextView
    private lateinit var stampKeep: TextView
    private lateinit var msgBox: LinearLayout
    private lateinit var msgText: TextView
    private lateinit var resetBtn: Button
    private lateinit var infoText: TextView
    private lateinit var keepBtn: Button
    private lateinit var undoBtn: Button
    private lateinit var delBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("swipeclean", MODE_PRIVATE)
        sortMode = prefs.getInt("sort", 0).coerceIn(0, sortNames.size - 1)
        val saved = prefs.getStringSet("kept", emptySet()) ?: emptySet()
        for (s in saved) s.toLongOrNull()?.let { kept.add(it) }
        val dm = resources.displayMetrics
        decodeLimit = (maxOf(dm.widthPixels, dm.heightPixels) * 0.75f).toInt().coerceIn(900, 2000)
        buildUi()
        attachSwipe()
        ensurePermissionThenLoad()
    }

    override fun onResume() {
        super.onResume()
        if (::trashBtn.isInitialized) updateHeader()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun pill(label: String, color: Int): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 16f
        b.setTextColor(color)
        val bg = GradientDrawable()
        bg.setColor(Color.WHITE)
        bg.setStroke(dp(2), color)
        bg.cornerRadius = dp(28).toFloat()
        b.background = bg
        b.stateListAnimator = null
        b.setPadding(dp(22), dp(10), dp(22), dp(10))
        return b
    }

    private fun stamp(label: String, color: Int, gravity: Int, rot: Float): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 30f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(color)
        t.setPadding(dp(12), dp(2), dp(12), dp(2))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#DDFFFFFF"))
        bg.setStroke(dp(4), color)
        bg.cornerRadius = dp(4).toFloat()
        t.background = bg
        t.rotation = rot
        t.alpha = 0f
        t.elevation = dp(20).toFloat()
        val lp = FrameLayout.LayoutParams(WRAP, WRAP, gravity)
        lp.setMargins(dp(36), dp(36), dp(36), 0)
        t.layoutParams = lp
        return t
    }

    private fun makeCard(elevationDp: Int): ImageView {
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setBackgroundColor(Color.WHITE)
        iv.setPadding(dp(10), dp(10), dp(10), dp(10))
        iv.elevation = dp(elevationDp).toFloat()
        return iv
    }

    private fun cardParams(): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(MATCH, MATCH)
        lp.setMargins(dp(14), dp(14), dp(14), dp(14))
        return lp
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)

        // top bar
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(16), dp(10), dp(12), dp(10))

        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        counter = TextView(this)
        counter.textSize = 18f
        counter.setTypeface(null, Typeface.BOLD)
        counter.setTextColor(INK)
        tally = TextView(this)
        tally.textSize = 12f
        tally.setTextColor(MUTED)
        info.addView(counter)
        info.addView(tally)
        top.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))

        menuBtn = pill("Menu", INK)
        menuBtn.setOnClickListener { showMenu() }
        trashBtn = pill("Trash (0)", RED)
        trashBtn.setOnClickListener { sendTrashRequest() }
        val mp = LinearLayout.LayoutParams(WRAP, dp(44))
        mp.rightMargin = dp(8)
        top.addView(menuBtn, mp)
        top.addView(trashBtn, LinearLayout.LayoutParams(WRAP, dp(44)))
        root.addView(top)

        // progress bar (scaled, so it animates without re-layout)
        progressTrack = FrameLayout(this)
        progressTrack.setBackgroundColor(Color.parseColor("#B4C0CB"))
        progressFill = View(this)
        progressFill.setBackgroundColor(INK)
        progressFill.pivotX = 0f
        progressFill.scaleX = 0f
        progressTrack.addView(progressFill, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(progressTrack, LinearLayout.LayoutParams(MATCH, dp(4)))

        // stage: back card (next photo) under the front card
        stage = FrameLayout(this)
        stage.setBackgroundColor(TABLE)
        backCard = makeCard(3)
        backCard.scaleX = BACK_SCALE
        backCard.scaleY = BACK_SCALE
        backCard.visibility = View.INVISIBLE
        stage.addView(backCard, cardParams())
        card = makeCard(8)
        stage.addView(card, cardParams())

        stampDel = stamp("DELETE", RED, Gravity.TOP or Gravity.START, -12f)
        stampKeep = stamp("KEEP", GREEN, Gravity.TOP or Gravity.END, 12f)
        stage.addView(stampDel)
        stage.addView(stampKeep)

        msgBox = LinearLayout(this)
        msgBox.orientation = LinearLayout.VERTICAL
        msgBox.gravity = Gravity.CENTER
        msgBox.setPadding(dp(32), dp(32), dp(32), dp(32))
        msgBox.visibility = View.GONE
        msgBox.elevation = dp(24).toFloat()
        msgText = TextView(this)
        msgText.textSize = 18f
        msgText.gravity = Gravity.CENTER
        msgText.setTextColor(INK)
        resetBtn = pill("Review kept photos again", INK)
        resetBtn.visibility = View.GONE
        resetBtn.setOnClickListener { resetKept() }
        msgBox.addView(msgText)
        val rlp = LinearLayout.LayoutParams(WRAP, WRAP)
        rlp.topMargin = dp(16)
        msgBox.addView(resetBtn, rlp)
        stage.addView(msgBox, FrameLayout.LayoutParams(MATCH, MATCH))

        root.addView(stage, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // file info line
        infoText = TextView(this)
        infoText.textSize = 12f
        infoText.gravity = Gravity.CENTER
        infoText.setTextColor(MUTED)
        infoText.setSingleLine(true)
        infoText.setPadding(dp(16), dp(8), dp(16), 0)
        root.addView(infoText, LinearLayout.LayoutParams(MATCH, WRAP))

        // bottom bar
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER
        bottom.setPadding(dp(8), dp(8), dp(8), dp(16))
        keepBtn = pill("\u2713  Keep", GREEN)
        keepBtn.setOnClickListener { decide(false) }
        undoBtn = pill("Undo", INK)
        undoBtn.setOnClickListener { undo() }
        delBtn = pill("\u2715  Delete", RED)
        delBtn.setOnClickListener { decide(true) }
        val bp = LinearLayout.LayoutParams(WRAP, dp(52))
        bp.setMargins(dp(6), 0, dp(6), 0)
        bottom.addView(keepBtn, bp)
        bottom.addView(undoBtn, bp)
        bottom.addView(delBtn, bp)
        root.addView(bottom)

        setContentView(root)
    }

    private fun showMessage(text: String, showReset: Boolean = false) {
        msgText.text = text
        resetBtn.visibility = if (showReset) View.VISIBLE else View.GONE
        msgBox.visibility = View.VISIBLE
    }

    private fun hideMessage() {
        msgBox.visibility = View.GONE
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1048576.0
        return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024)
        else if (mb >= 1) String.format(Locale.US, "%.0f MB", mb)
        else String.format(Locale.US, "%d KB", maxOf(1L, bytes / 1024))
    }

    private fun canTrashSilently(): Boolean =
        Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(this)

    private fun updateHeader() {
        counter.text = "${min(index + 1, photos.size)} / ${photos.size}"
        val sb = StringBuilder("Deleted ${pending.size + trashedCount}   \u00B7   Kept $keptCount")
        if (freedBytes > 0) sb.append("   \u00B7   Freed ").append(formatSize(freedBytes))
        val a = album
        if (a != null) sb.append("\nAlbum: ").append(a)
        tally.text = sb.toString()
        trashBtn.text = "Trash (${pending.size})"
        trashBtn.visibility = if (canTrashSilently() && pending.isEmpty()) View.GONE else View.VISIBLE
        undoBtn.alpha = if (history.isEmpty()) 0.4f else 1f
        val frac = if (photos.isEmpty()) 0f else index.toFloat() / photos.size
        progressFill.animate().scaleX(frac).setDuration(150).start()
    }

    // ------------------------------------------------------- menu

    private fun showMenu() {
        val labels = ArrayList<CharSequence>()
        val actions = ArrayList<() -> Unit>()
        labels.add(if (album == null) "Choose album (all photos)" else "Choose album ($album)")
        actions.add { showAlbumPicker() }
        labels.add("Order: ${sortNames[sortMode]}")
        actions.add { showSortPicker() }
        labels.add("Shuffle remaining photos")
        actions.add { shuffleRemaining() }
        if (Build.VERSION.SDK_INT >= 31 && !canTrashSilently()) {
            labels.add("Delete without popups")
            actions.add { openManageMedia() }
        }
        if (kept.isNotEmpty()) {
            labels.add("Show kept photos again")
            actions.add { resetKept() }
        }
        AlertDialog.Builder(this)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun showSortPicker() {
        val items = ArrayList<CharSequence>()
        for (n in sortNames) items.add(n)
        AlertDialog.Builder(this)
            .setTitle("Show photos in this order")
            .setItems(items.toTypedArray()) { _, which ->
                sortMode = which
                prefs.edit().putInt("sort", which).apply()
                applyFilter()
            }
            .show()
    }

    private fun showAlbumPicker() {
        val counts = LinkedHashMap<String, Int>()
        var total = 0
        for (p in allPhotos) {
            if (kept.contains(p.id) || deletedIds.contains(p.id)) continue
            counts[p.bucket] = (counts[p.bucket] ?: 0) + 1
            total++
        }
        val names = counts.entries.sortedByDescending { it.value }.map { it.key }
        val labels = ArrayList<CharSequence>()
        labels.add("All photos ($total)")
        for (n in names) labels.add("$n (${counts[n]})")
        AlertDialog.Builder(this)
            .setTitle("Choose album")
            .setItems(labels.toTypedArray()) { _, which ->
                album = if (which == 0) null else names[which - 1]
                applyFilter()
            }
            .show()
    }

    private fun openManageMedia() {
        try {
            val i = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:$packageName"))
            startActivity(i)
            toast("Turn on the switch for this app, then come back.")
        } catch (e: Exception) {
            toast("Could not open that setting on this phone.")
        }
    }

    // ------------------------------------------------------- permission + loading

    private fun ensurePermissionThenLoad() {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            loadPhotos()
        } else {
            showMessage("Loading\u2026")
            requestPermissions(arrayOf(perm), REQ_PERM)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadPhotos()
            } else {
                showMessage("Photo access is needed.\nOpen Settings > Apps > Swipe Clean > Permissions and allow Photos.")
            }
        }
    }

    private fun loadPhotos() {
        pending.clear()
        history.clear()
        deletedIds.clear()
        keptCount = 0
        trashedCount = 0
        freedBytes = 0L
        index = 0
        ready = false
        showMessage("Loading photos\u2026")
        io.execute {
            val list = ArrayList<Photo>()
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )
            val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            contentResolver.query(base, projection, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val taken = c.getLong(takenCol)
                    val date = if (taken > 0) taken else c.getLong(addedCol) * 1000L
                    val bucket = c.getString(bucketCol) ?: "Other"
                    list.add(
                        Photo(
                            id,
                            ContentUris.withAppendedId(base, id),
                            c.getLong(sizeCol),
                            date,
                            c.getString(nameCol) ?: "",
                            bucket
                        )
                    )
                }
            }
            main.post {
                allPhotos = list
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val list = ArrayList<Photo>()
        for (p in allPhotos) {
            if (kept.contains(p.id) || deletedIds.contains(p.id)) continue
            val a = album
            if (a != null && p.bucket != a) continue
            list.add(p)
        }
        when (sortMode) {
            1 -> list.sortBy { it.date }
            2 -> list.sortByDescending { it.size }
            3 -> list.shuffle()
            else -> list.sortByDescending { it.date }
        }
        photos = list
        index = 0
        history.clear()
        hideMessage()
        showCurrent()
    }

    // ------------------------------------------------------------ decoding + cache

    private fun decode(uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val longest = maxOf(w, h)
            if (longest > decodeLimit) {
                val f = decodeLimit.toFloat() / longest
                dec.setTargetSize(maxOf(1, (w * f).toInt()), maxOf(1, (h * f).toInt()))
            }
        }
    }

    /** Gets a photo's bitmap: from cache right away, otherwise decodes in the background. */
    private fun request(p: Photo, cb: ((Bitmap?) -> Unit)?) {
        val cached = cache.get(p.id)
        if (cached != null) {
            if (cb != null) cb(cached)
            return
        }
        val existing = waiting[p.id]
        if (existing != null) {
            if (cb != null) existing.add(cb)
            return
        }
        val callbacks = ArrayList<(Bitmap?) -> Unit>()
        if (cb != null) callbacks.add(cb)
        waiting[p.id] = callbacks
        decoder.execute {
            val bmp: Bitmap? = try {
                decode(p.uri)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (bmp != null) cache.put(p.id, bmp)
                val list = waiting.remove(p.id)
                if (list != null) for (f in list) f(bmp)
            }
        }
    }

    // ------------------------------------------------------------ showing photos

    private fun showCurrent() {
        updateHeader()
        if (index >= photos.size) {
            finishedState()
            return
        }
        hideMessage()
        card.visibility = View.VISIBLE
        val p = photos[index]
        val token = ++loadToken
        card.animate().cancel()
        card.translationX = 0f
        card.translationY = 0f
        card.rotation = 0f
        card.scaleX = 1f
        card.scaleY = 1f

        val cached = cache.get(p.id)
        if (cached != null) {
            showFront(p, cached, false)
        } else {
            ready = false
            card.alpha = 0f
            backCard.alpha = 0f
            request(p) { bmp ->
                if (token == loadToken) {
                    if (bmp == null) {
                        index++
                        showCurrent()
                    } else {
                        showFront(p, bmp, true)
                    }
                }
            }
        }
    }

    private fun showFront(p: Photo, bmp: Bitmap, fade: Boolean) {
        currentBmp = bmp
        card.setImageBitmap(bmp)
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.date))
        infoText.text = "${p.name}   \u00B7   ${formatSize(p.size)}   \u00B7   $date"
        if (fade) {
            card.alpha = 0f
            card.animate().alpha(1f).setDuration(120).start()
        } else {
            card.alpha = 1f
        }
        ready = true
        loadBack()
    }

    private fun loadBack() {
        val token = loadToken
        backCard.animate().cancel()
        backCard.scaleX = BACK_SCALE
        backCard.scaleY = BACK_SCALE
        val n = index + 1
        if (n >= photos.size) {
            backCard.visibility = View.INVISIBLE
        } else {
            val np = photos[n]
            val c = cache.get(np.id)
            if (c != null) {
                backCard.setImageBitmap(c)
                backCard.alpha = 1f
                backCard.visibility = View.VISIBLE
            } else {
                backCard.alpha = 0f
                request(np) { b ->
                    if (token == loadToken && b != null) {
                        backCard.setImageBitmap(b)
                        backCard.alpha = 1f
                        backCard.visibility = View.VISIBLE
                    }
                }
            }
        }
        // warm up the cache for the photos after that
        for (k in 2..3) {
            val i = index + k
            if (i < photos.size) request(photos[i], null)
        }
    }

    private fun finishedState() {
        ready = false
        card.visibility = View.INVISIBLE
        backCard.visibility = View.INVISIBLE
        infoText.text = ""
        val toDelete = pending.size + trashedCount
        val life = lifetime()
        val freed = (if (freedBytes > 0) "\nFreed about ${formatSize(freedBytes)} this time." else "") +
            (if (life > 0) "\nAll-time freed: ${formatSize(life)}." else "")
        val msg = if (photos.isEmpty() && allPhotos.isEmpty())
            "No photos found."
        else
            "All done.\n$keptCount kept, $toDelete deleted.$freed\n\nDeleted photos stay in your Gallery's Trash for about 30 days."
        showMessage(msg, kept.isNotEmpty())
        if (pending.isNotEmpty()) sendTrashRequest()
    }

    // ---------------------------------------------------------------- swiping

    private fun attachSwipe() {
        var downX = 0f
        var lastX = 0f
        var lastT = 0L
        var vx = 0f
        var dragging = false
        card.setOnTouchListener { v, e ->
            if (!ready || busy) {
                true
            } else {
                val threshold = dp(90).toFloat()
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        lastX = e.rawX
                        lastT = e.eventTime
                        vx = 0f
                        dragging = true
                        stampDel.animate().cancel()
                        stampKeep.animate().cancel()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (dragging) {
                            val dx = e.rawX - downX
                            val dt = e.eventTime - lastT
                            if (dt > 0) {
                                vx = 0.5f * vx + 0.5f * ((e.rawX - lastX) / dt * 1000f)
                                lastX = e.rawX
                                lastT = e.eventTime
                            }
                            v.translationX = dx
                            v.rotation = dx / 25f
                            val k = min(abs(dx) / threshold, 1f)
                            stampDel.alpha = if (dx > 0) k else 0f
                            stampKeep.alpha = if (dx < 0) k else 0f
                            val s = BACK_SCALE + (1f - BACK_SCALE) * k
                            backCard.scaleX = s
                            backCard.scaleY = s
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            dragging = false
                            val dx = e.rawX - downX
                            val up = e.actionMasked == MotionEvent.ACTION_UP
                            val fling = dp(800).toFloat()
                            val minMove = dp(30).toFloat()
                            if (up && (dx > threshold || (dx > minMove && vx > fling))) decide(true)
                            else if (up && (dx < -threshold || (dx < -minMove && vx < -fling))) decide(false)
                            else snapBack()
                        }
                    }
                }
                true
            }
        }
    }

    private fun snapBack() {
        card.animate().translationX(0f).translationY(0f).rotation(0f).setDuration(150).start()
        backCard.animate().scaleX(BACK_SCALE).scaleY(BACK_SCALE).setDuration(150).start()
        stampDel.alpha = 0f
        stampKeep.alpha = 0f
    }

    /** The swiped card flies off as a separate "ghost" so the next photo is usable immediately. */
    private fun launchGhost(dir: Float) {
        val bmp = currentBmp ?: return
        val g = makeCard(12)
        g.setImageBitmap(bmp)
        g.layoutParams = cardParams()
        g.translationX = card.translationX
        g.translationY = card.translationY
        g.rotation = card.rotation
        stage.addView(g)
        g.animate()
            .translationX(dir * stage.width)
            .rotation(dir * 20f)
            .alpha(0f)
            .setDuration(180)
            .withLayer()
            .withEndAction { stage.removeView(g) }
            .start()
    }

    private fun decide(delete: Boolean) {
        if (!ready || busy || index >= photos.size) return
        val p = photos[index]
        val silent = delete && canTrashSilently()
        val dir = if (delete) 1f else -1f
        card.performHapticFeedback(if (delete) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CONFIRM)

        val shown = if (delete) stampDel else stampKeep
        val other = if (delete) stampKeep else stampDel
        other.animate().cancel()
        other.alpha = 0f
        shown.animate().cancel()
        shown.animate().alpha(0f).setDuration(120).start()

        launchGhost(dir)

        val action = Action(p, delete, index, silent)
        if (delete) {
            deletedIds.add(p.id)
            if (silent) trashNow(p, action) else pending.add(p)
        } else {
            kept.add(p.id)
            saveKept()
            keptCount++
        }
        history.add(action)
        index++
        showCurrent()
        if (delete && !silent && pending.size % BATCH == 0) sendTrashRequest()
    }

    private fun undo() {
        if (busy || history.isEmpty()) return
        val a = history.removeAt(history.size - 1)
        if (a.deleted) {
            deletedIds.remove(a.photo.id)
            pending.remove(a.photo)
            if (a.silent) {
                a.undone = true
                if (a.silentDone) {
                    trashedCount--
                    freedBytes -= a.photo.size
                    addLifetime(-a.photo.size)
                }
                busy = true
                ready = false
                val u = a.photo.uri
                val target = a.index
                io.execute {
                    try {
                        val v = ContentValues()
                        v.put(MediaStore.MediaColumns.IS_TRASHED, 0)
                        contentResolver.update(MediaStore.setIncludeTrashed(u), v, null, null)
                    } catch (e: Exception) {
                        // nothing to restore
                    }
                    main.post {
                        busy = false
                        index = target
                        showCurrent()
                    }
                }
                return
            }
        } else {
            kept.remove(a.photo.id)
            saveKept()
            keptCount--
        }
        index = a.index
        showCurrent()
    }

    private fun shuffleRemaining() {
        if (busy || index >= photos.size) return
        photos.subList(index, photos.size).shuffle()
        showCurrent()
    }

    private fun lifetime(): Long = prefs.getLong("lifetimeFreed", 0L)

    private fun addLifetime(delta: Long) {
        val v = maxOf(0L, lifetime() + delta)
        prefs.edit().putLong("lifetimeFreed", v).apply()
    }

    private fun saveKept() {
        prefs.edit().putStringSet("kept", kept.map { it.toString() }.toSet()).apply()
    }

    private fun resetKept() {
        kept.clear()
        saveKept()
        loadPhotos()
    }

    // ------------------------------------------------------------------- trash

    /** Instant mode: trash one photo right away (needs "Media management" allowed). */
    private fun trashNow(p: Photo, action: Action) {
        io.execute {
            var ok: Boolean
            try {
                val v = ContentValues()
                v.put(MediaStore.MediaColumns.IS_TRASHED, 1)
                ok = contentResolver.update(p.uri, v, null, null) > 0
            } catch (e: Exception) {
                ok = false
            }
            main.post {
                if (ok) {
                    if (!action.undone) {
                        action.silentDone = true
                        trashedCount++
                        freedBytes += p.size
                        addLifetime(p.size)
                    }
                } else if (!action.undone) {
                    pending.add(p)
                    if (!warnedFallback) {
                        warnedFallback = true
                        toast("Could not trash directly, so I will ask in batches instead.")
                    }
                }
                updateHeader()
            }
        }
    }

    /** Batched mode: one Android confirmation for all waiting photos. */
    private fun sendTrashRequest() {
        if (pending.isEmpty()) {
            toast("Nothing waiting to be trashed.")
            return
        }
        trashingBatch = ArrayList(pending)
        try {
            val pi = MediaStore.createTrashRequest(contentResolver, trashingBatch.map { it.uri }, true)
            startIntentSenderForResult(pi.intentSender, REQ_TRASH, null, 0, 0, 0)
        } catch (e: Exception) {
            toast("Could not start the trash request: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TRASH) {
            if (resultCode == RESULT_OK) {
                val ids = trashingBatch.map { it.id }.toSet()
                var bytes = 0L
                for (p in trashingBatch) bytes += p.size
                trashedCount += trashingBatch.size
                freedBytes += bytes
                addLifetime(bytes)
                pending.removeAll { ids.contains(it.id) }
                history.removeAll { it.deleted && ids.contains(it.photo.id) }
                toast("Moved ${trashingBatch.size} photos to Trash.")
            } else {
                toast("Cancelled. Photos are still waiting; tap Trash to try again.")
            }
            trashingBatch = emptyList()
            updateHeader()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (pending.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("${pending.size} photos are waiting to be deleted")
                .setPositiveButton("Trash them") { _, _ -> sendTrashRequest() }
                .setNegativeButton("Leave without deleting") { _, _ -> finish() }
                .setNeutralButton("Stay", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }
}
