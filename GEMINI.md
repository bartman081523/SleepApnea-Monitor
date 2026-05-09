# Schlafapnoe Projekt: Edge-KI Android-App

Dieses Projekt ist eine Konzeptstudie und Implementierung einer Edge-KI-basierten Android-App zur Echtzeit-Erkennung von obstruktiver Schlafapnoe (OSAS) mittels akustischer Analyse.

## Projektübersicht

Das System nutzt das Smartphone als nicht-invasiven Sensor, um Atemgeräusche während des Schlafs aufzuzeichnen und lokal auf dem Gerät (Edge Computing) zu analysieren. Bei Detektion eines Apnoe-Ereignisses (Atemstillstand > 10s) interveniert die App durch einen akustischen Warnton, um den Patienten zu wecken oder eine Lageänderung zu triggern.

### Kerntechnologien
- **Maschinelles Lernen:** Python, TensorFlow, TensorFlow Hub (YAMNet), Librosa (Audio-Preprocessing).
- **Edge Deployment:** TensorFlow Lite (TFLite) mit Dynamic Range Quantization und XNNPack-Optimierung.
- **Android App:** Native Entwicklung (Kotlin), Foreground Services, AudioRecord API, AudioAttributes (DND-Override).

## Architektur

### 1. Python ML Pipeline (`python_ml/`)
Die Pipeline dient der Vorverarbeitung von Trainingsdaten und der Erstellung des optimierten TFLite-Modells.
- **`1_vorverarbeitung.py`**: Lädt 7h+ Audioaufnahmen, filtert Medien (Podcasts/Musik) mittels YAMNet und identifiziert heuristisch Stille-Phasen via RMS-Energie.
- **`2_train_und_quantize.py`**: Implementiert Transfer Learning auf Basis von YAMNet. Das Modell ist für 60-Sekunden-Zeitfenster optimiert und wird für den Einsatz auf mobilen CPUs quantisiert (INT8-Gewichte).

### 2. Android App (`android_app/`)
Die App ist für einen ressourcenschonenden Dauerbetrieb ausgelegt.
- **Kaskaden-Architektur**: Ein leichtgewichtiger RMS-Check prüft kontinuierlich den Pegel; das ML-Modell wird nur bei verdächtigen Mustern "geweckt".
- **Foreground Service**: `ApneaMonitoringService.kt` hält das Mikrofon im Hintergrund offen und verwaltet `WakeLocks`.
- **DND-Override**: `AudioIntervention.kt` nutzt den Alarm-Kanal und Systemberechtigungen, um die Stummschaltung ("Bitte nicht stören") im Notfall zu umgehen.

## Entwicklung und Setup

### Python Umgebung
Das Projekt nutzt eine Conda Umgebung (`venv_osas/`).
- **Abhängigkeiten**: `librosa`, `tensorflow`, `tensorflow-hub`, `numpy`, `matplotlib`.
- **CUDA Support**: Die GPU-Beschleunigung wurde durch die Installation von `nvidia-*-cu12` Pip-Paketen und die Konfiguration von `LD_LIBRARY_PATH` (via Conda activation scripts in `etc/conda/activate.d/cuda.sh`) aktiviert.
- **Workflow**:
    1. Audioaufnahme als `schlafaufnahme_nacht1.wav` in `python_ml/` ablegen.
    2. `1_vorverarbeitung.py` ausführen, um Labels/Zeitstempel zu generieren.
    3. `2_train_und_quantize.py` ausführen, um das `apnea_model_quantized.tflite` zu erzeugen.

### Android Entwicklung
- **IDE**: Android Studio.
- **Min SDK**: API 21 (Lollipop), für Foreground Service Features wird API 34+ empfohlen.
- **Wichtige Berechtigungen**:
    - `RECORD_AUDIO`
    - `FOREGROUND_SERVICE_MICROPHONE`
    - `WAKE_LOCK`
    - `ACCESS_NOTIFICATION_POLICY` (Manuelle Freigabe durch Nutzer erforderlich).

## Wichtige Dateien
- `Schlafapnoe-Erkennung per Android-App.md`: Ausführliches Konzeptpapier und technische Spezifikation.
- `python_ml/1_vorverarbeitung.py`: Signalverarbeitung und Feature-Extraktion.
- `python_ml/2_train_und_quantize.py`: Modell-Architektur und TFLite-Export.
- `android_app/AndroidManifest.xml`: System-Konfiguration und Berechtigungen.
- `android_app/ApneaMonitoringService.kt`: Herzstück der Hintergrund-Überwachung.
- `android_app/AudioIntervention.kt`: Logik für den therapeutischen Alarm.

## TODOs / Ausblick
- [ ] Implementierung der manuellen Annotation (Export aus Audacity -> Training).
- [ ] Validierung des Modells mit klinischen Datensätzen (z.B. Snore-Apnea Dataset).
- [ ] Verfeinerung des UX-Onboardings für die korrekte Smartphone-Positionierung.
