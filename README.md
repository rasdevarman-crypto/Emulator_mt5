# MT5 RAS Runner

Android launcher scaffold for a future Wine/Box64-based MetaTrader 5 runtime.

## Current scope
- Native Android launcher UI in Bengali
- Official MT5 download page shortcut
- Installer file picker (selects a file; does not install it)
- Diagnostic log export and keep-screen-on preference
- GitHub Actions workflow to build a debug APK and upload it as an artifact

## Important limitation
This is not yet a complete Windows MT5 emulator. Wine/Box64 Android/ARM64 integration, x86-64 translation, graphics/input support, runtime prefix management, and device testing are not implemented. Do not treat this scaffold as able to run the Windows MT5 terminal yet.
