[app]
title = Living Image v20
package.name = livingimage
package.domain = org.vhanma
source.dir = .
source.include_exts = py,png,jpg,jpeg,svg,json,csv,txt
version = 2.0
requirements = python3,kivy==2.3.1,pillow,numpy,requests,androidstorage4kivy,svgwrite
orientation = portrait
android.permissions = READ_EXTERNAL_STORAGE,WRITE_EXTERNAL_STORAGE,INTERNET
android.api = 33
android.minapi = 24
android.ndk = 25b
android.archs = arm64-v8a
android.allow_backup = True
fullscreen = 0
android.presplash_color = #0D0D1A

[buildozer]
log_level = 2
