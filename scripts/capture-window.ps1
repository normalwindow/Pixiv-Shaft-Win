# 截取应用窗口区域，用来确认标题栏到底长什么样。
param([string]$Out = "$env:TEMP\pixshaft-window.png")

$src = @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class CAP {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int left, top, right, bottom; }
}
'@
Add-Type -TypeDefinition $src
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms

function Get-ShaftWindow {
  $script:found = [IntPtr]::Zero
  $cb = [CAP+EnumProc]{
    param($h, $l)
    $sb = New-Object System.Text.StringBuilder 512
    [void][CAP]::GetWindowTextW($h, $sb, 512)
    if ($sb.ToString() -eq "PixShaft-Win" -and [CAP]::IsWindowVisible($h)) { $script:found = $h; return $false }
    return $true
  }
  [void][CAP]::EnumWindows($cb, [IntPtr]::Zero)
  return $script:found
}

$hwnd = Get-ShaftWindow
if ($hwnd -eq [IntPtr]::Zero) { Write-Output "找不到可见窗口"; exit 1 }
[void][CAP]::SetForegroundWindow($hwnd)
Start-Sleep -Milliseconds 800
$r = New-Object CAP+RECT
[void][CAP]::GetWindowRect($hwnd, [ref]$r)
$w = $r.right - $r.left
$h = $r.bottom - $r.top
Write-Output ("窗口 ({0},{1}) {2}x{3}" -f $r.left, $r.top, $w, $h)

$bmp = New-Object System.Drawing.Bitmap($w, $h)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($r.left, $r.top, 0, 0, (New-Object System.Drawing.Size($w, $h)))
$g.Dispose()
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output ("已保存: " + $Out)
