package com.example.vesselv2.util

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams

/**
 * ?�스??�??�태�? ?�비게이??�????�이�?고려?�여 뷰의 마진???�정?�는 ?�장 ?�수
 */

fun View.applySystemBarsMargin(isTop: Boolean = false, extraMarginDp: Int = 0) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

        view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            if (isTop) {
                topMargin = systemBars.top + extraMarginDp
            } else {
                bottomMargin = systemBars.bottom + extraMarginDp
            }
        }
        insets
    }
}

/**
 * ?�비게이??�??�이�?고려?�여 ?�단 마진???�정 (기존 SysBottomMargin ?�환)
 */
fun View.setSystemBottomMargin(extraMarginDp: Int = 32) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight + extraMarginDp
        }
        insets
    }
}

/**
 * ?�태�??�이�?고려?�여 ?�단 마진???�정 (기존 SysTopMargin ?�환)
 */
fun View.setSystemTopMargin(extraMarginDp: Int = 5) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
        view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight + extraMarginDp
        }
        insets
    }
}
