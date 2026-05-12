"""
Living Image v20 — full replacement main.py
On-device locked.
Safe app storage.
Exports finished output as a ZIP into shared Downloads.
"""

import os
import time
import zipfile
import threading
import traceback

from kivy.app import App
from kivy.clock import mainthread
from kivy.core.window import Window
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.progressbar import ProgressBar
from kivy.uix.scrollview import ScrollView
from kivy.uix.spinner import Spinner
from kivy.uix.textinput import TextInput
from kivy.utils import platform

Window.clearcolor = (0.05, 0.05, 0.10, 1)


def get_downloads_dir():
    if platform == "android":
        try:
            from jnius import autoclass

            PythonActivity = autoclass("org.kivy.android.PythonActivity")
            context = PythonActivity.mActivity
            base = context.getExternalFilesDir(None).getAbsolutePath()
            out_dir = os.path.join(base, "LivingImage_outputs")
        except Exception:
            app = App.get_running_app()
            out_dir = os.path.join(app.user_data_dir, "LivingImage_outputs")
    else:
        out_dir = os.path.expanduser("~/LivingImage_outputs")

    os.makedirs(out_dir, exist_ok=True)
    return out_dir


def export_output_zip(out_dir):
    zip_name = "LivingImage_export_%s.zip" % time.strftime("%Y%m%d_%H%M%S")
    zip_path = os.path.join(out_dir, zip_name)

    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for root, dirs, files in os.walk(out_dir):
            for name in files:
                full = os.path.join(root, name)

                if full == zip_path:
                    continue

                if name.lower().endswith(".zip"):
                    continue

                arc = os.path.relpath(full, out_dir)
                z.write(full, arc)

    if platform != "android":
        return zip_path, zip_path

    try:
        from jnius import autoclass
        from androidstorage4kivy import SharedStorage

        Environment = autoclass("android.os.Environment")
        ss = SharedStorage()

        shared_ref = ss.copy_to_shared(
            zip_path,
            collection=Environment.DIRECTORY_DOWNLOADS,
            filepath=os.path.join("LivingImage_export", zip_name),
        )

        if shared_ref:
            return zip_path, str(shared_ref)

        shared_ref = ss.copy_to_shared(
            zip_path,
            collection=Environment.DIRECTORY_DOCUMENTS,
            filepath=os.path.join("LivingImage_export", zip_name),
        )

        return zip_path, str(shared_ref)

    except Exception as e:
        return zip_path, "Shared export failed: %r" % e


class LivingImageApp(App):
    def build(self):
        self.image_path = None
        self.running = False

        root = BoxLayout(orientation="vertical", padding=12, spacing=8)

        root.add_widget(
            Label(
                text="[b]Living Image v20[/b]",
                markup=True,
                font_size="22sp",
                size_hint_y=None,
                height=42,
                color=(0.4, 0.9, 1, 1),
            )
        )

        mode_row = BoxLayout(size_hint_y=None, height=36, spacing=8)
        mode_row.add_widget(
            Label(
                text="Mode:",
                size_hint_x=0.25,
                color=(0.85, 0.85, 0.85, 1),
            )
        )
        mode_row.add_widget(
            Label(
                text="[b]On-Device Locked[/b]",
                markup=True,
                color=(0.4, 1, 0.6, 1),
                font_size="16sp",
            )
        )
        root.add_widget(mode_row)

        self.pick_btn = Button(
            text="Pick Image",
            size_hint_y=None,
            height=46,
            background_color=(0.1, 0.55, 0.25, 1),
            font_size="16sp",
        )
        self.pick_btn.bind(on_press=self.pick_image)
        root.add_widget(self.pick_btn)

        self.img_label = Label(
            text="No image selected",
            size_hint_y=None,
            height=28,
            color=(0.65, 0.65, 0.65, 1),
            font_size="12sp",
        )
        root.add_widget(self.img_label)

        opt_row = BoxLayout(size_hint_y=None, height=40, spacing=8)

        opt_row.add_widget(
            Label(
                text="Duration(s):",
                size_hint_x=0.32,
                color=(0.85, 0.85, 0.85, 1),
            )
        )

        self.duration_input = TextInput(
            text="30",
            multiline=False,
            input_filter="int",
            background_color=(0.1, 0.1, 0.2, 1),
            foreground_color=(1, 1, 1, 1),
            size_hint_x=0.22,
            font_size="15sp",
        )
        opt_row.add_widget(self.duration_input)

        opt_row.add_widget(
            Label(
                text="Theme:",
                size_hint_x=0.22,
                color=(0.85, 0.85, 0.85, 1),
            )
        )

        self.theme_spinner = Spinner(
            text="omni_hanma",
            values=["omni_hanma", "neural_regeneration", "general_regeneration"],
            size_hint_x=0.50,
            background_color=(0.1, 0.25, 0.55, 1),
        )
        opt_row.add_widget(self.theme_spinner)

        root.add_widget(opt_row)

        self.run_btn = Button(
            text="RUN ENCODE",
            size_hint_y=None,
            height=52,
            background_color=(0.65, 0.15, 0.75, 1),
            font_size="18sp",
            bold=True,
        )
        self.run_btn.bind(on_press=self.run_encode)
        root.add_widget(self.run_btn)

        self.progress = ProgressBar(max=100, value=0, size_hint_y=None, height=14)
        root.add_widget(self.progress)

        scroll = ScrollView()

        self.log_label = Label(
            text="Ready. On-device mode locked. Storage fix v2 + Pillow fix v3 + Export full replacement v5 + V21 engine active.\n",
            markup=True,
            font_size="12sp",
            size_hint_y=None,
            valign="top",
            halign="left",
            color=(0.7, 1, 0.7, 1),
            padding=(6, 6),
        )
        self.log_label.bind(texture_size=self.log_label.setter("size"))

        scroll.add_widget(self.log_label)
        root.add_widget(scroll)

        return root

    def pick_image(self, *args):
        if platform == "android":
            try:
                from android.permissions import request_permissions, Permission

                perms = []

                if hasattr(Permission, "READ_MEDIA_IMAGES"):
                    perms.append(Permission.READ_MEDIA_IMAGES)

                if hasattr(Permission, "READ_EXTERNAL_STORAGE"):
                    perms.append(Permission.READ_EXTERNAL_STORAGE)

                if hasattr(Permission, "WRITE_EXTERNAL_STORAGE"):
                    perms.append(Permission.WRITE_EXTERNAL_STORAGE)

                if perms:
                    request_permissions(perms)

            except Exception:
                pass

            try:
                from androidstorage4kivy import SharedStorage, Chooser

                self._chooser = Chooser(self._on_image_chosen)
                self._chooser.choose_content("image/*")

            except Exception as e:
                self.log("[color=ff4444]Picker error: %s[/color]" % e)

        else:
            from kivy.uix.filechooser import FileChooserIconView
            from kivy.uix.popup import Popup

            chooser = FileChooserIconView(filters=["*.jpg", "*.jpeg", "*.png"])
            popup = Popup(title="Pick Image", content=chooser, size_hint=(0.9, 0.9))

            def on_submit(instance, selection, touch=None):
                if selection:
                    self.image_path = selection[0]
                    self.img_label.text = os.path.basename(self.image_path)
                    popup.dismiss()

            chooser.bind(on_submit=on_submit)
            popup.open()

    def _on_image_chosen(self, uri_list):
        if not uri_list:
            return

        try:
            from androidstorage4kivy import SharedStorage

            ss = SharedStorage()
            path = ss.copy_from_shared(uri_list[0])

            if path:
                self.image_path = path
                self.img_label.text = os.path.basename(path)
                self.log("Image: %s" % os.path.basename(path))
            else:
                self.log("[color=ff4444]Image picker returned no private file.[/color]")

        except Exception as e:
            self.log("[color=ff4444]Image load error: %s[/color]" % e)

    def run_encode(self, *args):
        if self.running:
            return

        if not self.image_path or not os.path.exists(self.image_path):
            self.log("[color=ff4444]Pick an image first.[/color]")
            return

        try:
            duration = int(self.duration_input.text or "30")
        except Exception:
            duration = 30

        theme = self.theme_spinner.text

        self.log("Mode locked: On-Device")
        self.running = True
        self.run_btn.disabled = True
        self.set_progress(0)

        thread = threading.Thread(
            target=self._run_device,
            args=(self.image_path, duration, theme),
            daemon=True,
        )
        thread.start()

    def _run_device(self, image_path, duration, theme):
        try:
            self.log("Starting on-device encode...")
            self.set_progress(5)

            import sys

            app_dir = os.path.dirname(os.path.abspath(__file__))
            if app_dir not in sys.path:
                sys.path.insert(0, app_dir)

            from v21_living_image import LivingImageV21

            out_dir = get_downloads_dir()
            self.log("Output: %s" % out_dir)

            self.set_progress(10)

            encoder = LivingImageV21(
                duration_s=duration,
                output_dir=out_dir,
                theme=theme,
            )

            self.log("Running encode. This can take a while...")

            manifest = encoder.run(image_path)

            if not isinstance(manifest, dict):
                manifest = {"files": []}

            self.set_progress(90)

            zip_path, shared_ref = export_output_zip(out_dir)

            files = manifest.get("files", [])
            file_count = len(files) if isinstance(files, list) else 0

            self.log("[color=00ff99]Done! %s files saved to:[/color]\n%s" % (file_count, out_dir))
            self.log("[color=00ff99]Export ZIP created:[/color]\n%s" % zip_path)
            self.log("[color=00ff99]Shared export target:[/color]\n%s" % shared_ref)
            self.log("[color=00ff99]Check Files app: Downloads > LivingImage_export[/color]")

            self.set_progress(100)

        except Exception as e:
            self.log("[color=ff4444]Error: %s\n%s[/color]" % (e, traceback.format_exc()))

        finally:
            self._done()

    @mainthread
    def log(self, msg):
        self.log_label.text += msg + "\n"

    @mainthread
    def set_progress(self, value):
        self.progress.value = value

    @mainthread
    def _done(self):
        self.running = False
        self.run_btn.disabled = False


if __name__ == "__main__":
    LivingImageApp().run()
