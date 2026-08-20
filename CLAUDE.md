# Homebank — instructions pour Claude Code

## Contexte du projet
Application de gestion financière personnelle : backend Spring Boot (Java), frontend Vue 3
(Composition API, `<script setup>`), PostgreSQL.

## Avant toute modification frontend
- Vérifier s'il existe déjà un composant réutilisable dans `frontend/src/components/`
  avant d'en créer un nouveau (ex: `CategoryAutocomplete.vue`, `PayeeAutocomplete.vue`).
- Éviter les `<input list="...">` (datalist HTML natif) sur des champs pouvant contenir
  beaucoup d'options (tiers, catégories) : cause des ralentissements clavier avérés sur
  ce projet avec un historique bancaire réel. Préférer un autocomplete JS filtré,
  résultats plafonnés (~30) et calculs mémoïsés (pas de tri/normalisation à chaque frappe).

## Avant de dire qu'une tâche est terminée
- Lancer `npm run build` dans `frontend/` et `mvn compile` (ou `mvn test`) dans `backend/`.
- Résumer les fichiers modifiés.

## Git
- Ne jamais faire de `git commit`, `git push`, ou toute autre action git qui modifie
  l'historique ou le dépôt distant, même si demandé implicitement ou si tout semble
  fonctionner. Je fais moi-même les commits après avoir relu les changements.

## Style
- Commentaires de code en français, comme le reste du projet.
- Ne jamais casser la création de tiers/catégories à la volée (texte libre autorisé
  pour le tiers, résolu côté backend).