# 找出光标下面到底是哪个窗口 —— 这决定了「真实鼠标为什么没有消息」。
$src = @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class WH {
  [DllImport("user32.dll")] public static extern IntPtr WindowFromPoint(POINT p);
  [DllImport("user32.dll")] public static extern IntPtr GetAncestor(IntPtr h, uint flags);
  [DllImport("user32.dll")] public static extern IntPtr GetParent(IntPtr h);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassNameW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool IsWindowEnabled(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int x, y; }
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int left, top, right, bottom; }
  public static string Describe(IntPtr h) {
    if (h == IntPtr.Zero) return "(null)";
    var t = new StringBuilder(512); GetWindowTextW(h, t, 512);
    var c = new StringBuilder(256); GetClassNameW(h, c, 256);
    RECT r; GetWindowRect(h, out r);
    uint pid; GetWindowThreadProcessId(h, out pid);
    return string.Format("hwnd=0x{0:X} pid={1} class={2} title='{3}' vis={4} enabled={5} rect=({6},{7}) {8}x{9}",
      h.ToInt64(), pid, c, t, IsWindowVisible(h), IsWindowEnabled(h),
      r.left, r.top, r.right - r.left, r.bottom - r.top);
  }
}
'@
Add-Type -TypeDefinition $src

$p = New-Object WH+POINT
[void][WH]::GetCursorPos([ref]$p)
Write-Output ("光标当前位置: ({0},{1})" -f $p.x, $p.y)

# 把光标放到「窗口内标题栏」，再问系统那个点上是哪个窗口
$target = New-Object WH+POINT
$target.x = 654; $target.y = 155
[void][WH]::SetCursorPos($target.x, $target.y)
Start-Sleep -Milliseconds 300
[void][WH]::GetCursorPos([ref]$p)
Write-Output ("移动后光标: ({0},{1})  (期望 654,155)" -f $p.x, $p.y)

$hit = [WH]::WindowFromPoint($target)
Write-Output ("`nWindowFromPoint(654,155):")
Write-Output ("  " + [WH]::Describe($hit))
$root = [WH]::GetAncestor($hit, 2)  # GA_ROOT
Write-Output ("  顶层祖先:")
Write-Output ("  " + [WH]::Describe($root))

# 顺便看看我们自己的窗口在不在
Write-Output "`n所有可见顶层窗口（前 15 个）:"
$src2 = @'
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class EN {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassNameW(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int left, top, right, bottom; }
  public static List<string> Top() {
    var l2 = new List<string>();
    EnumWindows((h, l) => {
      if (!IsWindowVisible(h)) return true;
      RECT r; GetWindowRect(h, out r);
      int w = r.right - r.left, ht = r.bottom - r.top;
      if (w < 50 || ht < 50) return true;
      var t = new StringBuilder(256); GetWindowTextW(h, t, 256);
      var c = new StringBuilder(128); GetClassNameW(h, c, 128);
      uint pid; GetWindowThreadProcessId(h, out pid);
      l2.Add(string.Format("0x{0:X} pid={1} {2} '{3}' ({4},{5}) {6}x{7}", h.ToInt64(), pid, c, t, r.left, r.top, w, ht));
      return true;
    }, IntPtr.Zero);
    return l2;
  }
}
'@
Add-Type -TypeDefinition $src2
[EN]::Top() | Select-Object -First 15 | ForEach-Object { Write-Output ("  " + $_) }
