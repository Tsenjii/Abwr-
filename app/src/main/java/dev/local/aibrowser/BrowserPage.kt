package dev.local.aibrowser

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Shared native pages: readable hierarchy, predictable controls and keyboard-safe insets. */
internal class BrowserPage(val activity: Activity, title: String, subtitle: String = "", val dark: Boolean) {
    val bg = if (dark) Color.rgb(25, 19, 18) else Color.rgb(255, 250, 247)
    val card = if (dark) Color.rgb(60, 49, 48) else Color.rgb(238, 227, 223)
    val ink = if (dark) Color.rgb(241, 231, 229) else Color.rgb(45, 32, 31)
    val subtle = if (dark) Color.rgb(199, 183, 180) else Color.rgb(114, 94, 90)
    val accent = if (dark) Color.rgb(255, 181, 174) else Color.rgb(146, 65, 60)
    val dialog = Dialog(activity, if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
    val root = column()
    val toolbar = row()
    val body = column()
    val scroll = ScrollView(activity)
    val footer = row()
    var onDismiss: (() -> Unit)? = null
    val motion get() = ValueAnimator.areAnimatorsEnabled() && !activity.getSharedPreferences("abwr_settings", 0).getBoolean("reduce_motion", false)
    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        root.setBackgroundColor(bg)
        toolbar.setPadding(dp(12), dp(8), dp(12), dp(8))
        toolbar.addView(icon("back", "返回") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val heading = column()
        heading.addView(text(title, 22f).apply { setTypeface(Typeface.DEFAULT, Typeface.BOLD) })
        if (subtitle.isNotBlank()) heading.addView(text(subtitle, 12f, subtle).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0)
        })
        toolbar.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        root.addView(toolbar)
        body.setPadding(dp(20), dp(8), dp(20), dp(24))
        scroll.addView(body)
        scroll.isFillViewport = false
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        footer.setPadding(dp(20), dp(8), dp(20), dp(12)); footer.visibility = View.GONE
        root.addView(footer)
        dialog.setContentView(root)
        dialog.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = bg; window.navigationBarColor = bg
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            if (!dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            target.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        dialog.setOnDismissListener { onDismiss?.invoke() }
    }
    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false }
    fun shape(color: Int, radius: Int = 20) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    fun ripple(color: Int = Color.TRANSPARENT, radius: Int = 20) = RippleDrawable(
        ColorStateList.valueOf(if (dark) 0x24FFFFFF else 0x18000000), shape(color, radius), shape(Color.WHITE, radius))
    fun text(value: String, size: Float = 15f, color: Int = ink) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = true
    }
    fun icon(name: String, label: String, action: () -> Unit) = ImageButton(activity).apply {
        contentDescription = label; tooltipText = label; setImageDrawable(BrowserIcon(name, ink))
        setPadding(dp(12), dp(12), dp(12), dp(12)); background = ripple(); minimumHeight = dp(48); minimumWidth = dp(48)
        setOnClickListener { action() }
    }
    fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(activity).apply {
        text = label; textSize = 14f; isAllCaps = false; minHeight = dp(48); minimumHeight = dp(48)
        setTextColor(if (primary) bg else ink); background = ripple(if (primary) accent else card, 16)
        setPadding(dp(16), dp(6), dp(16), dp(6)); setOnClickListener { action() }
    }
    fun field(hint: String, value: String = "", multiline: Boolean = false) = EditText(activity).apply {
        this.hint = hint; contentDescription = hint; setText(value); textSize = 15f; setTextColor(ink); setHintTextColor(subtle)
        background = shape(card, 16); setPadding(dp(16), dp(12), dp(16), dp(12)); minimumHeight = dp(52)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            (if (multiline) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        setSingleLine(!multiline); gravity = Gravity.TOP or Gravity.START
    }
    fun search(hint: String, changed: (String) -> Unit): EditText = field(hint).apply {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
        })
    }
    fun section(title: String, parent: LinearLayout = body) {
        parent.addView(text(title, 12f, accent).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD); setPadding(dp(4), dp(22), dp(4), dp(10))
        })
    }
    fun card(parent: LinearLayout = body, build: (LinearLayout) -> Unit): LinearLayout {
        val box = column().apply { background = shape(card); setPadding(dp(12), dp(12), dp(12), dp(12)) }
        build(box)
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        return box
    }
    fun item(parent: LinearLayout = body, icon: String, title: String, detail: String = "", action: (() -> Unit)? = null): LinearLayout {
        val line = row().apply {
            minimumHeight = dp(64); setPadding(dp(8), dp(10), dp(8), dp(10)); background = ripple()
            if (action != null) { isFocusable = true; setOnClickListener { action() } }
        }
        line.addView(ImageView(activity).apply { setImageDrawable(BrowserIcon(icon, accent)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) })
        val labels = column(); labels.addView(text(title).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        if (detail.isNotBlank()) labels.addView(text(detail, 12f, subtle).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) })
        line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(line)
        return line
    }
    fun toggle(parent: LinearLayout, icon: String, title: String, detail: String, checked: Boolean, changed: (Boolean) -> Unit) {
        val line = item(parent, icon, title, detail)
        val control = Switch(activity).apply {
            isChecked = checked; contentDescription = title
            thumbTintList = ColorStateList.valueOf(accent)
            setOnCheckedChangeListener { _, enabled -> changed(enabled) }
        }
        line.addView(control, LinearLayout.LayoutParams(dp(56), dp(48)))
        line.setOnClickListener { control.isChecked = !control.isChecked }
    }
    fun empty(title: String, detail: String, parent: LinearLayout = body) {
        val box = column().apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(50), dp(20), dp(50)) }
        box.addView(ImageView(activity).apply { setImageDrawable(BrowserIcon("tabs", subtle)) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        box.addView(text(title, 19f).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(8)) })
        box.addView(text(detail, 14f, subtle).apply { gravity = Gravity.CENTER })
        parent.addView(box)
    }
    fun show() {
        dialog.show(); dialog.window?.setLayout(-1, -1); ViewCompat.requestApplyInsets(root)
        if (motion) { root.alpha = 0f; root.translationY = dp(16).toFloat(); root.animate().alpha(1f).translationY(0f).setDuration(210).start() }
    }
}
