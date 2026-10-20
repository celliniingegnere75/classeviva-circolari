# classeviva-circolari

Script che gira su un telefono Android (Termux): ogni 2 ore legge la bacheca ClasseViva,
scarica le circolari nuove con gli allegati e le invia a un ricevitore Google Apps Script,
che le manda per mail.

Le credenziali ClasseViva restano solo sul telefono (`~/.classeviva/config.json`).
Questo repository contiene solo codice.

## Installazione (in Termux)

```
curl -fsSL https://raw.githubusercontent.com/famcellini/classeviva-circolari/main/install.sh | bash
```

## Comandi

- `python ~/classeviva/cv.py test`: prova l'accesso e mostra le ultime circolari
- `python ~/classeviva/cv.py invio`: imposta il ricevitore Apps Script
- `python ~/classeviva/cv.py run`: controllo manuale
- `python ~/classeviva/cv.py diagnosi`: ultime circolari, firma richiesta e se sono state inviate
- `python ~/classeviva/cv.py recupera 251 252`: invia le circolari indicate (senza numeri: quelle non inviate degli ultimi 3 giorni)
- `python ~/classeviva/cv.py credenziali`: cambia utente e password
- `cat ~/classeviva/log.txt`: storico dei controlli automatici

## Aggiornare lo script sul telefono

```
curl -fsSL https://raw.githubusercontent.com/famcellini/classeviva-circolari/main/cv.py -o ~/classeviva/cv.py
```
