import { computed, ref, watch } from 'vue'

/**
 * Préférence de thème de l'interface : « light » (par défaut) et « dark » le
 * forcent, « system » suit le réglage clair/sombre du système d'exploitation.
 * Appliquée via l'attribut data-theme sur <html> (absent = suivre le système,
 * voir les variables de couleur de style.css). Un petit script dans
 * index.html (et public/demarrage.html) applique la même préférence avant le
 * premier affichage, pour éviter un flash de l'autre thème au chargement.
 */
export const THEME_STORAGE_KEY = 'homebank-theme'
const THEME_CHOICES = ['system', 'light', 'dark']
/** Thème sans choix mémorisé : clair, quel que soit le réglage du système. */
export const DEFAULT_THEME = 'light'

export function normalizeThemePreference(value) {
  return THEME_CHOICES.includes(value) ? value : DEFAULT_THEME
}

/** Lecture tolérante : stockage indisponible (navigation privée...) = thème par défaut. */
export function readThemePreference(storage = globalThis.localStorage) {
  try {
    return normalizeThemePreference(storage?.getItem(THEME_STORAGE_KEY))
  } catch {
    return DEFAULT_THEME
  }
}

/** Seul le thème par défaut n'est pas mémorisé ; « system » l'est, puisqu'il n'est plus le défaut. */
export function saveThemePreference(preference, storage = globalThis.localStorage) {
  try {
    if (preference === DEFAULT_THEME) storage?.removeItem(THEME_STORAGE_KEY)
    else storage?.setItem(THEME_STORAGE_KEY, preference)
  } catch {
    // Préférence non mémorisée : le choix reste valable pour la session en cours.
  }
}

export function applyThemePreference(preference, root = globalThis.document?.documentElement) {
  if (!root) return
  if (preference === 'system') delete root.dataset.theme
  else root.dataset.theme = preference
}

export const themePreference = ref(readThemePreference())

watch(themePreference, (preference) => {
  const normalized = normalizeThemePreference(preference)
  applyThemePreference(normalized)
  saveThemePreference(normalized)
})

/** Thème réellement affiché : la préférence, ou le réglage du système si elle vaut « system ». */
export function resolveTheme(preference, systemPrefersDark) {
  const normalized = normalizeThemePreference(preference)
  if (normalized !== 'system') return normalized
  return systemPrefersDark ? 'dark' : 'light'
}

const systemDarkQuery = globalThis.matchMedia?.('(prefers-color-scheme: dark)')
const systemPrefersDark = ref(Boolean(systemDarkQuery?.matches))
systemDarkQuery?.addEventListener?.('change', (event) => { systemPrefersDark.value = event.matches })

/**
 * Change aussi quand le système bascule clair/sombre alors que la préférence
 * vaut « system » : les graphiques (canvas, couleurs lues au dessin) doivent
 * suivre ce signal plutôt que themePreference seul.
 */
export const effectiveTheme = computed(() => resolveTheme(themePreference.value, systemPrefersDark.value))
