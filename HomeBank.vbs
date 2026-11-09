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
' LIBERER LE PORT 5173 (ANCIEN VITE RESTE ACTIF)
'
' Si un ancien serveur Vite tourne encore (arret precedent rate,
' lancement depuis un terminal...), Vite demarre sur 5174 alors
' qu'Edge ouvre 5173 : on affichait alors un vieux serveur. On
' libere donc le port avant de lancer le nouveau.
' =========================================================

FreePort 5173


' =========================================================
' BACKEND SPRING BOOT
'
' Repertoire de travail = backend/, comme mvn/IntelliJ (chemins
' relatifs de export.dossier-sortie / export.chemin-script).
' =========================================================

backendPid = StartHiddenProcess( _
    rootDir & "\backend", "java -jar """ & jarPath & """", logsDir & "\backend.log" _
)


' =========================================================
' FRONTEND VUE / VITE
'
' Demarre en parallele du backend (Vite n'a pas besoin du
' backend pour ouvrir son port).
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
' fermeture (demarrage a froid possible, jusqu'a ~15 s).
WaitForEdgeToAppear edgeProfile


' =========================================================
' ATTENDRE LA FERMETURE DE HOME BANK
' =========================================================

Do While EdgeHomeBankExists(edgeProfile)
    WScript.Sleep 2000
Loop


' =========================================================
' EXPORT PDF AUTOMATIQUE A LA FERMETURE
'
' Doit avoir lieu avant l'arret du backend/frontend : le backend lance le
' script Playwright, qui capture le tableau de bord servi par Vite. Bloquant ;
' un echec est ecrit dans logs\export-fermeture.log mais n'empeche pas
' l'arret.
' =========================================================

ExportPdfALaFermeture

StopProcessTree backendPid
StopProcessTree frontendPid

Set fso = Nothing
Set shell = Nothing


' =========================================================
' LANCER UN PROCESSUS CACHE, LOGS REDIRIGES VERS UN FICHIER
'
' WMI (Win32_Process.Create) avec ShowWindow = 0 : aucune fenetre, le
' repertoire de travail est un parametre natif, et le PID est renvoye
' directement. La sortie est redirigee par cmd.exe vers logFile.
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
' DECLENCHER L'EXPORT PDF DU TABLEAU DE BORD
'
' Appelle le meme endpoint que le bouton "Exporter en PDF" (voir
' ControleurExport).
' =========================================================

Sub ExportPdfALaFermeture()

    Dim command

    command = "powershell.exe -NoProfile -Command " & _
              Chr(34) & _
              "try { Invoke-RestMethod -Method Post -TimeoutSec 45 " & _
              "-Uri 'http://127.0.0.1:8080/api/export/dashboard-pdf' | Out-Null } " & _
              "catch { $_.Exception.Message | Out-File -FilePath '" & logsDir & "\export-fermeture.log' } " & _
              Chr(34)

    shell.Run command, 0, True

End Sub


' =========================================================
' ARRETER UN PROCESSUS ET SES ENFANTS
' =========================================================

Sub StopProcessTree(pid)

    If Trim(pid) <> "" Then
        shell.Run "taskkill /F /T /PID " & pid, 0, True
    End If

End Sub


' =========================================================
' LIBERER UN PORT TCP
'
' Arrete le(s) processus qui ecoutent sur le port donne. Aucun
' guillemet double a l'interieur du script PowerShell (pas de
' probleme d'echappement).
' =========================================================

Sub FreePort(port)

    shell.Run _
        "powershell.exe -NoProfile -Command " & _
        Chr(34) & _
        "Get-NetTCPConnection -LocalPort " & port & " -State Listen -ErrorAction SilentlyContinue | " & _
        "ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }" & _
        Chr(34), _
        0, _
        True

    WScript.Sleep 500

End Sub


' =========================================================
' ATTENDRE QU'UN PORT SOIT DISPONIBLE
'
' Se connecte explicitement en IPv4 (127.0.0.1) : Vite n'ecoute que sur
' cette adresse (vite.config.js), et Edge est lance sur 127.0.0.1.
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
' Tolere un demarrage a froid d'Edge (jusqu'a ~15 s). Rater Edge ici
' a tort tuerait prematurement le backend/frontend.
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
' Requete WMI directement en VBScript (plus de PowerShell imbrique, dont
' les guillemets doubles cassaient le filtre et faisaient toujours
' renvoyer "Edge introuvable" -> backend/frontend tues ~1 min apres le
' lancement alors qu'Edge restait ouvert).
'
' Reessaie 3 fois avant de conclure a la fermeture : une requete WMI peut
' echouer ponctuellement et donner un faux negatif.
' =========================================================

Function EdgeHomeBankExists(profile)

    Dim wmi
    Dim procs
    Dim p
    Dim i

    EdgeHomeBankExists = False

    For i = 1 To 3

        On Error Resume Next
        Set wmi = GetObject("winmgmts:\\.\root\cimv2")
        Set procs = wmi.ExecQuery("SELECT CommandLine FROM Win32_Process WHERE Name = 'msedge.exe'")

        If Err.Number = 0 Then
            For Each p In procs
                If Not IsNull(p.CommandLine) Then
                    If InStr(1, p.CommandLine, profile, vbTextCompare) > 0 Then
                        EdgeHomeBankExists = True
                        Exit For
                    End If
                End If
            Next
        End If

        Err.Clear
        On Error GoTo 0

        If EdgeHomeBankExists Then Exit Function

        WScript.Sleep 300

    Next

End Function