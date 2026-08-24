package com.codex.mobile

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object AgentTaskRegistry {
    data class Snapshot(
        val agentId: ExternalAgentId,
        val sessionId: String,
        val startedAtMs: Long,
        val abortRequested: Boolean,
    )

    private data class RunningTask(
        val agentId: ExternalAgentId,
        val sessionId: String,
        val process: Process,
        val startedAtMs: Long,
        @Volatile var abortRequested: Boolean = false,
    )

    private val tasks = ConcurrentHashMap<String, RunningTask>()
    private val abortedTasks = ConcurrentHashMap.newKeySet<String>()

    private fun key(agentId: ExternalAgentId, sessionId: String): String =
        "${agentId.value}:${sessionId.trim()}"

    fun register(agentId: ExternalAgentId, sessionId: String, process: Process) {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isEmpty()) return
        val taskKey = key(agentId, normalizedSessionId)
        abortedTasks.remove(taskKey)
        tasks[taskKey] = RunningTask(
            agentId = agentId,
            sessionId = normalizedSessionId,
            process = process,
            startedAtMs = System.currentTimeMillis(),
        )
    }

    fun clear(agentId: ExternalAgentId, sessionId: String, process: Process? = null) {
        val taskKey = key(agentId, sessionId)
        val current = tasks[taskKey] ?: return
        if (process == null || current.process === process) {
            tasks.remove(taskKey, current)
        }
    }

    fun snapshot(agentId: ExternalAgentId, sessionId: String): Snapshot? {
        val task = tasks[key(agentId, sessionId)] ?: return null
        if (!task.process.isAlive) {
            tasks.remove(key(agentId, sessionId), task)
            return null
        }
        return Snapshot(
            agentId = task.agentId,
            sessionId = task.sessionId,
            startedAtMs = task.startedAtMs,
            abortRequested = task.abortRequested,
        )
    }

    fun isRunning(agentId: ExternalAgentId, sessionId: String): Boolean =
        snapshot(agentId, sessionId) != null

    fun wasAbortRequested(agentId: ExternalAgentId, sessionId: String): Boolean =
        abortedTasks.remove(key(agentId, sessionId)) || tasks[key(agentId, sessionId)]?.abortRequested == true

    fun abort(agentId: ExternalAgentId, sessionId: String): Boolean {
        val task = tasks[key(agentId, sessionId)] ?: return false
        task.abortRequested = true
        abortedTasks.add(key(agentId, sessionId))
        terminate(task)
        return true
    }

    private fun terminate(task: RunningTask) {
        val process = task.process
        runCatching { process.destroy() }
        Thread {
            val exited = runCatching { process.waitFor(900, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!exited && process.isAlive) {
                runCatching { process.destroyForcibly() }
                runCatching { process.waitFor(1_500, TimeUnit.MILLISECONDS) }
            }
        }.apply {
            name = "agent-task-abort-${task.agentId.value}"
            isDaemon = true
            start()
        }
    }
}
