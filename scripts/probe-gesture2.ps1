# 对正在运行的 PixShaft-Win 做端到端拖动 / 缩放验证。
# 直接给窗口发 Win32 消息（WM_NCHITTEST -> WM_NCLBUTTONDOWN -> WM_MOUSEMOVE -> WM_LBUTTONUP），
# 走的是应用真实的窗口过程，只是不经过真实鼠标。
$code = @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class G {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern IntPtr SendMessageW(IntPtr h, int msg, IntPtr wp, IntPtr lp);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern int GetDpiForWindow(IntPtr h);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int left, top, right, bottom; }
}
'@
Add-Type -TypeDefinition $code

function Get-ShaftWindow {
  $script:found = [IntPtr]::Zero
  $cb = [G+EnumProc]{
    param($h, $l)
    $sb = New-Object System.Text.StringBuilder 512
    [void][G]::GetWindowTextW($h, $sb, 512)
    if ($sb.ToString() -eq "PixShaft-Win") { $script:found = $h; return $false }
    return $true
  }
  [void][G]::EnumWindows($cb, [IntPtr]::Zero)
  return $script:found
}
function Pack([int]$x, [int]$y) { return [IntPtr]((($y -band 0xFFFF) -shl 16) -bor ($x -band 0xFFFF)) }
function Rect($hwnd) { $r = New-Object G+RECT; [void][G]::GetWindowRect($hwnd, [ref]$r); return $r }

$hwnd = Get-ShaftWindow
if ($hwnd -eq [IntPtr]::Zero) { Write-Output "window not found"; exit 1 }
[void][G]::SetForegroundWindow($hwnd)
Start-Sleep -Milliseconds 400

$WM_NCHITTEST = 0x0084
$WM_NCLBUTTONDOWN = 0x00A1
$WM_MOUSEMOVE = 0x0200
$WM_LBUTTONUP = 0x0202
$MK_LBUTTON = 1

$r0 = Rect $hwnd
$w = $r0.right - $r0.left
$h = $r0.bottom - $r0.top
# Win32 一律物理像素；AWT 的 bounds 是逻辑像素，差一个 DPI 比例。
$scale = 1.0   # GetWindowRect 与鼠标消息同一坐标系，1:1
Write-Output ("start  ({0},{1}) {2}x{3} scale={4}" -f $r0.left, $r0.top, $w, $h, $scale)

$grabX = $r0.left + [int](40 * $scale)
$grabY = $r0.top + [int](24 * $scale)
$hit = [int][G]::SendMessageW($hwnd, $WM_NCHITTEST, [IntPtr]::Zero, (Pack $grabX $grabY))
Write-Output ("titlebar hitcode={0} (2=HTCAPTION)" -f $hit)
[void][G]::SendMessageW($hwnd, $WM_NCLBUTTONDOWN, [IntPtr]2, (Pack $grabX $grabY))
$dx = [int](120 * $scale)
$dy = [int](60 * $scale)
$cx = [int](40 * $scale)
$cy = [int](24 * $scale)
for ($i = 1; $i -le 6; $i++) {
  [void][G]::SendMessageW($hwnd, $WM_MOUSEMOVE, [IntPtr]$MK_LBUTTON, (Pack ($cx + [int]($dx * $i / 6)) ($cy + [int]($dy * $i / 6))))
}
[void][G]::SendMessageW($hwnd, $WM_LBUTTONUP, [IntPtr]::Zero, (Pack ($cx + $dx) ($cy + $dy)))
Start-Sleep -Milliseconds 600
$r1 = Rect $hwnd
$mx = $r1.left - $r0.left
$my = $r1.top - $r0.top
Write-Output ("after drag ({0},{1})  delta=({2},{3}) want=({4},{5})" -f $r1.left, $r1.top, $mx, $my, $dx, $dy)
if ([Math]::Abs($mx - $dx) -le 2 -and [Math]::Abs($my - $dy) -le 2) { Write-Output "DRAG: OK" } else { Write-Output "DRAG: BROKEN" }

$rw = $r1.right - $r1.left
$rh = $r1.bottom - $r1.top
$edgeX = $r1.right - 1
$edgeY = $r1.top + [int]($rh / 2)
$hit2 = [int][G]::SendMessageW($hwnd, $WM_NCHITTEST, [IntPtr]::Zero, (Pack $edgeX $edgeY))
Write-Output ("edge hitcode={0} (11=HTRIGHT)" -f $hit2)
[void][G]::SendMessageW($hwnd, $WM_NCLBUTTONDOWN, [IntPtr]$hit2, (Pack $edgeX $edgeY))
$grow = [int](90 * $scale)
$clientX = $rw - 1
$clientY = [int]($rh / 2)
for ($i = 1; $i -le 5; $i++) {
  [void][G]::SendMessageW($hwnd, $WM_MOUSEMOVE, [IntPtr]$MK_LBUTTON, (Pack ($clientX + [int]($grow * $i / 5)) $clientY))
}
[void][G]::SendMessageW($hwnd, $WM_LBUTTONUP, [IntPtr]::Zero, (Pack ($clientX + $grow) $clientY))
Start-Sleep -Milliseconds 600
$r2 = Rect $hwnd
$dw = ($r2.right - $r2.left) - $rw
$dh = ($r2.bottom - $r2.top) - $rh
Write-Output ("after resize {0}x{1}  delta=({2},{3}) want=({4},0)" -f ($r2.right - $r2.left), ($r2.bottom - $r2.top), $dw, $dh, $grow)
if ([Math]::Abs($dw - $grow) -le 2 -and $dh -eq 0) { Write-Output "RESIZE: OK" } else { Write-Output "RESIZE: BROKEN" }
