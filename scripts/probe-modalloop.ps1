# 判定 DefWindowProc 的模态回路本身能不能起来。
# 用 PostMessage 发 WM_SYSCOMMAND/SC_MOVE（非阻塞），然后推光标 —— 不需要按住鼠标键。
param([string]$Action = "move")

$src = @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class MV {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool PostMessageW(IntPtr h, int msg, IntPtr wp, IntPtr lp);
  [DllImport("user32.dll")] public static extern IntPtr SendMessageW(IntPtr h, int msg, IntPtr wp, IntPtr lp);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int left, top, right, bottom; }
}
'@
Add-Type -TypeDefinition $src

function Get-ShaftWindow {
  $script:found = [IntPtr]::Zero
  $cb = [MV+EnumProc]{
    param($h, $l)
    $sb = New-Object System.Text.StringBuilder 512
    [void][MV]::GetWindowTextW($h, $sb, 512)
    if ($sb.ToString() -eq "PixShaft-Win" -and [MV]::IsWindowVisible($h)) { $script:found = $h; return $false }
    return $true
  }
  [void][MV]::EnumWindows($cb, [IntPtr]::Zero)
  return $script:found
}

$hwnd = Get-ShaftWindow
if ($hwnd -eq [IntPtr]::Zero) { Write-Output "找不到可见窗口"; exit 1 }
[void][MV]::SetForegroundWindow($hwnd)
Start-Sleep -Milliseconds 400
$r0 = New-Object MV+RECT
[void][MV]::GetWindowRect($hwnd, [ref]$r0)
Write-Output ("before ({0},{1}) {2}x{3}" -f $r0.left, $r0.top, ($r0.right - $r0.left), ($r0.bottom - $r0.top))

$gx = $r0.left + 200
$gy = $r0.top + 20
[void][MV]::SetCursorPos($gx, $gy)
Start-Sleep -Milliseconds 300

if ($Action -eq "move") {
  $WM_SYSCOMMAND = 0x0112
  $SC_MOVE = 0xF010
  # 0x0002 = MF_KEYBOARD（键盘移动，不需要鼠标键按住）；PostMessage 不阻塞
  [void][MV]::PostMessageW($hwnd, $WM_SYSCOMMAND, [IntPtr]($SC_MOVE -bor 2), [IntPtr]::Zero)
  Start-Sleep -Milliseconds 600
  # 模态回路会跟随光标；把光标推走
  for ($i = 1; $i -le 12; $i++) {
    [void][MV]::SetCursorPos(($gx + $i * 20), ($gy + $i * 10))
    Start-Sleep -Milliseconds 70
  }
  # 回车结束回路
  Add-Type -AssemblyName System.Windows.Forms
  [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
  Start-Sleep -Milliseconds 700
} else {
  $WM_SYSCOMMAND = 0x0112
  $SC_SIZE = 0xF000
  [void][MV]::PostMessageW($hwnd, $WM_SYSCOMMAND, [IntPtr]($SC_SIZE -bor 2), [IntPtr]::Zero)
  Start-Sleep -Milliseconds 600
  for ($i = 1; $i -le 12; $i++) {
    [void][MV]::SetCursorPos(($gx + $i * 20), ($gy + $i * 10))
    Start-Sleep -Milliseconds 70
  }
  Add-Type -AssemblyName System.Windows.Forms
  [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
  Start-Sleep -Milliseconds 700
}

$r1 = New-Object MV+RECT
[void][MV]::GetWindowRect($hwnd, [ref]$r1)
Write-Output ("after  ({0},{1}) {2}x{3}" -f $r1.left, $r1.top, ($r1.right - $r1.left), ($r1.bottom - $r1.top))
$dx = $r1.left - $r0.left
$dy = $r1.top - $r0.top
$dw = ($r1.right - $r1.left) - ($r0.right - $r0.left)
Write-Output ("delta pos=({0},{1}) width={2}" -f $dx, $dy, $dw)
if ($dx -ne 0 -or $dy -ne 0 -or $dw -ne 0) { Write-Output "MODAL-LOOP: OK" } else { Write-Output "MODAL-LOOP: 没动" }
