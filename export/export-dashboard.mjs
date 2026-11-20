// Exporte le tableau de bord Homebank en PDF, en capturant la page telle
// qu'elle est reellement rendue (graphiques Chart.js compris) — evite de
// dupliquer la mise en page du tableau de bord dans un template PDF separe.
// Appele par ServiceExport (backend) via ProcessBuilder :
//   node export-dashboard.mjs <dossier-sortie> [url-tableau-de-bord] [date-dernier-import-iso]
//
// Prerequis (une seule fois) : npm install && npx playwright install chromium
//
// L'export tient en deux orientations differentes (voir style.css) : les
// tableaux d'analyse mensuelle ont besoin du paysage (12 colonnes de mois),
// le reste se lit mieux en portrait sur un telephone (l'export est consulte
// a distance). On capture donc la meme page deux fois avec des classes CSS
// qui masquent alternativement l'une ou l'autre partie, puis on fusionne les
// deux PDF resultants avec pdf-lib — qui sert aussi a numeroter les pages et
// poser la date du dernier import de fichier en en-tete de la premiere page
// (plus utile pour juger de la fraicheur des donnees que l'instant de
// generation du PDF, voir ServiceDernierImport cote backend) et la date de
// generation en pied de chaque page (Playwright numeroterait chaque partie
// independamment si on utilisait son displayHeaderFooter).

import { chromium } from 'playwright'
// Version ESM de pdf-lib en un seul fichier : « pdf-lib » seul est resolu vers
// sa version CommonJS (139 fichiers), chargee en ~0,75 s au lieu de ~0,1 s
// (mesure ; PDF produit identique octet pour octet, verifie).
import { PDFDocument, StandardFonts, rgb } from 'pdf-lib/dist/pdf-lib.esm.min.js'
import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'

const [, , dossierSortie, urlTableauDeBord, dateDernierImportIso] = process.argv

if (!dossierSortie) {
  console.error('Usage: node export-dashboard.mjs <dossier-sortie> [url-tableau-de-bord] [date-dernier-import-iso]')
  process.exit(1)
}

// 127.0.0.1 : voir export.url-tableau-de-bord (application.properties).
const url = urlTableauDeBord || 'http://127.0.0.1:5173/'
// Nom fixe (pas d'horodatage) : chaque export remplace le precedent, plutot
// que d'accumuler un fichier par export dans le dossier synchronise.
const cheminSortie = path.join(dossierSortie, 'tableau-de-bord.pdf')

await mkdir(dossierSortie, { recursive: true })

/*
 * Le serveur de dev Vite peut recharger la page a tout moment (dependance
 * re-optimisee au premier affichage, fichier modifie pendant une session de
 * developpement). Sans garde-fou, un rechargement pendant l'export donnait
 * soit une erreur ("Execution context was destroyed"), soit — pire — un PDF
 * vide ou incomplet annonce comme reussi, qui remplacait le bon dans le
 * dossier synchronise (reproduit : 2 pages de 2 Ko au lieu de 4 pages).
 * Chaque capture est donc faite sur un document marque, verifie apres coup,
 * et recommencee si la page a ete rechargee entre-temps.
 */
const ESSAIS_MAX = 3
const CLASSES_EXPORT = ['pdf-cacher-tableaux-larges', 'pdf-ne-montrer-que-tableaux-larges']

/**
 * Marque le document AVANT d'attendre son rendu complet (donnees chargees,
 * graphiques dessines) : un rechargement pendant cette attente efface la
 * marque et sera donc detecte. Marquer apres coup validait a tort un
 * document recharge a moitie rendu (reproduit : 3 pages au lieu de 4).
 */
async function attendreTableauDeBord(page) {
  await page.waitForLoadState('networkidle')
  // Document deja marque et rendu par la capture precedente : rien a attendre.
  const dejaPret = await page.evaluate(() => window.__exportHomebank)
  if (dejaPret) return dejaPret
  const jeton = `${Date.now()}-${Math.random()}`
  await page.evaluate((valeur) => { window.__exportHomebank = valeur }, jeton)
  await attendreGraphiquesDessines(page)
  return jeton
}

/**
 * Attend que chaque graphique du tableau de bord soit dessine. La page est
 * ouverte en mode « moins d'animations » (voir newPage) : l'appli coupe alors
 * les animations de Chart.js (voir frontend/src/services/chart.js), et un
 * graphique est dessine des sa creation, qui marque son canvas ($chartjs).
 * Remplace une attente fixe d'1 s (fin de l'animation d'entree). Le canvas de
 * « Ou va mon argent ? » n'existe que s'il y a des depenses a montrer : tout
 * canvas present a donc son graphique.
 */
async function attendreGraphiquesDessines(page) {
  await page.waitForFunction(() => {
    const canvas = [...document.querySelectorAll('.dashboard-page canvas')]
    return canvas.length > 0 && canvas.every((c) => c.$chartjs)
  }, null, { timeout: 20000 })
  // Redimensionnement des graphiques a leur conteneur (image suivante).
  await page.evaluate(() => new Promise((fin) => requestAnimationFrame(() => requestAnimationFrame(fin))))
}

/** Vrai si la page est toujours le document marque par `jeton` (pas de rechargement depuis). */
async function memeDocument(page, jeton) {
  try {
    return (await page.evaluate(() => window.__exportHomebank)) === jeton
  } catch {
    return false // contexte detruit : la page vient d'etre rechargee
  }
}

/** Capture PDF avec une seule classe d'export active, garantie faite sur le tableau de bord complet. */
async function capturer(page, classe, options) {
  for (let essai = 1; essai <= ESSAIS_MAX; essai++) {
    try {
      const jeton = await attendreTableauDeBord(page)
      await page.evaluate(([active, toutes]) => {
        document.documentElement.classList.remove(...toutes)
        document.documentElement.classList.add(active)
      }, [classe, CLASSES_EXPORT])
      const pdf = await page.pdf(options)
      if (await memeDocument(page, jeton)) return pdf
    } catch (erreur) {
      if (essai === ESSAIS_MAX) throw erreur
    }
    console.error(`Page rechargee pendant l'export (essai ${essai}/${ESSAIS_MAX}) : nouvelle capture.`)
  }
  throw new Error(`Page rechargee pendant chacun des ${ESSAIS_MAX} essais d'export : PDF non ecrit.`)
}

const browser = await chromium.launch()
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1024 }, reducedMotion: 'reduce' })
  await page.goto(url, { waitUntil: 'networkidle' })

  const marge = { top: '10mm', bottom: '14mm', left: '8mm', right: '8mm' }
  const pdfPortrait = await capturer(page, 'pdf-cacher-tableaux-larges', { format: 'A4', landscape: false, printBackground: true, margin: marge })
  const pdfPaysage = await capturer(page, 'pdf-ne-montrer-que-tableaux-larges', { format: 'A4', landscape: true, printBackground: true, margin: marge })

  const documentFinal = await PDFDocument.create()
  for (const octets of [pdfPortrait, pdfPaysage]) {
    const source = await PDFDocument.load(octets)
    const pages = await documentFinal.copyPages(source, source.getPageIndices())
    pages.forEach((p) => documentFinal.addPage(p))
  }

  const police = await documentFinal.embedFont(StandardFonts.Helvetica)
  const dateGeneration = new Date().toLocaleString('fr-FR', { dateStyle: 'long', timeStyle: 'short' })
  // LocalDateTime cote backend (pas de fuseau) : interprete en heure locale,
  // coherent avec dateGeneration ci-dessus (meme machine).
  const dateDernierImport = dateDernierImportIso
    ? new Date(dateDernierImportIso).toLocaleString('fr-FR', { dateStyle: 'long', timeStyle: 'short' })
    : null
  const nombreTotalPages = documentFinal.getPageCount()
  documentFinal.getPages().forEach((p, i) => {
    const { width, height } = p.getSize()
    const taille = 8

    if (i === 0) {
      // Date du dernier import de fichier en haut de la premiere page, pour
      // la voir sans avoir a faire defiler (le pied de page ci-dessous,
      // present sur chaque page, garde lui la date de generation du PDF —
      // utile pour verifier une page isolee/imprimee).
      const texteEntete = dateDernierImport
        ? `Dernier import de fichier : ${dateDernierImport}`
        : 'Dernier import de fichier : aucun'
      p.drawText(texteEntete, {
        x: 24,
        y: height - 16,
        size: taille,
        font: police,
        color: rgb(0.55, 0.55, 0.55)
      })
    }

    const textePied = `Genere le ${dateGeneration} — page ${i + 1}/${nombreTotalPages}`
    p.drawText(textePied, {
      x: width - police.widthOfTextAtSize(textePied, taille) - 24,
      y: 18,
      size: taille,
      font: police,
      color: rgb(0.55, 0.55, 0.55)
    })
  })

  documentFinal.setTitle('Tableau de bord Homebank')
  documentFinal.setProducer('Homebank export-dashboard.mjs')
  documentFinal.setCreationDate(new Date())

  await writeFile(cheminSortie, await documentFinal.save())
  console.log(cheminSortie)
} finally {
  await browser.close()
}
