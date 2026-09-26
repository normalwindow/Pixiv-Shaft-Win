# Mouse probe: injects real mouse events into the PixShaft-Win window.
# Usage:
#   pwsh -File scripts/click-probe.ps1 -Action move   -X 283 -Y 450
#   pwsh -File scripts/click-probe.ps1 -Action click  -X 283 -Y 450
#   pwsh -File scripts/click-probe.ps1 -Action middle -X 600 -Y 400
#   pwsh -File scripts/click-probe.ps1 -Action right  -X 600 -Y 400
#   pwsh -File scripts/click-probe.ps1 -Action x1     -X 600 -Y 400
# Coordinates are absolute screen pixels (same space capture-window.ps1 prints).
param(
    [Parameter(Mandatory = $true)][ValidateSet("move", "click", "middle", "right", "x1", "x2")][string]$Action,
    [int]$X = 0,
    [int]$Y = 0,
    [string]$Out = "",
    [int]$PostDelayMs = 450,
    [switch]$Foreground
)

$src = @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class MP {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, uint d, UIntPtr e);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int left, top, right, bottom; }
}
'@
Add-Type -TypeDefinition $src

function Get-ShaftWindow {
    $script:found = [IntPtr]::Zero
    $cb = [MP+EnumProc] {
        param($h, $l)
        $sb = New-Object System.Text.StringBuilder 512
        [void][MP]::GetWindowTextW($h, $sb, 512)
        if ($sb.ToString() -eq "PixShaft-Win" -and [MP]::IsWindowVisible($h)) { $script:found = $h; return $false }
        return $true
    }
    [void][MP]::EnumWindows($cb, [IntPtr]::Zero)
    return $script:found
}

$hwnd = Get-ShaftWindow
if ($hwnd -eq [IntPtr]::Zero) { Write-Output "PixShaft-Win window not found"; exit 1 }
if ($Foreground) {
    [void][MP]::SetForegroundWindow($hwnd)
    Start-Sleep -Milliseconds 400
}

# MOUSEEVENTF_*: MOVE=0x0001 LEFTDOWN=0x0002 LEFTUP=0x0004 RIGHTDOWN=0x0008 RIGHTUP=0x0010
# MIDDLEDOWN=0x0020 MIDDLEUP=0x0040 XDOWN=0x0080 XUP=0x0100
switch ($Action) {
    "move" {
        # 先落到附近再挪进来：SetCursorPos 到同一个点可能不产生新的移动消息，
        # Compose 的 hover（Enter/Exit）就收不到，看起来像「悬停没反应」。
        [void][MP]::SetCursorPos($X - 60, $Y - 40); Start-Sleep -Milliseconds 200
        [void][MP]::SetCursorPos($X - 2, $Y - 1); Start-Sleep -Milliseconds 200
        [void][MP]::SetCursorPos($X, $Y); Start-Sleep -Milliseconds 500
    }
    "click" {
        [void][MP]::SetCursorPos($X, $Y); Start-Sleep -Milliseconds 200
        [MP]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 70
        [MP]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
    }
    "middle" {
        [void][MP]::SetCursorPos($X, $Y); Start-Sleep -Milliseconds 200
        [MP]::mouse_event(0x0020, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 70
        [MP]::mouse_event(0x0040, 0, 0, 0, [UIntPtr]::Zero)
    }
    "right" {
        [void][MP]::SetCursorPos($X, $Y); Start-Sleep -Milliseconds 200
        [MP]::mouse_event(0x0008, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 70
        [MP]::mouse_event(0x0010, 0, 0, 0, [UIntPtr]::Zero)
    }
    "x1" {
        [void][MP]::SetCursorPos($X, $Y); Start-Sleep -Milliseconds 200
        [MP]::mouse_event(0x0080, 0, 0, 1, [UIntPtr]::Zero); Start-Sleep -Milliseconds 70
        [MP]::mouse_event(0x0100, 0, 0, 1, [UIntPtr]::Zero)
    }
    "x2" {
        [void][MP]::SetCursorPos($X, $Y); Start-Sleep -Milliseconds 200
        [MP]::mouse_event(0x0080, 0, 0, 2, [UIntPtr]::Zero); Start-Sleep -Milliseconds 70
        [MP]::mouse_event(0x0100, 0, 0, 2, [UIntPtr]::Zero)
    }
}
Start-Sleep -Milliseconds $PostDelayMs
Write-Output ("action={0} at ({1},{2})" -f $Action, $X, $Y)

if ($Out -ne "") {
    Add-Type -AssemblyName System.Drawing
    $r = New-Object MP+RECT
    [void][MP]::GetWindowRect($hwnd, [ref]$r)
    $w = $r.right - $r.left
    $h = $r.bottom - $r.top
    $bmp = New-Object System.Drawing.Bitmap($w, $h)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($r.left, $r.top, 0, 0, (New-Object System.Drawing.Size($w, $h)))
    $g.Dispose()
    $bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output ("saved: " + $Out)
}
