<template>
  <div class="desktop-layout" :style="layoutStyle">
    <aside v-if="!isSidebarCollapsed" class="desktop-sidebar">
      <slot name="sidebar" />
    </aside>
    <section class="desktop-main">
      <slot name="content" />
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    isSidebarCollapsed?: boolean
  }>(),
  {
    isSidebarCollapsed: false,
  },
)

const DEFAULT_SIDEBAR_WIDTH = 320
const layoutStyle = computed(() => {
  if (props.isSidebarCollapsed) {
    return {
      '--sidebar-width': '0px',
      '--layout-columns': 'minmax(0, 1fr)',
    }
  }
  return {
    '--sidebar-width': `${DEFAULT_SIDEBAR_WIDTH}px`,
    '--layout-columns': 'var(--sidebar-width) minmax(0, 1fr)',
  }
})
</script>

<style scoped>
@reference "tailwindcss";

.desktop-layout {
  @apply h-screen grid bg-slate-100 text-slate-900 overflow-hidden;
  grid-template-columns: var(--layout-columns);
}

.desktop-sidebar {
  @apply bg-slate-100 min-h-0 overflow-y-auto;
}

.desktop-main {
  @apply bg-white min-h-0 overflow-y-hidden overflow-x-visible;
}
</style>
