# 复现崩溃：给运行中的窗口发一条系统命令，看进程是否还活着。
# 用法: powershell -File scripts\probe-syscommand.ps1 [maximize|restore|minimize]
param([string]$Action = "maximize")

$src = @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class SC {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern IntPtr SendMessageW(IntPtr h, int msg, IntPtr wp, IntPtr lp);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int left, top, right, bottom; }
}
'@
Add-Type -TypeDefinition $src

function Get-ShaftWindow {
  $script:found = [IntPtr]::Zero
  $cb = [SC+EnumProc]{
    param($h, $l)
    $sb = New-Object System.Text.StringBuilder 512
    [void][SC]::GetWindowTextW($h, $sb, 512)
    if ($sb.ToString() -eq "PixShaft-Win" -and [SC]::IsWindowVisible($h)) { $script:found = $h; return $false }
    return $true
  }
  [void][SC]::EnumWindows($cb, [IntPtr]::Zero)
  return $script:found
}
function Show-Rect($hwnd, $tag) {
  $r = New-Object SC+RECT
  [void][SC]::GetWindowRect($hwnd, [ref]$r)
  Write-Output ("{0} ({1},{2}) {3}x{4}" -f $tag, $r.left, $r.top, ($r.right - $r.left), ($r.bottom - $r.top))
}

$hwnd = Get-ShaftWindow
if ($hwnd -eq [IntPtr]::Zero) { Write-Output "找不到可见的 PixShaft-Win 窗口"; exit 1 }
Write-Output ("hwnd=0x{0:X}" -f $hwnd.ToInt64())
Show-Rect $hwnd "before"

$SC = @{ minimize = 0xF020; maximize = 0xF030; restore = 0xF120 }[$Action]
$WM_SYSCOMMAND = 0x0112
[void][SC]::SendMessageW($hwnd, $WM_SYSCOMMAND, [IntPtr]$SC, [IntPtr]::Zero)
Start-Sleep -Milliseconds 1200

$still = Get-ShaftWindow
if ($still -eq [IntPtr]::Zero) {
  Write-Output "after : 窗口消失（进程可能崩了或窗口被销毁）"
} else {
  Show-Rect $still "after "
}
