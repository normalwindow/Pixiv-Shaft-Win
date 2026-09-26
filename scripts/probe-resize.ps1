$code = @'
using System;
using System.Text;
using System.Runtime.InteropServices;
using System.Threading;

public static class DragHarness {
  public delegate IntPtr HookProc(int nCode, IntPtr wParam, IntPtr lParam);
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [StructLayout(LayoutKind.Sequential)] public struct MSLLHOOKSTRUCT { public int x; public int y; public uint mouseData; public uint flags; public uint time; public IntPtr extra; }

  [DllImport("user32.dll")] public static extern IntPtr SetWindowsHookExW(int id, HookProc fn, IntPtr mod, uint thread);
  [DllImport("user32.dll")] public static extern bool UnhookWindowsHookEx(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr CallNextHookEx(IntPtr h, int code, IntPtr wp, IntPtr lp);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, uint d, UIntPtr e);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);

  public static IntPtr Target = IntPtr.Zero;
  public static int StartX, StartY, Steps, StepX, StepY;
  public static bool Injected = false;
  public static HookProc Proc;

  public static IntPtr FindWindow(string title) {
    IntPtr found = IntPtr.Zero;
    EnumWindows((h, l) => {
      var sb = new StringBuilder(256);
      GetWindowTextW(h, sb, 256);
      if (sb.ToString() == title) { found = h; return false; }
      return true;
    }, IntPtr.Zero);
    return found;
  }

  public static IntPtr Run(int startX, int startY, int steps, int stepX, int stepY) {
    StartX = startX; StartY = startY; Steps = steps; StepX = stepX; StepY = stepY;
    Injected = false;
    Proc = (nCode, wParam, lParam) => {
      if (nCode >= 0 && !Injected) {
        Injected = true;
        SetCursorPos(StartX, StartY);
        Thread.Sleep(60);
        mouse_event(0x0002, 0, 0, 0, UIntPtr.Zero);   // LEFTDOWN
        Thread.Sleep(120);
        for (int i = 1; i <= Steps; i++) {
          SetCursorPos(StartX + i * StepX, StartY + i * StepY);
          Thread.Sleep(45);
        }
        mouse_event(0x0004, 0, 0, 0, UIntPtr.Zero);   // LEFTUP
      }
      return CallNextHookEx(IntPtr.Zero, nCode, wParam, lParam);
    };
    IntPtr hook = SetWindowsHookExW(14, Proc, IntPtr.Zero, 0);
    if (hook == IntPtr.Zero) return IntPtr.Zero;
    return hook;
  }
}
'@
Add-Type -TypeDefinition $code

param()
$code2 = @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class R {
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int left, top, right, bottom; }
}
'@
Add-Type -TypeDefinition $code2

$hwnd = [DragHarness]::FindWindow("PixShaft-Win")
if ($hwnd -eq [IntPtr]::Zero) { Write-Output "window not found"; exit 1 }
[void][R]::SetForegroundWindow($hwnd)
Start-Sleep -Milliseconds 500

$before = New-Object R+RECT
[void][R]::GetWindowRect($hwnd, [ref]$before)
$startX = $before.left + [int](($before.right - $before.left) * 0.45)
$startY = $before.top + 20
Write-Output ("before ({0},{1}) {2}x{3}" -f $before.left, $before.top, ($before.right - $before.left), ($before.bottom - $before.top))

$hook = [DragHarness]::Run($startX, $startY, 6, 20, 8)
if ($hook -eq [IntPtr]::Zero) { Write-Output "SetWindowsHookEx failed; real mouse can never reach the app"; exit 1 }

# 等用户在 6 秒内点一下鼠标（左键或右键都行），注入会被钩子拦下
$proc = New-Object System.Diagnostics.ProcessStartInfo
$proc.FileName = "powershell"
$proc.Arguments = "-NoProfile -Command `"Add-Type -AssemblyName System.Windows.Forms; Start-Sleep -Milliseconds 6000`""
$proc.UseShellExecute = $false
$p = [System.Diagnostics.Process]::Start($proc)
$p.WaitForExit()

[void][DragHarness]::UnhookWindowsHookEx($hook)
Start-Sleep -Milliseconds 700

$after = New-Object R+RECT
[void][R]::GetWindowRect($hwnd, [ref]$after)
Write-Output ("after  ({0},{1}) {2}x{3}" -f $after.left, $after.top, ($after.right - $after.left), ($after.bottom - $after.top))

$dw = $after.right - $after.left - ($before.right - $before.left)
$dh = $after.bottom - $after.top - ($before.bottom - $before.top)
if ($dw -ne 0 -or $dh -ne 0) { Write-Output ("RESIZE RESULT: size changed by ({0},{1}) -> OK" -f $dw, $dh) }
else { Write-Output "RESIZE RESULT: size unchanged" }
