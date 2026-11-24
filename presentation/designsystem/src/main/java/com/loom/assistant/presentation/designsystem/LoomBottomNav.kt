package com.loom.assistant.presentation.designsystem

import android.content.Context
import android.content.res.XmlResourceParser
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.xmlpull.v1.XmlPullParser

/**
 * LOOM bottom navigation — the 5 locked tabs (Home, Tasks, Skills, Memory, More).
 * Items are read from R.menu.loom_bottom_nav (single source of truth) and mapped
 * to [LoomDestination.bottomNav] in menu order.
 */
class LoomBottomNav @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    data class Item(val title: String, val iconRes: Int, val destination: LoomDestination)

    var onTabSelected: ((LoomDestination) -> Unit)? = null

    private val items: List<Item> = readMenu()
    private val itemViews = mutableListOf<LinearLayout>()
    private var selected: LoomDestination = LoomDestination.Home

    init {
        orientation = HORIZONTAL
        setBackgroundColor(context.getColor(R.color.loom_surface))
        gravity = Gravity.CENTER_VERTICAL
        buildItems()
        select(LoomDestination.Home, notify = false)
    }

    private fun readMenu(): List<Item> {
        val parsed = mutableListOf<Pair<String, Int>>()
        val parser: XmlResourceParser = resources.getXml(R.menu.loom_bottom_nav)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                var title = ""
                var icon = 0
                for (i in 0 until parser.attributeCount) {
                    when (parser.getAttributeName(i)) {
                        "title" -> title = parser.getAttributeValue(i)
                            .removePrefix("@string/").ifEmpty { parser.getAttributeValue(i) }
                        "icon" -> icon = parser.getAttributeResourceValue(i, 0)
                    }
                }
                // Titles may be literal strings in the menu XML.
                if (title.startsWith("@")) title = title.substringAfterLast("/")
                parsed.add(title to icon)
            }
            event = parser.next()
        }
        parser.close()
        val destinations = LoomDestination.bottomNav
        require(parsed.size == destinations.size) {
            "menu items (${parsed.size}) != bottom-nav destinations (${destinations.size})"
        }
        return parsed.mapIndexed { i, (title, icon) -> Item(title, icon, destinations[i]) }
    }

    private fun buildItems() {
        val iconSize = resources.getDimensionPixelSize(R.dimen.loom_bottom_nav_icon)
        items.forEach { item ->
            val holder = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                isClickable = true
                isFocusable = true
                setOnClickListener { select(item.destination, notify = true) }
            }
            val icon = ImageView(context).apply {
                layoutParams = LayoutParams(iconSize, iconSize)
                setImageResource(item.iconRes)
            }
            val label = TextView(context).apply {
                layoutParams = LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                )
                text = item.title
                setTextAppearance(R.style.Loom_BottomNav_Label)
                setPadding(0, resources.getDimensionPixelSize(R.dimen.loom_space_4), 0, 0)
            }
            holder.addView(icon)
            holder.addView(label)
            holder.setTag(R.id.loom_bottom_nav_tag_icon, icon)
            holder.setTag(R.id.loom_bottom_nav_tag_label, label)
            addView(holder)
            itemViews.add(holder)
        }
    }

    fun select(destination: LoomDestination, notify: Boolean = true) {
        selected = destination
        val accent = context.getColor(R.color.loom_accent_bright)
        val muted = context.getColor(R.color.loom_text_muted)
        itemViews.forEachIndexed { i, holder ->
            val active = items[i].destination == destination
            val icon = holder.getTag(R.id.loom_bottom_nav_tag_icon) as ImageView
            val label = holder.getTag(R.id.loom_bottom_nav_tag_label) as TextView
            icon.setColorFilter(if (active) accent else muted)
            label.setTextColor(if (active) accent else muted)
        }
        if (notify) onTabSelected?.invoke(destination)
    }
}
