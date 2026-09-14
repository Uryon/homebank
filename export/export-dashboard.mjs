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
import { PDFDocument, StandardFonts, rgb } from 'pdf-lib'
import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'

const [, , dossierSortie, urlTableauDeBord, dateDernierImportIso] = process.argv

if (!dossierSortie) {
  console.error('Usage: node export-dashboard.mjs <dossier-sortie> [url-tableau-de-bord] [date-dernier-import-iso]')
  process.exit(1)
}

const url = urlTableauDeBord || 'http://localhost:5173/'
// Nom fixe (pas d'horodatage) : chaque export remplace le precedent, plutot
// que d'accumuler un fichier par export dans le dossier synchronise.
const cheminSortie = path.join(dossierSortie, 'tableau-de-bord.pdf')

await mkdir(dossierSortie, { recursive: true })

const browser = await chromium.launch()
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1024 } })
  await page.goto(url, { waitUntil: 'networkidle' })
  // Laisse les graphiques Chart.js finir leur animation d'entree avant capture.
  await page.waitForTimeout(1000)

  const marge = { top: '10mm', bottom: '14mm', left: '8mm', right: '8mm' }

  await page.evaluate(() => {
    document.documentElement.classList.add('pdf-cacher-tableaux-larges')
  })
  const pdfPortrait = await page.pdf({ format: 'A4', landscape: false, printBackground: true, margin: marge })

  await page.evaluate(() => {
    document.documentElement.classList.remove('pdf-cacher-tableaux-larges')
    document.documentElement.classList.add('pdf-ne-montrer-que-tableaux-larges')
  })
  const pdfPaysage = await page.pdf({ format: 'A4', landscape: true, printBackground: true, margin: marge })

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
