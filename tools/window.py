"""Capture or gracefully close only a development Java window identified by its launch report."""
import argparse
import ctypes
from ctypes import wintypes
import json
from pathlib import Path
import time

parser = argparse.ArgumentParser()
parser.add_argument("launch", type=Path)
parser.add_argument("--capture", type=Path)
parser.add_argument("--close", action="store_true")
parser.add_argument("--click", nargs=2, type=int, metavar=("X", "Y"))
parser.add_argument("--use", action="store_true", help="Right-click the focused development game at its current crosshair")
args = parser.parse_args()
pid = json.loads(args.launch.read_text())["pid"]
user = ctypes.WinDLL("user32", use_last_error=True)
user.SetProcessDPIAware()
windows = []
callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
user.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
user.IsWindowVisible.argtypes = [wintypes.HWND]
user.SetForegroundWindow.argtypes = [wintypes.HWND]
user.GetClientRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT)]
user.ClientToScreen.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.POINT)]
user.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]

@callback_type
def visit(hwnd, unused):
    owner = wintypes.DWORD()
    user.GetWindowThreadProcessId(hwnd, ctypes.byref(owner))
    if owner.value == pid and user.IsWindowVisible(hwnd):
        windows.append(hwnd)
    return True

user.EnumWindows(visit, 0)
if not windows:
    raise SystemExit(f"No visible window for launch PID {pid}")
window = windows[0]
if args.use:
    user.SetForegroundWindow(window)
    time.sleep(.2)
    user.mouse_event(0x0008, 0, 0, 0, 0)
    time.sleep(.1)
    user.mouse_event(0x0010, 0, 0, 0, 0)
    time.sleep(.2)
if args.click:
    user.SetForegroundWindow(window)
    rect, point = wintypes.RECT(), wintypes.POINT()
    user.GetClientRect(window, ctypes.byref(rect))
    user.ClientToScreen(window, ctypes.byref(point))
    x, y = args.click
    if not (0 <= x < rect.right and 0 <= y < rect.bottom):
        raise SystemExit("Click is outside the development window")
    user.SetCursorPos(point.x + x, point.y + y)
    time.sleep(.15)
    user.mouse_event(0x0002, 0, 0, 0, 0)
    time.sleep(.1)
    user.mouse_event(0x0004, 0, 0, 0, 0)
    time.sleep(.2)
if args.capture:
    from PIL import ImageGrab
    user.SetForegroundWindow(window)
    time.sleep(.3)
    rect, point = wintypes.RECT(), wintypes.POINT()
    user.GetClientRect(window, ctypes.byref(rect))
    user.ClientToScreen(window, ctypes.byref(point))
    args.capture.parent.mkdir(parents=True, exist_ok=True)
    ImageGrab.grab((point.x, point.y, point.x + rect.right, point.y + rect.bottom), all_screens=True).save(args.capture)
    print(args.capture)
if args.close:
    user.PostMessageW(window, 0x0010, 0, 0)
    print(f"Requested normal close for PID {pid}")
