package com.stealth.bot.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean

class WorkflowEngine {

    private val TAG = "StealthWorkflowEngine"
    private val handler = Handler(Looper.getMainLooper())
    private val random = Random()
    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    data class Step(
        val name: String,
        val delayMinMs: Long = 300L,
        val delayMaxMs: Long = 900L,
        val retries: Int = 2,
        val action: (onStepDone: (Boolean) -> Unit) -> Unit
    )

    fun runWorkflow(
        steps: List<Step>,
        onFinished: ((Boolean) -> Unit)? = null
    ) {
        if (steps.isEmpty()) {
            onFinished?.invoke(true)
            return
        }

        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "Workflow is already running.")
            onFinished?.invoke(false)
            return
        }

        stopped.set(false)
        executeStepAt(steps, index = 0, onFinished = onFinished)
    }

    private fun executeStepAt(
        steps: List<Step>,
        index: Int,
        onFinished: ((Boolean) -> Unit)?
    ) {
        if (stopped.get()) {
            running.set(false)
            Log.d(TAG, "Workflow stopped.")
            handler.post { onFinished?.invoke(false) }
            return
        }

        if (index >= steps.size) {
            running.set(false)
            Log.d(TAG, "Workflow completed successfully.")
            handler.post { onFinished?.invoke(true) }
            return
        }

        val step = steps[index]
        val minD = step.delayMinMs.coerceAtLeast(0L)
        val maxD = step.delayMaxMs.coerceAtLeast(minD)
        val preDelay = if (maxD > minD) minD + random.nextInt((maxD - minD).toInt() + 1) else minD

        handler.postDelayed({
            if (stopped.get()) {
                running.set(false)
                handler.post { onFinished?.invoke(false) }
                return@postDelayed
            }

            attemptStep(step, remainingRetries = step.retries) { ok ->
                if (ok) {
                    executeStepAt(steps, index + 1, onFinished)
                } else {
                    running.set(false)
                    Log.w(TAG, "Workflow failed at step: ${step.name}")
                    handler.post { onFinished?.invoke(false) }
                }
            }
        }, preDelay)
    }

    private fun attemptStep(
        step: Step,
        remainingRetries: Int,
        onResult: (Boolean) -> Unit
    ) {
        if (stopped.get()) {
            onResult(false)
            return
        }

        try {
            step.action { success ->
                if (stopped.get()) {
                    onResult(false)
                    return@action
                }

                if (success) {
                    Log.d(TAG, "Step passed: ${step.name}")
                    onResult(true)
                } else if (remainingRetries > 0) {
                    val retryDelay = 400L + random.nextInt(500)
                    Log.w(TAG, "Step '${step.name}' failed, retrying in ${retryDelay}ms...")
                    handler.postDelayed({
                        attemptStep(step, remainingRetries - 1, onResult)
                    }, retryDelay)
                } else {
                    onResult(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in step '${step.name}': ${e.message}")
            if (remainingRetries > 0 && !stopped.get()) {
                val retryDelay = 400L + random.nextInt(500)
                handler.postDelayed({
                    attemptStep(step, remainingRetries - 1, onResult)
                }, retryDelay)
            } else {
                onResult(false)
            }
        }
    }

    fun stop() {
        stopped.set(true)
        running.set(false)
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "WorkflowEngine stopped and callbacks cleared.")
    }

    fun isRunning(): Boolean = running.get()
}
