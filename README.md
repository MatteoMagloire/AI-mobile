# Axo

Ton **ami virtuel** qui vit dans ton téléphone et fonctionne **hors ligne** (Samsung Galaxy S21, Snapdragon 888, 8 Go de RAM). Rien ne quitte le téléphone.

- App Android native en **Kotlin** (Jetpack Compose)
- Moteur **llama.cpp** compilé avec le NDK, appelé via JNI
- Modèles **Ministral 3 3B** (Mistral AI, Apache 2.0) en GGUF :
  - **Instruct** au quotidien (réponse directe, rapide)
  - **Reasoning** en mode « Réflexion » (réfléchit avant de répondre)
- Mises à jour de l'app via **GitHub Releases**

## Ce que fait la version actuelle (v0.4)
- Télécharge le modèle au premier lancement (environ 2,1 Go, une seule fois, Wi-Fi par défaut, avec reprise).
- Une seule conversation qui continue : elle est gardée sur le téléphone et reprend au redémarrage. Bouton Stop, et « Effacer la conversation » dans les réglages.
- **Reprise instantanée** : la mémoire de travail du modèle (cache KV) est enregistrée quand l'app passe en arrière-plan, puis rechargée au retour. Le prompt système est aussi mis en cache, pour ne pas le relire à chaque ouverture.
- Bouton « Réflexion » : bascule sur le modèle Reasoning ; sa réflexion est repliée au-dessus de la réponse.
- Réponses d'Axo : le gras et l'italique s'affichent sans leurs astérisques, un calcul avec son signe (« 3×12 »), et ses actions entre astérisques (« *sourit* ») ne s'affichent pas (`chat/AnswerText.kt`).
- **Consignes juste avant certains messages** (`brain/Cue.kt`), lues même si le Cerveau ne répond pas : un message de détresse (« envie d'en finir », « plus rien n'a de sens », `brain/Distress.kt`) est à prendre au sérieux, avec le 3114, gratuit, jour et nuit ; la météo et les infos, qu'Axo ne connaît pas sans internet ; une inquiétude de santé, pour laquelle il conseille un médecin, ou un vétérinaire pour un animal ; « toi tu es Axo », où il lui donne raison sur qui est qui ; un simple salut, où il ne sait rien de sa journée et le lui demande au lieu de supposer. Un « tu c'est » écrit pour « tu sais » est aussi précisé. Elles restent dans la conversation, sauf celle du salut : relue ensuite, elle faisait douter Axo du prénom de l'utilisateur. Une fois la réponse écrite, le message est réécrit sans elle dans le KV (`rewriteLastExchange`, quelques dizaines de tokens relus).
- **Cerveau** (prototype) : après chaque réponse, en tâche de fond, le modèle Instruct note ce qui dure sur l'utilisateur dans des notes Markdown (`filesDir/brain/notes/*.md`, la note « Moi » d'abord). Elle se fait dans un second contexte llama.cpp sur les mêmes poids : le modèle ne voit que le dernier message (avec la question d'Axo avant une réponse courte), jamais la conversation, qui reste intacte. Ses consignes et ses exemples restent dans ce contexte : lus une fois, leur KV est gardé dans `cacheDir/notes-kv/` et rechargé aux lancements suivants (relus seulement si le modèle ou llama.cpp change). Une étape relève les faits (« Note : fait. », format imposé par une grammaire GBNF) ; le code écarte ce que le message ne dit pas, ce qui ne dure pas et les doublons, et une seconde étape ne sert que si un fait ressemblant est déjà noté (lequel n'est plus vrai maintenant, avec le message sous les yeux). Un message envoyé interrompt la prise de notes. « Retiens que… » et « Oublie… » sont compris. Avant chaque message, les faits proches (recherche par mots) sont glissés devant pour le modèle seulement, puis ceux des notes qu'ils nomment (deux liens suivis au plus, `Recall.follow`). Une question sur lui, même écrite vite (« tu c'est comment je m'appelle »), fait relire son prénom et la note « Moi » (son prénom seul s'il ne demande que lui). Écran Cerveau : recherche, étiquettes, journal avec « Annuler », lecture mise au propre ou brute, correction à la main. **Test du Cerveau** (Réglages > Mesures) : 10 messages écrits d'avance, faits retenus, notés pour rien, durée de lecture et d'écriture.
- **Liens du Cerveau** : une note qui en nomme une autre (son titre, ou le prénom pour « Moi ») y est reliée. Les liens sont calculés par le code à chaque changement, sans le modèle ; on en ajoute à la main avec `[[Titre]]` ou la rubrique `## Liens`. Chaque note montre ses liens et ses rétroliens, et les noms soulignés ouvrent la note nommée. **Onglet Graphe** : disposition par ressorts (Fruchterman et Reingold, `brain/GraphLayout.kt`), « Moi » au centre, la même d'une visite à l'autre, zoom, filtres (actives, étiquette, autour d'une note). **Mise en veille** : une note ni ouverte ni utilisée depuis le délai réglé (30 jours par défaut, Réglages > Cerveau) passe en veille ; elle reste cherchable, pèse moins dans les souvenirs, et se réveille dès qu'on s'en sert.
- **Cerveau en direct** : chaque geste du Cerveau (chercher, lire, suivre un lien, réveiller une note, écrire) est un `BrainEvent` que `brain/LiveBrain.kt` déroule à l'écran, chaque geste assez long pour se voir sans jamais faire attendre Axo. Le graphe l'anime (éclat, battement, boule sur le lien suivi, traînée) et glisse vers la note où il travaille ; une bande au-dessus de la réponse montre les notes touchées en arcs ; « Rejouer » remontre le dernier échange au ralenti. Les deux se coupent dans Réglages > Cerveau.
- **Test de vitesse** (Réglages > Mesures) : premier mot, vitesse d'écriture, lecture d'un long message, mémoire utilisée et chauffe, avec les cibles du cahier des charges. Un test d'endurance de 10 minutes compare la vitesse de la fin à celle du début.
- **Comparer les fichiers** (Réglages > Mesures) : Q4_K_M, UD-Q4_K_XL et Q4_0 répondent aux 10 mêmes questions, puis tu votes à l'aveugle. L'app recommande le meilleur fichier et peut l'utiliser tout de suite.
- Réglages : créativité (0,15 par défaut, la valeur conseillée par Mistral pour Ministral 3 Instruct ; le mode Réflexion garde 0,7), longueur max, personnalité d'Axo (prompt système, réglée au banc d'Axo), affichage de la vitesse, Wi-Fi uniquement.
- Fonctionne entièrement en mode avion une fois le modèle téléchargé.
- Vérifie les nouvelles versions : tout de suite si internet est là, sinon dès le retour de la connexion (WorkManager), puis une fois par jour.

## Organisation du code
| Dossier | Rôle |
|---|---|
| `app/src/main/cpp/` | Pont JNI vers llama.cpp (version figée dans `CMakeLists.txt`), tirage avec pénalité DRY contre les répétitions, sauvegarde et reprise du cache KV |
| `.../llm/` | Moteur local : chargement, streaming, séparation de la réflexion, cache des sessions (`SessionCache`) |
| `.../chat/` | Conversation enregistrée, fenêtre d'historique envoyée au modèle, réponses telles qu'affichées |
| `.../brain/` | Cerveau : notes Markdown, prise de notes en tâche de fond, souvenirs (et liens suivis), « retiens / oublie », journal, liens, disposition du graphe, déroulé en direct |
| `.../measure/` | Test de vitesse, endurance, comparaison à l'aveugle, mesures mémoire et chauffe |
| `.../model/` | Catalogue des modèles (3 fichiers par modèle) et téléchargement en arrière-plan |
| `.../update/` | Vérification, téléchargement, vérification SHA-256 et installation des mises à jour |
| `.../settings/` | Réglages (DataStore) et personnalité par défaut d'Axo |
| `.../ui/` | Écrans Compose, thème « Une lueur dans la poche », icônes |
| `app/src/test/.../bench/` | Banc d'Axo : conversations de test et leurs vérifications, sur le vrai modèle |

## Compiler
Il faut Android Studio (ou le SDK Android + NDK) et une connexion internet au premier build (llama.cpp est téléchargé par CMake).

```bash
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

La CI (`.github/workflows/build.yml`) lance les tests et compile l'APK debug à chaque PR ; il est téléchargeable dans l'onglet Actions.

## Banc d'Axo
Le workflow **Banc d'Axo** (`.github/workflows/bench.yml`, lancé à la main dans l'onglet Actions) fait tourner de vraies conversations avec le vrai modèle, par le code de l'appli : le pont JNI est compilé pour l'ordinateur de GitHub, et `AxoBenchTest` charge le fichier choisi (Q4_0 par défaut, gardé en cache) avec la personnalité, les souvenirs du Cerveau (`Recall.recollect`, sur un Cerveau inventé) et les réglages par défaut. Chaque conversation de `bench/BenchCases.kt` (son prénom écrit vite, qui est qui, un salut sans rien supposer de sa journée, rester sur le sujet, ne pas inventer de souvenir, la météo, les emojis, un réveil qu'il ne peut pas promettre…) est rejouée comme à la réouverture de l'appli, les messages d'avant avec leurs souvenirs (sans le rappel du salut), et vérifiée automatiquement, sur la réponse telle que l'appli l'affiche ; le rapport (`report.md`, avec chaque réponse) s'affiche dans le résumé du workflow et se télécharge avec ses fichiers. Après un salut, le banc réécrit aussi le message comme l'appli et vérifie que la suite est celle de la conversation relue. Le banc mesure sans rien bloquer : le workflow ne rate que si le banc n'a pas pu tourner. Le champ « Autre personnalité » essaie un autre texte sans toucher à l'appli (`\n` pour aller à la ligne), « Conversations à rejouer » n'en rejoue que quelques-unes (leurs noms, séparés par des virgules), et « Rappels à ne pas lire » en retire (`HELLO`, `WHO`…) pour mesurer ce qu'ils changent, jusque dans les messages d'après. Hors de ce workflow, le banc est ignoré.

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
