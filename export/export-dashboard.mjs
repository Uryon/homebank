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
//
// Un second fichier, tableau-de-bord-telephone.pdf, est fait pour l'ecran
// d'un telephone : une seule longue page a la largeur d'un telephone (on la
// fait defiler sans zoomer, sans coupure de page), dans la mise en page
// mobile de l'appli, avec le contenu de la partie portrait (les grands
// tableaux de 12 mois restent dans le PDF A4).

import { chromium } from 'playwright'
// Version ESM de pdf-lib en un seul fichier : « pdf-lib » seul est resolu vers
// sa version CommonJS (139 fichiers), chargee en ~0,75 s au lieu de ~0,1 s
// (mesure ; PDF produit identique octet pour octet, verifie).
import { PDFDocument, StandardFonts, rgb } from 'pdf-lib/dist/pdf-lib.esm.min.js'
import { randomUUID } from 'node:crypto'
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
const cheminTelephone = path.join(dossierSortie, 'tableau-de-bord-telephone.pdf')

/** Largeur de la page du PDF telephone (px CSS) : celle d'un telephone courant. */
const LARGEUR_TELEPHONE = 412
const MARGE_TELEPHONE = { haut: 30, bas: 30, cote: 12 }

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
  const jeton = randomUUID()
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
    } catch (error_) {
      if (essai === ESSAIS_MAX) throw error_
    }
    console.error(`Page rechargee pendant l'export (essai ${essai}/${ESSAIS_MAX}) : nouvelle capture.`)
  }
  throw new Error(`Page rechargee pendant chacun des ${ESSAIS_MAX} essais d'export : PDF non ecrit.`)
}

/**
 * PDF telephone : la page est mise a la largeur imprimable du telephone (mise
 * en page mobile, graphiques redessines a cette largeur), sa hauteur mesuree
 * en mode impression, puis imprimee sur une page unique de cette hauteur.
 */
async function capturerTelephone(page) {
  const largeurUtile = LARGEUR_TELEPHONE - 2 * MARGE_TELEPHONE.cote
  for (let essai = 1; essai <= ESSAIS_MAX; essai++) {
    try {
      const jeton = await attendreTableauDeBord(page)
      await page.evaluate(([toutes]) => {
        document.documentElement.classList.remove(...toutes)
        document.documentElement.classList.add('pdf-cacher-tableaux-larges')
      }, [CLASSES_EXPORT])
      await page.setViewportSize({ width: largeurUtile, height: 900 })
      await page.emulateMedia({ media: 'print' })
      // Graphiques redimensionnes a la nouvelle largeur (ResizeObserver de Chart.js).
      await page.evaluate(() => new Promise((fin) => requestAnimationFrame(() => requestAnimationFrame(() => setTimeout(fin, 150)))))
      const hauteur = await page.evaluate(() => Math.ceil(document.documentElement.scrollHeight))
      const pdf = await page.pdf({
        width: `${LARGEUR_TELEPHONE}px`,
        // +2 px : un arrondi de mise en page ne doit jamais creer une seconde page presque vide.
        height: `${hauteur + MARGE_TELEPHONE.haut + MARGE_TELEPHONE.bas + 2}px`,
        printBackground: true,
        margin: {
          top: `${MARGE_TELEPHONE.haut}px`,
          bottom: `${MARGE_TELEPHONE.bas}px`,
          left: `${MARGE_TELEPHONE.cote}px`,
          right: `${MARGE_TELEPHONE.cote}px`
        }
      })
      if (await memeDocument(page, jeton)) return pdf
    } catch (error_) {
      if (essai === ESSAIS_MAX) throw error_
    }
    console.error(`Page rechargee pendant l'export telephone (essai ${essai}/${ESSAIS_MAX}) : nouvelle capture.`)
  }
  throw new Error(`Page rechargee pendant chacun des ${ESSAIS_MAX} essais d'export telephone : PDF non ecrit.`)
}

/** En-tete (date du dernier import, premiere page) et pied (date de generation, numero de page) de chaque page. */
async function annoter(documentPdf, dateDernierImport, dateGeneration) {
  const police = await documentPdf.embedFont(StandardFonts.Helvetica)
  const nombreTotalPages = documentPdf.getPageCount()
  documentPdf.getPages().forEach((p, i) => {
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
      p.drawText(texteEntete, { x: 24, y: height - 16, size: taille, font: police, color: rgb(0.55, 0.55, 0.55) })
    }

    // Page unique (PDF telephone) : pas de numero de page.
    const textePied = nombreTotalPages > 1
      ? `Genere le ${dateGeneration} — page ${i + 1}/${nombreTotalPages}`
      : `Genere le ${dateGeneration}`
    p.drawText(textePied, {
      x: Math.max(24, width - police.widthOfTextAtSize(textePied, taille) - 24),
      y: 18,
      size: taille,
      font: police,
      color: rgb(0.55, 0.55, 0.55)
    })
  })
  documentPdf.setTitle('Tableau de bord Homebank')
  documentPdf.setProducer('Homebank export-dashboard.mjs')
  documentPdf.setCreationDate(new Date())
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

  const dateGeneration = new Date().toLocaleString('fr-FR', { dateStyle: 'long', timeStyle: 'short' })
  // LocalDateTime cote backend (pas de fuseau) : interprete en heure locale,
  // coherent avec dateGeneration ci-dessus (meme machine).
  const dateDernierImport = dateDernierImportIso
    ? new Date(dateDernierImportIso).toLocaleString('fr-FR', { dateStyle: 'long', timeStyle: 'short' })
    : null
  await annoter(documentFinal, dateDernierImport, dateGeneration)

  await writeFile(cheminSortie, await documentFinal.save())

  // Apres le PDF A4 : la largeur de la page change pour le telephone.
  const documentTelephone = await PDFDocument.load(await capturerTelephone(page))
  await annoter(documentTelephone, dateDernierImport, dateGeneration)
  await writeFile(cheminTelephone, await documentTelephone.save())
  console.log(`${cheminSortie} ; ${cheminTelephone}`)
} finally {
  await browser.close()
}
