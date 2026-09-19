Option Explicit

Dim shell
Dim fso
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

Dim backendPid
Dim frontendPid
Dim edgeProfile
Dim jarPath
Dim rootDir
Dim logsDir

rootDir = "G:\git\homebank"
edgeProfile = rootDir & "\.homebank-edge"
jarPath = rootDir & "\backend\target\homebank-1.0.0.jar"
logsDir = rootDir & "\logs"

If Not fso.FolderExists(logsDir) Then
    fso.CreateFolder(logsDir)
End If


' =========================================================
' POSTGRESQL
' =========================================================

StartHiddenProcess "", "net start postgresql-x64-18", logsDir & "\postgres.log"


' =========================================================
' BACKEND SPRING BOOT
' =========================================================

' Repertoire de travail = backend/, comme mvn/IntelliJ (voir les chemins
' par defaut de export.dossier-sortie/export.chemin-script dans
' application.properties, relatifs a backend/) : sans ce cdDir, le process
' heritait du dossier du script (racine du projet), et "../export/..."
' pointait alors un niveau trop haut -- l'export PDF echouait uniquement
' quand l'appli etait lancee via ce raccourci.
backendPid = StartHiddenProcess( _
    rootDir & "\backend", "java -jar """ & jarPath & """", logsDir & "\backend.log" _
)

' =========================================================
' FRONTEND VUE / VITE
'
' Demarre en parallele du backend (Vite n'a pas besoin du
' backend pour ouvrir son port) au lieu d'attendre que Spring
' Boot soit pret avant de le lancer.
' =========================================================

frontendPid = StartHiddenProcess( _
    rootDir & "\frontend", "npm run dev", logsDir & "\frontend.log" _
)

' Attendre que les deux soient disponibles
WaitForPort 8080
WaitForPort 5173


' =========================================================
' LANCER HOME BANK
' =========================================================

shell.Run _
    "msedge.exe --app=""http://127.0.0.1:5173"" --user-data-dir=""" & edgeProfile & """ --no-first-run --start-maximized", _
    3, _
    False

' Laisser le temps a Edge d'apparaitre avant de commencer a surveiller sa
' fermeture : juste apres shell.Run, le process n'est pas forcement encore
' visible via WMI (demarrage a froid d'Edge en concurrence avec postgres/
' java/node qui viennent de demarrer). Sans cette attente, la toute premiere
' verification peut ne rien trouver et EdgeHomeBankExists conclut a tort que
' Home Bank est deja fermee -- ce qui tue le backend/frontend une poignee de
' secondes apres le lancement (symptome : "network error" presque immediat).
WaitForEdgeToAppear edgeProfile


' =========================================================
' ATTENDRE LA FERMETURE DE HOME BANK
' =========================================================

Do While EdgeHomeBankExists(edgeProfile)
    WScript.Sleep 1000
Loop


' =========================================================
' FIN — arrêt du backend et du frontend (postgres reste actif,
' c'est un service Windows partagé, pas un process de l'appli)
' =========================================================

StopProcessTree backendPid
StopProcessTree frontendPid

Set fso = Nothing
Set shell = Nothing


' =========================================================
' LANCER UN PROCESSUS CACHE, LOGS REDIRIGES VERS UN FICHIER
'
' WScript.Shell.Exec ouvre toujours une console visible pour un programme
' console (cmd.exe) : impossible de la masquer. On passe donc par WMI
' (Win32_Process.Create) avec ShowWindow = 0 (SW_HIDE) : aucune fenetre, le
' repertoire de travail est un parametre natif, et le PID est renvoye
' directement. La sortie et les erreurs du programme sont redirigees par
' cmd.exe lui-meme (">" ... "2>&1") vers logFile.
' =========================================================

Function StartHiddenProcess(cdDir, command, logFile)

    Dim wmi
    Dim startup
    Dim config
    Dim pid
    Dim resultat

    Set wmi = GetObject("winmgmts:root\cimv2")
    Set startup = wmi.Get("Win32_ProcessStartup")
    Set config = startup.SpawnInstance_
    config.ShowWindow = 0

    If cdDir = "" Then
        cdDir = Null
    End If

    resultat = wmi.Get("Win32_Process").Create( _
        "cmd.exe /c " & command & " > """ & logFile & """ 2>&1", cdDir, config, pid)

    If resultat <> 0 Then
        StartHiddenProcess = ""
    Else
        StartHiddenProcess = pid
    End If

End Function


' =========================================================
' ARRETER UN PROCESSUS ET SES ENFANTS
' =========================================================

Sub StopProcessTree(pid)

    If Trim(pid) <> "" Then
        shell.Run "taskkill /F /T /PID " & pid, 0, True
    End If

End Sub


' =========================================================
' ATTENDRE QU'UN PORT SOIT DISPONIBLE
'
' Un seul process PowerShell qui boucle en interne sur une
' simple connexion TCP, plutot qu'un nouveau powershell.exe
' (+ Test-NetConnection, lourd : ping, chargement de module...)
' relance toutes les 500ms. Beaucoup plus rapide a l'usage.
'
' Se connecte explicitement en IPv4 (127.0.0.1) : Vite est
' configure pour n'ecouter que sur cette adresse (vite.config.js),
' et Edge est lance sur http://127.0.0.1:5173 -- on verifie donc
' la meme adresse que celle utilisee au final, sans dependre de
' la resolution de "localhost" qui peut varier (IPv4 vs IPv6).
' =========================================================

Sub WaitForPort(port)

    shell.Run _
        "powershell.exe -NoProfile -Command " & _
        Chr(34) & _
        "while ($true) { " & _
        "$c=New-Object Net.Sockets.TcpClient; " & _
        "try { $c.Connect('127.0.0.1', " & port & "); break } " & _
        "catch { Start-Sleep -Milliseconds 200 } " & _
        "finally { $c.Dispose() } }" & _
        Chr(34), _
        0, _
        True

End Sub


' =========================================================
' ATTENDRE L'APPARITION D'EDGE APRES SON LANCEMENT
'
' Budget bien plus large que celui d'EdgeHomeBankExists (qui, lui, sert a
' detecter une fermeture en cours de session et doit rester reactif) : ici
' on tolere un demarrage a froid d'Edge (jusqu'a ~15s), car le manquer une
' fois ne fait que retarder la surveillance, alors que le manquer a tort
' tue prematurement le backend/frontend encore en train de servir la page.
' =========================================================

Sub WaitForEdgeToAppear(profile)

    Dim tries
    tries = 0

    Do While Not EdgeHomeBankExists(profile) And tries < 30
        WScript.Sleep 500
        tries = tries + 1
    Loop

End Sub


' =========================================================
' VERIFIER SI HOME BANK EST ENCORE OUVERTE
'
' Reessaie plusieurs fois avant de conclure a la fermeture :
' une requete WMI peut echouer ponctuellement (service WMI
' sollicite) et donner un faux negatif. Si on le croit sur
' parole immediatement, le script tue le backend/frontend
' alors qu'Edge est toujours ouvert -- l'appli reste alors
' plantee/blanche a l'ecran.
' =========================================================

Function EdgeHomeBankExists(profile)

    Dim command

    command = "powershell.exe -NoProfile -Command " & _
              Chr(34) & _
              "$found=$false; for ($i=0; $i -lt 3; $i++) { " & _
              "try { $p=Get-CimInstance Win32_Process -Filter ""Name='msedge.exe'"" -ErrorAction Stop | " & _
              "Where-Object {$_.CommandLine -like '*" & profile & "*'}; if ($p) { $found=$true; break } } " & _
              "catch {}; Start-Sleep -Milliseconds 300 }; " & _
              "if ($found) {exit 0} else {exit 1}" & _
              Chr(34)

    ' shell.Run avec le style de fenetre 0 (cache), comme WaitForPort plus
    ' haut -- shell.Exec (utilise auparavant ici) ne permet pas de masquer
    ' la fenetre et faisait flasher une console PowerShell a chaque appel ;
    ' avec WaitForEdgeToAppear qui interroge cette fonction en rafale au
    ' demarrage, ca se traduisait par des dizaines de fenetres visibles.
    EdgeHomeBankExists = (shell.Run(command, 0, True) = 0)

End Function
