HomeBank — Iteration 9 (UX reliability & accessibility)

Files are relative to the frontend project root. Apply by extracting this ZIP at the project root (the folder containing frontend/), allowing overwrite.

Changes:
- Duplicate review: explicit loading and error states; retry action; no false “no duplicates” message while loading/failing.
- Duplicate actions: visible success/error feedback; refresh after partial deletion failures; refresh transactions even when optional payee-pattern learning fails after a deletion.
- Replaced native window.prompt with a HomeBank text dialog for payee pattern updates.
- Feedback dialogs: initial focus, keyboard Tab focus loop, Escape/backdrop cancellation, and focus restoration.
- Import: explicit no-account state, selected filename, and clear selected file/result when changing CSV/QIF format.
- Added a keyboard skip link to the main content landmark.

No backend code or business rules (duplicate matching, transaction classification, account balances) were changed.

Validation:
- Source changes inspected.
- `npm run build` could not be executed because frontend dependencies are not installed in this environment (`vite: not found`). Run `npm ci` then `npm run build` locally before using in production.
