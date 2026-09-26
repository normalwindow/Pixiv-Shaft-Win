# 对正在运行的 PixShaft-Win 窗口做非侵入式 Win32 探测：
# 1) 窗口样式（无边框窗口 = WS_POPUP + 边框位）
# 2) WM_NCHITTEST 返回什么 —— 验证原生命中测试是否真的接管了拖动 / 缩放热区
$src = @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class W {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern IntPtr GetWindowLongPtrW(IntPtr h, int i);
  [DllImport("user32.dll")] public static extern IntPtr SendMessageW(IntPtr h, int msg, IntPtr wp, IntPtr lp);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int left, top, right, bottom; }
}
'@
Add-Type -TypeDefinition $src

function Find-ShaftWindow {
  $found = [IntPtr]::Zero
  $cb = [W+EnumProc]{
    param($h, $l)
    $sb = New-Object System.Text.StringBuilder 512
    [void][W]::GetWindowTextW($h, $sb, 512)
    if ($sb.ToString() -eq "PixShaft-Win") { $script:found = $h; return $false }
    return $true
  }
  [void][W]::EnumWindows($cb, [IntPtr]::Zero)
  return $script:found
}

$hwnd = Find-ShaftWindow
if ($hwnd -eq [IntPtr]::Zero) { Write-Output "window not found"; exit 1 }
$style = [int64][W]::GetWindowLongPtrW($hwnd, -16)
Write-Output ("hwnd=0x{0:X} style=0x{1:X8}" -f [int64]$hwnd, $style)

$r = New-Object W+RECT
[void][W]::GetWindowRect($hwnd, [ref]$r)
$w = $r.right - $r.left; $h = $r.bottom - $r.top
Write-Output ("rect=({0},{1}) {2}x{3}" -f $r.left, $r.top, $w, $h)

function Hit([int]$x, [int]$y) {
  $lp = [IntPtr]((($y -band 0xFFFF) -shl 16) -bor ($x -band 0xFFFF))
  return [int][W]::SendMessageW($hwnd, 0x0084, [IntPtr]::Zero, $lp)
}
Write-Output "codes: HTCLIENT=1 HTCAPTION=2 HTLEFT=10 HTRIGHT=11 HTTOP=12 HTTOPLEFT=13 HTTOPRIGHT=14 HTBOTTOM=15 HTBOTTOMRIGHT=17"
Write-Output ("top-left corner    : " + (Hit ($r.left+1)            ($r.top+1)))
Write-Output ("top edge middle    : " + (Hit ($r.left+[int]($w/2))  ($r.top+1)))
Write-Output ("top-right corner   : " + (Hit ($r.right-2)          ($r.top+1)))
Write-Output ("left edge middle   : " + (Hit ($r.left+1)           ($r.top+[int]($h/2))))
Write-Output ("bottom-right       : " + (Hit ($r.right-2)          ($r.bottom-2)))
Write-Output ("title bar middle   : " + (Hit ($r.left+[int]($w/2))  ($r.top+20)))
Write-Output ("title bar right 2/3: " + (Hit ($r.left+[int]($w*2/3)) ($r.top+20)))
Write-Output ("caption button area: " + (Hit ($r.left+$w-70)       ($r.top+20)))
Write-Output ("client center      : " + (Hit ($r.left+[int]($w/2))  ($r.top+[int]($h/2)+80)))
