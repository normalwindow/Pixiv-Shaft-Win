# 列出指定 java 进程的所有顶层窗口（不按标题过滤），用来判断应用窗口到底有没有出现。
$src = @'
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class LW {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassNameW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int left, top, right, bottom; }
  public static List<string> All() {
    var list = new List<string>();
    EnumWindows((h, l) => {
      uint pid; GetWindowThreadProcessId(h, out pid);
      var t = new StringBuilder(512); GetWindowTextW(h, t, 512);
      var c = new StringBuilder(256); GetClassNameW(h, c, 256);
      RECT r; GetWindowRect(h, out r);
      int w = r.right - r.left, ht = r.bottom - r.top;
      if (w <= 0 || ht <= 0) return true;
      list.Add(string.Format("pid={0} vis={1} class={2} title='{3}' ({4},{5}) {6}x{7}",
        pid, IsWindowVisible(h), c, t, r.left, r.top, w, ht));
      return true;
    }, IntPtr.Zero);
    return list;
  }
}
'@
Add-Type -TypeDefinition $src
$pids = (Get-Process java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)
if (-not $pids) { Write-Output "没有 java 进程"; exit }
Write-Output ("java pids: " + ($pids -join ", "))
[LW]::All() | Where-Object { $_ -match ("pid=(" + (($pids | ForEach-Object { [string]$_ }) -join "|") + ") ") } | ForEach-Object { Write-Output $_ }
