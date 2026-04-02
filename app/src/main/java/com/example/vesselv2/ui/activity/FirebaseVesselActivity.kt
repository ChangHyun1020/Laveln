package com.example.vesselv2.ui.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vesselv2.R
import com.example.vesselv2.databinding.ActivityFirebaseVesselBinding
import com.example.vesselv2.data.model.Vessel
import com.example.vesselv2.ui.adapter.VesselAdapter
import com.example.vesselv2.ui.viewmodel.VesselViewModel
import com.example.vesselv2.util.NavigationHelper
import com.example.vesselv2.util.setupEdgeToEdgeInsets
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore

/**
 * 파이어베이스에 등록된 모선 목록을 보여주는 액티비티
 * (기존 MainActivity 의 기능을 별도 액티비티로 복구)
 */
class FirebaseVesselActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFirebaseVesselBinding
    private val viewModel: VesselViewModel by viewModels()
    private lateinit var vesselAdapter: VesselAdapter
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFirebaseVesselBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 시스템 바 영역 확보
        setupEdgeToEdgeInsets(binding.appBarId, binding.fab)

        setupToolbar()
        setupSidebar()
        setupRecyclerView()
        setupSearchView()
        setupFab()
        observeViewModel()

        // 모선 목록 로드
        viewModel.loadVessels()

        // 뒤로가기 핸들링 (사이드바 닫기 우선)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    // 메인(DGT 리스트)으로 돌아가거나 앱 종료
                    finish()
                }
            }
        })
    }

    private fun setupToolbar() {
        binding.ivMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    private fun setupSidebar() {
        NavigationHelper.setupSidebar(this, binding.navigationView) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        // 현재 메뉴 아이템 하이라이트
        binding.navigationView.setCheckedItem(R.id.nav_timeCal)
    }

    private fun setupRecyclerView() {
        vesselAdapter = VesselAdapter(
            onClick = { vessel: Vessel ->
                val intent = Intent(this, DetailActivity::class.java).apply {
                    putExtra("vesselName", vessel.vesselName)
                    putExtra("vesselDocId", vessel.id)
                }
                startActivity(intent)
            },
            onLongClick = { vessel: Vessel, _ ->
                showDeleteDialog(vessel)
            }
        )

        binding.recyclerView.apply {
            adapter = vesselAdapter
            layoutManager = LinearLayoutManager(this@FirebaseVesselActivity)
        }
    }

    private fun setupSearchView() {
        binding.searchView.setOnSearchClickListener {
            binding.topName.visibility = View.GONE
        }
        binding.searchView.setOnCloseListener {
            binding.topName.visibility = View.VISIBLE
            false
        }
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.setFirebaseSearchQuery(query ?: "")
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setFirebaseSearchQuery(newText ?: "")
                return true
            }
        })
    }

    private fun setupFab() {
        binding.fab.setOnClickListener {
            startActivity(Intent(this, AddVesselActivity::class.java))
        }
    }

    private fun observeViewModel() {
        // 로딩 상태 관찰
        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        // 필터링된 목록 관찰
        viewModel.filteredVessels.observe(this) { list ->
            vesselAdapter.submitList(list)
            
            val isSearch = !viewModel.firebaseSearchQuery.value.isNullOrEmpty()
            val isEmpty = list.isEmpty()
            
            binding.tvEmpty.visibility = if (isEmpty && !isSearch) View.VISIBLE else View.GONE
            binding.tvSearchEmpty.visibility = if (isEmpty && isSearch) View.VISIBLE else View.GONE
        }
    }

    private fun showDeleteDialog(vessel: Vessel) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_title_delete)
            .setMessage(getString(R.string.dialog_msg_delete, vessel.vesselName))
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                deleteVesselFromFirebase(vessel)
            }
            .show()
    }

    private fun deleteVesselFromFirebase(vessel: Vessel) {
        db.collection(com.example.vesselv2.util.Constants.VESSEL_COLLECTION).document(vessel.vesselName)
            .delete()
            .addOnSuccessListener {
                Toast.makeText(this, R.string.toast_delete_success, Toast.LENGTH_SHORT).show()
                viewModel.loadVessels() // 목록 갱신
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, getString(R.string.toast_delete_fail, e.message), Toast.LENGTH_LONG).show()
            }
    }
}
