[app]
title = DNA Forge Max
package.name = dnaforgemax
package.domain = org.vhanma
source.dir = .
source.include_exts = py,png,jpg,jpeg,json,txt,md,wav
source.exclude_dirs = .git,.github,.buildozer,v1-python,v2-js,v3,dist,node_modules,__pycache__
version = 3.0
requirements = python3,kivy==2.3.0,pillow
orientation = portrait
fullscreen = 0
android.permissions = READ_EXTERNAL_STORAGE,WRITE_EXTERNAL_STORAGE,READ_MEDIA_IMAGES,INTERNET
android.api = 33
android.minapi = 24
android.ndk = 25b
android.ndk_api = 24
android.archs = arm64-v8a
android.allow_backup = True
android.presplash_color = #080814

[buildozer]
log_level = 2
warn_on_root = 0
