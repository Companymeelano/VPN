/**
 * System proxy on Windows, without native modules: a PowerShell one-shot writes the WinINET
 * per-user settings and broadcasts InternetSetOption so running apps pick the change up
 * immediately (the broadcast is the part everyone forgets — without it browsers keep the old
 * proxy until reboot).
 *
 * Bypass list is Iran-shaped by design: loopback, .ir domains and the LAN stay direct, so
 * domestic sites never ride the tunnel («کاملاً متناسب با ایران»).
 */

import { spawn } from "node:child_process";

const PS = (body: string) => `
$ErrorActionPreference = "Stop"
${body}
$signature = @'
using System;
using System.Runtime.InteropServices;
public class WinInet {
  [DllImport("wininet.dll", SetLastError = true)]
  public static extern bool InternetSetOption(IntPtr h, int o, IntPtr b, int l);
}
'@
Add-Type -TypeDefinition $signature -ErrorAction SilentlyContinue
[WinInet]::InternetSetOption(0, 39, 0, 0) | Out-Null
[WinInet]::InternetSetOption(0, 37, 0, 0) | Out-Null
`;

const SET_ON = PS(`
$k = "HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
Set-ItemProperty -Path $k -Name ProxyEnable -Value 1 -Type DWord
Set-ItemProperty -Path $k -Name ProxyServer -Value "http=127.0.0.1:10808;https=127.0.0.1:10808;socks=127.0.0.1:10808"
Set-ItemProperty -Path $k -Name ProxyOverride -Value "<-loopback>;localhost;127.*;10.*;192.168.*;172.16.*;*.ir;<local>"
Set-ItemProperty -Path $k -Name AutoConfigURL -Value "" -Type String
`);

const SET_OFF = PS(`
$k = "HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
Set-ItemProperty -Path $k -Name ProxyEnable -Value 0 -Type DWord
Remove-ItemProperty -Path $k -Name AutoConfigURL -ErrorAction SilentlyContinue
`);

function run(script: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn("powershell.exe", ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script], {
      windowsHide: true,
      stdio: "ignore",
    });
    child.on("error", reject);
    child.on("exit", (code) => (code === 0 ? resolve() : reject(new Error(`powershell exited ${code}`))));
  });
}

export async function setSystemProxy(enabled: boolean): Promise<void> {
  await run(enabled ? SET_ON : SET_OFF);
}
