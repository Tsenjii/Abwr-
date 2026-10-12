package dev.local.aibrowser

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

/** Full-screen tab library. Groups organize existing sessions inside the current container. */
internal class TabOverview(private val activity: Activity, private val ctrl: BrowserController,
    private val dark: Boolean, private val opened: () -> Unit) : Dialog(activity) {
    private val bg = if (dark) Color.rgb(25, 19, 18) else Color.rgb(255, 250, 247)
    private val card = if (dark) Color.rgb(60, 49, 48) else Color.rgb(238, 227, 223)
    private val ink = if (dark) Color.rgb(241, 231, 229) else Color.rgb(45, 32, 31)
    private val subtle = if (dark) Color.rgb(199, 183, 180) else Color.rgb(114, 94, 90)
    private val accent = if (dark) Color.rgb(255, 181, 174) else Color.rgb(146, 65, 60)
    private val groupColors = intArrayOf(0xFFFFB5AE.toInt(), 0xFFB6CDFB.toInt(), 0xFFBCE3C3.toInt(),
        0xFFDAC1ED.toInt(), 0xFFF4CE9A.toInt(), 0xFFD4D6DA.toInt())
    private val dialogContext = ContextThemeWrapper(activity,
        if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
    private val profile = ctrl.status().getString("activeProfile")
    private val selected = linkedSetOf<String>()
    private var selectionMode = false
    private var groupId: String? = null
    private var lastState = ""
    private var query = ""
    private val root = LinearLayout(activity)
    private val heading = LinearLayout(activity)
    private val grid = LinearLayout(activity)
    private val bottom = FrameLayout(activity)
    private lateinit var search: EditText
    private val scroll = ScrollView(activity)
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 24, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (border != null) setStroke(dp(2), border)
    }
    private fun ripple(color: Int = Color.TRANSPARENT, radius: Int = 22) = RippleDrawable(
        ColorStateList.valueOf(if (dark) 0x24FFFFFF else 0x18000000), shape(color, radius), shape(Color.WHITE, radius))
    private fun text(value: String, size: Float, color: Int = ink) = TextView(activity).apply {
        this.text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = true
    }
    private fun icon(name: String, description: String, color: Int = ink, action: () -> Unit) = ImageButton(activity).apply {
        setImageDrawable(BrowserIcon(name, color)); contentDescription = description; tooltipText = description
        background = ripple(); scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12)); minimumHeight = dp(48); minimumWidth = dp(48)
        setOnClickListener { action() }
    }
    private fun row() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false
    }
    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        root.orientation = LinearLayout.VERTICAL; root.setBackgroundColor(bg)
        ViewCompat.setOnApplyWindowInsetsListener(root) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            target.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom)); insets
        }
        heading.orientation = LinearLayout.VERTICAL
        root.addView(heading)
        search = EditText(activity).apply {
            hint = "搜尋分頁與群組"; setTextColor(ink); setHintTextColor(subtle); textSize = 16f
            isSingleLine = true; background = shape(card, 32); setPadding(dp(20), 0, dp(20), 0)
            contentDescription = "搜尋分頁與群組"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString()?.trim().orEmpty(); renderGrid()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply {
            setMargins(dp(16), dp(6), dp(16), dp(12))
        })
        grid.orientation = LinearLayout.VERTICAL; grid.setPadding(dp(16), 0, dp(16), dp(12))
        scroll.addView(grid); scroll.isFillViewport = true; scroll.isVerticalScrollBarEnabled = false
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(76)))
        setContentView(root)
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            statusBarColor = bg; navigationBarColor = bg
            WindowCompat.setDecorFitsSystemWindows(this, false)
        }
        root.isFocusableInTouchMode = true; root.requestFocus()
        setOnShowListener {
            window?.setLayout(-1, -1)
            window?.let { WindowCompat.getInsetsController(it, root).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            } }
            ViewCompat.requestApplyInsets(root)
            if (android.animation.ValueAnimator.areAnimatorsEnabled() && !activity.getSharedPreferences("abwr_settings", 0).getBoolean("reduce_motion", false)) {
                root.alpha = 0f; root.translationY = dp(18).toFloat()
                root.animate().alpha(1f).translationY(0f).setDuration(230L).start()
            }
            refresh(true)
            ctrl.requestTabPreview { if (isShowing) refresh(true) }
        }
    }
    fun refresh(force: Boolean = false) {
        if (ctrl.status().getString("activeProfile") != profile) { dismiss(); return }
        val state = ctrl.tabs(profile).toString()
        if (!force && state == lastState) return
        lastState = state
        val ids = tabs().map { it.getString("id") }.toSet()
        selected.retainAll(ids)
        if (groupId != null && ctrl.tabGroups.all(profile).none { it.id == groupId }) groupId = null
        val scrollPosition = scroll.scrollY
        renderHeader(); renderGrid(); renderBottom()
        scroll.post { if (isShowing) scroll.scrollTo(0, scrollPosition) }
    }
    private fun tabs(): List<JSONObject> {
        val array = ctrl.tabs(profile).getJSONArray("tabs")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    private fun group() = groupId?.let { ctrl.tabGroups.group(profile, it) }
    private fun renderHeader() {
        heading.removeAllViews()
        val top = row().apply { setPadding(dp(8), dp(6), dp(8), dp(2)) }
        top.addView(icon(if (selectionMode) "close" else "back", if (selectionMode) "取消選取" else "返回") { back() },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        val group = group()
        val labels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        labels.addView(text(if (selectionMode) "已選 ${selected.size} 個分頁" else group?.name ?: "分頁", 20f).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        labels.addView(text(if (group == null) "$profile · ${tabs().size} 個分頁 · ${ctrl.tabGroups.all(profile).size} 個群組"
            else "${group.tabs.size} 個分頁 · $profile", 12f, subtle).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        top.addView(labels, LinearLayout.LayoutParams(0, dp(58), 1f))
        top.addView(icon("container", "建立分頁群組") { editGroup(null, selected.toList()) },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        heading.addView(top)
    }
    private fun renderBottom() {
        bottom.removeAllViews()
        if (selectionMode) {
            bottom.addView(Button(activity).apply {
                text = "加入群組（${selected.size}）"; isAllCaps = false; setTextColor(bg)
                background = ripple(accent, 24); isEnabled = selected.isNotEmpty()
                setOnClickListener { chooseGroup(selected.toList()) }
            }, FrameLayout.LayoutParams(-1, dp(52), Gravity.CENTER).apply { setMargins(dp(20), 0, dp(20), 0) })
        } else {
            bottom.addView(icon("plus", if (groupId == null) "新增分頁" else "在群組新增分頁", bg) {
                attempt {
                    val id = ctrl.createTab(BrowserSearch.home(activity.getSharedPreferences("abwr_settings", 0)
                        .getString("search_engine", "google") ?: "google"), profile, fromUser = true).getString("id")
                    groupId?.let { ctrl.moveTabsToGroup(listOf(id), it, profile) }
                    dismiss(); opened()
                }
            }.apply { background = ripple(accent, 22) }, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
            bottom.addView(icon("menu", "更多分頁操作") { showMenu() },
                FrameLayout.LayoutParams(dp(52), dp(56), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(12) })
        }
    }
    private fun matching(tab: JSONObject) = query.isEmpty() ||
        tab.optString("title").contains(query, true) || tab.optString("url").contains(query, true)
    private fun renderGrid() {
        grid.removeAllViews()
        val all = tabs()
        val tiles = mutableListOf<View>()
        val inside = group()
        if (inside != null) {
            all.filter { it.getString("id") in inside.tabs && matching(it) }.forEach { tiles.add(tabCard(it)) }
        } else if (selectionMode || query.isNotEmpty()) {
            all.filter(::matching).forEach { tiles.add(tabCard(it)) }
            if (!selectionMode) ctrl.tabGroups.all(profile).filter { it.name.contains(query, true) }
                .forEach { tiles.add(groupCard(it, all)) }
        } else {
            val placed = mutableSetOf<String>()
            all.forEach { tab ->
                val id = tab.getString("id")
                val membership = ctrl.tabGroups.membership(profile, id)
                if (membership == null) tiles.add(tabCard(tab))
                else if (placed.add(membership)) tiles.add(groupCard(ctrl.tabGroups.group(profile, membership), all))
            }
            ctrl.tabGroups.all(profile).filter { it.id !in placed }.forEach { tiles.add(groupCard(it, all)) }
        }
        if (tiles.isEmpty()) {
            grid.addView(text(if (query.isEmpty()) "群組還沒有分頁\n點下方 ＋ 開始瀏覽" else "找不到符合的分頁", 17f, subtle)
                .apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(72), dp(12), dp(72)) })
        }
        val width = (activity.resources.displayMetrics.widthPixels - dp(48)) / 2
        val height = (width * 1.36f).toInt().coerceAtLeast(dp(212))
        tiles.chunked(2).forEach { pair ->
            val line = row()
            pair.forEachIndexed { index, tile -> line.addView(tile, LinearLayout.LayoutParams(0, height, 1f).apply {
                if (index == 0) marginEnd = dp(12)
            }) }
            if (pair.size == 1) line.addView(View(activity), LinearLayout.LayoutParams(0, height, 1f))
            grid.addView(line, LinearLayout.LayoutParams(-1, height).apply { bottomMargin = dp(14) })
        }
    }
    private fun tabCard(tab: JSONObject): View {
        val id = tab.getString("id"); val active = tab.optBoolean("active")
        val chosen = id in selected
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(5), 0, dp(5), dp(5))
            background = shape(if (chosen) accent else card, 24, if (active && !selectionMode) accent else null)
            clipToOutline = true
        }
        val foreground = if (chosen) bg else ink
        val header = row()
        header.addView(text(host(tab).take(1).uppercase(), 12f, foreground).apply {
            gravity = Gravity.CENTER; background = shape(bg, 12)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(8) })
        header.addView(text(title(tab), 13f, foreground).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(icon(if (selectionMode) (if (chosen) "check" else "circle") else "close",
            if (selectionMode) "選取分頁 ${title(tab)}" else "關閉分頁 ${title(tab)}", foreground) {
            if (selectionMode) toggle(id) else attempt {
                if (id == "popup") ctrl.closePopup(profile, fromUser = true) else ctrl.closeTab(id, profile, fromUser = true)
                refresh(true)
            }
        }, LinearLayout.LayoutParams(dp(42), dp(48)))
        box.addView(header)
        val preview = preview(tab)
        preview.contentDescription = "開啟分頁 ${title(tab)}" + if (active) "，目前使用中" else ""
        preview.isFocusable = true
        preview.setOnClickListener {
            if (selectionMode) toggle(id) else attempt {
                if (id != "popup") {
                    if (ctrl.tabs(profile).optBoolean("popupActive")) ctrl.closePopup(profile, fromUser = true)
                    ctrl.selectTab(id, profile, fromUser = true)
                }
                dismiss(); opened()
            }
        }
        preview.setOnLongClickListener {
            if (id != "popup") { selectionMode = true; selected.add(id); refresh(true) }; true
        }
        box.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        return box
    }
    private fun groupCard(group: TabGroups.Group, all: List<JSONObject>): View {
        val color = groupColors[group.color]
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; background = shape(color, 24)
            setPadding(dp(5), 0, dp(5), dp(5)); clipToOutline = true
        }
        val header = row()
        header.addView(text(group.name, 14f, 0xFF342626.toInt()).apply {
            setPadding(dp(10), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(icon("menu", "群組選單 ${group.name}", 0xFF342626.toInt()) { groupMenu(group) },
            LinearLayout.LayoutParams(dp(42), dp(48)))
        box.addView(header)
        val collage = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; clipToOutline = true; background = shape(card, 18)
            contentDescription = "開啟群組 ${group.name}，${group.tabs.size} 個分頁"; isFocusable = true
            setOnClickListener { groupId = group.id; search.setText(""); refresh(true); scroll.scrollTo(0, 0) }
        }
        val members = all.filter { it.getString("id") in group.tabs }
        repeat(2) { y ->
            val strip = row()
            repeat(2) { x ->
                val index = y * 2 + x
                val tile = if (index == 3 && members.size > 4)
                    text("+${members.size - 3}", 22f, 0xFF342626.toInt()).apply {
                        gravity = Gravity.CENTER; setBackgroundColor(color)
                    } else members.getOrNull(index)?.let { preview(it, 6) }
                        ?: text(if (members.isEmpty() && index == 0) "＋" else "", 25f, subtle).apply {
                            gravity = Gravity.CENTER; setBackgroundColor(card)
                        }
                tile.isClickable = false; tile.isFocusable = false
                tile.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                strip.addView(tile, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
            collage.addView(strip, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        box.addView(collage, LinearLayout.LayoutParams(-1, 0, 1f))
        return box
    }
    private fun preview(tab: JSONObject, radius: Int = 18): FrameLayout {
        val frame = FrameLayout(activity).apply { background = shape(bg, radius); clipToOutline = true }
        frame.addView(text(host(tab), 14f, subtle).apply {
            gravity = Gravity.CENTER; maxLines = 3; setPadding(dp(12), dp(12), dp(12), dp(12))
        }, FrameLayout.LayoutParams(-1, -1))
        val image = object : ImageView(activity) {
            private var bitmap: Bitmap? = null
            fun display(value: Bitmap) { bitmap = value; setImageBitmap(value); adjust() }
            fun clear() { bitmap = null; setImageDrawable(null) }
            private fun adjust() {
                val b = bitmap ?: return
                if (width == 0 || height == 0) return
                val scale = maxOf(width.toFloat() / b.width, height.toFloat() / b.height)
                imageMatrix = Matrix().apply { setScale(scale, scale); postTranslate((width - b.width * scale) / 2, 0f) }
            }
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); adjust() }
        }.apply { scaleType = ImageView.ScaleType.MATRIX; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        // Only visible cards hold bitmaps. A container can contain 120 tabs without
        // decoding/retaining 120 full previews at once.
        var requested = false
        var loadGeneration = 0
        val visibleBounds = Rect()
        fun updatePreview() {
            val visible = frame.isAttachedToWindow && frame.getGlobalVisibleRect(visibleBounds)
            if (visible && !requested) {
                requested = true
                val generation = ++loadGeneration
                ctrl.previews.read(profile, tab.getString("id")) { bitmap ->
                    if (generation == loadGeneration && frame.isAttachedToWindow && bitmap != null) image.display(bitmap)
                }
            } else if (!visible && requested) {
                requested = false; loadGeneration++; image.clear()
            }
        }
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { updatePreview() }
        val scrollListener = ViewTreeObserver.OnScrollChangedListener { updatePreview() }
        frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
                v.viewTreeObserver.addOnScrollChangedListener(scrollListener)
                v.post { updatePreview() }
            }
            override fun onViewDetachedFromWindow(v: View) {
                if (v.viewTreeObserver.isAlive) {
                    v.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
                    v.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
                }
                requested = false; loadGeneration++; image.clear()
            }
        })
        return frame
    }
    private fun title(tab: JSONObject) = tab.optString("title").ifBlank { host(tab) }
    private fun host(tab: JSONObject) = runCatching { android.net.Uri.parse(tab.optString("url")).host }
        .getOrNull()?.removePrefix("www.") ?: "新分頁"
    private fun toggle(id: String) {
        if (id == "popup") return
        if (!selected.add(id)) selected.remove(id)
        refresh(true)
    }
    private fun showMenu() {
        val options = arrayOf("選取分頁", "建立新群組", "返回瀏覽器")
        AlertDialog.Builder(dialogContext).setItems(options) { _, which -> when (which) {
            0 -> { selectionMode = true; refresh(true) }
            1 -> editGroup(null, emptyList())
            2 -> dismiss()
        } }.show()
    }
    private fun groupMenu(group: TabGroups.Group) {
        AlertDialog.Builder(dialogContext).setTitle(group.name)
            .setItems(arrayOf("重新命名與顏色", "加入現有分頁", "解散群組，保留分頁")) { _, which -> when (which) {
                0 -> editGroup(group, emptyList())
                1 -> { groupId = null; selectionMode = true; selected.clear(); selected.addAll(group.tabs); refresh(true) }
                2 -> attempt { ctrl.ungroupTabs(group.id, profile); refresh(true) }
            } }.show()
    }
    private fun chooseGroup(ids: List<String>) {
        val groups = ctrl.tabGroups.all(profile)
        val names = arrayOf("建立新群組", "移出群組") + groups.map { it.name }.toTypedArray()
        AlertDialog.Builder(dialogContext).setTitle("加入群組").setItems(names) { _, which ->
            if (which == 0) editGroup(null, ids) else attempt {
                ctrl.moveTabsToGroup(ids, if (which == 1) null else groups[which - 2].id, profile)
                selectionMode = false; selected.clear(); refresh(true)
            }
        }.show()
    }
    private fun editGroup(existing: TabGroups.Group?, members: List<String>) {
        val body = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        val name = EditText(dialogContext).apply {
            hint = "群組名稱"; setSingleLine(); setText(existing?.name.orEmpty()); contentDescription = "群組名稱"
            setTextColor(ink); setHintTextColor(subtle); backgroundTintList = ColorStateList.valueOf(accent)
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
        }
        body.addView(name, LinearLayout.LayoutParams(-1, dp(56)))
        var chosenColor = existing?.color ?: 0
        val colors = row()
        val labels = listOf("粉紅", "藍色", "綠色", "紫色", "橘色", "灰色")
        fun colorChoices() {
            colors.removeAllViews()
            groupColors.forEachIndexed { index, color ->
                colors.addView(text(if (chosenColor == index) "✓" else "", 19f, 0xFF342626.toInt()).apply {
                    gravity = Gravity.CENTER; background = shape(color, 24)
                    contentDescription = "群組顏色 ${labels[index]}"; isFocusable = true
                    setOnClickListener { chosenColor = index; colorChoices() }
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(3), dp(8), dp(3), dp(8)) })
            }
        }
        colorChoices(); body.addView(colors)
        val dialog = AlertDialog.Builder(dialogContext).setTitle(if (existing == null) "建立分頁群組" else "編輯群組")
            .setView(body).setNegativeButton("取消", null).setPositiveButton("儲存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(subtle)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = name.text.toString().trim()
            if (value.isEmpty()) { name.error = "請輸入群組名稱"; return@setOnClickListener }
            attempt {
                if (existing == null) ctrl.createTabGroup(value, chosenColor, members, profile)
                else ctrl.renameTabGroup(existing.id, value, chosenColor, profile)
                selectionMode = false; selected.clear(); dialog.dismiss(); refresh(true)
            }
        } }
        dialog.show()
    }
    private fun attempt(action: () -> Unit) { runCatching(action).onFailure {
        Toast.makeText(activity, it.message ?: "無法完成操作", Toast.LENGTH_SHORT).show()
    } }
    private fun back() {
        when {
            selectionMode -> { selectionMode = false; selected.clear(); refresh(true) }
            groupId != null -> { groupId = null; search.setText(""); refresh(true) }
            else -> dismiss()
        }
    }
    @Suppress("DEPRECATION") override fun onBackPressed() { back() }
}
