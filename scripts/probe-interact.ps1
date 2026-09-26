# 用「延迟注入鼠标按下」的方式驱动真实窗口：
# 后台进程等一会儿后按下左键，主线程此刻正把光标按在标题栏 / 窗口边缘上，
# 于是系统会像用户真的按住不放一样进入拖动 / 缩放模态回路，随后主线程移动光标即可拖动。
# 用法：powershell -File probe-interact.ps1 [drag|resize]
$Mode = if ($args.Count -gt 0) { $args[0] } else { "drag" }

$code = @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class M {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, uint d, UIntPtr e);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int left, top, right, bottom; }
}
'@
Add-Type -TypeDefinition $code

function Get-ShaftWindow {
  $script:found = [IntPtr]::Zero
  $cb = [M+EnumProc]{
    param($h, $l)
    $sb = New-Object System.Text.StringBuilder 512
    [void][M]::GetWindowTextW($h, $sb, 512)
    if ($sb.ToString() -eq "PixShaft-Win") { $script:found = $h; return $false }
    return $true
  }
  [void][M]::EnumWindows($cb, [IntPtr]::Zero)
  return $script:found
}

$hwnd = Get-ShaftWindow
if ($hwnd -eq [IntPtr]::Zero) { Write-Output "window not found"; exit 1 }
[void][M]::SetForegroundWindow($hwnd)
Start-Sleep -Milliseconds 500

$before = New-Object M+RECT
[void][M]::GetWindowRect($hwnd, [ref]$before)
$w = $before.right - $before.left
$h = $before.bottom - $before.top

if ($Mode -eq "drag") {
  $grabX = $before.left + [int]($w * 0.45)
  $grabY = $before.top + 20
  $moveX = 22; $moveY = 12; $steps = 8
} else {
  $grabX = $before.right - 3
  $grabY = $before.top + [int]($h / 2)
  $moveX = 16; $moveY = 0; $steps = 8
}
Write-Output ("mode={0} before=({1},{2}) {3}x{4} grab=({5},{6})" -f $Mode, $before.left, $before.top, $w, $h, $grabX, $grabY)

[void][M]::SetCursorPos($grabX, $grabY)
Start-Sleep -Milliseconds 300

$down = Start-Job -ScriptBlock {
  Add-Type -TypeDefinition @"
using System.Runtime.InteropServices;
public static class J {
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, uint d, UIntPtr e);
}
"@
  Start-Sleep -Milliseconds 800
  [J]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
}
Start-Sleep -Milliseconds 800

for ($i = 1; $i -le $steps; $i++) {
  [void][M]::SetCursorPos($grabX + $i * $moveX, $grabY + $i * $moveY)
  Start-Sleep -Milliseconds 70
}
[M]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 200
Wait-Job $down -Timeout 5 | Out-Null
Remove-Job $down -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 500

$after = New-Object M+RECT
[void][M]::GetWindowRect($hwnd, [ref]$after)
Write-Output ("mode={0} after =({1},{2}) {3}x{4}" -f $Mode, $after.left, $after.top, ($after.right - $after.left), ($after.bottom - $after.top))

$dx = $after.left - $before.left
$dy = $after.top - $before.top
$dw = ($after.right - $after.left) - $w
$dh = ($after.bottom - $after.top) - $h
if ($Mode -eq "drag") {
  if ($dx -ne 0 -or $dy -ne 0) { Write-Output "RESULT: dragged by ($dx,$dy) -> OK" }
  else { Write-Output "RESULT: window did NOT move -> DRAG BROKEN" }
} else {
  if ($dw -ne 0 -or $dh -ne 0) { Write-Output "RESULT: resized by ($dw,$dh) -> OK" }
  else { Write-Output "RESULT: size unchanged -> RESIZE BROKEN" }
}
