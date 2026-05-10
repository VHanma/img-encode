"""
Living Image v20 — Android App
On-device encode OR server mode (upload image, get files back)
"""
import os, threading, json, zipfile, time
from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.spinner import Spinner
from kivy.uix.textinput import TextInput
from kivy.uix.scrollview import ScrollView
from kivy.uix.togglebutton import ToggleButton
from kivy.uix.progressbar import ProgressBar
from kivy.clock import Clock, mainthread
from kivy.utils import platform
from kivy.core.window import Window

Window.clearcolor = (0.05, 0.05, 0.1, 1)

SERVER_URL = "https://your-server.example.com/encode"  # user editable in UI


def get_downloads_dir():
    if platform == 'android':
        from android.storage import primary_external_storage_path
        d = os.path.join(primary_external_storage_path(), 'LivingImage')
    else:
        d = os.path.expanduser('~/LivingImage_outputs')
    os.makedirs(d, exist_ok=True)
    return d


class LivingImageApp(App):
    def build(self):
        self.image_path = None
        self.running = False
        root = BoxLayout(orientation='vertical', padding=16, spacing=10)

        # Title
        root.add_widget(Label(
            text='[b]Living Image v20[/b]', markup=True,
            font_size='22sp', size_hint_y=None, height=44,
            color=(0.4, 0.9, 1, 1)
        ))

        # Mode toggle
        mode_row = BoxLayout(size_hint_y=None, height=44, spacing=8)
        mode_row.add_widget(Label(text='Mode:', size_hint_x=0.25, color=(0.8,0.8,0.8,1)))
        self.btn_device = ToggleButton(text='On-Device', group='mode', state='down',
                                       background_color=(0.1,0.5,0.9,1))
        self.btn_server = ToggleButton(text='Server', group='mode',
                                       background_color=(0.2,0.2,0.3,1))
        self.btn_device.bind(on_press=self._on_mode)
        self.btn_server.bind(on_press=self._on_mode)
        mode_row.add_widget(self.btn_device)
        mode_row.add_widget(self.btn_server)
        root.add_widget(mode_row)

        # Server URL (hidden in on-device mode)
        self.server_row = BoxLayout(size_hint_y=None, height=44, spacing=8)
        self.server_row.add_widget(Label(text='URL:', size_hint_x=0.18, color=(0.8,0.8,0.8,1)))
        self.server_input = TextInput(
            text=SERVER_URL, multiline=False,
            background_color=(0.1,0.1,0.2,1), foreground_color=(1,1,1,1),
            font_size='13sp'
        )
        self.server_row.add_widget(self.server_input)
        self.server_row.opacity = 0
        self.server_row.disabled = True
        root.add_widget(self.server_row)

        # Image picker button
        self.pick_btn = Button(
            text='Pick Image', size_hint_y=None, height=52,
            background_color=(0.15, 0.6, 0.3, 1), font_size='16sp'
        )
        self.pick_btn.bind(on_press=self.pick_image)
        root.add_widget(self.pick_btn)

        # Image label
        self.img_label = Label(
            text='No image selected', size_hint_y=None, height=30,
            color=(0.6, 0.6, 0.6, 1), font_size='12sp'
        )
        root.add_widget(self.img_label)

        # Duration + Theme row
        opt_row = BoxLayout(size_hint_y=None, height=44, spacing=8)
        opt_row.add_widget(Label(text='Duration(s):', size_hint_x=0.32, color=(0.8,0.8,0.8,1)))
        self.duration_input = TextInput(
            text='300', multiline=False, input_filter='int',
            background_color=(0.1,0.1,0.2,1), foreground_color=(1,1,1,1),
            size_hint_x=0.22, font_size='15sp'
        )
        opt_row.add_widget(self.duration_input)
        opt_row.add_widget(Label(text='Theme:', size_hint_x=0.22, color=(0.8,0.8,0.8,1)))
        self.theme_spinner = Spinner(
            text='omni_hanma',
            values=['omni_hanma', 'neural_regeneration', 'general_regeneration'],
            size_hint_x=0.45, background_color=(0.1,0.3,0.6,1)
        )
        opt_row.add_widget(self.theme_spinner)
        root.add_widget(opt_row)

        # Run button
        self.run_btn = Button(
            text='RUN ENCODE', size_hint_y=None, height=58,
            background_color=(0.8, 0.2, 0.9, 1), font_size='18sp', bold=True
        )
        self.run_btn.bind(on_press=self.run_encode)
        root.add_widget(self.run_btn)

        # Progress bar
        self.progress = ProgressBar(max=100, value=0, size_hint_y=None, height=18)
        root.add_widget(self.progress)

        # Log output
        scroll = ScrollView()
        self.log_label = Label(
            text='Ready.\n', markup=True, font_size='12sp',
            size_hint_y=None, valign='top', halign='left',
            color=(0.7, 1, 0.7, 1), padding=(8, 8)
        )
        self.log_label.bind(texture_size=self.log_label.setter('size'))
        scroll.add_widget(self.log_label)
        root.add_widget(scroll)

        return root

    def _on_mode(self, btn):
        server_mode = (btn.text == 'Server')
        self.server_row.opacity = 1 if server_mode else 0
        self.server_row.disabled = not server_mode

    def pick_image(self, *a):
        if platform == 'android':
            from android.permissions import request_permissions, Permission
            request_permissions([Permission.READ_EXTERNAL_STORAGE,
                                  Permission.WRITE_EXTERNAL_STORAGE])
            try:
                from androidstorage4kivy import SharedStorage, Chooser
                self._chooser = Chooser(self._on_image_chosen)
                self._chooser.choose_content('image/*')
            except Exception as e:
                self.log(f'[color=ff4444]Picker error: {e}[/color]')
        else:
            # Desktop fallback — use filechooser
            from kivy.uix.filechooser import FileChooserIconView
            from kivy.uix.popup import Popup
            fc = FileChooserIconView(filters=['*.jpg','*.jpeg','*.png'])
            popup = Popup(title='Pick Image', content=fc, size_hint=(0.9,0.9))
            def _sel(inst, val, *a):
                if val:
                    self.image_path = val[0]
                    self.img_label.text = os.path.basename(val[0])
                    popup.dismiss()
            fc.bind(on_submit=_sel)
            popup.open()

    def _on_image_chosen(self, uri_list):
        if not uri_list:
            return
        from androidstorage4kivy import SharedStorage
        ss = SharedStorage()
        path = ss.copy_from_shared(uri_list[0])
        if path:
            self.image_path = path
            self.img_label.text = os.path.basename(path)
            self.log(f'Image: {os.path.basename(path)}')

    def run_encode(self, *a):
        if self.running:
            return
        if not self.image_path or not os.path.exists(self.image_path):
            self.log('[color=ff4444]Please pick an image first.[/color]')
            return
        duration = int(self.duration_input.text or '300')
        theme = self.theme_spinner.text
        server_mode = self.btn_server.state == 'down'
        self.running = True
        self.run_btn.disabled = True
        self.set_progress(0)
        if server_mode:
            threading.Thread(target=self._run_server,
                             args=(self.image_path, duration, theme), daemon=True).start()
        else:
            threading.Thread(target=self._run_device,
                             args=(self.image_path, duration, theme), daemon=True).start()

    def _run_device(self, image_path, duration, theme):
        self.log('Starting on-device encode...')
        self.set_progress(5)
        try:
            import sys
            app_dir = os.path.dirname(os.path.abspath(__file__))
            if app_dir not in sys.path:
                sys.path.insert(0, app_dir)
            from v20_living_image import LivingImageV20
            out_dir = get_downloads_dir()
            self.log(f'Output: {out_dir}')
            self.set_progress(10)
            enc = LivingImageV20(duration_s=duration, output_dir=out_dir, theme=theme)
            self.log('Running encode (this takes a while)...')
            manifest = enc.run(image_path)
            self.set_progress(95)
            n = len(manifest.get('files', []))
            self.log(f'[color=00ff99]Done! {n} files saved to:[/color]\n{out_dir}')
            self.set_progress(100)
        except Exception as e:
            import traceback
            self.log(f'[color=ff4444]Error: {e}\n{traceback.format_exc()}[/color]')
        finally:
            self._done()

    def _run_server(self, image_path, duration, theme):
        self.log('Uploading to server...')
        self.set_progress(10)
        try:
            import requests as req
            url = self.server_input.text.strip()
            with open(image_path, 'rb') as fh:
                r = req.post(url, files={'image': fh},
                             data={'duration': duration, 'theme': theme},
                             timeout=600, stream=True)
            if r.status_code != 200:
                self.log(f'[color=ff4444]Server error {r.status_code}: {r.text[:200]}[/color]')
                self._done(); return
            self.set_progress(70)
            out_dir = get_downloads_dir()
            zip_path = os.path.join(out_dir, 'v20_result.zip')
            with open(zip_path, 'wb') as fh:
                for chunk in r.iter_content(1024*1024):
                    fh.write(chunk)
            self.set_progress(88)
            with zipfile.ZipFile(zip_path, 'r') as zf:
                zf.extractall(out_dir)
            os.remove(zip_path)
            self.log(f'[color=00ff99]Done! Files saved to:[/color]\n{out_dir}')
            self.set_progress(100)
        except Exception as e:
            import traceback
            self.log(f'[color=ff4444]Error: {e}\n{traceback.format_exc()}[/color]')
        finally:
            self._done()

    @mainthread
    def log(self, msg):
        self.log_label.text += msg + '\n'

    @mainthread
    def set_progress(self, val):
        self.progress.value = val

    @mainthread
    def _done(self):
        self.running = False
        self.run_btn.disabled = False


if __name__ == '__main__':
    LivingImageApp().run()
