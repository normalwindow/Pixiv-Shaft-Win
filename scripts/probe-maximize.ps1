$code = @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class D {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern IntPtr SendMessageW(IntPtr h, int msg, IntPtr wp, IntPtr lp);
  [DllImport("user32.dll")] public static extern IntPtr GetWindowLongPtrW(IntPtr h, int i);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int left, top, right, bottom; }
}
'@
Add-Type -TypeDefinition $code

function Get-ShaftWindow {
  $script:found = [IntPtr]::Zero
  $cb = [D+EnumProc]{
    param($h, $l)
    $sb = New-Object System.Text.StringBuilder 512
    [void][D]::GetWindowTextW($h, $sb, 512)
    if ($sb.ToString() -eq "PixShaft-Win") { $script:found = $h; return $false }
    return $true
  }
  [void][D]::EnumWindows($cb, [IntPtr]::Zero)
  return $script:found
}

function Show([string]$tag, $hwnd) {
  $r = New-Object D+RECT
  [void][D]::GetWindowRect($hwnd, [ref]$r)
  $style = [int64][D]::GetWindowLongPtrW($hwnd, -16)
  Write-Output ("{0,-12} rect=({1},{2}) {3}x{4} style=0x{5:X8} frameBits=0x{6:X}" -f `
    $tag, $r.left, $r.top, ($r.right - $r.left), ($r.bottom - $r.top), $style, ($style -band 0x00C70000))
}

$hwnd = Get-ShaftWindow
if ($hwnd -eq [IntPtr]::Zero) { Write-Output "window not found"; exit 1 }
[void][D]::SetForegroundWindow($hwnd)
Start-Sleep -Milliseconds 300
Show "floating" $hwnd

$WM_SYSCOMMAND = 0x0112
[void][D]::SendMessageW($hwnd, $WM_SYSCOMMAND, [IntPtr]0xF030, [IntPtr]::Zero)
Start-Sleep -Milliseconds 900
Show "maximized" $hwnd

[void][D]::SendMessageW($hwnd, $WM_SYSCOMMAND, [IntPtr]0xF120, [IntPtr]::Zero)
Start-Sleep -Milliseconds 900
Show "restored" $hwnd
