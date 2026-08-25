<template>
  <section class="collaboration-board" aria-label="三智能体协作看板">
    <div class="collaboration-board-header">
      <h2>三智能体协作看板</h2>
      <button type="button" aria-label="关闭三智能体协作看板" @click="$emit('close')">关闭</button>
    </div>
    <p v-if="error" class="collaboration-error" aria-live="assertive">{{ error }}</p>
    <p class="collaboration-board-status" role="status">{{ boardStatusLabel }}</p>
    <p v-if="runs.length === 0" class="collaboration-empty">可在任一智能体页面打开三智能体协作后发送任务。</p>
    <article v-for="run in runs" :key="run.id" class="collaboration-run">
      <div class="collaboration-run-title">
        <strong>{{ run.status === 'running' ? '当前任务' : '历史任务' }}：{{ run.title }}</strong>
        <span>{{ runStatusLabel(run.status) }}</span>
      </div>
      <p>总调度：{{ agentLabel(run.leader) }}</p>
      <p>{{ runDurationLabel(run) }}</p>
      <p v-if="run.errorText" class="collaboration-error">任务异常：{{ run.errorText }}</p>
      <details>
        <summary>用户原始任务</summary>
        <p class="collaboration-summary">{{ run.prompt }}</p>
      </details>
      <ul>
        <li v-for="agentId in agentIds" :key="agentId">
          <details>
            <summary>{{ agentLabel(agentId) }}，{{ roleLabel(run.agents[agentId].role) }}，{{ agentStatusLabel(run.agents[agentId].status) }}</summary>
            <p v-if="run.agents[agentId].actionText" class="collaboration-summary" aria-live="polite">当前进展：{{ run.agents[agentId].actionText }}</p>
            <p v-if="run.agents[agentId].responseText" class="collaboration-summary">{{ run.agents[agentId].role === 'leader' ? (run.status === 'completed' ? '最终汇总' : '阶段输出') : '分工输出' }}：{{ run.agents[agentId].responseText }}</p>
            <p v-if="run.agents[agentId].errorText" class="collaboration-error">失败原因：{{ run.agents[agentId].errorText }}</p>
          </details>
        </li>
      </ul>
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
import { computed } from 'vue'
import type { CollaborationAgentId, CollaborationAgentStatus, CollaborationRun } from '../../api/collaborationGateway'

const props = defineProps<{
  runs: CollaborationRun[]
  error?: string
}>()

defineEmits<{
  close: []
  abort: [runId: string]
}>()

const agentIds: CollaborationAgentId[] = ['codex', 'claude', 'minis']
const boardStatusLabel = computed(() => {
  const running = props.runs.filter((run) => run.status === 'running').length
  if (running > 0) return `当前有${running}项协作任务正在运行，状态将自动更新。`
  if (props.runs.length > 0) return '当前没有运行中的协作任务，下方是历史协作记录。'
  return '当前没有协作任务。'
})

function agentLabel(agentId: CollaborationAgentId): string {
  if (agentId === 'claude') return 'Claude Code'
  if (agentId === 'minis') return 'Minis'
  return 'Codex'
}

function roleLabel(role: 'leader' | 'worker'): string {
  return role === 'leader' ? '总调度' : '协作成员'
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

.collaboration-board-status {
  font-weight: 600;
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
