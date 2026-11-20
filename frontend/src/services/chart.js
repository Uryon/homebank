/**
 * Chart.js réduit aux seuls éléments utilisés par l'application, au lieu de
 * 'chart.js/auto' qui enregistre tous les types de graphiques, échelles et
 * plugins (environ 210 ko). Graphiques utilisés : courbes (solde et
 * prévision du tableau de bord, capital restant des prêts, avec
 * remplissage), barres (épargne mensuelle) et anneau (« Où va mon argent ? »).
 * Un nouveau type de graphique ou d'échelle doit être ajouté ici, sinon
 * Chart.js lève « "xxx" is not a registered controller/scale ».
 */
import {
  ArcElement,
  BarController,
  BarElement,
  CategoryScale,
  Chart,
  DoughnutController,
  Filler,
  Legend,
  LinearScale,
  LineController,
  LineElement,
  PointElement,
  Tooltip
} from 'chart.js'

Chart.register(
  LineController, BarController, DoughnutController,
  LineElement, PointElement, BarElement, ArcElement,
  CategoryScale, LinearScale,
  Filler, Legend, Tooltip
)

/**
 * Pas d'animation des graphiques quand le système demande moins d'animations
 * (accessibilité, comme les règles CSS prefers-reduced-motion de l'appli),
 * Chart.js ne le faisant pas de lui-même. L'export PDF s'en sert aussi
 * (voir export/export-dashboard.mjs) : graphiques dessinés d'un coup, au lieu
 * d'attendre la fin d'une animation avant la capture.
 */
if (globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
  Chart.defaults.animation = false
}

export default Chart
