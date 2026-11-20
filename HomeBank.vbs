Option Explicit

' =========================================================
' LANCEUR HOMEBANK (raccourci HomeBank.lnk)
'
' 1. Une seule instance : si HomeBank est deja ouvert, sa fenetre est
'    ramenee au premier plan (restauree si reduite) au lieu de tout
'    relancer. Un double-clic ne lance plus deux fois les serveurs (verrou,
'    voir WaitForLauncherLock). Dans l'appli, une seule fenetre active a la
'    fois (Ctrl+N...) : voir frontend\src\services\singleWindow.js.
' 2. Restes d'un lancement precedent mal termine (PID notes dans
'    logs\lanceur.pids) arretes. Un serveur lance depuis IntelliJ n'est
'    jamais arrete : il est reutilise (sauf un serveur de TEST, signale).
' 3. Backend lance en premier : demarre tout de suite si son code est
'    compile, sinon compile (le raccourci lancait sinon une ancienne
'    version) -- sauf si IntelliJ ou mvn l'ont deja fait (classes reprises) --,
'    par javac directement ou par Maven (voir StartBackendBuild),
'    en arriere-plan, pendant la compilation du frontend et l'ouverture de
'    la fenetre. Depuis ses classes et ses bibliotheques
'    extraites (voir la partie BACKEND), en mode rapide (archive CDS, voir
'    PrepareFastStart) des qu'il est prepare, et avec des options de
'    demarrage leger (voir BACKEND_JVM_OPTIONS).
' 4. Frontend servi en version de production (Vite preview : environ
'    deux fois plus rapide a l'usage que le serveur de developpement),
'    recompile d'abord (en arriere-plan) si son code a change. La fenetre, elle, s'ouvre des
'    le clic sur une page d'attente (frontend\public\demarrage.html, lue
'    comme fichier) qui passe sur le frontend des qu'il repond, puis sur
'    l'appli des que le serveur repond.
' 5. Compilations du backend et du frontend menees en parallele : chaque
'    serveur demarre des la fin de la sienne (voir FinishBuilds).
' 6. A la fermeture de la fenetre : export PDF du tableau de bord, arret de
'    ce que le lanceur a demarre (et seulement de cela), puis preparation du
'    demarrage rapide s'il manque ou ne sert plus (fenetre deja fermee :
'    invisible).
'
' Journal horodate de chaque lancement : logs\lanceur.log.
' Fichier enregistre en Windows-1252 : wscript lit les .vbs en ANSI
' (accents des messages).
' =========================================================

Const BACKEND_PORT = 8080
Const FRONTEND_PORT = 5173
Const APP_TITLE = "HomeBank"
Const FRONTEND_TIMEOUT_S = 90
Const BUILD_TIMEOUT_S = 600
Const FAST_START_TIMEOUT_S = 180
Const LOCK_TIMEOUT_S = 120
Const FRONTEND_BUILD_TIMEOUT_S = 300
Const EXPORT_TIMEOUT_MS = 60000
Const LOG_MAX_BYTES = 200000

' Demarrage leger du serveur, propre au lanceur (IntelliJ et les tests gardent
' les reglages standard, dont la verification de toutes les requetes au
' demarrage). Compilateur rapide seul : serveur pret environ 1 s plus tot, et
' premiers chargements plus rapides ; en contrepartie, un rechargement complet
' repete prend environ 110 ms au lieu de 60, et un gros import reste environ
' 1 s plus long. Depots prepares en parallele : voir config.PreparationDepots.
Const BACKEND_JVM_OPTIONS = "-XX:TieredStopAtLevel=1"
Const BACKEND_APP_OPTIONS = "--spring.data.jpa.repositories.bootstrap-mode=lazy"

Const BACKEND_MAIN_CLASS = "com.homebank.HomebankApplication"

' Rejets de l'archive CDS notes dans logs\cds.log (ecrase a chaque demarrage) ;
' chemin relatif au repertoire backend\ : un "G:" casserait la syntaxe -Xlog.
Const CDS_RUNTIME_LOG = "-Xlog:cds=off -Xlog:cds+dynamic=off -Xlog:cds*=warning:file=..\logs\cds.log::filecount=0"

' Compilation pour un lancement : tests ni compiles ni lances (maven.test.skip,
' ~2 s de moins que -DskipTests qui les compile quand meme), Maven lui-meme en
' compilateur rapide (~1 s de moins). Le marqueur n'est ecrit que si Maven
' reussit : une compilation en echec vide target\classes.
Const MAVEN_JVM_OPTIONS = "-XX:TieredStopAtLevel=1"
Const MAVEN_BUILD_OPTIONS = "-q -Dmaven.test.skip=true"
Const BUILD_OK_MARKER = "LANCEUR-COMPILATION-OK"

' Compilation directe par javac quand seul le code a change (pas le pom.xml) :
' ~3,5 s au lieu de ~6,5 s par Maven, dont le seul demarrage coute ~3 s
' (mesure). Classes identiques octet pour octet a celles de Maven (verifie) :
' -g et -parameters sont ses reglages (maven-compiler-plugin et
' spring-boot-starter-parent), --release vient du pom.xml (java.version) et le
' classpath de compilation est celui que Maven a note (compileClasspathFile).
Const JAVAC_OPTIONS = "-J-XX:TieredStopAtLevel=1 -g -parameters -encoding UTF-8 -proc:full"

' Vite lance directement, sans passer par npm, avec une configuration sans le
' plugin Vue : serveur pret en 0,5 s au lieu de 1,5 s.
Const FRONTEND_PREVIEW_COMMAND = "node node_modules\vite\bin\vite.js preview --config vite.preview.config.mjs"
' Idem pour la compilation (sans npm : ~0,5 s de moins).
Const FRONTEND_BUILD_COMMAND = "node node_modules\vite\bin\vite.js build"

Dim shell, fso
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

Dim rootDir, logsDir, edgeProfile, jarPath, pidsFile, launcherLog, lockFile
' Fichier verrou tenu ouvert pendant le lancement (voir WaitForLauncherLock).
Dim launcherLock
rootDir = fso.GetParentFolderName(WScript.ScriptFullName)
logsDir = rootDir & "\logs"
edgeProfile = rootDir & "\.homebank-edge"
jarPath = rootDir & "\backend\target\homebank-1.0.0.jar"
pidsFile = logsDir & "\lanceur.pids"
lockFile = logsDir & "\lanceur.lock"

' Fichiers du serveur lance par le raccourci (voir la partie BACKEND) :
' bibliotheques extraites du jar, copie des classes compilees et son temoin,
' archive CDS (voir PrepareFastStart).
Dim fastStartDir, fastStartJar, fastStartArchive, backendSnapshot, backendSnapshotStamp, cdsLog, backendBuildLog
fastStartDir = rootDir & "\backend\target\demarrage-rapide"
fastStartJar = fastStartDir & "\app\homebank-1.0.0.jar"
fastStartArchive = fastStartDir & "\homebank.jsa"
backendSnapshot = fastStartDir & "\classes"
backendSnapshotStamp = fastStartDir & "\classes.compilees"
cdsLog = logsDir & "\cds.log"
backendBuildLog = logsDir & "\build.log"

' Compilation directe (voir JAVAC_OPTIONS) : classpath note par Maven a chaque
' compilation par Maven, classes compilees a part puis substituees a la copie
' lue par le serveur, arguments de javac dans un fichier (classpath de ~9 000
' caracteres : au-dela de la limite de 8 191 d'une ligne de commande cmd.exe).
Dim compileClasspathFile, javacOutputDir, javacArgsFile
compileClasspathFile = rootDir & "\backend\target\classpath-compilation.txt"
javacOutputDir = fastStartDir & "\classes.compilation"
javacArgsFile = fastStartDir & "\javac.args"

' Frontend compile (FRONTEND_BUILD_COMMAND), servi par FRONTEND_PREVIEW_COMMAND.
Dim frontendDir, distIndex
frontendDir = rootDir & "\frontend"
distIndex = frontendDir & "\dist\index.html"
launcherLog = logsDir & "\lanceur.log"

If Not fso.FolderExists(logsDir) Then fso.CreateFolder logsDir

' PID des processus demarres par CE lancement (vides si reutilises), dont la
' compilation du backend en cours (voir StartBackendBuild).
Dim backendPid, frontendPid, backendBuildPid, backendBuildNeedsJar, backendBuildSourceDate, backendBuildDirect
backendPid = ""
frontendPid = ""
backendBuildPid = ""
' Compilation du frontend en cours (voir StartFrontend).
Dim frontendBuildPid
frontendBuildPid = ""

' Relance pendant une fermeture (voir RELAIS ENTRE DEUX LANCEMENTS).
Dim closingFile, relaunchRequestFile, handoverFile, windowOpenedEarly
closingFile = logsDir & "\lanceur.fermeture"
relaunchRequestFile = logsDir & "\lanceur.relance"
handoverFile = logsDir & "\lanceur.relais"
windowOpenedEarly = False

' Ouverture de la fenetre (voir OpenWindow et ReopenWindowIfVanished).
Dim windowOpenedAt, windowReopened, windowSeen
windowOpenedAt = Empty
windowReopened = False
windowSeen = False

' Reconnaissance des serveurs du lanceur (cmd.exe ... > journal) : par leur
' journal, seul chemin absolu present dans tous les modes de demarrage (le
' classpath du backend est relatif).
Dim backendLogMarker, frontendLogMarker
backendLogMarker = logsDir & "\backend.log"
frontendLogMarker = logsDir & "\frontend.log"

Main


Sub Main()

    If Not WaitForLauncherLock() Then Exit Sub
    ' Verrou obtenu : aucune fermeture en cours (temoin d'un lanceur interrompu).
    DeleteIfExists closingFile

    ' Fenetre deja ouverte si ce lancement a attendu une fermeture.
    If Not windowOpenedEarly Then
        ' Un seul essai : verrou obtenu, aucun autre lanceur n'est actif, et le cas
        ' courant (pas de fenetre) n'attend plus les essais de EdgeHomeBankExists.
        If EdgeHomeBankFound(edgeProfile) Then
            Log "HomeBank deja ouvert : fenetre ramenee au premier plan."
            BringWindowToFront
            Exit Sub
        End If
        StartLog
        ' Fenetre ouverte des le clic, avant toute verification ou compilation
        ' (avant : 3 a 9 s sans aucun signe de vie) : voir OpenWindow.
        OpenWindow
    Else
        ' If imbriques : VBScript evalue toujours les deux cotes d'un And, et
        ' chercher une fenetre absente coute ~1 s avant le demarrage du serveur.
        ReopenWindowIfVanished
        If Not EdgeHomeBankExists(edgeProfile) Then
            ' Fenetre fermee pendant l'attente : rien a lancer ; serveurs laisses
            ' par la fermeture precedente arretes.
            Log "Fenetre fermee pendant l'attente : lancement abandonne."
            If TakeOverHandedServers() Then StopStartedProcesses
            Exit Sub
        End If
    End If

    If Not TakeOverHandedServers() Then StopLeftoversFromPreviousRun

    ' --- Backend d'abord (le plus long) : demarre si son code est compile, sinon
    '     compile en parallele de la compilation du frontend (mesure : les deux
    '     finies en ~9,4 s au lieu de ~12,2 s l'une apres l'autre). Lance AVANT
    '     de verifier les ports : cette requete WMI coute 0,3 a 0,7 s (mesure),
    '     qui retardaient d'autant le serveur. Serveur repris (backendPid) : rien
    '     a lancer ---
    If backendPid = "" Then
        If Not EnsurePostgresRunning() Then
            CloseWindow
            Fail "PostgreSQL n'est pas démarré : HomeBank ne peut pas lire vos données." & vbCrLf & vbCrLf & _
                 "Démarrez le service PostgreSQL (Services Windows), puis relancez HomeBank."
            Exit Sub
        End If
        If BackendCodeOutdated() Or Not LibrariesReady() Then
            StartBackendBuild
        Else
            StartBackendProcess
        End If
    End If

    ' Ports du backend et du frontend en une seule requete.
    Dim listening
    Set listening = ListeningPorts()

    ' --- Un autre serveur occupait deja le port (ex : IntelliJ) ? Celui qu'on
    '     vient de lancer n'a pas encore pu ouvrir le port : son lancement est
    '     annule (Tomcat reserve le port avant d'initialiser la base : il
    '     n'aurait de toute facon rien touche) et l'autre est reutilise ---
    If listening.Exists(CLng(BACKEND_PORT)) Then
        If IsForeignListener(listening(CLng(BACKEND_PORT))) Then
            CancelBackendStart
            If IsTestBackend() Then
                CloseWindow
                Fail "Un serveur HomeBank de TEST (données de démonstration, ex : IntelliJ « All TEST ») " & _
                     "occupe déjà le port " & BACKEND_PORT & "." & vbCrLf & vbCrLf & _
                     "Arrêtez-le, puis relancez HomeBank pour retrouver vos données."
                Exit Sub
            End If
            Log "Backend deja en marche sur le port " & BACKEND_PORT & " (ex : IntelliJ) : reutilise, non arrete a la fermeture."
        End If
    End If

    ' --- Frontend ---
    If listening.Exists(CLng(FRONTEND_PORT)) Then
        If HttpGetContains("http://127.0.0.1:" & FRONTEND_PORT & "/", "<title>HomeBank") Then
            If frontendPid = "" Then Log "Frontend deja en marche sur le port " & FRONTEND_PORT & " : reutilise."
        Else
            StopStartedProcesses
            CloseWindow
            Fail "Le port " & FRONTEND_PORT & " est occupé par un programme qui ne répond pas comme HomeBank." & vbCrLf & vbCrLf & _
                 PortOwnerDescription(FRONTEND_PORT) & vbCrLf & vbCrLf & _
                 "S'il s'agit d'un ancien serveur Vite de HomeBank bloqué (node.exe), arrêtez-le " & _
                 "dans le Gestionnaire des tâches, puis relancez HomeBank."
            Exit Sub
        End If
    Else
        StartFrontend
    End If

    ' Serveurs lances : verifier que la fenetre est bien apparue.
    ReopenWindowIfVanished

    FinishBuilds

    If Not WaitForPort(FRONTEND_PORT, FRONTEND_TIMEOUT_S) Then
        CloseWindow
        Fail "Le frontend (Vite) n'a pas démarré en " & FRONTEND_TIMEOUT_S & " s." & vbCrLf & vbCrLf & _
             "Journal : " & logsDir & "\frontend.log"
        StopStartedProcesses
        Exit Sub
    End If

    WaitForEdgeToAppear edgeProfile
    ' Fenetre deja affichee : la requete WMI ne retarde plus rien.
    EnsureGoogleDriveRunning
    WatchUntilWindowClosed

    CreateEmptyFile closingFile
    ExportPdfOnClose
    ' Drive a pu etre ferme pendant la session : le PDF et la sauvegarde du
    ' jour ne partiraient pas. Il continue de tourner apres HomeBank.
    EnsureGoogleDriveRunning
    If fso.FileExists(relaunchRequestFile) Then
        HandOverServers
    Else
        StopStartedProcesses
    End If
    DeleteIfExists closingFile
    ' Verrou rendu avant la preparation du demarrage rapide (~40 s, compatible
    ' avec un lancement en parallele) : relancer juste apres une fermeture
    ' n'attend pas.
    ReleaseLauncherLock
    PrepareFastStart
    Log "Fermeture terminee."

End Sub


' =========================================================
' UN SEUL LANCEMENT A LA FOIS
'
' La detection de la fenetre Edge ne suffisait pas : un double-clic lancait
' deux lanceurs avant que la fenetre n'existe (journal : deux "Lancement"
' a la meme seconde, deux fenetres). Le lanceur tient donc ouvert un fichier
' verrou (ouverture exclusive : refusee a un second processus, rendue par
' Windows a la fin du processus, meme en cas de plantage). Un second
' lancement attend : la fenetre du premier apparait (il la ramene au premier
' plan et s'arrete), ou le premier finit de se fermer (il demarre alors, en
' reprenant ses serveurs : voir RELAIS ENTRE DEUX LANCEMENTS).
' =========================================================

Function WaitForLauncherLock()

    Dim tries
    WaitForLauncherLock = False
    For tries = 1 To LOCK_TIMEOUT_S * 2
        If TryLauncherLock() Then
            WaitForLauncherLock = True
            Exit Function
        End If
        ' Sa propre fenetre, ouverte en attendant une fermeture, ne compte pas.
        If windowOpenedEarly Then
            ReopenWindowIfVanished
        Else
            If EdgeHomeBankExists(edgeProfile) Then
                Log "Lancement deja en cours : fenetre ramenee au premier plan."
                BringWindowToFront
                Exit Function
            End If
            If fso.FileExists(closingFile) Then
                ' Un seul lancement attend la fermeture : vu le 19/11, un second
                ' clic ouvrait une seconde fenetre et redemarrait des serveurs a vide.
                If RecentHandoverRequest() Then
                    Log "Relance deja demandee par un clic precedent : sa fenetre est ramenee au premier plan."
                    BringWindowToFront
                    Exit Function
                End If
                RequestHandover
            End If
        End If
        WScript.Sleep 500
    Next
    Log "Un autre lancement de HomeBank ne se termine pas (verrou " & lockFile & ") : abandon."

End Function


Function TryLauncherLock()

    On Error Resume Next
    Set launcherLock = fso.OpenTextFile(lockFile, 2, True)
    TryLauncherLock = (Err.Number = 0)
    Err.Clear
    On Error GoTo 0

End Function


Sub ReleaseLauncherLock()

    If IsObject(launcherLock) Then
        If Not launcherLock Is Nothing Then launcherLock.Close
    End If
    Set launcherLock = Nothing

End Sub


' =========================================================
' RELAIS ENTRE DEUX LANCEMENTS
'
' Fermer puis rouvrir aussitot faisait attendre la fin de la fermeture
' (export PDF ~10 s, puis arret des serveurs) sans rien afficher, avant de
' redemarrer les memes serveurs (journal : chaque lancement 1 s apres la
' "Fermeture terminee" precedente). Desormais :
' - le lanceur qui se ferme le signale (lanceur.fermeture) ;
' - un lancement qui l'attend ouvre la fenetre tout de suite et demande le
'   relais (lanceur.relance) ;
' - apres l'export PDF, le lanceur qui se ferme laisse alors ses serveurs en
'   marche (lanceur.relais, PID dans lanceur.pids) au lieu de les arreter ;
' - le nouveau lancement les reprend : appli affichee sans redemarrage, et
'   serveurs arretes a SA fermeture.
' Une demande arrivee trop tard (serveurs deja arretes) est sans effet : la
' page d'attente patiente jusqu'aux nouveaux serveurs.
' =========================================================

Sub RequestHandover()

    CreateEmptyFile relaunchRequestFile
    StartLog
    Log "Fermeture du lancement precedent en cours : fenetre ouverte, ses serveurs seront repris."
    OpenWindow
    windowOpenedEarly = True

End Sub


' Vrai si un autre clic attend deja la fin de la fermeture. Une demande plus
' ancienne que l'attente maximale d'un lanceur est un reste (lanceur interrompu).
Function RecentHandoverRequest()

    RecentHandoverRequest = False
    If Not fso.FileExists(relaunchRequestFile) Then Exit Function
    RecentHandoverRequest = DateDiff("s", fso.GetFile(relaunchRequestFile).DateLastModified, Now) < LOCK_TIMEOUT_S

End Function


' A la fermeture, si une relance l'a demande : serveurs laisses en marche.
Sub HandOverServers()

    On Error Resume Next
    DeleteIfExists handoverFile
    fso.MoveFile relaunchRequestFile, handoverFile
    If Err.Number <> 0 Then
        Err.Clear
        On Error GoTo 0
        StopStartedProcesses
        Exit Sub
    End If
    On Error GoTo 0
    Log "Relance pendant la fermeture : serveurs laisses au lancement suivant."
    ' Ils ne sont plus a ce lanceur : rien a arreter de son cote.
    backendPid = ""
    frontendPid = ""

End Sub


' Reprend les serveurs laisses par le lancement precedent ; vrai s'il y a eu
' relais (les PID notes ne sont alors pas des restes a arreter).
Function TakeOverHandedServers()

    Dim stream, line, parts
    TakeOverHandedServers = False
    ' Demande arrivee apres l'arret des serveurs : sans objet.
    DeleteIfExists relaunchRequestFile
    If Not fso.FileExists(handoverFile) Then Exit Function
    DeleteIfExists handoverFile
    TakeOverHandedServers = True
    If Not fso.FileExists(pidsFile) Then Exit Function

    Set stream = fso.OpenTextFile(pidsFile, 1)
    Do While Not stream.AtEndOfStream
        line = Trim(stream.ReadLine)
        parts = Split(line, "=")
        If UBound(parts) = 1 Then
            If parts(0) = "backend" And IsLauncherProcess(parts(1), backendLogMarker) Then backendPid = parts(1)
            If parts(0) = "frontend" And IsLauncherProcess(parts(1), frontendLogMarker) Then frontendPid = parts(1)
        End If
    Loop
    stream.Close
    SavePids
    Log "Serveurs repris du lancement precedent (backend PID " & backendPid & ", frontend PID " & frontendPid & ")."

End Function


Sub CreateEmptyFile(path)

    On Error Resume Next
    fso.CreateTextFile(path, True).Close
    On Error GoTo 0

End Sub


Sub DeleteIfExists(path)

    On Error Resume Next
    If fso.FileExists(path) Then fso.DeleteFile path, True
    On Error GoTo 0

End Sub


' =========================================================
' FRONTEND : VERSION DE PRODUCTION, RECOMPILEE SI BESOIN
'
' Le serveur de developpement (npm run dev) sert Vue en version de
' developpement et chaque module a part : mesure sur 5 000 transactions,
' tableau de bord 1,3 s -> 0,6 s et pages ~2,5 fois plus rapides a
' construire en version de production. Il recharge aussi la page a la
' moindre modification, ce qui perturbait l'export PDF. Il reste celui
' d'IntelliJ (All PROD / All TEST) pour developper.
' =========================================================

' Demarre le serveur du frontend, ou d'abord sa recompilation si son code a
' change : lancee sans l'attendre (~2,5 s avec Vite 8), pendant celle du backend ; le
' serveur demarre a sa fin (voir FinishBuilds).
Sub StartFrontend()

    If FrontendIsOutdated() Then
        Log "Code frontend plus recent que sa version compilee : recompilation (vite build) en arriere-plan..."
        frontendBuildPid = StartHiddenProcess(frontendDir, FRONTEND_BUILD_COMMAND, logsDir & "\build-frontend.log")
        ' Lancement impossible : constate tout de suite (version compilee perimee).
        If frontendBuildPid = "" Then FinishFrontendBuildAndStart
        Exit Sub
    End If
    StartFrontendServer FRONTEND_PREVIEW_COMMAND

End Sub


' Recompilation du frontend terminee : version compilee servie si elle est a
' jour, sinon serveur de developpement (vite build vide dist\ avant d'ecrire :
' pas forcement d'ancienne version).
Sub FinishFrontendBuildAndStart()

    frontendBuildPid = ""
    If FrontendIsOutdated() Then
        Log "ECHEC de la recompilation du frontend (voir logs\build-frontend.log) : serveur de developpement utilise."
        StartFrontendServer "npm run dev"
    Else
        Log "Recompilation du frontend reussie."
        StartFrontendServer FRONTEND_PREVIEW_COMMAND
    End If

End Sub


Sub StartFrontendServer(command)

    frontendPid = StartHiddenProcess(frontendDir, command, logsDir & "\frontend.log")
    SavePids
    Log "Frontend demarre (" & command & ", PID " & frontendPid & ")."

End Sub


' Vrai si un fichier source du frontend est plus recent que sa version compilee (ou s'il n'y en a pas).
Function FrontendIsOutdated()

    Dim distDate, name
    FrontendIsOutdated = True
    If Not fso.FileExists(distIndex) Then Exit Function
    distDate = fso.GetFile(distIndex).DateLastModified
    If NewestFileDate(fso.GetFolder(frontendDir & "\src")) > distDate Then Exit Function
    If NewestFileDate(fso.GetFolder(frontendDir & "\public")) > distDate Then Exit Function
    For Each name In Array("index.html", "vite.config.mjs", "vite.preview.config.mjs", "package.json", "package-lock.json")
        If fso.FileExists(frontendDir & "\" & name) Then
            If fso.GetFile(frontendDir & "\" & name).DateLastModified > distDate Then Exit Function
        End If
    Next
    FrontendIsOutdated = False

End Function


' =========================================================
' BACKEND : COMPILATION SI BESOIN, PUIS DEMARRAGE
'
' Le serveur tourne depuis ses classes compilees et ses bibliotheques
' extraites du jar, pas depuis le jar executable :
' - un changement de code ne demande qu'une compilation (pas d'empaquetage
'   ni d'extraction du jar, ~2 s de moins), par javac directement si
'   possible (voir JAVAC_OPTIONS) ;
' - les bibliotheques ne bougent qu'avec le pom.xml : l'archive CDS, qui
'   n'enregistre pas les classes chargees depuis un dossier, reste valable
'   apres une recompilation (mesure, profil test : 4,5 s -> 2,8 s), et n'est
'   plus a refaire a chaque fermeture.
' Le serveur lit une COPIE des classes (backendSnapshot) : une compilation
' en echec vide target\classes (verifie : 0 classe), et IntelliJ ou mvn y
' recompilent pendant que HomeBank tourne.
'
' Repertoire de travail = backend\, comme mvn/IntelliJ (chemins relatifs
' de export.dossier-sortie / export.chemin-script, et de logs\cds.log).
' =========================================================

' Lance la compilation sans l'attendre (voir FinishBackendBuildAndStart) :
' Maven "package" si les bibliotheques sont a extraire de nouveau (pom.xml
' modifie), sinon javac directement (voir PrepareDirectCompile), a defaut
' Maven "compile". Maven note au passage le classpath de compilation, pour
' les compilations directes suivantes.
Sub StartBackendBuild()

    Dim goal
    goal = "compile"
    If Not LibrariesReady() Then goal = "package"
    backendBuildNeedsJar = (goal = "package")
    ' Date du code compile, relevee AVANT la compilation : un fichier modifie
    ' pendant celle-ci sera recompile au lancement suivant.
    backendBuildSourceDate = NewestBackendSourceDate()

    backendBuildDirect = False
    If Not backendBuildNeedsJar Then
        If ReuseCompiledClasses() Then Exit Sub
        backendBuildDirect = PrepareDirectCompile()
    End If
    If backendBuildDirect Then
        Log "Code backend modifie : compilation (javac) en arriere-plan..."
        backendBuildPid = StartHiddenProcess(rootDir & "\backend", _
            "(javac " & JAVAC_OPTIONS & " @""" & javacArgsFile & """ && echo " & BUILD_OK_MARKER & ")", backendBuildLog)
        Exit Sub
    End If

    Log "Code backend modifie : compilation (mvn " & goal & ") en arriere-plan..."
    backendBuildPid = StartHiddenProcess(rootDir & "\backend", _
        "(set ""MAVEN_OPTS=" & MAVEN_JVM_OPTIONS & """ && mvn " & MAVEN_BUILD_OPTIONS & " " & goal & _
        " dependency:build-classpath -Dmdep.includeScope=compile -Dmdep.outputFile=""" & compileClasspathFile & _
        """ && echo " & BUILD_OK_MARKER & ")", backendBuildLog)

End Sub


' Reprend target\classes deja a jour (voir CompiledClassesUpToDate) au lieu
' de compiler (copie ~0,3 s au lieu de 4 a 6 s), puis demarre le serveur.
' Cas courant : code modifie puis compile ou teste dans IntelliJ, ou par mvn
' (compile, test). Faux si elles ne sont pas a jour, ou si elles ont change
' pendant la copie (compilation IntelliJ en cours : copie peut-etre
' incoherente) : la compilation a alors lieu.
Function ReuseCompiledClasses()

    Dim classesDir, newestBefore
    ReuseCompiledClasses = False
    classesDir = rootDir & "\backend\target\classes"
    If Not CompiledClassesUpToDate(classesDir) Then Exit Function
    newestBefore = NewestFileDate(fso.GetFolder(classesDir))
    UpdateBackendSnapshot backendBuildSourceDate
    If Not fso.FileExists(backendSnapshotStamp) Then Exit Function
    If NewestFileDate(fso.GetFolder(classesDir)) <> newestBefore Then
        DeleteIfExists backendSnapshotStamp
        Exit Function
    End If
    Log "Code backend deja compile (IntelliJ ou mvn) : classes reprises sans recompilation."
    StartBackendProcess
    ReuseCompiledClasses = True

End Function


' Vrai si classesDir correspond aux sources actuelles, selon la regle de
' Maven (source plus recente que sa classe = a recompiler), en plus strict :
' - chaque source Java a sa classe, au moins aussi recente qu'elle (une
'   compilation en echec n'ecrit pas la classe, mvn vide meme le dossier) ;
' - aucune classe sans source (fichier supprime : classe perimee, qu'un
'   composant Spring ou une entite pourrait encore declarer) ;
' - chaque ressource copiee, au moins aussi recente que l'originale.
Function CompiledClassesUpToDate(classesDir)

    Dim javaDir
    CompiledClassesUpToDate = False
    javaDir = rootDir & "\backend\src\main\java"
    If Not fso.FolderExists(classesDir) Then Exit Function
    If Not CopiesUpToDate(fso.GetFolder(javaDir), classesDir, "class") Then Exit Function
    If Not ClassesHaveSources(fso.GetFolder(classesDir), javaDir) Then Exit Function
    If fso.FolderExists(rootDir & "\backend\src\main\resources") Then
        If Not CopiesUpToDate(fso.GetFolder(rootDir & "\backend\src\main\resources"), classesDir, "") Then Exit Function
    End If
    CompiledClassesUpToDate = True

End Function


' Vrai si chaque fichier de folder (et de ses sous-dossiers) a son equivalent
' dans targetDir, au moins aussi recent : meme nom, ou extension remplacee par
' targetExtension (sources .java seules alors).
Function CopiesUpToDate(folder, targetDir, targetExtension)

    Dim file, subFolder, targetPath
    CopiesUpToDate = False
    For Each file In folder.Files
        targetPath = ""
        If targetExtension = "" Then
            targetPath = targetDir & "\" & file.Name
        ElseIf LCase(fso.GetExtensionName(file.Name)) = "java" Then
            targetPath = targetDir & "\" & fso.GetBaseName(file.Name) & "." & targetExtension
        End If
        If targetPath <> "" Then
            If Not fso.FileExists(targetPath) Then Exit Function
            If fso.GetFile(targetPath).DateLastModified < file.DateLastModified Then Exit Function
        End If
    Next
    For Each subFolder In folder.SubFolders
        If Not CopiesUpToDate(subFolder, targetDir & "\" & subFolder.Name, targetExtension) Then Exit Function
    Next
    CopiesUpToDate = True

End Function


' Vrai si chaque classe de folder a sa source dans javaDir (classe interne
' A$B : source A.java).
Function ClassesHaveSources(folder, javaDir)

    Dim file, subFolder
    ClassesHaveSources = False
    For Each file In folder.Files
        If LCase(fso.GetExtensionName(file.Name)) = "class" Then
            If Not fso.FileExists(javaDir & "\" & Split(fso.GetBaseName(file.Name), "$")(0) & ".java") Then Exit Function
        End If
    Next
    For Each subFolder In folder.SubFolders
        If Not ClassesHaveSources(subFolder, javaDir & "\" & subFolder.Name) Then Exit Function
    Next
    ClassesHaveSources = True

End Function


' Prepare une compilation directe par javac (dossier de sortie vide, fichier
' d'arguments) ; faux si elle n'est pas possible, Maven compile alors :
' - classpath de compilation pas encore note, ou anterieur au pom.xml ;
' - version de Java introuvable dans le pom.xml ;
' - ressources a filtrer (Maven remplace les @...@ des application*.properties,
'   javac ne fait que les copier).
Function PrepareDirectCompile()

    Dim release, classpath, stream
    PrepareDirectCompile = False
    If Not fso.FileExists(compileClasspathFile) Then Exit Function
    If fso.GetFile(compileClasspathFile).DateLastModified < fso.GetFile(rootDir & "\backend\pom.xml").DateLastModified Then Exit Function
    release = PomJavaVersion()
    If release = "" Or ResourcesNeedFiltering() Then Exit Function
    classpath = Replace(Replace(Trim(ReadText(compileClasspathFile)), vbCr, ""), vbLf, "")
    If classpath = "" Then Exit Function

    On Error Resume Next
    If fso.FolderExists(javacOutputDir) Then fso.DeleteFolder javacOutputDir, True
    ' Dossier encore present (verrouille) : sa creation echoue, Maven compile.
    fso.CreateFolder javacOutputDir
    Set stream = fso.CreateTextFile(javacArgsFile, True)
    stream.WriteLine "--release " & release
    stream.WriteLine "-cp " & ArgFileQuote(classpath)
    stream.WriteLine "-d " & ArgFileQuote(javacOutputDir)
    WriteJavaSources stream, fso.GetFolder(rootDir & "\backend\src\main\java")
    stream.Close
    PrepareDirectCompile = (Err.Number = 0)
    If Err.Number <> 0 Then Log "Compilation directe impossible (" & Err.Description & ") : Maven utilise."
    Err.Clear
    On Error GoTo 0

End Function


' Version de Java du projet (<java.version> du pom.xml), "" si introuvable.
Function PomJavaVersion()

    Dim regex, matches
    PomJavaVersion = ""
    Set regex = New RegExp
    regex.Pattern = "<java\.version>\s*(\d+)\s*</java\.version>"
    Set matches = regex.Execute(ReadText(rootDir & "\backend\pom.xml"))
    If matches.Count > 0 Then PomJavaVersion = matches(0).SubMatches(0)

End Function


' Vrai si une ressource filtree par Maven (application*.properties/.yml,
' reglage de spring-boot-starter-parent) contient un @...@ a remplacer.
Function ResourcesNeedFiltering()

    Dim regex, folder, file
    ResourcesNeedFiltering = False
    folder = rootDir & "\backend\src\main\resources"
    If Not fso.FolderExists(folder) Then Exit Function
    Set regex = New RegExp
    regex.Pattern = "@[^@\s]+@"
    For Each file In fso.GetFolder(folder).Files
        If LCase(Left(file.Name, 11)) = "application" Then
            If regex.Test(ReadText(file.Path)) Then
                ResourcesNeedFiltering = True
                Exit Function
            End If
        End If
    Next

End Function


' Chemins des sources Java, un par ligne du fichier d'arguments de javac.
Sub WriteJavaSources(stream, folder)

    Dim file, subFolder
    For Each file In folder.Files
        If LCase(fso.GetExtensionName(file.Name)) = "java" Then stream.WriteLine ArgFileQuote(file.Path)
    Next
    For Each subFolder In folder.SubFolders
        WriteJavaSources stream, subFolder
    Next

End Sub


' Argument entre guillemets pour un fichier d'arguments de javac, ou "\" sert
' d'echappement : barres obliques, que javac accepte aussi sous Windows.
Function ArgFileQuote(value)

    ArgFileQuote = """" & Replace(value, "\", "/") & """"

End Function


' Attend les compilations en cours (backend, frontend) et demarre chaque
' serveur des la fin de la sienne : avant, le backend attendait la fin de la
' compilation du frontend et l'ouverture du port de Vite (~1 s de retard
' quand les deux avaient change). Fenetre surveillee pendant ce temps.
Sub FinishBuilds()

    Dim startedAt
    startedAt = Now
    Do While backendBuildPid <> "" Or frontendBuildPid <> ""
        If backendBuildPid <> "" Then
            If BuildEnded(backendBuildPid, startedAt, BUILD_TIMEOUT_S) Then FinishBackendBuildAndStart
        End If
        If frontendBuildPid <> "" Then
            If BuildEnded(frontendBuildPid, startedAt, FRONTEND_BUILD_TIMEOUT_S) Then FinishFrontendBuildAndStart
        End If
        If backendBuildPid <> "" Or frontendBuildPid <> "" Then
            ReopenWindowIfVanished
            WScript.Sleep 100
        End If
    Loop

End Sub


' Vrai si la compilation est terminee ; au-dela du delai, elle est
' interrompue et consideree comme terminee (en echec).
Function BuildEnded(pid, startedAt, timeoutSeconds)

    BuildEnded = True
    If Not ProcessExists(pid) Then Exit Function
    If DateDiff("s", startedAt, Now) > timeoutSeconds Then
        Log "Compilation (PID " & pid & ") trop longue (> " & timeoutSeconds & " s) : interrompue."
        StopProcessTree pid
        Exit Function
    End If
    BuildEnded = False

End Function


' Compilation du backend terminee (voir FinishBuilds) : copie lue par le
' serveur mise a jour, puis demarrage.
Sub FinishBackendBuildAndStart()

    backendBuildPid = ""
    If InStr(1, LastLines(backendBuildLog, 3), BUILD_OK_MARKER, vbBinaryCompare) > 0 Then
        Log "Compilation reussie."
        If backendBuildNeedsJar Then EnsureExtractedJar
        UpdateBackendSnapshot backendBuildSourceDate
    ElseIf fso.FileExists(backendSnapshotStamp) Then
        Log "ECHEC de la compilation : ancienne version utilisee (voir logs\build.log)."
        ShowWarning "La mise à jour du serveur HomeBank a échoué : l'ancienne version est utilisée." & vbCrLf & vbCrLf & _
                    "Détail : " & backendBuildLog
    Else
        Fail "Le serveur HomeBank n'a pas pu être compilé." & vbCrLf & vbCrLf & _
             "Détail : " & backendBuildLog
        Exit Sub
    End If

    StartBackendProcess

End Sub


' Demarre le serveur : depuis la copie des classes et les bibliotheques
' extraites (mode rapide si l'archive CDS existe), a defaut depuis le jar
' executable.
Sub StartBackendProcess()

    Dim command, mode
    If LibrariesReady() And fso.FileExists(backendSnapshotStamp) Then
        command = "java " & BACKEND_JVM_OPTIONS
        If fso.FileExists(fastStartArchive) Then
            mode = "mode rapide"
            ' Rejet de l'archive (mise a jour de Java...) note dans logs\cds.log :
            ' elle sera refaite a la fermeture (voir FastStartArchiveUsable).
            command = command & " -XX:SharedArchiveFile=""" & fastStartArchive & """ " & CDS_RUNTIME_LOG
        Else
            mode = "classes compilees"
        End If
        command = command & " -cp """ & BackendClasspath() & """ " & BACKEND_MAIN_CLASS & " " & BACKEND_APP_OPTIONS
    ElseIf fso.FileExists(jarPath) Then
        mode = "jar executable"
        command = "java " & BACKEND_JVM_OPTIONS & " -jar """ & jarPath & """ " & BACKEND_APP_OPTIONS
    Else
        Fail "Le serveur HomeBank n'est pas compilé (ni " & backendSnapshot & ", ni " & jarPath & ")."
        Exit Sub
    End If
    backendPid = StartHiddenProcess(rootDir & "\backend", command, logsDir & "\backend.log")
    SavePids
    Log "Backend demarre (" & mode & ", PID " & backendPid & ")."

End Sub


' Bibliotheques puis classes : l'ordre de l'archive CDS (classes en dernier,
' non archivees : leur changement ne l'invalide pas). Chemins RELATIFS au
' repertoire backend\ (fastStartDir, backendSnapshot) : en chemins absolus,
' la JVM refuse d'ecrire l'archive ("Cannot have non-empty directory in
' paths", verifie).
Function BackendClasspath()

    BackendClasspath = "target\demarrage-rapide\app\lib\*;target\demarrage-rapide\classes"

End Function


' Vrai si un fichier de backend\src\main ou le pom.xml a change depuis la
' derniere compilation reussie copiee (ou s'il n'y en a pas).
Function BackendCodeOutdated()

    Dim stream, compiledDate
    BackendCodeOutdated = True
    If Not fso.FileExists(backendSnapshotStamp) Then Exit Function
    On Error Resume Next
    Set stream = fso.OpenTextFile(backendSnapshotStamp, 1)
    compiledDate = CDbl(Trim(stream.ReadLine))
    stream.Close
    If Err.Number <> 0 Then
        Err.Clear
        On Error GoTo 0
        Exit Function
    End If
    On Error GoTo 0
    BackendCodeOutdated = NewestBackendSourceDate() > compiledDate

End Function


' Date du fichier le plus recent de src\main et du pom.xml, en secondes
' entieres depuis 1970 : ecrite et relue sans decimale ni reglage regional.
Function NewestBackendSourceDate()

    Dim newest, pomDate
    newest = NewestFileDate(fso.GetFolder(rootDir & "\backend\src\main"))
    pomDate = fso.GetFile(rootDir & "\backend\pom.xml").DateLastModified
    If pomDate > newest Then newest = pomDate
    NewestBackendSourceDate = DateDiff("s", DateSerial(1970, 1, 1), newest)

End Function


' Remplace la copie lue par le serveur par la compilation qui vient de
' reussir : classes de javac completees des ressources (copiees telles quelles,
' voir ResourcesNeedFiltering), ou target\classes de Maven. Le temoin, ecrit
' en dernier, note la date du code compile.
Sub UpdateBackendSnapshot(sourceDate)

    Dim stream
    On Error Resume Next
    If fso.FileExists(backendSnapshotStamp) Then fso.DeleteFile backendSnapshotStamp, True
    If fso.FolderExists(backendSnapshot) Then fso.DeleteFolder backendSnapshot, True
    If Not fso.FolderExists(fastStartDir) Then fso.CreateFolder fastStartDir
    If backendBuildDirect Then
        CopyFolderContents rootDir & "\backend\src\main\resources", javacOutputDir
        fso.MoveFolder javacOutputDir, backendSnapshot
    Else
        fso.CopyFolder rootDir & "\backend\target\classes", backendSnapshot, True
    End If
    If Err.Number <> 0 Then
        Log "ECHEC de la copie des classes compilees (" & Err.Description & ") : serveur demarre depuis le jar."
        Err.Clear
        On Error GoTo 0
        Exit Sub
    End If
    Set stream = fso.CreateTextFile(backendSnapshotStamp, True)
    stream.WriteLine CStr(sourceDate)
    stream.Close
    If Err.Number <> 0 Then
        Log "ECHEC de l'ecriture de " & backendSnapshotStamp & " (" & Err.Description & ") : compilation refaite au prochain lancement."
        Err.Clear
        If fso.FileExists(backendSnapshotStamp) Then fso.DeleteFile backendSnapshotStamp, True
    End If
    On Error GoTo 0

End Sub


' Copie le contenu d'un dossier (fichiers et sous-dossiers) dans un autre.
' Erreurs laissees a l'appelant.
Sub CopyFolderContents(source, destination)

    Dim file, subFolder
    If Not fso.FolderExists(source) Then Exit Sub
    For Each file In fso.GetFolder(source).Files
        file.Copy destination & "\" & file.Name, True
    Next
    For Each subFolder In fso.GetFolder(source).SubFolders
        subFolder.Copy destination & "\" & subFolder.Name, True
    Next

End Sub


Function NewestFileDate(folder)

    Dim file, subFolder, candidate
    NewestFileDate = CDate("1970-01-01")
    For Each file In folder.Files
        If file.DateLastModified > NewestFileDate Then NewestFileDate = file.DateLastModified
    Next
    For Each subFolder In folder.SubFolders
        candidate = NewestFileDate(subFolder)
        If candidate > NewestFileDate Then NewestFileDate = candidate
    Next

End Function


' =========================================================
' BIBLIOTHEQUES (extraites du jar)
'
' Charger les bibliotheques depuis des jars ordinaires plutot qu'imbriques
' dans le jar executable fait aussi demarrer le serveur ~1,9 s plus vite
' (mesure, profil test : 5,6 s -> 3,6 s). Extraction (~1 s) refaite
' seulement quand le pom.xml change.
' =========================================================

' Vrai si les bibliotheques extraites correspondent au pom.xml actuel.
Function LibrariesReady()

    Dim jarDate
    LibrariesReady = False
    If Not fso.FileExists(jarPath) Or Not fso.FileExists(fastStartJar) Then Exit Function
    jarDate = fso.GetFile(jarPath).DateLastModified
    LibrariesReady = jarDate >= fso.GetFile(rootDir & "\backend\pom.xml").DateLastModified _
                     And fso.GetFile(fastStartJar).DateLastModified >= jarDate

End Function


' Extrait le jar (deja recompile) ; l'ancienne copie des classes et
' l'ancienne archive CDS, perimees, partent avec l'ancien dossier.
Sub EnsureExtractedJar()

    Dim pid
    On Error Resume Next
    If fso.FolderExists(fastStartDir) Then fso.DeleteFolder fastStartDir, True
    On Error GoTo 0
    ' Encore utilise (ex : preparation du demarrage rapide d'une fermeture precedente).
    If fso.FolderExists(fastStartDir) Then
        Log "Bibliotheques non extraites : dossier " & fastStartDir & " verrouille."
        Exit Sub
    End If

    pid = StartHiddenProcess(rootDir & "\backend", _
        "java -Djarmode=tools -jar """ & jarPath & """ extract --destination """ & fastStartDir & "\app""", _
        logsDir & "\extraction-jar.log")
    WaitForProcess pid, FAST_START_TIMEOUT_S
    If Not fso.FileExists(fastStartJar) Then Log "ECHEC de l'extraction du jar (voir logs\extraction-jar.log)."

End Sub


' =========================================================
' DEMARRAGE RAPIDE (archive CDS de la JVM)
'
' Les classes des bibliotheques chargees par un demarrage du serveur et une
' ouverture de l'appli sont enregistrees dans une archive, relue aux
' demarrages suivants. Preparee a la FERMETURE (fenetre deja fermee : ~40 s
' invisibles), seulement quand il n'y en a pas, que les bibliotheques ont
' change, ou que la JVM l'a rejetee (mise a jour de Java...). Tant qu'elle
' n'est pas prete, le serveur demarre normalement.
' =========================================================

Function FastStartArchiveUsable()

    FastStartArchiveUsable = False
    If Not fso.FileExists(fastStartArchive) Then Exit Function
    If fso.GetFile(fastStartArchive).DateLastModified < fso.GetFile(fastStartJar).DateLastModified Then Exit Function
    FastStartArchiveUsable = InStr(1, LastLines(cdsLog, 20), "Unable to use shared archive", vbTextCompare) = 0

End Function


Sub PrepareFastStart()

    Dim prepLog, tmpArchive, pid
    If Not LibrariesReady() Or Not fso.FileExists(backendSnapshotStamp) Then Exit Sub
    If FastStartArchiveUsable() Then Exit Sub

    Log "Preparation du demarrage rapide (archive CDS)..."
    prepLog = logsDir & "\demarrage-rapide.log"
    tmpArchive = fastStartArchive & ".tmp"

    ' Archive perimee supprimee (lecture seule).
    On Error Resume Next
    If fso.FileExists(fastStartArchive) Then fso.DeleteFile fastStartArchive, True
    On Error GoTo 0
    If fso.FileExists(fastStartArchive) Then
        Log "Demarrage rapide non prepare : archive " & fastStartArchive & " verrouillee."
        Exit Sub
    End If

    ' Demarrage d'entrainement complet, qui lit les donnees d'une ouverture de
    ' l'appli puis s'arrete (voir config.EntrainementDemarrage), sur un port
    ' libre quelconque : jamais en conflit avec un serveur deja lance. Archive
    ' ecrite sous un nom temporaire, pour qu'un lancement concurrent ne lise
    ' jamais une archive incomplete.
    pid = StartHiddenProcess(rootDir & "\backend", _
        "java " & BACKEND_JVM_OPTIONS & " -XX:ArchiveClassesAtExit=""" & tmpArchive & _
        """ -Xlog:cds=off -Xlog:cds+dynamic=off -cp """ & BackendClasspath() & """ " & BACKEND_MAIN_CLASS & _
        " --server.port=0 --homebank.entrainement-demarrage=true " & BACKEND_APP_OPTIONS, prepLog)
    WaitForProcess pid, FAST_START_TIMEOUT_S

    If fso.FileExists(tmpArchive) And InStr(1, LastLines(prepLog, 40), "APPLICATION FAILED", vbTextCompare) = 0 Then
        fso.MoveFile tmpArchive, fastStartArchive
        ' Ancien rejet oublie : il concernait l'archive precedente.
        If fso.FileExists(cdsLog) Then fso.DeleteFile cdsLog, True
        Log "Demarrage rapide pret pour le prochain lancement."
    Else
        Log "ECHEC de la preparation du demarrage rapide (voir logs\demarrage-rapide.log) : demarrage normal conserve."
        If fso.FileExists(tmpArchive) Then fso.DeleteFile tmpArchive
    End If

End Sub



' =========================================================
' SURVEILLANCE PENDANT L'UTILISATION
'
' Si le serveur demarre par le lanceur s'arrete (base injoignable, port
' pris...), on le signale une fois, avec la fin de son journal, au lieu
' de laisser la page d'attente tourner sans explication.
' =========================================================

Sub WatchUntilWindowClosed()

    Dim backendFailureReported
    backendFailureReported = False

    Do While EdgeHomeBankExists(edgeProfile)
        If backendPid <> "" And Not backendFailureReported Then
            If Not ProcessExists(backendPid) Then
                backendFailureReported = True
                Log "Le backend s'est arrete de lui-meme. Fin de logs\backend.log :" & vbCrLf & LastLines(logsDir & "\backend.log", 12)
                ShowWarning "Le serveur HomeBank s'est arrêté." & vbCrLf & vbCrLf & _
                            LastLines(logsDir & "\backend.log", 12) & vbCrLf & _
                            "Journal complet : " & logsDir & "\backend.log"
            End If
        End If
        WScript.Sleep 2000
    Loop

End Sub


' =========================================================
' EXPORT PDF AUTOMATIQUE A LA FERMETURE
'
' Avant l'arret du backend/frontend : le backend lance le script
' Playwright, qui capture le tableau de bord servi par Vite (voir
' ControleurExport, meme endpoint que l'export apres un rapprochement valide).
' =========================================================

Sub ExportPdfOnClose()

    Dim status, body
    If Not IsBackendReady() Then
        Log "Export PDF ignore : serveur injoignable."
        Exit Sub
    End If
    Log "Export PDF du tableau de bord..."
    HttpRequest "POST", "http://127.0.0.1:" & BACKEND_PORT & "/api/export/dashboard-pdf", EXPORT_TIMEOUT_MS, status, body
    If status >= 200 And status < 300 Then
        Log "Export PDF termine."
    Else
        Log "ECHEC de l'export PDF (statut HTTP " & status & ") : " & Left(body, 300)
    End If

End Sub


' =========================================================
' PROCESSUS
' =========================================================

' Lance une commande cachee (WMI, ShowWindow = 0), sortie redirigee vers logFile ; renvoie le PID de cmd.exe.
Function StartHiddenProcess(cdDir, command, logFile)

    Dim wmi, startup, config, pid, resultat
    Set wmi = GetObject("winmgmts:root\cimv2")
    Set startup = wmi.Get("Win32_ProcessStartup")
    Set config = startup.SpawnInstance_
    config.ShowWindow = 0

    resultat = wmi.Get("Win32_Process").Create( _
        "cmd.exe /c " & command & " > """ & logFile & """ 2>&1", cdDir, config, pid)

    If resultat <> 0 Then
        Log "ECHEC du lancement de : " & command & " (code WMI " & resultat & ")"
        StartHiddenProcess = ""
    Else
        StartHiddenProcess = pid
    End If

End Function


Function ProcessExists(pid)

    ProcessExists = False
    If Trim(pid & "") = "" Then Exit Function
    ProcessExists = GetObject("winmgmts:root\cimv2").ExecQuery( _
        "SELECT ProcessId FROM Win32_Process WHERE ProcessId = " & pid).Count > 0

End Function


' Ligne de commande d'un processus ("" s'il n'existe plus).
Function ProcessCommandLine(pid)

    Dim p
    ProcessCommandLine = ""
    If Trim(pid & "") = "" Then Exit Function
    For Each p In GetObject("winmgmts:root\cimv2").ExecQuery( _
            "SELECT CommandLine FROM Win32_Process WHERE ProcessId = " & pid)
        If Not IsNull(p.CommandLine) Then ProcessCommandLine = p.CommandLine
    Next

End Function


' Attend la fin d'un processus ; au-dela du delai, il est arrete (avec ses enfants).
Sub WaitForProcess(pid, timeoutSeconds)

    ' Sondage au 1/5 de seconde : a la seconde, chaque compilation attendue
    ' retardait la suite jusqu'a 1 s.
    Dim waited
    waited = 0
    Do While ProcessExists(pid) And waited < timeoutSeconds * 5
        WScript.Sleep 200
        waited = waited + 1
    Loop
    If ProcessExists(pid) Then
        Log "Processus " & pid & " trop long (> " & timeoutSeconds & " s) : interrompu."
        StopProcessTree pid
    End If

End Sub


Sub StopProcessTree(pid)

    If Trim(pid & "") <> "" Then shell.Run "taskkill /F /T /PID " & pid, 0, True

End Sub


' Arrete uniquement ce que CE lancement a demarre.
Sub StopStartedProcesses()

    If backendPid <> "" Then
        StopProcessTree backendPid
        Log "Backend arrete."
    End If
    If frontendPid <> "" Then
        StopProcessTree frontendPid
        Log "Frontend arrete."
    End If
    If backendBuildPid <> "" Then
        StopProcessTree backendBuildPid
        Log "Compilation du backend interrompue."
    End If
    If frontendBuildPid <> "" Then
        StopProcessTree frontendBuildPid
        Log "Compilation du frontend interrompue."
    End If
    backendPid = ""
    frontendPid = ""
    backendBuildPid = ""
    frontendBuildPid = ""
    If fso.FileExists(pidsFile) Then fso.DeleteFile pidsFile

End Sub


' =========================================================
' RESTES D'UN LANCEMENT PRECEDENT
'
' Si le lanceur a ete interrompu (session fermee, plantage...), ses
' serveurs ont pu rester actifs : un Vite oublie faisait demarrer le
' nouveau sur 5174 alors que la fenetre ouvre 5173, et un vieux backend
' occupait 8080. Seuls les PID notes par le lanceur sont arretes, et
' seulement si leur ligne de commande est bien la sienne (un PID peut
' avoir ete reattribue) : un serveur lance depuis IntelliJ n'est jamais
' touche (l'ancien script liberait le port 5173 sans distinction).
' =========================================================

Sub StopLeftoversFromPreviousRun()

    Dim stream, line, parts
    If Not fso.FileExists(pidsFile) Then Exit Sub

    Set stream = fso.OpenTextFile(pidsFile, 1)
    Do While Not stream.AtEndOfStream
        line = Trim(stream.ReadLine)
        parts = Split(line, "=")
        If UBound(parts) = 1 Then
            If parts(0) = "backend" Then StopIfOurs parts(1), backendLogMarker
            If parts(0) = "frontend" Then StopIfOurs parts(1), frontendLogMarker
        End If
    Loop
    stream.Close
    fso.DeleteFile pidsFile

End Sub


' Vrai si ce PID est encore un processus lance par le lanceur (un PID peut
' avoir ete reattribue a un autre programme).
Function IsLauncherProcess(pid, marker)

    Dim commandLine
    commandLine = ProcessCommandLine(pid)
    IsLauncherProcess = commandLine <> "" And InStr(1, commandLine, marker, vbTextCompare) > 0

End Function


Sub StopIfOurs(pid, marker)

    Dim child, childCommand
    If IsLauncherProcess(pid, marker) Then
        Log "Reste d'un lancement precedent arrete (PID " & pid & ")."
        StopProcessTree pid
        Exit Sub
    End If
    ' cmd.exe deja termine mais java/node orphelin : retrouve par son parent et
    ' son chemin, ou sa classe principale (classpath relatif du backend).
    For Each child In GetObject("winmgmts:root\cimv2").ExecQuery( _
            "SELECT ProcessId, CommandLine FROM Win32_Process WHERE ParentProcessId = " & pid)
        If Not IsNull(child.CommandLine) Then
            childCommand = child.CommandLine
            If InStr(1, childCommand, rootDir, vbTextCompare) > 0 _
               Or InStr(1, childCommand, BACKEND_MAIN_CLASS, vbTextCompare) > 0 Then
                Log "Processus orphelin d'un lancement precedent arrete (PID " & child.ProcessId & ")."
                StopProcessTree child.ProcessId
            End If
        End If
    Next

End Sub


Sub SavePids()

    Dim stream
    Set stream = fso.CreateTextFile(pidsFile, True)
    If backendPid <> "" Then stream.WriteLine "backend=" & backendPid
    If frontendPid <> "" Then stream.WriteLine "frontend=" & frontendPid
    stream.Close

End Sub


' =========================================================
' RESEAU
' =========================================================

' Vrai si un processus ecoute sur ce port TCP (toutes adresses).
' Vrai si le processus qui ecoute (ownerPid) est un autre serveur que celui
' du lanceur (cmd.exe backendPid, parent du java). Un processus deja termine
' (reste d'un lancement precedent tout juste arrete) ne compte pas.
Function IsForeignListener(ownerPid)

    Dim p
    IsForeignListener = False
    For Each p In GetObject("winmgmts:root\cimv2").ExecQuery( _
            "SELECT ParentProcessId FROM Win32_Process WHERE ProcessId = " & ownerPid)
        IsForeignListener = CStr(p.ParentProcessId) <> CStr(backendPid)
    Next

End Function


' Annule le serveur (ou sa compilation) lance par CE lancement : un autre
' serveur occupe deja le port et sera reutilise.
Sub CancelBackendStart()

    If backendBuildPid <> "" Then StopProcessTree backendBuildPid
    If backendPid <> "" Then StopProcessTree backendPid
    If backendBuildPid <> "" Or backendPid <> "" Then Log "Lancement du backend annule : port deja occupe."
    backendBuildPid = ""
    backendPid = ""
    SavePids

End Sub


' Ports du backend et du frontend en ecoute (cle : port, valeur : PID du
' processus qui ecoute), en une seule requete (0,3 a 0,7 s, mesure).
Function ListeningPorts()

    ' Dictionnaire local : dans la fonction, "ListeningPorts(cle) = ..." serait
    ' un appel de la fonction elle-meme, pas une ecriture dans le dictionnaire.
    Dim ports, connections, connection
    Set ports = CreateObject("Scripting.Dictionary")
    On Error Resume Next
    Set connections = GetObject("winmgmts:root\StandardCimv2").ExecQuery( _
        "SELECT LocalPort, OwningProcess FROM MSFT_NetTCPConnection WHERE State = 2 AND (LocalPort = " & _
        BACKEND_PORT & " OR LocalPort = " & FRONTEND_PORT & ")")
    If Err.Number = 0 Then
        For Each connection In connections
            ports(CLng(connection.LocalPort)) = connection.OwningProcess
        Next
    Else
        Log "Ports non verifies (WMI : " & Err.Description & ")."
    End If
    Err.Clear
    On Error GoTo 0
    Set ListeningPorts = ports

End Function


Function IsPortListening(port)

    Dim connections
    IsPortListening = False
    On Error Resume Next
    Set connections = GetObject("winmgmts:root\StandardCimv2").ExecQuery( _
        "SELECT LocalPort FROM MSFT_NetTCPConnection WHERE LocalPort = " & port & " AND State = 2")
    If Err.Number = 0 Then IsPortListening = connections.Count > 0
    Err.Clear
    On Error GoTo 0

End Function


' Processus qui ecoute sur ce port (PID et ligne de commande), pour un message d'erreur exploitable.
' Jamais arrete par le lanceur : il peut s'agir d'un serveur lance depuis IntelliJ.
Function PortOwnerDescription(port)

    Dim connections, connection, commandLine
    PortOwnerDescription = "Programme en cause : inconnu."
    On Error Resume Next
    Set connections = GetObject("winmgmts:root\StandardCimv2").ExecQuery( _
        "SELECT OwningProcess FROM MSFT_NetTCPConnection WHERE LocalPort = " & port & " AND State = 2")
    If Err.Number <> 0 Then
        Err.Clear
        On Error GoTo 0
        Exit Function
    End If
    On Error GoTo 0
    For Each connection In connections
        commandLine = ProcessCommandLine(connection.OwningProcess)
        PortOwnerDescription = "Programme en cause : PID " & connection.OwningProcess & " - " & Left(commandLine, 160)
        Log PortOwnerDescription
        Exit Function
    Next

End Function


Function WaitForPort(port, timeoutSeconds)

    Dim waited
    waited = 0
    Do While Not IsPortListening(port) And waited < timeoutSeconds * 5
        WScript.Sleep 100
        waited = waited + 1
    Loop
    WaitForPort = IsPortListening(port)

End Function


' Requete HTTP synchrone ; status = 0 si le serveur ne repond pas.
Sub HttpRequest(method, url, receiveTimeoutMs, ByRef status, ByRef body)

    Dim http
    status = 0
    body = ""
    On Error Resume Next
    Set http = CreateObject("MSXML2.ServerXMLHTTP.6.0")
    http.setTimeouts 2000, 2000, 5000, receiveTimeoutMs
    http.open method, url, False
    http.send
    If Err.Number = 0 Then
        status = http.status
        body = http.responseText
    End If
    Err.Clear
    On Error GoTo 0

End Sub


Function HttpGetContains(url, expected)

    Dim status, body
    HttpRequest "GET", url, 5000, status, body
    HttpGetContains = status = 200 And InStr(1, body, expected, vbTextCompare) > 0

End Function


Function IsBackendReady()

    Dim status, body
    HttpRequest "GET", "http://127.0.0.1:" & BACKEND_PORT & "/api/accounts", 5000, status, body
    IsBackendReady = status = 200

End Function


' Profil Spring "test" (H2, donnees de demonstration) : seul a exposer la console H2.
Function IsTestBackend()

    IsTestBackend = HttpGetContains("http://127.0.0.1:" & BACKEND_PORT & "/h2-console", "H2 Console")

End Function


' =========================================================
' POSTGRESQL
'
' Le service demarre normalement avec Windows. L'ancien "net start"
' systematique echouait toujours ("Acces refuse" : droits administrateur
' requis) ; il n'est plus tente que si le service est arrete.
' =========================================================

Function EnsurePostgresRunning()

    Dim services, service, found
    EnsurePostgresRunning = True
    found = False
    On Error Resume Next
    Set services = GetObject("winmgmts:root\cimv2").ExecQuery( _
        "SELECT Name, State FROM Win32_Service WHERE Name LIKE 'postgresql%'")
    If Err.Number <> 0 Then
        Err.Clear
        On Error GoTo 0
        Exit Function
    End If
    On Error GoTo 0

    For Each service In services
        found = True
        If service.State <> "Running" Then
            Log "Service " & service.Name & " arrete : tentative de demarrage."
            shell.Run "net start " & service.Name, 0, True
            EnsurePostgresRunning = ServiceIsRunning(service.Name)
        End If
    Next
    ' Pas de service local : base distante, rien a verifier ici.
    If Not found Then Log "Aucun service PostgreSQL local trouve : verification ignoree."

End Function


Function ServiceIsRunning(name)

    Dim service
    ServiceIsRunning = False
    For Each service In GetObject("winmgmts:root\cimv2").ExecQuery( _
            "SELECT State FROM Win32_Service WHERE Name = '" & name & "'")
        ServiceIsRunning = service.State = "Running"
    Next

End Function


' =========================================================
' GOOGLE DRIVE
'
' Le PDF du tableau de bord et la sauvegarde quotidienne sont ecrits dans
' export\sortie, envoye sur Google Drive par Google Drive pour ordinateur...
' seulement quand celui-ci tourne : ferme, rien ne part. Le lanceur le
' demarre donc s'il ne tourne pas. Jamais bloquant : sans Drive installe,
' seul le journal le signale.
' =========================================================

Sub EnsureGoogleDriveRunning()

    Dim command
    On Error Resume Next
    If GetObject("winmgmts:root\cimv2").ExecQuery( _
            "SELECT ProcessId FROM Win32_Process WHERE Name = 'GoogleDriveFS.exe'").Count > 0 Then
        If Err.Number = 0 Then Exit Sub
    End If
    Err.Clear
    On Error GoTo 0

    command = GoogleDriveCommand()
    If command = "" Then
        Log "Google Drive pour ordinateur introuvable : export\sortie ne sera pas synchronise."
        Exit Sub
    End If
    Log "Google Drive ne tournait pas : demarrage (" & command & ")."
    On Error Resume Next
    shell.Run command, 0, False
    If Err.Number <> 0 Then Log "ECHEC du demarrage de Google Drive : " & Err.Description
    Err.Clear
    On Error GoTo 0

End Sub


' Commande de demarrage de Google Drive : celle que Drive inscrit lui-meme au
' demarrage de Windows (suit ses mises a jour, le dossier porte la version),
' sinon son lanceur stable launch.bat ; "" s'il n'est pas installe.
Function GoogleDriveCommand()

    Dim registered, launcher
    GoogleDriveCommand = ""
    On Error Resume Next
    registered = shell.RegRead("HKCU\Software\Microsoft\Windows\CurrentVersion\Run\GoogleDriveFS")
    Err.Clear
    On Error GoTo 0
    If Trim(registered & "") <> "" Then
        GoogleDriveCommand = registered
        Exit Function
    End If

    launcher = shell.ExpandEnvironmentStrings("%ProgramFiles%") & "\Google\Drive File Stream\launch.bat"
    If fso.FileExists(launcher) Then GoogleDriveCommand = """" & launcher & """"

End Function


' =========================================================
' FENETRE EDGE
' =========================================================

' Ramene au premier plan la fenetre deja ouverte. AppActivate seul active
' une fenetre reduite sans la restaurer (rien ne s'affichait) : la
' restauration passe par HomeBank-fenetre.ps1 (~1 s, seulement dans ce cas).
Sub BringWindowToFront()

    Dim script
    script = rootDir & "\HomeBank-fenetre.ps1"
    If fso.FileExists(script) Then
        shell.Run "powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & _
                  script & """ -Profil """ & edgeProfile & """", 0, True
    End If
    shell.AppActivate APP_TITLE

End Sub


' Ouvre la fenetre sur la page d'attente lue comme FICHIER : elle s'affiche
' sans attendre aucun serveur, puis se recharge depuis le frontend des qu'il
' repond (voir frontend\public\demarrage.html).
Sub OpenWindow()

    Dim url
    url = "file:///" & Replace(Replace(frontendDir & "\public\demarrage.html", "\", "/"), " ", "%20") & _
          "?port=" & FRONTEND_PORT
    shell.Run "msedge.exe --app=""" & url & """ --user-data-dir=""" & _
              edgeProfile & """ --no-first-run --start-maximized", 3, False
    windowOpenedAt = Now
    Log "Fenetre ouverte."

End Sub


' Edge peut confier l'ouverture au processus d'une fenetre du meme profil en
' train de se fermer : aucune fenetre n'apparait alors (vu le 19/11 apres une
' fermeture : il avait fallu recliquer). Fenetre rouverte une fois, seulement
' si elle n'est JAMAIS apparue : une fenetre apparue puis fermee l'a ete par
' l'utilisateur. Une vraie fenetre lance plusieurs processus Edge (rendu,
' GPU...) en moins d'une seconde ; une ouverture confiee n'en laisse aucun.
Sub ReopenWindowIfVanished()

    Dim processCount, elapsed
    If windowReopened Or windowSeen Or IsEmpty(windowOpenedAt) Then Exit Sub
    processCount = EdgeProfileProcessCount()
    If processCount >= 3 Then
        windowSeen = True
        Exit Sub
    End If
    elapsed = DateDiff("s", windowOpenedAt, Now)
    If processCount > 0 Or elapsed < 2 Or elapsed > 15 Then Exit Sub
    windowReopened = True
    Log "Fenetre jamais apparue apres son ouverture : reouverture."
    OpenWindow

End Sub


' Nombre de processus Edge du profil HomeBank (0 si WMI echoue).
Function EdgeProfileProcessCount()

    Dim p
    EdgeProfileProcessCount = 0
    On Error Resume Next
    For Each p In GetObject("winmgmts:root\cimv2").ExecQuery( _
            "SELECT CommandLine FROM Win32_Process WHERE Name = 'msedge.exe'")
        If Not IsNull(p.CommandLine) Then
            If InStr(1, p.CommandLine, edgeProfile, vbTextCompare) > 0 Then
                EdgeProfileProcessCount = EdgeProfileProcessCount + 1
            End If
        End If
    Next
    Err.Clear
    On Error GoTo 0

End Function


' Ferme la fenetre (lancement abandonne : le message d'erreur explique
' pourquoi). Seuls les Edge du profil HomeBank sont concernes.
Sub CloseWindow()

    Dim p
    On Error Resume Next
    For Each p In GetObject("winmgmts:root\cimv2").ExecQuery( _
            "SELECT CommandLine FROM Win32_Process WHERE Name = 'msedge.exe'")
        If Not IsNull(p.CommandLine) Then
            If InStr(1, p.CommandLine, edgeProfile, vbTextCompare) > 0 Then p.Terminate
        End If
    Next
    Err.Clear
    On Error GoTo 0

End Sub


' Tolere un demarrage a froid d'Edge (jusqu'a ~15 s) : rater Edge ici tuerait les serveurs trop tot.
Sub WaitForEdgeToAppear(profile)

    Dim tries
    tries = 0
    Do While Not EdgeHomeBankExists(profile) And tries < 30
        ReopenWindowIfVanished
        WScript.Sleep 500
        tries = tries + 1
    Loop

End Sub


' Vrai si une fenetre Edge du profil HomeBank est ouverte. Requete WMI
' directe (un PowerShell imbrique cassait le filtre) ; 3 essais, une
' requete WMI pouvant echouer ponctuellement (faux negatif).
Function EdgeHomeBankExists(profile)

    Dim i
    EdgeHomeBankExists = False

    For i = 1 To 3
        EdgeHomeBankExists = EdgeHomeBankFound(profile)
        If EdgeHomeBankExists Then Exit Function
        WScript.Sleep 300
    Next

End Function


' Un seul passage de la recherche de la fenetre (voir EdgeHomeBankExists).
Function EdgeHomeBankFound(profile)

    Dim procs, p
    EdgeHomeBankFound = False
    On Error Resume Next
    Set procs = GetObject("winmgmts:\\.\root\cimv2").ExecQuery( _
        "SELECT CommandLine FROM Win32_Process WHERE Name = 'msedge.exe'")
    If Err.Number = 0 Then
        For Each p In procs
            If Not IsNull(p.CommandLine) Then
                If InStr(1, p.CommandLine, profile, vbTextCompare) > 0 Then
                    EdgeHomeBankFound = True
                    Exit For
                End If
            End If
        Next
    End If
    Err.Clear
    On Error GoTo 0

End Function


' =========================================================
' JOURNAL ET MESSAGES
' =========================================================

Sub StartLog()

    If fso.FileExists(launcherLog) Then
        If fso.GetFile(launcherLog).Size > LOG_MAX_BYTES Then fso.DeleteFile launcherLog
    End If
    Log "===== Lancement de HomeBank (" & rootDir & ") ====="

End Sub


Sub Log(message)

    Dim stream
    On Error Resume Next
    Set stream = fso.OpenTextFile(launcherLog, 8, True)
    stream.WriteLine FormatDateTime(Now, 2) & " " & FormatDateTime(Now, 3) & "  " & message
    stream.Close
    On Error GoTo 0

End Sub


Sub Fail(message)

    Log "ERREUR : " & Replace(message, vbCrLf, " ")
    MsgBox message, vbCritical, APP_TITLE

End Sub


Sub ShowWarning(message)

    MsgBox message, vbExclamation, APP_TITLE

End Sub


' Contenu d'un fichier texte ("" s'il est illisible).
Function ReadText(path)

    Dim stream
    ReadText = ""
    On Error Resume Next
    Set stream = fso.OpenTextFile(path, 1)
    ReadText = stream.ReadAll
    stream.Close
    Err.Clear
    On Error GoTo 0

End Function


' Dernieres lignes non vides d'un fichier (journal en cours d'ecriture accepte).
Function LastLines(path, count)

    Dim stream, content, lines, i, first
    LastLines = ""
    If Not fso.FileExists(path) Then Exit Function

    On Error Resume Next
    Set stream = fso.OpenTextFile(path, 1)
    content = stream.ReadAll
    stream.Close
    If Err.Number <> 0 Then
        Err.Clear
        On Error GoTo 0
        Exit Function
    End If
    On Error GoTo 0

    lines = Split(Replace(content, vbCrLf, vbLf), vbLf)
    first = UBound(lines) - count
    If first < 0 Then first = 0
    For i = first To UBound(lines)
        If Len(Trim(lines(i))) > 0 Then LastLines = LastLines & Left(lines(i), 160) & vbCrLf
    Next

End Function
