<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">◆</span> HomeBank</div>
      <nav>
        <RouterLink to="/" class="nav-item" :class="{ active: $route.name === 'dashboard' }">
          <span class="dot"></span> Tableau de bord
        </RouterLink>
        <RouterLink to="/accounts" class="nav-item" :class="{ active: $route.name === 'accounts' }">
          <span class="dot"></span> Comptes
        </RouterLink>
        <RouterLink to="/transactions" class="nav-item" :class="{ active: $route.name === 'transactions' }">
          <span class="dot"></span> Transactions
        </RouterLink>
        <RouterLink to="/categories" class="nav-item" :class="{ active: $route.name === 'categories' }">
          <span class="dot"></span> Catégories
        </RouterLink>
        <RouterLink to="/payees" class="nav-item" :class="{ active: $route.name === 'payees' }">
          <span class="dot"></span> Tiers
        </RouterLink>
        <RouterLink to="/reports" class="nav-item" :class="{ active: $route.name === 'reports' }">
          <span class="dot"></span> Rapports
        </RouterLink>
        <RouterLink to="/import" class="nav-item" :class="{ active: $route.name === 'import' }">
          <span class="dot"></span> Import CSV/QIF
        </RouterLink>
      </nav>
    </aside>
    <main class="main-content">
      <RouterView />
    </main>
  </div>
</template>

<script setup>
</script>
