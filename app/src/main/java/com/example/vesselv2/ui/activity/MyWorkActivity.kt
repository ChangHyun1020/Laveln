package com.example.vesselv2.ui.activity

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vesselv2.data.local.MyWorkDatabase
import com.example.vesselv2.data.local.MyWorkEntity
import com.example.vesselv2.databinding.ActivityMyWorkBinding
import com.example.vesselv2.ui.adapter.MyWorkAdapter
import com.example.vesselv2.util.NavigationHelper
import com.example.vesselv2.util.WorkCalculator
import com.example.vesselv2.util.setupEdgeToEdgeInsets
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MyWorkActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyWorkBinding
    private lateinit var adapter: MyWorkAdapter
    private var currentCalendar = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyWorkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 시스템 바 영역 확보
        setupEdgeToEdgeInsets(binding.toolbar)

        setupAdapter()
        setupUI()
        observeWorkData()
    }

    private fun setupAdapter() {
        adapter =
            MyWorkAdapter(
                items = emptyList(),
                onEditClick = { work -> showEditWorkDialog(work) },
                onDeleteClick = { work -> showDeleteConfirmDialog(work) }
            )
        binding.rvWorkList.apply {
            adapter = this@MyWorkActivity.adapter
            layoutManager = LinearLayoutManager(this@MyWorkActivity)
        }
    }

    private fun setupUI() {
        binding.ivMenu.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }

        NavigationHelper.setupSidebar(this, binding.navigationView) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        binding.btnPrevMonth.setOnClickListener {
            currentCalendar.add(Calendar.MONTH, -1)
            updateMonthDisplay()
            observeWorkData()
        }

        binding.btnNextMonth.setOnClickListener {
            currentCalendar.add(Calendar.MONTH, 1)
            updateMonthDisplay()
            observeWorkData()
        }

        updateMonthDisplay()

        // 금액 정보 숨김 처리
        binding.tvMonthlyTotalAmount.visibility = View.GONE
    }

    @SuppressLint("SetTextI18n")
    private fun updateMonthDisplay() {
        val sdf = SimpleDateFormat("yyyy년 MM월", Locale.KOREAN)
        binding.tvCurrentMonth.text = sdf.format(currentCalendar.time)
    }

    private fun observeWorkData() {
        val monthStr = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(currentCalendar.time)
        val dao = MyWorkDatabase.getDatabase(this).myWorkDao()

        lifecycleScope.launch {
            dao.getWorkByMonth(monthStr).collectLatest { workList ->
                updateStatistics(workList)
                adapter.updateItems(workList)
                binding.tvEmpty.visibility = if (workList.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    @SuppressLint("SetTextI18n", "DefaultLocale")
    private fun updateStatistics(workList: List<MyWorkEntity>) {
        val totalHours = workList.sumOf { it.totalHours }
        binding.tvMonthlyTotalHours.text = "이달의 총 근무 : ${totalHours.toDouble()} 시간"

        // 총 예상 급여 UI 숨김 (setupUI에서 Gone 설정)
    }

    private fun showEditWorkDialog(work: MyWorkEntity) {
        val startCal = Calendar.getInstance().apply { timeInMillis = work.startTimeMs }
        val endCal = Calendar.getInstance().apply { timeInMillis = work.endTimeMs }

        android.app.DatePickerDialog(
            this,
            { _, year, month, day ->
                android.app.TimePickerDialog(
                    this,
                    { _, startH, startM ->
                        android.app.TimePickerDialog(
                            this,
                            { _, endH, endM ->
                                showRainHoursInputDialog(
                                    work,
                                    year,
                                    month,
                                    day,
                                    startH,
                                    startM,
                                    endH,
                                    endM
                                )
                            },
                            endCal.get(Calendar.HOUR_OF_DAY),
                            endCal.get(Calendar.MINUTE),
                            true
                        )
                            .show()
                    },
                    startCal.get(Calendar.HOUR_OF_DAY),
                    startCal.get(Calendar.MINUTE),
                    true
                )
                    .show()
            },
            startCal.get(Calendar.YEAR),
            startCal.get(Calendar.MONTH),
            startCal.get(Calendar.DAY_OF_MONTH)
        )
            .show()
    }

    private fun showRainHoursInputDialog(
        work: MyWorkEntity,
        y: Int,
        m: Int,
        d: Int,
        sH: Int,
        sM: Int,
        eH: Int,
        eM: Int
    ) {
        val editText =
            EditText(this).apply {
                hint = "우천 시간 (예: 1)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(work.rainHours.toString())
            }
        val lp =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        lp.setMargins(60, 20, 60, 20)
        val container = LinearLayout(this).apply { addView(editText, lp) }
    }


    private fun updateWorkInDb(
        work: MyWorkEntity,
        y: Int,
        m: Int,
        d: Int,
        sH: Int,
        sM: Int,
        eH: Int,
        eM: Int,
        rain: Int
    ) {
        lifecycleScope.launch {
            val start =
                Calendar.getInstance().apply {
                    set(y, m, d, sH, sM, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            val end =
                Calendar.getInstance().apply {
                    set(y, m, d, eH, eM, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            if (end.before(start)) end.add(Calendar.DAY_OF_MONTH, 1)

            val calcResult =
                WorkCalculator.calculate(
                    start.timeInMillis,
                    end.timeInMillis,
                    work.isSkill,
                    rain,
                    false
                )
            val updated =
                work.copy(
                    workDate =
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            .format(start.time),
                    startTimeMs = start.timeInMillis,
                    endTimeMs = end.timeInMillis,
                    totalHours = calcResult.totalHours,
                    amount = calcResult.amount,
                    rainHours = rain
                )
            MyWorkDatabase.getDatabase(this@MyWorkActivity).myWorkDao().insertWork(updated)
            Toast.makeText(this@MyWorkActivity, "수정되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteConfirmDialog(work: MyWorkEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("기록 삭제")
            .setMessage("[${work.vesselName}]의 근무 기록을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                lifecycleScope.launch {
                    MyWorkDatabase.getDatabase(this@MyWorkActivity).myWorkDao().deleteWork(work)
                    Toast.makeText(this@MyWorkActivity, "삭제되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
