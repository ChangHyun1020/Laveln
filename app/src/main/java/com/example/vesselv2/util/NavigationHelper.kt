package com.example.vesselv2.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import android.widget.Toast
import com.example.vesselv2.R
import com.example.vesselv2.ui.activity.FirebaseVesselActivity
import com.example.vesselv2.ui.activity.MainActivity
import com.example.vesselv2.ui.activity.MyWorkActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView

/**
 * [유틸리티] NavigationHelper — 사이드바 네비게이션 설정 헬퍼
 *
 * ▶ 역할:
 *   앱의 모든 화면(Activity)에서 공통으로 사용하는 사이드바(DrawerLayout + NavigationView)의
 *   메뉴 항목 클릭 처리 로직을 한 곳에서 관리합니다.
 *   중복 코드를 없애고 메뉴 변경 시 이 파일만 수정하면 됩니다.
 *
 * ▶ 적용 화면:
 *   - MainActivity
 *   - FirebaseVesselActivity
 *   - DetailActivity
 *   - MyWorkActivity
 *
 * ▶ 사이드바 메뉴 구성:
 *   - 홈 (nav_main): MainActivity로 이동
 *   - 모선 목록 (nav_timeCal): FirebaseVesselActivity로 이동
 *   - 비상 연락망 (nav_Col): 이미지 다이얼로그 표시
 *   - DGT 바로가기 (nav_gotoDgt): DGT 웹사이트 외부 브라우저 열기
 *   - PASC 바로가기 (nav_direct): PASC 웹사이트 외부 브라우저 열기
 *   - BNMT 바로가기 (nav_gotoBnmt): BNMT 웹사이트 외부 브라우저 열기
 *   - HJNC 바로가기 (nav_gotoHjnc): HJNC 웹사이트 외부 브라우저 열기
 *   - 개발자 정보 (nav_maker): 토스트 표시
 *
 * ▶ 외부 링크 목록 (항만 관련 웹사이트):
 *   - DGT: https://www.dgtbusan.com (동부산컨테이너터미널)
 *   - PASC: http://pasc.dothome.co.kr (PASC 작업 관리)
 *   - BNMT: http://www.bnmt.co.kr/ebiz (부산신항만)
 *   - HJNC: https://bayplan.hjnc.co.kr (한진부두)
 *
 * ⚠️ 보안 참고:
 *   외부 링크 URL은 항만 공개 사이트로 민감 정보가 아닙니다.
 *   비상 연락망 이미지(erm.jpg)는 .gitignore에 포함되어 Git에 올라가지 않습니다.
 */
object NavigationHelper {

    /**
     * NavigationView의 메뉴 항목 클릭 처리를 설정합니다.
     *
     * 각 Activity의 onCreate()에서 호출하여 사이드바를 초기화합니다.
     * 메뉴 항목 클릭 후 자동으로 사이드바를 닫습니다.
     *
     * @param context 현재 Activity Context (Intent 실행에 필요)
     * @param navigationView 사이드바 NavigationView
     * @param onDrawerClose 사이드바를 닫는 함수 (DrawerLayout.closeDrawer 호출 람다)
     */
    fun setupSidebar(
        context: Context,
        navigationView: NavigationView,
        onDrawerClose: () -> Unit
    ) {
        val layoutInflater = LayoutInflater.from(context)

        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {

                // ── 비상 연락망 ────────────────────────────────────────────────
                R.id.nav_Col -> {
                    // ⚠️ 보안: 비상 연락망 이미지(dialog_contact_image)는
                    //           .gitignore에 의해 Git에 포함되지 않습니다.
                    val dialogView = layoutInflater.inflate(R.layout.dialog_contact_image, null)
                    MaterialAlertDialogBuilder(context)
                        .setView(dialogView)
                        .setBackground(Color.TRANSPARENT.toDrawable())
                        .show()
                }

                // ── DGT 웹사이트 (동부산컨테이너터미널 선석 스케줄) ────────────
                R.id.nav_gotoDgt -> {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW,
                            "https://www.dgtbusan.com/DGT/esvc/vessel/berthScheduleG".toUri())
                    )
                }

                // ── PASC 웹사이트 ────────────────────────────────────────────
                R.id.nav_direct -> {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, "http://pasc.dothome.co.kr/".toUri())
                    )
                }

                // ── BNMT 웹사이트 (부산신항만) ───────────────────────────────
                R.id.nav_gotoBnmt -> {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, "http://www.bnmt.co.kr/ebiz/".toUri())
                    )
                }

                // ── HJNC 웹사이트 (한진부두 베이플랜) ───────────────────────
                R.id.nav_gotoHjnc -> {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, "https://bayplan.hjnc.co.kr/".toUri())
                    )
                }

                // ── 홈 (메인 화면: DGT 스케줄 그래프) ──────────────────────
                R.id.nav_main -> {
                    // 이미 MainActivity인 경우 중복 실행 방지
                    if (context !is MainActivity) {
                        context.startActivity(Intent(context, MainActivity::class.java))
                    }
                }

                // ── 모선 목록 (Firebase 등록 모선 목록) ─────────────────────
                R.id.nav_timeCal -> {
                    // 이미 FirebaseVesselActivity인 경우 중복 실행 방지
                    if (context !is FirebaseVesselActivity) {
                        context.startActivity(Intent(context, FirebaseVesselActivity::class.java))
                    }
                }

                // ── 개발자 정보 ───────────────────────────────────────────────
                R.id.nav_maker -> {
                    Toast.makeText(context, ".", Toast.LENGTH_SHORT).show()
                }
            }
            // 메뉴 항목 클릭 후 사이드바 닫기
            onDrawerClose()
            true
        }
    }
}
