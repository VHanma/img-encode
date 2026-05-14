[app]
title = Living Image v20
package.name = livingimage
package.domain = org.vhanma
source.dir = .
source.include_exts = py,png,jpg,jpeg,svg,json,csv,txt
source.exclude_dirs = .git,.github,.buildozer,p4a-stable,v1-python,v2-js,v3,dist,node_modules,__pycache__
version = 0.22

requirements = python3==3.11.5,hostpython3==3.11.5,kivy==2.3.0,pillow==9.5.0,numpy,requests,androidstorage4kivy

orientation = portrait
android.permissions = READ_MEDIA_IMAGES,READ_EXTERNAL_STORAGE,WRITE_EXTERNAL_STORAGE,INTERNET
android.api = 33
android.minapi = 24
android.ndk = 25c
android.ndk_api = 24
android.archs = arm64-v8a
android.allow_backup = True
fullscreen = 0
android.presplash_color = #0D0D1A

[buildozer]
log_level = 2
warn_on_root = 0
