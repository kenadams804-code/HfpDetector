package com.example.hfpdetector

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

class ModeAdapter(
    context: Context,
    private val items: List<String>
) : ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, items) {

    var enableLan: Boolean = true
    var enableBt: Boolean = false

    override fun isEnabled(position: Int): Boolean {
        return when (position) {
            0 -> enableLan
            1 -> enableBt
            else -> true
        }
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = super.getDropDownView(position, convertView, parent) as TextView
        val en = isEnabled(position)
        v.setTextColor(if (en) Color.BLACK else Color.GRAY)
        return v
    }
}
