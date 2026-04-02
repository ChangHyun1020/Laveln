package com.example.vesselv2.util

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams

/**
 * 시스템 창 맞춤(Edge-to-Edge)을 활성화하여 시스템 UI 영역까지 화면을 확장하고,
 * 헤더나 하단 뷰(FAB 등)가 가려지지 않도록 insets 기반 마진을 설정합니다.
 */
fun Activity.setupEdgeToEdgeInsets(
    headerView: View? = null,
    bottomView: View? = null,
    extraBottomMarginDp: Int = 16
) {
    WindowCompat.setDecorFitsSystemWindows(window, false)

    headerView?.let { view ->
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
            }
            insets
        }
    }

    bottomView?.let { view ->
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val extraMarginPx = (extraBottomMarginDp * view.context.resources.displayMetrics.density).toInt()
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = navBarHeight + extraMarginPx
            }
            insets
        }
    }
}
