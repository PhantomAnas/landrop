# -*- mode: python ; coding: utf-8 -*-
# PyInstaller recipe for the Windows exe (one file, no console, ~47 MB).
# Build from the repo root:
#     pip install -r requirements.txt pyinstaller
#     pyinstaller --noconfirm --distpath dist-pc --workpath build-pc LanDrop.spec
# -> dist-pc/LanDrop.exe. All paths are relative to this file (SPECPATH).
import os

ROOT = SPECPATH

a = Analysis(
    [os.path.join(ROOT, 'LANDROP.py')],
    pathex=[],
    binaries=[],
    datas=[(os.path.join(ROOT, 'android', 'app', 'src', 'main', 'res', 'font', 'nunito.ttf'), '.')],
    hiddenimports=[],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    # Packages the app never uses (keeps the exe small when they happen to be installed)
    # and Qt modules it does not need.
    excludes=['pygame', 'cv2', 'numpy', 'scipy', 'pandas', 'matplotlib', 'tkinter', 'IPython', 'jupyter',
              'notebook', 'sklearn', 'torch', 'PyQt6.QtQml', 'PyQt6.QtQuick', 'PyQt6.QtQuickWidgets',
              'PyQt6.QtWebEngineCore', 'PyQt6.QtWebEngineWidgets', 'PyQt6.QtMultimedia',
              'PyQt6.QtMultimediaWidgets', 'PyQt6.QtOpenGL', 'PyQt6.QtOpenGLWidgets', 'PyQt6.QtPdf',
              'PyQt6.QtPdfWidgets', 'PyQt6.QtSql', 'PyQt6.QtTest', 'PyQt6.QtXml', 'PyQt6.QtSvg',
              'PyQt6.QtSvgWidgets', 'PyQt6.QtDBus', 'PyQt6.QtNetwork', 'PyQt6.QtPrintSupport',
              'PyQt6.QtBluetooth', 'PyQt6.QtNfc', 'PyQt6.QtPositioning', 'PyQt6.QtRemoteObjects',
              'PyQt6.QtSensors', 'PyQt6.QtSerialPort', 'PyQt6.QtWebChannel', 'PyQt6.QtWebSockets',
              'PyQt6.QtDesigner', 'PyQt6.QtHelp', 'PyQt6.Qt3DCore', 'PyQt6.QtCharts',
              'PyQt6.QtDataVisualization', 'PyQt6.QtSpatialAudio', 'PyQt6.QtTextToSpeech', 'PyQt6.QtWebView'],
    noarchive=False,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='LanDrop',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=[os.path.join(ROOT, 'icon.ico')],
)
