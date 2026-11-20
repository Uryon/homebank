// Serveur de la version compilée (lancé par HomeBank.vbs) : configuration à part,
// sans le plugin Vue inutile ici, pour que le serveur réponde deux fois plus vite.

export const apiProxy = {
  '/api': {
    // 127.0.0.1 plutôt que localhost : pas d'essai IPv6 (::1) avant
    // chaque connexion, notamment pendant le démarrage du serveur.
    target: 'http://127.0.0.1:8080',
    changeOrigin: true
  }
}

// Même adresse que le serveur de dev, pour la fenêtre et l'export PDF.
export const previewServer = {
  host: '127.0.0.1',
  port: 5173,
  strictPort: true,
  proxy: apiProxy
}

export default {
  preview: previewServer
}
