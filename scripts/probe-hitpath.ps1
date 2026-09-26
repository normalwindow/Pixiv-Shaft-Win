# 统计窗口过程收到的消息种类，判断「窗口过程到底有没有在工作」。
param([string]$Seconds = "6")

$src = @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class HT {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr SendMessageW(IntPtr h, int msg, IntPtr wp, IntPtr lp);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int left, top, right, bottom; }
}
'@
Add-Type -TypeDefinition $src

function Get-ShaftWindow {
  $script:found = [IntPtr]::Zero
  $cb = [HT+EnumProc]{
    param($h, $l)
    $sb = New-Object System.Text.StringBuilder 512
    [void][HT]::GetWindowTextW($h, $sb, 512)
    if ($sb.ToString() -eq "PixShaft-Win" -and [HT]::IsWindowVisible($h)) { $script:found = $h; return $false }
    return $true
  }
  [void][HT]::EnumWindows($cb, [IntPtr]::Zero)
  return $script:found
}

$hwnd = Get-ShaftWindow
if ($hwnd -eq [IntPtr]::Zero) { Write-Output "找不到可见窗口"; exit 1 }
$r = New-Object HT+RECT
[void][HT]::GetWindowRect($hwnd, [ref]$r)
Write-Output ("hwnd=0x{0:X} rect=({1},{2}) {3}x{4}" -f $hwnd.ToInt64(), $r.left, $r.top, ($r.right-$r.left), ($r.bottom-$r.top))

$WM_NCHITTEST = 0x0084
$log = Join-Path $env:APPDATA "PixShaft\wndproc.log"
$before = if (Test-Path $log) { (Get-Content $log).Count } else { 0 }

function Pack($x, $y) { return [IntPtr]((($y -band 0xFFFF) -shl 16) -bor ($x -band 0xFFFF)) }

# 标题栏中心
$tx = $r.left + 400
$ty = $r.top + 17
$code = [HT]::SendMessageW($hwnd, $WM_NCHITTEST, [IntPtr]::Zero, (Pack $tx $ty))
Write-Output ("titlebar($tx,$ty) -> {0}" -f [int]$code)

# 右边缘
$ex = $r.right - 2
$ey = $r.top + 300
$code2 = [HT]::SendMessageW($hwnd, $WM_NCHITTEST, [IntPtr]::Zero, (Pack $ex $ey))
Write-Output ("rightedge($ex,$ey) -> {0}" -f [int]$code2)

# 客户区中心
$code3 = [HT]::SendMessageW($hwnd, $WM_NCHITTEST, [IntPtr]::Zero, (Pack ($r.left + 600) ($r.top + 400)))
Write-Output ("client -> {0}" -f [int]$code3)

Start-Sleep -Milliseconds 300
$after = if (Test-Path $log) { (Get-Content $log).Count } else { 0 }
Write-Output ("wndproc.log 行数: {0} -> {1}" -f $before, $after)
if ($after -gt $before) {
  Write-Output "窗口过程已收到我们发的 WM_NCHITTEST："
  Get-Content $log | Select-Object -Last ($after - $before)
} else {
  Write-Output "窗口过程**没有**收到 WM_NCHITTEST —— 说明我们的窗口过程根本没被调用！"
}

# 把光标真实移到标题栏上停 3 秒，看系统是否自己发 WM_NCHITTEST（真实鼠标路径）
Write-Output ("`n把光标移到标题栏中心，停留 {0} 秒..." -f $Seconds)
[void][HT]::SetCursorPos($tx, $ty)
Start-Sleep -Seconds ([int]$Seconds)
$after2 = if (Test-Path $log) { (Get-Content $log).Count } else { 0 }
Write-Output ("真实鼠标移动后 wndproc.log 行数: {0} -> {1}" -f $after, $after2)
if ($after2 -gt $after) {
  Get-Content $log | Select-Object -Last ($after2 - $after) | Select-Object -First 12
}
