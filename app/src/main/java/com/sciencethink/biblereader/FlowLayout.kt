package com.sciencethink.biblereader

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

class FlowLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var currentLineWidth = 0
        var currentLineHeight = 0
        var totalHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue

            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val lp = child.layoutParams as? MarginLayoutParams ?: MarginLayoutParams(child.layoutParams)
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin

            if (currentLineWidth + childWidth > widthSize && currentLineWidth > 0) {
                totalHeight += currentLineHeight
                currentLineWidth = childWidth
                currentLineHeight = childHeight
            } else {
                currentLineWidth += childWidth
                currentLineHeight = maxOf(currentLineHeight, childHeight)
            }
        }
        totalHeight += currentLineHeight

        val finalWidth = MeasureSpec.getSize(widthMeasureSpec)
        val finalHeight = totalHeight + paddingTop + paddingBottom
        setMeasuredDimension(finalWidth, maxOf(finalHeight, suggestedMinimumHeight))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val widthSize = r - l - paddingLeft - paddingRight
        var currentLeft = paddingLeft
        var currentTop = paddingTop
        var currentLineHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue

            val lp = child.layoutParams as? MarginLayoutParams ?: MarginLayoutParams(child.layoutParams)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            if (currentLeft + childWidth + lp.leftMargin + lp.rightMargin > paddingLeft + widthSize && currentLeft > paddingLeft) {
                currentLeft = paddingLeft
                currentTop += currentLineHeight
                currentLineHeight = 0
            }

            val childLeft = currentLeft + lp.leftMargin
            val childTop = currentTop + lp.topMargin
            child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)

            currentLineHeight = maxOf(currentLineHeight, childHeight + lp.topMargin + lp.bottomMargin)
            currentLeft += childWidth + lp.leftMargin + lp.rightMargin
        }
    }

    override fun generateLayoutParams(p: LayoutParams): LayoutParams = MarginLayoutParams(p)
    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams = MarginLayoutParams(context, attrs)
}
