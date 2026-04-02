package com.example.vesselv2.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.vesselv2.R
import com.example.vesselv2.databinding.ActivityMainBinding
import com.example.vesselv2.ui.activity.MainActivity
import com.example.vesselv2.ui.activity.MyWorkActivity
import com.example.vesselv2.ui.activity.FirebaseVesselActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView

object NavigationHelper {
    fun setupSidebar(
        context: Context,
        navigationView: NavigationView,
        onDrawerClose: () -> Unit
    ) {
        val layoutInflater = LayoutInflater.from(context)
        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_Col -> {
                    val dialogView = layoutInflater.inflate(R.layout.dialog_contact_image, null)
                    MaterialAlertDialogBuilder(context)
                        .setView(dialogView)
                        .setBackground(Color.TRANSPARENT.toDrawable())
                        .show()
                }

                R.id.nav_gotoDgt -> {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        "https://www.dgtbusan.com/DGT/esvc/vessel/berthScheduleG".toUri()
                    )
                    context.startActivity(intent)
                }

                R.id.nav_direct -> {
                    val intent = Intent(
                        Intent.ACTION_VIEW, "http://pasc.dothome.co.kr/".toUri()
                    )
                    context.startActivity(intent)
                }

                R.id.nav_gotoBnmt -> {
                    val intent = Intent(
                        Intent.ACTION_VIEW, "http://www.bnmt.co.kr/ebiz/".toUri()
                    )
                    context.startActivity(intent)
                }

                R.id.nav_gotoHjnc -> {
                    val intent = Intent(
                        Intent.ACTION_VIEW, "https://bayplan.hjnc.co.kr/".toUri()
                    )
                    context.startActivity(intent)
                }

                R.id.nav_main -> {
                    if (context !is MainActivity) {
                        context.startActivity(Intent(context, MainActivity::class.java))
                    }
                }

                R.id.nav_timeCal -> {
                    if (context !is FirebaseVesselActivity) {
                        context.startActivity(Intent(context, FirebaseVesselActivity::class.java))
                    }
                }


                R.id.nav_maker -> {
                    Toast.makeText(context, ".", Toast.LENGTH_SHORT).show()
                }
            }
            onDrawerClose()
            true
        }
    }
}
