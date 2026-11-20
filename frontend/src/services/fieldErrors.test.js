import { describe, expect, it, vi } from 'vitest'
import { useFieldErrors } from './fieldErrors'

function setup() {
  const focus = vi.fn()
  const api = useFieldErrors({ name: 'f-name', pattern: 'f-pattern' }, { focus })
  return { focus, ...api }
}

describe('useFieldErrors', () => {
  it('valide sans erreur ni focus quand tous les messages sont vides', () => {
    const { check, errors, focus } = setup()
    expect(check({ name: '', pattern: '' })).toBe(true)
    expect(errors).toEqual({ name: '', pattern: '' })
    expect(focus).not.toHaveBeenCalled()
  })

  it('place le curseur sur le premier champ en erreur, dans l’ordre du formulaire', () => {
    const { check, errors, focus } = setup()
    expect(check({ pattern: 'Motif requis', name: 'Nom requis' })).toBe(false)
    expect(errors.name).toBe('Nom requis')
    expect(focus).toHaveBeenCalledWith('f-name')
  })

  it('remplace les erreurs précédentes à chaque vérification', () => {
    const { check, errors } = setup()
    check({ name: 'Nom requis' })
    check({ pattern: 'Motif requis' })
    expect(errors).toEqual({ name: '', pattern: 'Motif requis' })
  })

  it('efface un champ ou tous', () => {
    const { check, clear, errors } = setup()
    check({ name: 'a', pattern: 'b' })
    clear('name')
    expect(errors).toEqual({ name: '', pattern: 'b' })
    clear()
    expect(errors).toEqual({ name: '', pattern: '' })
  })

  it('relie un champ invalide à son message', () => {
    const { check, invalidAttrs } = setup()
    expect(invalidAttrs('name')).toEqual({})
    check({ name: 'Nom requis' })
    expect(invalidAttrs('name')).toEqual({ 'aria-invalid': 'true', 'aria-describedby': 'f-name-error' })
  })
})
