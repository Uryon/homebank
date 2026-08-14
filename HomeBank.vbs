Option Explicit

Dim shell
Set shell = CreateObject("WScript.Shell")

Dim backendPid
Dim frontendPid
Dim edgeProfile
Dim jarPath

edgeProfile = "G:\git\homebank\.homebank-edge"
jarPath = "G:\git\homebank\backend\target\homebank-1.0.0.jar"


' =========================================================
' POSTGRESQL
' =========================================================

shell.Run "cmd /c net start postgresql-x64-18 >nul 2>&1", 0, False


' =========================================================
' BACKEND SPRING BOOT
' =========================================================

backendPid = StartHiddenProcess( _
    "java.exe", _
    "-jar """ & jarPath & """" _
)

' Attendre que Spring Boot soit disponible
WaitForPort "localhost", 8080


' =========================================================
' FRONTEND VUE / VITE
' =========================================================

frontendPid = StartHiddenProcess( _
    "cmd.exe", _
    "/c cd /d G:\git\homebank\frontend && npm run dev" _
)

' Attendre que Vite soit disponible
WaitForPort "localhost", 5173


' =========================================================
' LANCER HOME BANK
' =========================================================

shell.Run _
    "msedge.exe --app=""http://localhost:5173"" --user-data-dir=""" & edgeProfile & """ --no-first-run", _
    1, _
    False


' =========================================================
' ATTENDRE LA FERMETURE DE HOME BANK
' =========================================================

Do While EdgeHomeBankExists(edgeProfile)
    WScript.Sleep 1000
Loop


' =========================================================
' FERMER FRONTEND
' =========================================================

If frontendPid <> "" Then
    shell.Run _
        "cmd /c taskkill /PID " & frontendPid & " /T /F >nul 2>&1", _
        0, _
        True
End If


' =========================================================
' FERMER BACKEND
' =========================================================

If backendPid <> "" Then
    shell.Run _
        "cmd /c taskkill /PID " & backendPid & " /T /F >nul 2>&1", _
        0, _
        True
End If


' =========================================================
' FERMER POSTGRESQL
' =========================================================

shell.Run _
    "cmd /c net stop postgresql-x64-18 >nul 2>&1", _
    0, _
    True


Set shell = Nothing


' =========================================================
' LANCER UN PROCESSUS CACHE
' =========================================================

Function StartHiddenProcess(program, arguments)

    Dim ps
    Dim exec

    ps = "powershell.exe -NoProfile -WindowStyle Hidden -Command " & _
         Chr(34) & _
         "$p=Start-Process '" & program & "' -ArgumentList '" & _
         Replace(arguments, "'", "''") & _
         "' -WindowStyle Hidden -PassThru; $p.Id" & _
         Chr(34)

    Set exec = shell.Exec(ps)

    StartHiddenProcess = Trim(exec.StdOut.ReadAll)

    Set exec = Nothing

End Function


' =========================================================
' ATTENDRE QU'UN PORT SOIT DISPONIBLE
' =========================================================

Sub WaitForPort(host, port)

    Dim result

    Do
        result = shell.Run( _
            "powershell.exe -NoProfile -Command " & _
            Chr(34) & _
            "if ((Test-NetConnection -ComputerName " & host & _
            " -Port " & port & " -InformationLevel Quiet)) " & _
            "{ exit 0 } else { exit 1 }" & _
            Chr(34), _
            0, _
            True _
        )

        If result <> 0 Then
            WScript.Sleep 500
        End If

    Loop While result <> 0

End Sub


' =========================================================
' VERIFIER SI HOME BANK EST ENCORE OUVERTE
' =========================================================

Function EdgeHomeBankExists(profile)

    Dim exec
    Dim command

    command = "powershell.exe -NoProfile -Command " & _
              Chr(34) & _
              "$p=Get-CimInstance Win32_Process -Filter ""Name='msedge.exe'"" | " & _
              "Where-Object {$_.CommandLine -like '*" & profile & "*'}; " & _
              "if ($p) {exit 0} else {exit 1}" & _
              Chr(34)

    Set exec = shell.Exec(command)

    exec.StdOut.ReadAll

    EdgeHomeBankExists = (exec.ExitCode = 0)

    Set exec = Nothing

End Function