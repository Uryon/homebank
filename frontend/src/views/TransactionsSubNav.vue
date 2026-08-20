<template>
  <div class="subnav">
    <RouterLink to="/transactions" class="subnav-item" :class="{ active: $route.name === 'transactions' }">
      Toutes
    </RouterLink>
    <RouterLink to="/transactions/periodiques" class="subnav-item" :class="{ active: $route.name === 'transactions-periodic-suggestions' }">
      Périodiques
    </RouterLink>
  </div>
</template>


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
</style>
