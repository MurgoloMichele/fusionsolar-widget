# FusionSolar Widget

Widget Android per la schermata Home che mostra in tempo reale i dati del tuo impianto fotovoltaico **Huawei FusionSolar**, senza aprire l'app.

- ☀️ **Produzione FV** (kW)
- 🏠 **Consumo casa** (kW)
- ⚡ **Rete**: immissione / prelievo (kW)
- 🔋 **Batteria**: % di carica, carica/scarica (kW)
- 📅 **Oggi**: energia prodotta e consumata (kWh)

Widget trasparente, ridimensionabile (4×2), aggiornamento automatico ogni 15 minuti, pulsante ⟳ per aggiornare subito. Toccando il widget si apre l'app ufficiale FusionSolar.

## Installazione

1. Scarica l'APK dalla pagina [Releases](../../releases) e installalo (consenti "origini sconosciute").
2. Apri **Solar Widget**, inserisci utente e password FusionSolar, premi **Salva e prova**.
3. Schermata Home → tieni premuto → Widget → **Solar Widget**.

Le credenziali restano solo sul telefono; la password è cifrata con una chiave dell'Android Keystore. Nessun server di terze parti: l'app parla solo con `*.fusionsolar.huawei.com`.

## Compilare

Requisiti: JDK 17, Android SDK (platform 34).

```bash
./gradlew assembleRelease
```

L'APK si trova in `app/build/outputs/apk/release/`.

## Come funziona

Usa le stesse API web del portale FusionSolar usate dall'app ufficiale:

| Passo | Endpoint |
|---|---|
| Chiave pubblica | `GET /unisso/pubkey` |
| Login (password cifrata RSA-OAEP SHA-384) | `POST /unisso/v3/validateUser.action` |
| Sessione / token CSRF | `GET /rest/dpcloud/auth/v1/keep-alive` (fallback `/rest/neteco/auth/v1/keep-alive`) |
| Impianto | `POST /rest/pvms/web/station/v1/station/station-list` |
| Flusso energia in tempo reale | `GET /rest/pvms/web/station/v{1,2,3}/overview/energy-flow` |
| Energia del giorno | `GET /rest/pvms/web/station/v2/overview/energy-balance` |

Il server regionale (es. `uni004eu5`) viene rilevato automaticamente dal redirect del login. Il login parte da `eu5.fusionsolar.huawei.com` (Europa): per altre regioni modifica `loginHost` in `FusionApi.kt`.

Se un valore non torna, nell'app c'è **Mostra dati grezzi (debug)** con il log dei passaggi e la risposta JSON.

## Avvertenze

Progetto non ufficiale, non affiliato a Huawei. Le API non sono documentate e possono cambiare in qualsiasi momento.

## Licenza

[MIT](LICENSE)
