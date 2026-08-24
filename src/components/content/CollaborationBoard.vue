<template>
  <section class="collaboration-board" aria-label="三智能体协作看板">
    <div class="collaboration-board-header">
      <h2>三智能体协作看板</h2>
      <button type="button" aria-label="关闭三智能体协作看板" @click="$emit('close')">关闭</button>
    </div>
    <p v-if="error" class="collaboration-error" aria-live="assertive">{{ error }}</p>
    <p v-if="runs.length === 0" class="collaboration-empty">暂无协作任务</p>
    <article v-for="run in runs" :key="run.id" class="collaboration-run">
      <div class="collaboration-run-title">
        <strong>{{ run.title }}</strong>
        <span>{{ runStatusLabel(run.status) }}</span>
      </div>
      <p>总调度：{{ agentLabel(run.leader) }}</p>
      <p>{{ runDurationLabel(run) }}</p>
      <details>
        <summary>用户原始任务</summary>
        <p class="collaboration-summary">{{ run.prompt }}</p>
      </details>
      <ul>
        <li v-for="agentId in agentIds" :key="agentId">
          <strong>{{ agentLabel(agentId) }}</strong>
          <span>{{ agentStatusLabel(run.agents[agentId].status) }}</span>
          <span v-if="run.agents[agentId].sessionId">，会话已创建</span>
          <span v-if="run.agents[agentId].errorText">，{{ run.agents[agentId].errorText }}</span>
          <details>
            <summary>查看收发消息与动作</summary>
            <p v-if="run.agents[agentId].requestText" class="collaboration-summary">收到：{{ run.agents[agentId].requestText }}</p>
            <p v-if="run.agents[agentId].actionText" class="collaboration-summary" aria-live="polite">动作：{{ run.agents[agentId].actionText }}</p>
            <p v-if="run.agents[agentId].responseText" class="collaboration-summary">回复：{{ run.agents[agentId].responseText }}</p>
          </details>
        </li>
      </ul>
      <details v-if="run.finalSummary">
        <summary>总调度最终汇总</summary>
        <p class="collaboration-summary">{{ run.finalSummary }}</p>
      </details>
      <button
        v-if="run.status === 'running'"
        type="button"
        :aria-label="`终止协作任务 ${run.title}`"
        @click="$emit('abort', run.id)"
      >
        终止协作
      </button>
    </article>
  </section>
</template>

<script setup lang="ts">
import type { CollaborationAgentId, CollaborationAgentStatus, CollaborationRun } from '../../api/collaborationGateway'

defineProps<{
  runs: CollaborationRun[]
  error?: string
}>()

defineEmits<{
  close: []
  abort: [runId: string]
}>()

const agentIds: CollaborationAgentId[] = ['codex', 'claude', 'minis']

function agentLabel(agentId: CollaborationAgentId): string {
  if (agentId === 'claude') return 'Claude Code'
  if (agentId === 'minis') return 'Minis'
  return 'Codex'
}

function runStatusLabel(status: CollaborationRun['status']): string {
  return ({ running: '运行中', completed: '已完成', failed: '失败', aborted: '已终止' })[status]
}

function agentStatusLabel(status: CollaborationAgentStatus): string {
  return ({
    pending: '等待中',
    running: '执行中',
    synthesizing: '正在汇总',
    completed: '已完成',
    failed: '失败',
    aborted: '已终止',
  })[status]
}

function runDurationLabel(run: CollaborationRun): string {
  const endAtMs = run.completedAtMs ?? Date.now()
  const seconds = Math.max(0, Math.floor((endAtMs - run.createdAtMs) / 1000))
  if (seconds < 60) return `${run.status === 'running' ? '已运行' : '耗时'}${seconds}秒`
  const minutes = Math.floor(seconds / 60)
  const remainingSeconds = seconds % 60
  return `${run.status === 'running' ? '已运行' : '耗时'}${minutes}分${remainingSeconds}秒`
}
</script>

<style scoped>
.collaboration-board {
  width: min(100%, 700px);
  max-height: 55vh;
  margin: 0 auto 12px;
  padding: 12px 24px;
  overflow: auto;
  border-top: 1px solid #d4d4d8;
  border-bottom: 1px solid #d4d4d8;
  background: #fafafa;
  color: #18181b;
}

.collaboration-board-header,
.collaboration-run-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.collaboration-board h2 {
  margin: 0;
  font-size: 16px;
}

.collaboration-run {
  padding: 12px 0;
  border-top: 1px solid #e4e4e7;
}

.collaboration-run p,
.collaboration-run ul {
  margin: 8px 0;
}

.collaboration-run li {
  margin: 6px 0;
}

.collaboration-summary {
  white-space: pre-wrap;
}

.collaboration-error {
  color: #b91c1c;
}

.collaboration-empty {
  color: #52525b;
}

button {
  min-height: 36px;
  padding: 6px 12px;
  border: 1px solid #a1a1aa;
  border-radius: 6px;
  background: #fff;
  color: #18181b;
}
</style>
