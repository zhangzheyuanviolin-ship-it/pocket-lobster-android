<template>
  <section class="collaboration-board" aria-label="三智能体协作看板">
    <header class="collaboration-board-header">
      <h2>三智能体协作</h2>
      <div class="collaboration-board-header-actions">
        <button type="button" @click="$emit('refresh')">刷新</button>
        <button type="button" aria-label="关闭三智能体协作看板" @click="$emit('close')">关闭</button>
      </div>
    </header>
    <p v-if="error" class="collaboration-error" role="alert">{{ error }}</p>
    <p class="collaboration-board-status" role="status">{{ boardStatusLabel }}</p>

    <nav class="collaboration-filters" aria-label="协作任务筛选">
      <button type="button" :aria-pressed="filter === 'current'" @click="filter = 'current'">当前</button>
      <button type="button" :aria-pressed="filter === 'history'" @click="filter = 'history'">历史</button>
      <button type="button" :aria-pressed="filter === 'archived'" @click="filter = 'archived'">已归档</button>
    </nav>

    <p v-if="filteredRuns.length === 0" class="collaboration-empty">没有符合条件的协作任务</p>
    <div v-else class="collaboration-run-list" aria-label="协作任务列表">
      <button
        v-for="run in filteredRuns"
        :key="run.id"
        type="button"
        class="collaboration-run-row"
        :aria-current="selectedRunId === run.id ? 'true' : undefined"
        @click="selectedRunId = run.id"
      >
        <strong>{{ run.title }}</strong>
        <span>{{ runStatusLabel(run.status) }}，总调度{{ agentLabel(run.leader) }}</span>
      </button>
    </div>

    <article v-if="selectedRun" class="collaboration-run-detail" :aria-label="selectedRun.title">
      <header>
        <button type="button" @click="selectedRunId = ''">返回任务列表</button>
        <h3>{{ selectedRun.title }}</h3>
        <p>{{ runStatusLabel(selectedRun.status) }}，第{{ selectedRun.turnNumber }}轮对话，{{ runDurationLabel(selectedRun) }}</p>
      </header>
      <p v-if="selectedRun.errorText" class="collaboration-error" role="alert">任务异常：{{ selectedRun.errorText }}</p>
      <p v-if="selectedRun.finalSummary" class="collaboration-final"><strong>总调度回复：</strong>{{ selectedRun.finalSummary }}</p>

      <section
        v-for="agentId in orderedAgentIds(selectedRun.leader)"
        :key="agentId"
        class="collaboration-agent-region"
        :aria-labelledby="`${selectedRun.id}-${agentId}-heading`"
      >
        <h4 :id="`${selectedRun.id}-${agentId}-heading`">
          {{ agentLabel(agentId) }}，{{ roleLabel(selectedRun.agents[agentId].role) }}，{{ agentStatusLabel(selectedRun.agents[agentId].status) }}
        </h4>
        <p v-if="selectedRun.agents[agentId].assignmentText"><strong>本轮任务：</strong>{{ selectedRun.agents[agentId].assignmentText }}</p>
        <p v-if="selectedRun.agents[agentId].actionText"><strong>当前进展：</strong>{{ selectedRun.agents[agentId].actionText }}</p>
        <details v-if="selectedRun.agents[agentId].responseText">
          <summary>查看{{ selectedRun.agents[agentId].role === 'leader' ? '总调度回复' : '分工结果' }}</summary>
          <p class="collaboration-summary">{{ selectedRun.agents[agentId].responseText }}</p>
        </details>
        <p v-if="selectedRun.agents[agentId].errorText" class="collaboration-error">失败原因：{{ selectedRun.agents[agentId].errorText }}</p>
      </section>

      <details v-if="selectedRun.events.length > 0">
        <summary>任务动态，共{{ selectedRun.events.length }}条</summary>
        <ol class="collaboration-events" role="log" aria-label="协作任务动态">
          <li v-for="event in selectedRun.events" :key="event.id">{{ eventLabel(event) }}</li>
        </ol>
      </details>

      <form class="collaboration-continue" @submit.prevent="submitContinue">
        <label :for="`${selectedRun.id}-continue`">{{ isActive(selectedRun) ? '补充指令' : '继续协作' }}</label>
        <textarea :id="`${selectedRun.id}-continue`" v-model="continueText" rows="2" required />
        <button type="submit">发送</button>
      </form>

      <div class="collaboration-actions" aria-label="协作任务操作">
        <button v-if="isActive(selectedRun)" type="button" @click="$emit('abort', selectedRun.id)">终止</button>
        <button v-else type="button" @click="renameSelected">重命名</button>
        <button v-if="!isActive(selectedRun)" type="button" @click="$emit('archive', selectedRun.id, !selectedRun.archived)">
          {{ selectedRun.archived ? '取消归档' : '归档' }}
        </button>
        <button v-if="!isActive(selectedRun)" type="button" class="danger" @click="deleteSelected">删除</button>
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { isCollaborationRunActive, type CollaborationAgentId, type CollaborationAgentStatus, type CollaborationEvent, type CollaborationRun } from '../../api/collaborationGateway'

const props = defineProps<{ runs: CollaborationRun[]; error?: string }>()
const emit = defineEmits<{
  close: []
  refresh: []
  abort: [runId: string]
  continue: [runId: string, prompt: string]
  rename: [runId: string, title: string]
  archive: [runId: string, archived: boolean]
  delete: [runId: string]
}>()

const filter = ref<'current' | 'history' | 'archived'>('current')
const selectedRunId = ref('')
const continueText = ref('')
const filteredRuns = computed(() => {
  const current = props.runs.filter((run) => !run.archived && (isCollaborationRunActive(run) || run.status === 'waiting_user'))
  const latestId = props.runs.find((run) => !run.archived)?.id ?? ''
  return props.runs.filter((run) => {
  if (filter.value === 'current') return current.length > 0 ? current.some((candidate) => candidate.id === run.id) : run.id === latestId
  if (filter.value === 'archived') return run.archived
  return !run.archived && !isCollaborationRunActive(run) && run.status !== 'waiting_user'
  })
})
const selectedRun = computed(() => props.runs.find((run) => run.id === selectedRunId.value) ?? null)
const boardStatusLabel = computed(() => {
  const activeRuns = props.runs.filter(isCollaborationRunActive)
  if (activeRuns.length > 0) {
    const latest = activeRuns[0]?.events.at(-1)?.text
    return latest ? `当前任务：${latest}` : `当前有${activeRuns.length}项协作任务正在运行`
  }
  const waiting = props.runs.filter((run) => run.status === 'waiting_user').length
  if (waiting > 0) return `当前有${waiting}项协作任务等待您的回复`
  return '当前没有运行中的协作任务'
})

watch(filteredRuns, (runs) => {
  if (!runs.some((run) => run.id === selectedRunId.value)) selectedRunId.value = ''
})

watch(filter, () => {
  selectedRunId.value = ''
})

function isActive(run: CollaborationRun): boolean { return isCollaborationRunActive(run) }
function agentLabel(id: CollaborationAgentId): string { return id === 'claude' ? 'Claude Code' : id === 'minis' ? 'Minis' : 'Codex' }
function roleLabel(role: 'leader' | 'worker'): string { return role === 'leader' ? '总调度' : '协作成员' }
function orderedAgentIds(leader: CollaborationAgentId): CollaborationAgentId[] {
  return [leader, ...(['codex', 'claude', 'minis'] as CollaborationAgentId[]).filter((id) => id !== leader)]
}
function runStatusLabel(status: CollaborationRun['status']): string {
  return ({ planning: '总调度分析中', running: '成员执行中', reviewing: '总调度审核中', waiting_user: '等待用户', completed: '已完成', failed: '失败', aborted: '已终止' })[status]
}
function agentStatusLabel(status: CollaborationAgentStatus): string {
  return ({ idle: '未调用', pending: '等待中', planning: '分析中', running: '执行中', reviewing: '审核中', waiting_user: '等待用户', completed: '已完成', failed: '失败', aborted: '已终止' })[status]
}
function runDurationLabel(run: CollaborationRun): string {
  const seconds = Math.max(0, Math.floor(((run.completedAtMs ?? Date.now()) - run.createdAtMs) / 1000))
  return seconds < 60 ? `${seconds}秒` : `${Math.floor(seconds / 60)}分${seconds % 60}秒`
}
function eventLabel(event: CollaborationEvent): string {
  const source = event.agentId ? `${agentLabel(event.agentId)}：` : ''
  return `${new Date(event.atMs).toLocaleTimeString()}，${source}${event.text}`
}
function submitContinue(): void {
  if (!selectedRun.value || !continueText.value.trim()) return
  emit('continue', selectedRun.value.id, continueText.value.trim())
  continueText.value = ''
}
function renameSelected(): void {
  if (!selectedRun.value) return
  const title = window.prompt('新的任务名称', selectedRun.value.title)?.trim()
  if (title) emit('rename', selectedRun.value.id, title)
}
function deleteSelected(): void {
  if (!selectedRun.value || !window.confirm(`删除“${selectedRun.value.title}”？`)) return
  emit('delete', selectedRun.value.id)
}
</script>

<style scoped>
.collaboration-board { width: min(100%, 760px); max-height: 70vh; margin: 0 auto 12px; padding: 12px 20px; overflow: auto; border-block: 1px solid #d4d4d8; background: #fafafa; color: #18181b; }
.collaboration-board-header, .collaboration-board-header-actions, .collaboration-actions, .collaboration-filters { display: flex; align-items: center; gap: 8px; }
.collaboration-board-header { justify-content: space-between; }
.collaboration-board h2, .collaboration-board h3, .collaboration-board h4 { margin: 0; letter-spacing: 0; }
.collaboration-board h2 { font-size: 17px; }
.collaboration-board h3 { font-size: 16px; }
.collaboration-board h4 { font-size: 15px; }
.collaboration-filters { margin: 10px 0; }
.collaboration-run-list { border-block: 1px solid #e4e4e7; }
.collaboration-run-row { display: flex; width: 100%; min-height: 54px; flex-direction: column; align-items: flex-start; justify-content: center; border: 0; border-bottom: 1px solid #e4e4e7; border-radius: 0; }
.collaboration-run-row[aria-current="true"] { background: #e8f1ed; }
.collaboration-run-detail { padding-top: 14px; }
.collaboration-agent-region { padding: 10px 0; border-top: 1px solid #e4e4e7; }
.collaboration-agent-region p, .collaboration-run-detail p { margin: 6px 0; }
.collaboration-summary, .collaboration-final { white-space: pre-wrap; }
.collaboration-final { padding: 10px 0; border-top: 1px solid #a1a1aa; }
.collaboration-events { max-height: 220px; overflow: auto; }
.collaboration-events li { margin: 6px 0; }
.collaboration-continue { display: grid; gap: 6px; margin-top: 12px; }
.collaboration-continue textarea { width: 100%; resize: vertical; }
.collaboration-actions { margin-top: 10px; flex-wrap: wrap; }
.collaboration-error, .danger { color: #b91c1c; }
.collaboration-empty { color: #52525b; }
.collaboration-board-status { font-weight: 600; }
button { min-height: 36px; padding: 6px 12px; border: 1px solid #a1a1aa; border-radius: 6px; background: #fff; color: #18181b; }
</style>
