# iLO Console pour Android

Client Android moderne et **non officiel** pour les processeurs de gestion HPE **iLO 3, iLO 4, iLO 5**
(et iLO 6), destiné à remplacer l'application « HPE iLO Mobile » (1.09.7), abandonnée par HPE et qui ne
s'installe plus / ne fonctionne plus correctement sur les versions récentes d'Android.

L'application a été réécrite entièrement en Kotlin + Jetpack Compose (Material 3). Le protocole de la
console distante (« Integrated Remote Console ») a été ré-implémenté à partir de l'analyse de
l'ancienne application, pour l'interopérabilité ; aucun code ni ressource HPE n'est inclus.

## Fonctionnalités

- **Liste de serveurs** avec identifiants stockés chiffrés (clé AES-GCM dans l'Android Keystore).
- **Console distante (KVM)** :
  - vidéo (décodeur DVC de l'iLO, jusqu'à 1920×1200 et plus en mode « demi-hauteur ») ;
  - chiffrement du flux RC4 / AES (iLO 3/4) et protocole « v2 » avec dérivation de clés HMAC-SHA512 (iLO 5+) ;
  - souris tactile : appui = clic gauche, double appui = double-clic, appui long = clic droit,
    glisser à un doigt = déplacer le pointeur, deux doigts = défiler, pincer = zoomer ;
  - souris et clavier physiques (USB/Bluetooth) ;
  - clavier logiciel avec **choix de la disposition du serveur (US ou AZERTY français)** ;
  - barre de touches spéciales : Ctrl / Alt / AltGr / Win / Maj « collantes », Échap, Tab, flèches,
    F1–F12, Ctrl+Alt+Suppr… ;
  - bouton d'alimentation virtuel (appui bref, appui long, démarrage à froid, reset) ;
  - état de l'alimentation, santé du serveur et codes POST en direct.
- **Média virtuel** : connexion d'une image ISO par URL au lecteur CD/DVD virtuel, éjection et
  démarrage sur l'image au prochain redémarrage.
- **Vue d'ensemble** du serveur (modèle, n° de série, état, firmware iLO/BIOS) via Redfish ou l'API
  JSON des anciens iLO, et **commandes d'alimentation Redfish**.
- **Interface web** de l'iLO intégrée (identifiants pré-remplis), avec un bouton pour l'ouvrir
  dans le navigateur du téléphone si besoin.
- **Sécurité TLS** : l'ancienne application acceptait n'importe quel certificat. Celle-ci épingle le
  certificat (souvent auto-signé) de chaque iLO à la première connexion après confirmation de son
  empreinte SHA-256, et alerte s'il change.
- Interface en français et en anglais, thème clair/sombre et couleurs dynamiques (Android 12+).

Configuration requise : Android 8.0 (API 26) ou plus récent. Le téléphone doit pouvoir joindre l'iLO
en HTTPS (443) et sur le port de la console distante (17990 par défaut).

## Installer l'APK

Chaque push déclenche le workflow GitHub Actions **Android build**, qui exécute les tests et produit
les APK :

1. Onglet **Actions** → dernière exécution de « Android build » → artefact **ilo-console-apk**.
2. Décompresser et installer `app-release.apk` sur le téléphone (autoriser l'installation
   d'applications de sources inconnues).

Pour publier une version téléchargeable directement, pousser un tag `v*` (ex. `v2.0.0`) : l'APK est
alors attaché à une *Release* GitHub.

### Signature

Pour que chaque nouvelle version s'installe par-dessus la précédente, l'APK de release doit toujours
être signé avec la même clé. Elle est fournie au workflow par quatre *secrets* GitHub (Settings →
Secrets and variables → Actions) :

| Secret | Contenu |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | le keystore `.jks` encodé en base64 (`base64 -w0 release.jks`) |
| `SIGNING_STORE_PASSWORD` | mot de passe du keystore |
| `SIGNING_KEY_ALIAS` | alias de la clé |
| `SIGNING_KEY_PASSWORD` | mot de passe de la clé |

Une clé se crée avec :
`keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias ilo-console -keyalg RSA -keysize 4096 -validity 10000`.
Sans ces secrets, l'APK est signé avec la clé de debug du runner, qui change à chaque build (il faut
alors désinstaller l'application avant chaque mise à jour). Ne commitez jamais de keystore.

## Compiler soi-même

Prérequis : JDK 17+ et le SDK Android (API 35).

```bash
./gradlew :ilo-core:test          # tests du protocole (JVM pur)
./gradlew :app:assembleDebug      # APK dans app/build/outputs/apk/debug/
```

## Structure

| Module | Contenu |
|---|---|
| `ilo-core` | Bibliothèque Kotlin/JVM sans dépendance Android : API HTTPS de l'iLO (login JSON, `rc_info`, Redfish), épinglage TLS, canaux de la console (poignée de main v1/v2, RC4, AES-OFB8), décodeur vidéo DVC, clavier HID et dispositions US/FR. Testée unitairement. |
| `app` | Application Android : Compose, navigation, stockage chiffré, vue console (rendu, gestes, IME). |

## Limites connues

- Pas de support iLO 2 et antérieurs (pas d'API JSON).
- L'exécution de scripts RIBCL / QR code de l'ancienne application n'est pas reprise.
- Le média virtuel passe par Redfish (iLO 4 firmware 2.30+) et monte une image par URL http(s),
  téléchargée par l'iLO lui-même (licence iLO Advanced requise) : pas d'ISO stocké sur le téléphone.
- Sans licence iLO Advanced, l'iLO n'autorise la console que pendant le POST (limitation HPE).
- Les très vieux firmwares iLO 3 n'acceptant que TLS 1.0 peuvent ne plus être joignables depuis les
  Android récents : mettez le firmware à jour.
- Développée sans accès à du matériel : merci d'ouvrir une *issue* en cas de souci avec un modèle
  d'iLO ou une version de firmware particulière.

## Avertissement

Projet indépendant, non affilié à Hewlett Packard Enterprise. L'icône de l'application est celle de l'application
d'origine HPE iLO Mobile, reprise à la demande du propriétaire de ce dépôt pour un usage personnel. « HPE » et « iLO » sont des marques de
Hewlett Packard Enterprise Development LP.
