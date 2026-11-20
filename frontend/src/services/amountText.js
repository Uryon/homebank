/**
 * Lecture d'un montant écrit par l'utilisateur ou collé depuis un relevé, avec
 * les mêmes règles que l'import (voir ServiceImport#parseAmount) : espaces,
 * symbole monétaire, « 1 234,56 », « 1.234,56 », « 1,234.56 ». « 1,234 » et
 * « 1.234 » sont des milliers : un montant porte deux décimales.
 */
const DECORATION = /[\s\u00a0\u2007\u2009\u202f'\u2019€$£]|eur|usd|gbp|chf/gi
const THOUSANDS_ONLY = /^[+-]?[1-9]\d{0,2}([.,])\d{3}(?:\1\d{3})*$/
const PLAIN_NUMBER = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/
const EXPRESSION = /^[+-]?[\d.,]+(?:[+-][\d.,]+)*$/

function withoutDecoration(text) {
  return String(text ?? '').replace(DECORATION, '').replace(/[\u2212\u2013]/g, '-')
}

function toPlainNumber(term) {
  let plain
  if (term.includes(',') && term.includes('.')) {
    plain = term.lastIndexOf(',') > term.lastIndexOf('.')
      ? term.replaceAll('.', '').replace(',', '.')
      : term.replaceAll(',', '')
  } else if (THOUSANDS_ONLY.test(term)) {
    plain = term.replace(/[.,]/g, '')
  } else {
    plain = term.replace(',', '.')
  }
  return PLAIN_NUMBER.test(plain) ? plain : null
}

/** Un seul montant, sous forme de nombre en texte (« -1234.50 ») ; null s'il est illisible. */
export function parseAmountText(text) {
  return toPlainNumber(withoutDecoration(text))
}

/**
 * Montant ou calcul +/- (« 21000-500+300 »), arrondi au centime :
 * { value, valid }. Champ vide : value null, valide. Texte illisible : invalide.
 */
export function parseAmountExpression(text) {
  if (!String(text ?? '').trim()) return { value: null, valid: true }
  const cleaned = withoutDecoration(text)
  if (!EXPRESSION.test(cleaned)) return { value: null, valid: false }
  const terms = cleaned.match(/[+-]?[\d.,]+/g).map(toPlainNumber)
  if (terms.includes(null)) return { value: null, valid: false }
  const total = terms.reduce((sum, term) => sum + Number(term), 0)
  return { value: Math.round(total * 100) / 100, valid: true }
}
