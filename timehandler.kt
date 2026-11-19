package com.stealth.bot.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TimeHandler {

    private val TAG = "StealthTimeHandler"
    private val scheduler = Executors.newScheduledThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)

    // Active tasks
    private val activeTasks = ConcurrentHashMap<String, ScheduledFuture<*>>()

    // Holidays (MM-dd -> नाम)
    private val holidays = ConcurrentHashMap<String, String>()

    // =========================================================
    // 1. Delayed execution (एक बार चलने वाला — इसमें remove सही है)
    // =========================================================
    fun runDelayed(delayMs: Long, action: () -> Unit): String {
        val taskId = UUID.randomUUID().toString()
        if (closed.get()) return taskId

        val future = scheduler.schedule({
            if (!closed.get()) {
                handler.post {
                    try { action() } catch (e: Exception) {
                        Log.e(TAG, "Delayed task error: ${e.message}")
                    }
                }
            }
            activeTasks.remove(taskId)
        }, delayMs, TimeUnit.MILLISECONDS)

        activeTasks[taskId] = future
        return taskId
    }

    // =========================================================
    // 2. Periodic execution (बार-बार चलने वाला — यहाँ remove कभी नहीं होगा!)
    // =========================================================
    fun runPeriodic(
        initialDelayMs: Long = 0L,
        intervalMs: Long,
        action: () -> Unit
    ): String {
        val taskId = UUID.randomUUID().toString()
        if (closed.get()) return taskId

        val future = scheduler.scheduleAtFixedRate({
            if (!closed.get()) {
                handler.post {
                    try { action() } catch (e: Exception) {
                        Log.e(TAG, "Periodic task error: ${e.message}")
                    }
                }
            }
        }, initialDelayMs, intervalMs, TimeUnit.MILLISECONDS)

        // ध्यान दें: यहाँ activeTasks से कभी रिमूव नहीं होगा ताकि यह अनंत काल तक चलता रहे!
        activeTasks[taskId] = future
        return taskId
    }

    // =========================================================
    // 3. Infinite task with condition (अनंत लूप — यहाँ भी remove नहीं होगा)
    // =========================================================
    fun runInfiniteTask(
        intervalMs: Long,
        condition: () -> Boolean,
        action: () -> Unit
    ): String {
        val taskId = UUID.randomUUID().toString()
        if (closed.get()) return taskId

        val future = scheduler.scheduleWithFixedDelay({
            if (closed.get()) return@scheduleWithFixedDelay
            try {
                if (condition()) {
                    handler.post {
                        try { action() } catch (e: Exception) {
                            Log.e(TAG, "Infinite action error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Infinite condition error: ${e.message}")
            }
        }, 0L, intervalMs, TimeUnit.MILLISECONDS)

        activeTasks[taskId] = future
        return taskId
    }

    // =========================================================
    // 4. Time range check (रात के पार पूरी तरह सुरक्षित)
    // =========================================================
    fun isCurrentTimeInRange(startHour: Int, startMin: Int, endHour: Int, endMin: Int): Boolean {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = startHour * 60 + startMin
        val endMinutes = endHour * 60 + endMin

        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes..endMinutes
        } else {
            currentMinutes >= startMinutes || currentMinutes <= endMinutes
        }
    }

    // =========================================================
    // 5. Working hours check
    // =========================================================
    fun isWorkingHours(
        startHour: Int = 9,
        endHour: Int = 18,
        includeWeekend: Boolean = false
    ): Boolean {
        val now = Calendar.getInstance()
        val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)

        if (!includeWeekend) {
            if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) return false
        }

        val hour = now.get(Calendar.HOUR_OF_DAY)
        return if (startHour <= endHour) {
            hour in startHour until endHour
        } else {
            hour >= startHour || hour < endHour
        }
    }

    // =========================================================
    // 6. Weekend & Holiday support
    // =========================================================
    fun isWeekend(): Boolean {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return day == Calendar.SATURDAY || day == Calendar.SUNDAY
    }

    fun isSaturday(): Boolean = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY
    fun isSunday(): Boolean = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY

    fun addHoliday(mmdd: String, name: String) { holidays[mmdd] = name }
    fun removeHoliday(mmdd: String) { holidays.remove(mmdd) }

    fun isHoliday(): Boolean {
        val now = Calendar.getInstance()
        val mmdd = String.format(Locale.US, "%02d-%02d",
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH))
        return holidays.containsKey(mmdd)
    }

    fun getHolidayName(): String? {
        val now = Calendar.getInstance()
        val mmdd = String.format(Locale.US, "%02d-%02d",
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH))
        return holidays[mmdd]
    }

    // =========================================================
    // 7. Time read & Greetings
    // =========================================================
    fun getHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    fun getMinute(): Int = Calendar.getInstance().get(Calendar.MINUTE)
    fun getSecond(): Int = Calendar.getInstance().get(Calendar.SECOND)
    fun getDayOfMonth(): Int = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
    fun getMonth(): Int = Calendar.getInstance().get(Calendar.MONTH) + 1
    fun getYear(): Int = Calendar.getInstance().get(Calendar.YEAR)
    fun getDayOfWeek(): Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)

    fun getDayName(): String {
        return when (getDayOfWeek()) {
            Calendar.SUNDAY -> "रविवार"
            Calendar.MONDAY -> "सोमवार"
            Calendar.TUESDAY -> "मंगलवार"
            Calendar.WEDNESDAY -> "बुधवार"
            Calendar.THURSDAY -> "गुरुवार"
            Calendar.FRIDAY -> "शुक्रवार"
            Calendar.SATURDAY -> "शनिवार"
            else -> ""
        }
    }

    fun getMonthName(): String {
        return when (getMonth()) {
            1 -> "जनवरी"; 2 -> "फरवरी"; 3 -> "मार्च"
            4 -> "अप्रैल"; 5 -> "मई"; 6 -> "जून"
            7 -> "जुलाई"; 8 -> "अगस्त"; 9 -> "सितंबर"
            10 -> "अक्टूबर"; 11 -> "नवंबर"; 12 -> "दिसंबर"
            else -> ""
        }
    }

    fun getGreeting(): String {
        val hour = getHour()
        return when {
            hour < 5 -> "शुभ रात्रि"
            hour < 12 -> "सुप्रभात"
            hour < 17 -> "नमस्ते"
            hour < 20 -> "शुभ संध्या"
            else -> "शुभ रात्रि"
        }
    }

    // =========================================================
    // 8. Formatting
    // =========================================================
    fun formatTime(pattern: String = "HH:mm:ss"): String {
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
    }

    fun formatDate(pattern: String = "dd/MM/yyyy"): String {
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
    }

    fun formatFull(): String {
        return "${getDayName()}, ${getDayOfMonth()} ${getMonthName()} ${getYear()}, ${formatTime("HH:mm:ss")}"
    }

    // =========================================================
    // 9. Epoch & Future-Safe Humanize (भविष्य के समय / नेगेटिव डिफ पर सुरक्षित)
    // =========================================================
    fun nowEpochMs(): Long = System.currentTimeMillis()
    fun nowEpochSec(): Long = System.currentTimeMillis() / 1000

    fun epochToDate(epochMs: Long): Date = Date(epochMs)

    fun epochToFormatted(epochMs: Long, pattern: String = "dd/MM/yyyy HH:mm:ss"): String {
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMs))
    }

    fun parseDate(dateString: String, pattern: String = "dd/MM/yyyy"): Date? {
        return try {
            SimpleDateFormat(pattern, Locale.getDefault()).parse(dateString)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            null
        }
    }

    fun timeSince(epochMs: Long): Long = System.currentTimeMillis() - epochMs

    fun humanizeSince(epochMs: Long): String {
        val diff = timeSince(epochMs)
        // यदि epochMs भविष्य का है (यानी negative diff) या 1 सेकंड से कम है
        return when {
            diff < 0L -> "भविष्य में"
            diff < 1000L -> "अभी"
            diff < 60_000L -> "${diff / 1000} सेकंड पहले"
            diff < 3_600_000L -> "${diff / 60_000} मिनट पहले"
            diff < 86_400_000L -> "${diff / 3_600_000} घंटे पहले"
            diff < 2_592_000_000L -> "${diff / 86_400_000} दिन पहले"
            diff < 31_536_000_000L -> "${diff / 2_592_000_000} महीने पहले"
            else -> "${diff / 31_536_000_000L} साल पहले"
        }
    }

    fun daysBetween(epoch1: Long, epoch2: Long): Long {
        return Math.abs(epoch1 - epoch2) / 86_400_000L
    }

    fun isNotificationFresh(timestampMs: Long, freshWithinMs: Long = 60_000L): Boolean {
        val diff = timeSince(timestampMs)
        return diff in 0 until freshWithinMs
    }

    fun getNotificationAge(timestampMs: Long): String = humanizeSince(timestampMs)

    // =========================================================
    // 10. Time zone
    // =========================================================
    fun getTimeZone(): String = TimeZone.getDefault().id

    fun setTimeZone(zoneId: String): Boolean {
        return try {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
            true
        } catch (e: Exception) {
            Log.e(TAG, "TimeZone error: ${e.message}")
            false
        }
    }

    // =========================================================
    // 11. Task control & Sleep helper
    // =========================================================
    fun cancelTask(taskId: String) {
        activeTasks[taskId]?.let {
            it.cancel(true)
            activeTasks.remove(taskId)
            Log.d(TAG, "Task $taskId cancelled.")
        }
    }

    fun cancelAllTasks() {
        activeTasks.values.forEach { it.cancel(true) }
        activeTasks.clear()
        Log.d(TAG, "All tasks cancelled.")
    }

    fun getActiveTaskCount(): Int = activeTasks.size
    fun isTaskActive(taskId: String): Boolean = activeTasks.containsKey(taskId)

    fun isSleepTime(sleepHour: Int = 23, wakeHour: Int = 7): Boolean {
        val hour = getHour()
        return if (sleepHour <= wakeHour) {
            hour in sleepHour until wakeHour
        } else {
            hour >= sleepHour || hour < wakeHour
        }
    }

    fun isAwakeTime(sleepHour: Int = 23, wakeHour: Int = 7): Boolean = !isSleepTime(sleepHour, wakeHour)

    // =========================================================
    // 12. Close
    // =========================================================
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelAllTasks()
        scheduler.shutdownNow()
        holidays.clear()
        Log.d(TAG, "TimeHandler closed.")
    }
}
