HomeBank — Itération 10 : fiabilisation des actions

Contenu du patch (frontend uniquement)
- Comptes : la suppression depuis le tableau affiche désormais une erreur explicite si l'API ou le rafraîchissement échoue.
- Catégories : création, masquage/réaffichage et activation/désactivation du lissage annuel utilisent la gestion d'erreur centralisée ; les changements réussis affichent une confirmation.
- Prêts : création/modification, suppression, chargement initial et chargement de l'échéancier affichent des erreurs explicites au lieu de laisser échouer silencieusement les promesses.

Aucune règle métier ni endpoint backend n'a été modifié.

Installation
Extraire le contenu de l'archive à la racine du projet HomeBank en remplaçant les fichiers existants.

Validation
Lancer dans frontend : npm ci puis npm run build. Tester ensuite la suppression d'un compte, les opérations de catégorie et l'ajout/modification/suppression d'un prêt.
