$code = @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class I {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint flags, int dx, int dy, uint data, UIntPtr extra);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int left, top, right, bottom; }
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int x, y; }
  public const uint LEFTDOWN = 0x0002;
  public const uint LEFTUP = 0x0004;
}
'@
Add-Type -TypeDefinition $code

function Get-ShaftWindow {
  $script:found = [IntPtr]::Zero
  $cb = [I+EnumProc]{
    param($h, $l)
    $sb = New-Object System.Text.StringBuilder 512
    [void][I]::GetWindowTextW($h, $sb, 512)
    if ($sb.ToString() -eq "PixShaft-Win") { $script:found = $h; return $false }
    return $true
  }
  [void][I]::EnumWindows($cb, [IntPtr]::Zero)
  return $script:found
}

$hwnd = Get-ShaftWindow
if ($hwnd -eq [IntPtr]::Zero) { Write-Output "window not found"; exit 1 }
[void][I]::SetForegroundWindow($hwnd)
Start-Sleep -Milliseconds 400
$r = New-Object I+RECT
[void][I]::GetWindowRect($hwnd, [ref]$r)

# 在标题栏中部按下 -> 拖 120px -> 松开
$startX = $r.left + [int](($r.right - $r.left) * 0.4)
$startY = $r.top + 20
[void][I]::SetCursorPos($startX, $startY)
Start-Sleep -Milliseconds 250
$before = New-Object I+RECT
[void][I]::GetWindowRect($hwnd, [ref]$before)
Write-Output ("before  ({0},{1}) {2}x{3}  cursor=({4},{5})" -f $before.left, $before.top, ($before.right-$before.left), ($before.bottom-$before.top), $startX, $startY)

[I]::mouse_event([I]::LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 150
for ($i = 1; $i -le 6; $i++) {
  [void][I]::SetCursorPos($startX + ($i * 20), $startY + ($i * 8))
  Start-Sleep -Milliseconds 60
}
[I]::mouse_event([I]::LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 400

$after = New-Object I+RECT
[void][I]::GetWindowRect($hwnd, [ref]$after)
Write-Output ("after   ({0},{1}) {2}x{3}" -f $after.left, $after.top, ($after.right-$after.left), ($after.bottom-$after.top))
$dx = $after.left - $before.left; $dy = $after.top - $before.top
if ($dx -ne 0 -or $dy -ne 0) { Write-Output ("RESULT: dragged by ({0},{1}) -> OK" -f $dx, $dy) }
else { Write-Output "RESULT: window did NOT move -> title-bar drag is broken" }
