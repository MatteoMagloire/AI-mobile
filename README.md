# AI-mobile

Assistant IA personnel qui tourne **hors ligne** directement sur le téléphone (Samsung Galaxy S21, Snapdragon 888, 8 Go de RAM).

- App Android native en **Kotlin** (Jetpack Compose)
- Moteur **llama.cpp** compilé avec le NDK, appelé via JNI
- Modèles **Ministral 3 3B** (Mistral AI, Apache 2.0) en GGUF Q4_K_M :
  - **Instruct** au quotidien (réponse directe, rapide)
  - **Reasoning** en mode « Réflexion » (réfléchit avant de répondre)
- Mises à jour de l'app via **GitHub Releases**

## Ce que fait la version actuelle (MVP)
- Télécharge le modèle au premier lancement (environ 2,1 Go, une seule fois, Wi-Fi par défaut, avec reprise).
- Chat avec réponse en streaming, bouton Stop, nouvelle conversation.
- Bouton « Réflexion » : bascule sur le modèle Reasoning ; sa réflexion est repliée sous la réponse.
- Réglages : température, longueur max, instructions de l'assistant, Wi-Fi uniquement.
- Fonctionne entièrement en mode avion une fois le modèle téléchargé.
- Vérifie les nouvelles versions : tout de suite si internet est là, sinon dès le retour de la connexion (WorkManager), puis une fois par jour.

## Organisation du code
| Dossier | Rôle |
|---|---|
| `app/src/main/cpp/` | Pont JNI vers llama.cpp (version figée dans `CMakeLists.txt`) |
| `.../llm/` | Moteur local : chargement du modèle, streaming, séparation de la réflexion |
| `.../model/` | Catalogue des modèles et téléchargement en arrière-plan |
| `.../update/` | Vérification, téléchargement, vérification SHA-256 et installation des mises à jour |
| `.../settings/` | Réglages (DataStore) |
| `.../ui/` | Écrans Compose |

## Compiler
Il faut Android Studio (ou le SDK Android + NDK) et une connexion internet au premier build (llama.cpp est téléchargé par CMake).

```bash
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

La CI (`.github/workflows/build.yml`) compile l'APK debug à chaque PR ; il est téléchargeable dans l'onglet Actions.

## Publier une mise à jour
1. **Une seule fois : créer la clé de signature.** Toutes les versions doivent être signées avec la même clé, sinon Android refuse la mise à jour. Garde ce fichier et ses mots de passe en lieu sûr.
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias aimobile -keyalg RSA -keysize 4096 -validity 10000
   base64 -w0 release.jks > release.jks.b64   # sous Windows : certutil -encode release.jks release.jks.b64
   ```
2. **Une seule fois : ajouter les secrets** dans GitHub, Settings > Secrets and variables > Actions :
   | Secret | Valeur |
   |---|---|
   | `ANDROID_KEYSTORE_BASE64` | contenu de `release.jks.b64` |
   | `ANDROID_KEYSTORE_PASSWORD` | mot de passe du keystore |
   | `ANDROID_KEY_ALIAS` | `aimobile` |
   | `ANDROID_KEY_PASSWORD` | mot de passe de la clé |
3. **Dépôt public** conseillé : l'app lit les Releases sans jeton (un jeton embarqué dans l'APK pourrait être extrait).
4. **Publier** : créer un tag de version.
   ```bash
   git tag v0.2.0 && git push origin v0.2.0
   ```
   `.github/workflows/release.yml` compile, signe, calcule l'empreinte SHA-256 et crée la Release avec l'APK. Le numéro du tag devient la version de l'app (`versionCode` = 0.2.0 → 200).
5. Sur le téléphone, l'app propose la mise à jour. La première fois, Android demande d'autoriser « Installer des applis inconnues » pour l'assistant.

La toute première installation se fait à la main : télécharger l'APK de la Release sur le téléphone et l'ouvrir. Un APK debug ne peut pas être mis à jour par un APK signé en release (clés différentes) : désinstaller le debug avant.

## Modèles
Les modèles ne passent pas par les Releases (trop gros, et inutile de les retélécharger à chaque version). Ils viennent de Hugging Face :
- [Ministral-3-3B-Instruct-2512-GGUF](https://huggingface.co/mistralai/Ministral-3-3B-Instruct-2512-GGUF)
- [Ministral-3-3B-Reasoning-2512-GGUF](https://huggingface.co/mistralai/Ministral-3-3B-Reasoning-2512-GGUF)
