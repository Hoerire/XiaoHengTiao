#!/usr/bin/env python3
"""把 aapt2 产物 + R8 的 classes.dex + META-INF/xposed/* 组装成 APK，并对齐 resources.arsc。"""
import os
import zipfile

B = os.environ['BUILD_DIR']
SRC = os.environ['XPOSED_DIR']
OUT = os.path.join(B, 'app-aligned-unsigned.apk')
XPOSED_FILES = ('module.prop', 'java_init.list', 'scope.list', 'xposed_module')


def build(pad_arsc):
    zin = zipfile.ZipFile(os.path.join(B, 'app-res.apk'))
    zo = zipfile.ZipFile(OUT, 'w')
    for it in zin.infolist():
        data = zin.read(it.filename)
        extra = b'\x00' * pad_arsc if it.filename == 'resources.arsc' and pad_arsc else b''
        zi = zipfile.ZipInfo(it.filename, date_time=it.date_time)
        zi.compress_type = it.compress_type
        zi.external_attr = it.external_attr
        zi.extra = extra
        zo.writestr(zi, data)
    zo.write(os.path.join(B, 'dex', 'classes.dex'), 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
    for f in XPOSED_FILES:
        zo.write(os.path.join(SRC, f), 'META-INF/xposed/' + f, compress_type=zipfile.ZIP_DEFLATED)
    zo.close()
    zin.close()


build(0)
with zipfile.ZipFile(OUT) as z:
    i = z.getinfo('resources.arsc')
    off = i.header_offset + 30 + len(i.filename.encode()) + len(i.extra)
pad = (4 - (off % 4)) % 4
if pad:
    build(pad)
    with zipfile.ZipFile(OUT) as z:
        i = z.getinfo('resources.arsc')
        off = i.header_offset + 30 + len(i.filename.encode()) + len(i.extra)
print('APK:', OUT, 'size=', os.path.getsize(OUT), 'arsc_align=', off % 4)
