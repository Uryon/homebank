# Homebank — instructions pour Claude Code

## Stack
- Backend : Java / Spring Boot, PostgreSQL, Hibernate/JPA
- Frontend : Vue 3, Composition API (`<script setup>`), Vite, axios

## Avant de coder
- Chercher un composant/service existant avant d'en créer un nouveau
  (`frontend/src/components/`, `frontend/src/services/`) — ne pas dupliquer
  une logique déjà écrite ailleurs.
- Pour toute modification non triviale, lire le fichier concerné en entier
  d'abord (pas seulement la fonction à changer) pour comprendre le contexte
  et éviter de casser un comportement voisin.

## Frontend Vue — qualité et perf
- Composition API partout, pas d'Options API.
- `computed` pour toute valeur dérivée d'un état réactif ; jamais de calcul
  lourd (tri, filtrage, formatage) refait à chaque frappe/render sans
  mémoïsation — c'est la cause du ralentissement clavier déjà rencontré sur
  ce projet (voir historique : datalist natif + tri non mémoïsé).
- Éviter les `<input list="...">` (datalist HTML natif) sur des champs à
  choix multiples (tiers, catégories) : lent au-delà de quelques centaines
  d'options. Préférer un autocomplete JS filtré, résultats plafonnés
  (~30 items affichés), calculs coûteux (tri, normalisation, recherche par
  nom) mis en `computed` séparé et non recalculés à chaque frappe.
- Pas de scan linéaire (`.find()`, `.filter()`) répété sur de grandes listes
  dans un code déclenché à chaque frappe/render ; indexer avec une `Map` en
  `computed` si la liste peut être grande.
- Props/emits typés explicitement (`defineProps`, `defineEmits`).
- Pas de logique métier dans les templates ; extraire en fonctions/computed
  nommées clairement.
- CSS scoped par composant (`<style scoped>`), pas de styles globaux sauf
  dans `style.css`.

## Backend Spring Boot — qualité et perf
- Respecter les couches existantes : Controller → Service → Repository →
  Mapper. Pas de logique métier dans les controllers, pas d'accès direct au
  repository depuis un controller.
- Attention aux requêtes N+1 (JPA) : vérifier les associations chargées en
  boucle, utiliser des fetch joins ou des DTO projections si un endpoint
  liste beaucoup d'entités liées.
- DTOs pour les échanges API (pas d'exposition directe des entités JPA).
- Validation des entrées côté service (pas seulement côté frontend).
- Gestion des erreurs via `GlobalExceptionHandler`, pas de try/catch
  silencieux qui masque une erreur.
- Transactions (`@Transactional`) sur les opérations qui modifient
  plusieurs entités liées.

## Avant de dire qu'une tâche est terminée
- Frontend : `npm run build` dans `frontend/` doit passer sans erreur.
- Backend : `mvn compile` (ou `mvn test` si des tests existent pour la zone
  touchée) dans `backend/` doit passer sans erreur.
- Si une run configuration IntelliJ de test global existe ("Compound All
  TEST"), la lancer et rapporter le résultat plutôt que de simplement dire
  "ça devrait marcher".
- Résumer les fichiers modifiés et pourquoi.

## Git
- Ne jamais faire `git commit`, `git push`, ou toute action git qui modifie
  l'historique local ou distant, même si tout semble fonctionner et même si
  cela paraît implicitement demandé. Je fais moi-même les commits après
  relecture.

## Style
- Commentaires de code en français, comme le reste du projet.
- Nommage explicite plutôt que commentaires explicatifs quand possible.
- Ne jamais casser la création de tiers/catégories à la volée (texte libre
  autorisé pour le tiers, résolu côté backend).

## Qualité
Corrige les problèmes Sonar etc si possible
Utilise des loggers plutôt que des `System.out.println()`
Log au niveau INFO pour les actions utilisateur, WARN pour les erreurs non bloquantes, ERROR pour les exceptions bloquantes
Supprime le code mort