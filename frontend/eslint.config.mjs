import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'

/**
 * Analyse statique du frontend (npm run lint) : règles recommandées JS et
 * Vue 3, pour attraper avant Sonar le code mort (variables/imports inutilisés)
 * et les erreurs de template. Les règles de pure mise en forme sont
 * désactivées : le style existant du projet fait foi.
 */
export default [
  { ignores: ['dist/**', 'coverage/**'] },
  js.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.{js,mjs,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.browser }
    },
    rules: {
      'no-unused-vars': ['error', { argsIgnorePattern: '^_', caughtErrors: 'none' }],
      // Mise en forme : non imposée (le code existant a son propre style).
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/multiline-html-element-content-newline': 'off',
      'vue/html-self-closing': 'off',
      'vue/html-indent': 'off',
      'vue/html-closing-bracket-newline': 'off',
      'vue/first-attribute-linebreak': 'off',
      'vue/attributes-order': 'off',
      // Guillemets simples tolérés quand la valeur contient des guillemets doubles (placeholders d'exemple de recherche).
      'vue/html-quotes': ['warn', 'double', { avoidEscape: true }],
      // Noms de pages et de composants existants (Dashboard, Icon, Money...) : aucun conflit avec une balise HTML.
      'vue/multi-word-component-names': 'off'
    }
  },
  {
    // Tracés SVG statiques définis dans le composant, jamais de données utilisateur.
    files: ['src/components/Icon.vue'],
    rules: { 'vue/no-v-html': 'off' }
  },
  {
    files: ['**/*.test.js', 'vite.config.mjs'],
    languageOptions: { globals: { ...globals.node } }
  }
]
