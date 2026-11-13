/**
 * Directive v-grid-nav, à poser sur un <table> dont les cellules chiffrées
 * (td.num) révèlent un détail au survol (tableaux mensuels du tableau de bord).
 * Navigation clavier selon le motif « grille » de WAI-ARIA :
 * - un seul arrêt Tab pour tout le tableau (tabindex « itinérant ») ;
 * - flèches : cellule voisine ; Début / Fin : première / dernière cellule de la ligne ;
 * - le focus d'une cellule déclenche le même détail que le survol (événement
 *   mouseenter rejoué sur la cellule), sa perte ou Échap le referme (mouseleave).
 * Le calcul de la cellule cible est dans nextGridPosition (testé).
 */

const CELL_SELECTOR = 'tbody td.num'

/** Cellules navigables, ligne par ligne (les lignes sans cellule chiffrée sont ignorées). */
function cellMatrix(table) {
  return [...table.querySelectorAll('tbody tr')]
    .map(tr => [...tr.querySelectorAll('td.num')])
    .filter(row => row.length)
}

/**
 * Position cible après une touche, ou null si la touche ne déplace pas.
 * rowLengths : nombre de cellules de chaque ligne (les lignes peuvent différer).
 */
export function nextGridPosition(key, { row, col }, rowLengths) {
  const lastRow = rowLengths.length - 1
  const clampCol = (r, c) => Math.min(c, rowLengths[r] - 1)
  switch (key) {
    case 'ArrowRight': return { row, col: Math.min(col + 1, rowLengths[row] - 1) }
    case 'ArrowLeft': return { row, col: Math.max(col - 1, 0) }
    case 'ArrowDown': return row < lastRow ? { row: row + 1, col: clampCol(row + 1, col) } : { row, col }
    case 'ArrowUp': return row > 0 ? { row: row - 1, col: clampCol(row - 1, col) } : { row, col }
    case 'Home': return { row, col: 0 }
    case 'End': return { row, col: rowLengths[row] - 1 }
    default: return null
  }
}

function replay(cell, type) {
  cell.dispatchEvent(new MouseEvent(type))
}

function makeActive(table, cell) {
  if (table._gridActive && table._gridActive !== cell) table._gridActive.tabIndex = -1
  cell.tabIndex = 0
  table._gridActive = cell
}

/** (Ré)applique les tabindex : une seule cellule atteignable par Tab. */
function refreshTabStops(table) {
  const cells = [...table.querySelectorAll(CELL_SELECTOR)]
  for (const cell of cells) cell.tabIndex = -1
  const active = table._gridActive?.isConnected && cells.includes(table._gridActive) ? table._gridActive : cells[0]
  if (active) {
    active.tabIndex = 0
    table._gridActive = active
  }
}

function onKeydown(event) {
  const table = event.currentTarget
  const cell = event.target.closest?.('td.num')
  if (!cell || !table.contains(cell)) return
  if (event.key === 'Escape') {
    replay(cell, 'mouseleave')
    return
  }
  const matrix = cellMatrix(table)
  const row = matrix.findIndex(r => r.includes(cell))
  if (row === -1) return
  const target = nextGridPosition(event.key, { row, col: matrix[row].indexOf(cell) }, matrix.map(r => r.length))
  if (!target) return
  event.preventDefault()
  const next = matrix[target.row][target.col]
  if (next && next !== cell) next.focus()
}

function onFocusIn(event) {
  const cell = event.target
  if (!cell.matches?.(CELL_SELECTOR)) return
  makeActive(event.currentTarget, cell)
  replay(cell, 'mouseenter')
}

function onFocusOut(event) {
  if (event.target.matches?.(CELL_SELECTOR)) replay(event.target, 'mouseleave')
}

export const vGridNav = {
  mounted(table) {
    table.addEventListener('keydown', onKeydown)
    table.addEventListener('focusin', onFocusIn)
    table.addEventListener('focusout', onFocusOut)
    refreshTabStops(table)
  },
  // Lignes repliées/dépliées, changement de mois : de nouvelles cellules apparaissent.
  updated(table) {
    refreshTabStops(table)
  },
  unmounted(table) {
    table.removeEventListener('keydown', onKeydown)
    table.removeEventListener('focusin', onFocusIn)
    table.removeEventListener('focusout', onFocusOut)
  }
}
