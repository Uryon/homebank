<template>
  <div class="subnav">
    <RouterLink to="/transactions" class="subnav-item" :class="{ active: $route.name === 'transactions' }">
      Toutes
    </RouterLink>
    <RouterLink to="/transactions/a-traiter" class="subnav-item" :class="{ active: $route.name === 'transactions-to-process' }">
      À traiter
      <span v-if="pendingCount > 0" class="subnav-badge">{{ pendingCount }}</span>
    </RouterLink>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { dataStore } from '../services/dataStore'

const pendingCount = computed(() =>
  dataStore.state.transactions.filter(t => !t.validated).length
)
</script>

<style scoped>
.subnav {
  display: flex;
  gap: 4px;
  margin-bottom: 22px;
  border-bottom: 1px solid var(--line);
}
.subnav-item {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 9px 4px 11px;
  margin-right: 20px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-muted);
  border-bottom: 2px solid transparent;
  transition: color 0.15s, border-color 0.15s;
}
.subnav-item:hover {
  color: var(--text-primary);
}
.subnav-item.active {
  color: var(--azure);
  border-bottom-color: var(--azure);
}
.subnav-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--amber);
  color: #FFFFFF;
  font-size: 11px;
  font-weight: 700;
}
</style>
