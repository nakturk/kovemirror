package com.kove.mirror

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog

/**
 * Dialog for selecting route color and stroke width.
 */
class RouteStyleDialog(
    context: Context,
    private val initialColor: Int = Color.RED,
    private val initialWidth: Float = 5f,
    private val onStyleSelected: (color: Int, width: Float) -> Unit
) : Dialog(context) {

    companion object {
        val PRESET_COLORS = intArrayOf(
            Color.parseColor("#FF1744"),  // Red
            Color.parseColor("#FF9100"),  // Orange
            Color.parseColor("#FFEA00"),  // Yellow
            Color.parseColor("#00E676"),  // Green
            Color.parseColor("#2979FF"),  // Blue
            Color.parseColor("#D500F9"),  // Purple
            Color.parseColor("#FF4081"),  // Pink
            Color.parseColor("#FFFFFF"),  // White
            Color.parseColor("#00E5FF"),  // Cyan
            Color.parseColor("#76FF03"),  // Lime
        )
    }

    private var selectedColor: Int = initialColor
    private var selectedWidth: Float = initialWidth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_route_style)

        window?.setBackgroundDrawableResource(android.R.color.transparent)

        val colorGrid = findViewById<LinearLayout>(R.id.colorGrid)
        val seekWidth = findViewById<SeekBar>(R.id.seekWidth)
        val tvWidthValue = findViewById<TextView>(R.id.tvWidthValue)
        val previewLine = findViewById<View>(R.id.previewLine)
        val btnApply = findViewById<TextView>(R.id.btnApply)
        val btnCancel = findViewById<TextView>(R.id.btnCancel)

        // Build color grid
        buildColorGrid(colorGrid, previewLine)

        // Width SeekBar (range: 2–15, offset by 2)
        seekWidth.max = 13
        seekWidth.progress = (initialWidth - 2f).toInt().coerceIn(0, 13)
        tvWidthValue.text = "${initialWidth.toInt()}px"
        updatePreview(previewLine)

        seekWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedWidth = (progress + 2).toFloat()
                tvWidthValue.text = "${selectedWidth.toInt()}px"
                updatePreview(previewLine)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnApply.setOnClickListener {
            onStyleSelected(selectedColor, selectedWidth)
            dismiss()
        }

        btnCancel.setOnClickListener { dismiss() }
    }

    private fun buildColorGrid(container: LinearLayout, previewLine: View) {
        container.removeAllViews()
        val rowSize = 5
        var row: LinearLayout? = null

        for ((index, color) in PRESET_COLORS.withIndex()) {
            if (index % rowSize == 0) {
                row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dpToPx(4)
                    }
                }
                container.addView(row)
            }

            val swatch = ImageView(context).apply {
                val size = dpToPx(40)
                layoutParams = LinearLayout.LayoutParams(0, size, 1f).apply {
                    marginStart = dpToPx(4)
                    marginEnd = dpToPx(4)
                }
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(
                        if (color == selectedColor) dpToPx(3) else dpToPx(1),
                        if (color == selectedColor) Color.WHITE else Color.parseColor("#444444")
                    )
                }
                background = drawable
                setOnClickListener {
                    selectedColor = color
                    buildColorGrid(container, previewLine)
                    updatePreview(previewLine)
                }
            }
            row?.addView(swatch)
        }
    }

    private fun updatePreview(previewLine: View) {
        previewLine.setBackgroundColor(selectedColor)
        val lp = previewLine.layoutParams
        lp.height = dpToPx(selectedWidth.toInt().coerceAtLeast(2))
        previewLine.layoutParams = lp
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
