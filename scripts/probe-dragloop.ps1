# 决定性验证：Windows 到底会不会因为 HTCAPTION 而拖动窗口。
# 用 PostMessage 发 WM_NCLBUTTONDOWN(HTCAPTION)（不阻塞），然后推光标看窗口跟不跟。
$src = @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class DR {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr SendMessageW(IntPtr h, int msg, IntPtr wp, IntPtr lp);
  [DllImport("user32.dll")] public static extern bool PostMessageW(IntPtr h, int msg, IntPtr wp, IntPtr lp);
  [DllImport("user32.dll")] public static extern bool ReleaseCapture();
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int left, top, right, bottom; }
}
'@
Add-Type -TypeDefinition $src

function Get-Shaft {
  $script:f = [IntPtr]::Zero
  $cb = [DR+EnumProc]{
    param($h, $l)
    $sb = New-Object System.Text.StringBuilder 512
    [void][DR]::GetWindowTextW($h, $sb, 512)
    if ($sb.ToString() -eq "PixShaft-Win" -and [DR]::IsWindowVisible($h)) { $script:f = $h; return $false }
    return $true
  }
  [void][DR]::EnumWindows($cb, [IntPtr]::Zero)
  return $script:f
}

$hwnd = Get-Shaft
if ($hwnd -eq [IntPtr]::Zero) { Write-Output "找不到窗口"; exit 1 }
[void][DR]::SetForegroundWindow($hwnd)
Start-Sleep -Milliseconds 500

$r0 = New-Object DR+RECT
[void][DR]::GetWindowRect($hwnd, [ref]$r0)
Write-Output ("before ({0},{1}) {2}x{3}" -f $r0.left, $r0.top, ($r0.right - $r0.left), ($r0.bottom - $r0.top))

# 光标放到标题栏空白处（避开三大键，取左侧 1/3）
$gx = $r0.left + [int](($r0.right - $r0.left) * 0.25)
$gy = $r0.top + 17
[void][DR]::SetCursorPos($gx, $gy)
Start-Sleep -Milliseconds 400

$WM_NCHITTEST = 0x0084
$WM_NCLBUTTONDOWN = 0x00A1
function Pack($x, $y) { return [IntPtr]((($y -band 0xFFFF) -shl 16) -bor ($x -band 0xFFFF)) }

$hit = [int][DR]::SendMessageW($hwnd, $WM_NCHITTEST, [IntPtr]::Zero, (Pack $gx $gy))
Write-Output ("hit at ($gx,$gy) = {0} (2=HTCAPTION)" -f $hit)

# 关键一步：PostMessage 按下（WM_NCLBUTTONDOWN 会启动模态移动回路，SendMessage 会阻塞）
[void][DR]::PostMessageW($hwnd, $WM_NCLBUTTONDOWN, [IntPtr]2, (Pack $gx $gy))
Start-Sleep -Milliseconds 400

# 推光标：模态回路应当让窗口跟随
for ($i = 1; $i -le 10; $i++) {
  [void][DR]::SetCursorPos(($gx + $i * 30), ($gy + $i * 12))
  Start-Sleep -Milliseconds 90
}
[void][DR]::ReleaseCapture()
Start-Sleep -Milliseconds 600

$r1 = New-Object DR+RECT
[void][DR]::GetWindowRect($hwnd, [ref]$r1)
Write-Output ("after  ({0},{1}) {2}x{3}" -f $r1.left, $r1.top, ($r1.right - $r1.left), ($r1.bottom - $r1.top))
$dx = $r1.left - $r0.left
$dy = $r1.top - $r0.top
Write-Output ("delta=({0},{1})" -f $dx, $dy)
if ($dx -ne 0 -or $dy -ne 0) { Write-Output "DRAG-LOOP: OK —— 系统确实会拖动" } else { Write-Output "DRAG-LOOP: 没动" }
