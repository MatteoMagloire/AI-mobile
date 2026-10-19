# Axo

Ton **ami virtuel** qui vit dans ton téléphone et fonctionne **hors ligne** (Samsung Galaxy S21, Snapdragon 888, 8 Go de RAM). Rien ne quitte le téléphone.

- App Android native en **Kotlin** (Jetpack Compose)
- Moteur **llama.cpp** compilé avec le NDK, appelé via JNI
- Modèles **Ministral 3 3B** (Mistral AI, Apache 2.0) en GGUF :
  - **Instruct** au quotidien (réponse directe, rapide)
  - **Reasoning** en mode « Réflexion » (réfléchit avant de répondre)
- Mises à jour de l'app via **GitHub Releases**

## Ce que fait la version actuelle (v0.3)
- Télécharge le modèle au premier lancement (environ 2,1 Go, une seule fois, Wi-Fi par défaut, avec reprise).
- Une seule conversation qui continue : elle est gardée sur le téléphone et reprend au redémarrage. Bouton Stop, et « Effacer la conversation » dans les réglages.
- **Reprise instantanée** : la mémoire de travail du modèle (cache KV) est enregistrée quand l'app passe en arrière-plan, puis rechargée au retour. Le prompt système est aussi mis en cache, pour ne pas le relire à chaque ouverture.
- Bouton « Réflexion » : bascule sur le modèle Reasoning ; sa réflexion est repliée au-dessus de la réponse.
- **Cerveau** (prototype) : après chaque réponse, en tâche de fond, le modèle Instruct note ce qui dure sur l'utilisateur dans des notes Markdown (`filesDir/brain/notes/*.md`, la note « Moi » d'abord). Elle se fait dans un second contexte llama.cpp sur les mêmes poids : le modèle ne voit que le dernier message (avec la question d'Axo avant une réponse courte), jamais la conversation, qui reste intacte. Ses consignes et ses exemples restent dans ce contexte : lus une fois, leur KV est gardé dans `cacheDir/notes-kv/` et rechargé aux lancements suivants (relus seulement si le modèle ou llama.cpp change). Une étape relève les faits (« Note : fait. », format imposé par une grammaire GBNF) ; le code écarte ce que le message ne dit pas, ce qui ne dure pas et les doublons, et une seconde étape ne sert que si un fait ressemblant est déjà noté (lequel n'est plus vrai maintenant, avec le message sous les yeux). Un message envoyé interrompt la prise de notes. « Retiens que… » et « Oublie… » sont compris. Avant chaque message, les faits proches (recherche par mots) sont glissés devant pour le modèle seulement. Écran Cerveau : recherche, étiquettes, journal avec « Annuler », lecture mise au propre ou brute, correction à la main. **Test du Cerveau** (Réglages > Mesures) : 10 messages écrits d'avance, faits retenus, notés pour rien, durée de lecture et d'écriture.
- **Test de vitesse** (Réglages > Mesures) : premier mot, vitesse d'écriture, lecture d'un long message, mémoire utilisée et chauffe, avec les cibles du cahier des charges. Un test d'endurance de 10 minutes compare la vitesse de la fin à celle du début.
- **Comparer les fichiers** (Réglages > Mesures) : Q4_K_M, UD-Q4_K_XL et Q4_0 répondent aux 10 mêmes questions, puis tu votes à l'aveugle. L'app recommande le meilleur fichier et peut l'utiliser tout de suite.
- Réglages : créativité, longueur max, personnalité d'Axo (prompt système), affichage de la vitesse, Wi-Fi uniquement.
- Fonctionne entièrement en mode avion une fois le modèle téléchargé.
- Vérifie les nouvelles versions : tout de suite si internet est là, sinon dès le retour de la connexion (WorkManager), puis une fois par jour.

## Organisation du code
| Dossier | Rôle |
|---|---|
| `app/src/main/cpp/` | Pont JNI vers llama.cpp (version figée dans `CMakeLists.txt`), sauvegarde et reprise du cache KV |
| `.../llm/` | Moteur local : chargement, streaming, séparation de la réflexion, cache des sessions (`SessionCache`) |
| `.../chat/` | Conversation enregistrée et fenêtre d'historique envoyée au modèle |
| `.../brain/` | Cerveau : notes Markdown, prise de notes en tâche de fond, souvenirs, « retiens / oublie », journal |
| `.../measure/` | Test de vitesse, endurance, comparaison à l'aveugle, mesures mémoire et chauffe |
| `.../model/` | Catalogue des modèles (3 fichiers par modèle) et téléchargement en arrière-plan |
| `.../update/` | Vérification, téléchargement, vérification SHA-256 et installation des mises à jour |
| `.../settings/` | Réglages (DataStore) et personnalité par défaut d'Axo |
| `.../ui/` | Écrans Compose, thème « Une lueur dans la poche », icônes |

## Compiler
Il faut Android Studio (ou le SDK Android + NDK) et une connexion internet au premier build (llama.cpp est téléchargé par CMake).

```bash
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

La CI (`.github/workflows/build.yml`) lance les tests et compile l'APK debug à chaque PR ; il est téléchargeable dans l'onglet Actions.

## Publier une mise à jour
1. **Une seule fois : créer la clé de signature.** Toutes les versions doivent être signées avec la même clé, sinon Android refuse la mise à jour. Garde ce fichier et ses mots de passe en lieu sûr.
   ```bash
   keytool -genkeypair -v -keystore axo-release.keystore -alias axo -keyalg RSA -keysize 4096 -validity 10000
   base64 -w0 axo-release.keystore > axo-release.keystore.b64   # sous Windows : certutil -encode axo-release.keystore axo-release.keystore.b64
   ```
2. **Une seule fois : ajouter les secrets** dans GitHub, Settings > Secrets and variables > Actions :
   | Secret | Valeur |
   |---|---|
   | `ANDROID_KEYSTORE_BASE64` | contenu du fichier `.b64` |
   | `ANDROID_KEYSTORE_PASSWORD` | mot de passe du keystore |
   | `ANDROID_KEY_ALIAS` | `axo` |
   | `ANDROID_KEY_PASSWORD` | mot de passe de la clé |
3. **Dépôt public** conseillé : l'app lit les Releases sans jeton (un jeton embarqué dans l'APK pourrait être extrait).
4. **Notes de version** : écrire `.github/release-notes/v0.3.0.md`. Ce texte s'affiche dans la fenêtre de mise à jour de l'app ; sans ce fichier, GitHub génère des notes à partir des PR.
5. **Publier** : onglet Actions > Release > Run workflow, avec le numéro de version (`0.3.0`), ou créer un tag de version.
   ```bash
   git tag v0.3.0 && git push origin v0.3.0
   ```
   `.github/workflows/release.yml` compile, signe, calcule l'empreinte SHA-256 et crée la Release avec `Axo-0.3.0.apk`. Le numéro du tag devient la version de l'app (`versionCode` = 0.3.0 → 300).
6. Sur le téléphone, Axo propose la mise à jour. La première fois, Android demande d'autoriser « Installer des applis inconnues » pour Axo.

La toute première installation se fait à la main : télécharger l'APK de la Release sur le téléphone et l'ouvrir. Un APK debug ne peut pas être mis à jour par un APK signé en release (clés différentes) : désinstaller le debug avant.

## Modèles
Les modèles ne passent pas par les Releases (trop gros, et inutile de les retélécharger à chaque version). Ils viennent de Hugging Face :
- Q4_K_M (par défaut) : [Ministral-3-3B-Instruct-2512-GGUF](https://huggingface.co/mistralai/Ministral-3-3B-Instruct-2512-GGUF) et [Ministral-3-3B-Reasoning-2512-GGUF](https://huggingface.co/mistralai/Ministral-3-3B-Reasoning-2512-GGUF)
- UD-Q4_K_XL et Q4_0, pour la comparaison : [unsloth/Ministral-3-3B-Instruct-2512-GGUF](https://huggingface.co/unsloth/Ministral-3-3B-Instruct-2512-GGUF) et [unsloth/Ministral-3-3B-Reasoning-2512-GGUF](https://huggingface.co/unsloth/Ministral-3-3B-Reasoning-2512-GGUF)

## Polices
Bricolage Grotesque, Figtree et JetBrains Mono, sous licence SIL Open Font License 1.1 (textes dans `app/src/main/assets/licenses/`).
