# Ramene au premier plan la fenetre HomeBank deja ouverte, y compris
# reduite dans la barre des taches : AppActivate (HomeBank.vbs) active une
# fenetre reduite sans la restaurer, et rien ne s'affichait alors.
# Lance par HomeBank.vbs (ExecutionPolicy Bypass, fenetre cachee).
param(
    # Profil Edge dedie a HomeBank : seules ses fenetres sont concernees.
    [Parameter(Mandatory = $true)][string]$Profil
)

Add-Type -Namespace HomeBank -Name Fenetre -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool IsIconic(System.IntPtr fenetre);
[DllImport("user32.dll")] public static extern bool ShowWindowAsync(System.IntPtr fenetre, int commande);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(System.IntPtr fenetre);
'@

$SW_RESTORE = 9

Get-CimInstance Win32_Process -Filter "Name='msedge.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine.Contains($Profil) } |
    ForEach-Object { (Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue).MainWindowHandle } |
    Where-Object { $_ -and $_ -ne [System.IntPtr]::Zero } |
    ForEach-Object {
        if ([HomeBank.Fenetre]::IsIconic($_)) { [void][HomeBank.Fenetre]::ShowWindowAsync($_, $SW_RESTORE) }
        [void][HomeBank.Fenetre]::SetForegroundWindow($_)
    }
