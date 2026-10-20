#!/usr/bin/env python3
"""
ClasseViva -> postagenerale
Gira sul telefono (Termux). Legge la bacheca ClasseViva, scarica le circolari nuove
con gli allegati e le invia al progetto Apps Script, che le manda per mail.

Comandi:
  python cv.py test      prova l'accesso e mostra le ultime circolari (non invia nulla)
  python cv.py run       controlla e invia le circolari nuove (usato dalla pianificazione)
  python cv.py invio     imposta indirizzo e codice del ricevitore Apps Script
  python cv.py credenziali  reimposta utente e password ClasseViva
  python cv.py ultima    invia subito l'ultima circolare (prova completa)
  python cv.py diagnosi  mostra le ultime circolari con firma richiesta e stato di invio
  python cv.py recupera [NUMERI]  invia le circolari indicate (es. 251 252), o quelle
                         degli ultimi 3 giorni non ancora inviate se non indichi numeri
  python cv.py sms NUMERO   attiva l'SMS per ogni circolare nuova (sms off per disattivarlo)
  python cv.py prova-avviso manda una notifica (e un SMS se attivo) di prova
"""
import base64
import getpass
import json
import os
import sys
import time
import urllib.error
import urllib.request

DIR = os.path.expanduser("~/.classeviva")
CONF = os.path.join(DIR, "config.json")
STATO = os.path.join(DIR, "viste.json")
BASE = "https://web.spaggiari.eu/rest/v1"
HEADERS = {
    "User-Agent": "CVVS/std/4.2.3 Android/12",
    "Z-Dev-Apikey": "Tg1NWEwNGIgIC0K",
    "Content-Type": "application/json",
}


def log(msg):
    print(time.strftime("%Y-%m-%d %H:%M") + "  " + msg, flush=True)


def carica(path, default):
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return default


def salva(path, data):
    os.makedirs(DIR, exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f)
    os.chmod(path, 0o600)


def http(method, url, headers, body=None, binary=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, raw if binary else raw.decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


# ---------------- ClasseViva ----------------

def login(conf):
    code, body = http("POST", BASE + "/auth/login", HEADERS,
                      {"ident": None, "pass": conf["pass"], "uid": conf["user"]})
    if code != 200:
        raise SystemExit("Login non riuscito (HTTP %s): %s" % (code, body[:300]))
    j = json.loads(body)
    if "choices" in j and not j.get("token"):
        log("Profili disponibili: " + ", ".join(c.get("ident", "") for c in j["choices"]))
        code, body = http("POST", BASE + "/auth/login", HEADERS,
                          {"ident": j["choices"][0]["ident"], "pass": conf["pass"], "uid": conf["user"]})
        if code != 200:
            raise SystemExit("Login profilo non riuscito (HTTP %s): %s" % (code, body[:300]))
        j = json.loads(body)
    ident = j["ident"]
    h = dict(HEADERS)
    h["Z-Auth-Token"] = j["token"]
    return {"h": h, "ident": ident, "id": "".join(c for c in ident if c.isdigit())}


def bacheca(s):
    errori = []
    for percorso in ("students", "teachers"):
        code, body = http("GET", "%s/%s/%s/noticeboard" % (BASE, percorso, s["id"]), s["h"])
        if code == 200:
            s["percorso"] = percorso
            return json.loads(body).get("items", [])
        errori.append("%s -> HTTP %s %s" % (percorso, code, body[:150]))
    raise SystemExit("Bacheca non leggibile. " + " | ".join(errori))


def allegati(s, it):
    out = []
    for i, a in enumerate(it.get("attachments") or []):
        num = a.get("attachNum") or (i + 1)
        url = "%s/%s/%s/noticeboard/attach/%s/%s/%s" % (BASE, s["percorso"], s["id"], it["evtCode"], it["pubId"], num)
        code, data = http("GET", url, s["h"], binary=True)
        if code == 200 and isinstance(data, bytes):
            out.append({"nome": a.get("fileName") or "allegato_%d.pdf" % (i + 1),
                        "base64": base64.b64encode(data).decode()})
        else:
            log("Allegato non scaricato (%s): %s" % (code, a.get("fileName", "")))
    return out


AZIONI = (("needSign", "conferma e firma"), ("needJoin", "adesione"),
          ("needReply", "risposta"), ("needFile", "caricamento di un file"))


def azioni(it):
    """Azioni richieste dalla circolare (es. "Conferma e firma" sull'app)."""
    return [nome for campo, nome in AZIONI if it.get(campo)]


def numero(it):
    titolo = (it.get("cntTitle") or "").strip()
    n = titolo.split(" ", 1)[0].split("-", 1)[0].strip()
    return n if n.isdigit() else ""


def prepara(s, it):
    testo = it.get("cntText", "") or ""
    allegati_ok = allegati(s, it)
    note = []
    richieste = azioni(it)
    if richieste:
        note.append("AZIONE RICHIESTA su ClasseViva: " + ", ".join(richieste) + ".")
    attesi = len(it.get("attachments") or [])
    if attesi > len(allegati_ok):
        note.append("Allegati non scaricati: %d su %d (aprili dall'app ClasseViva%s)."
                    % (attesi - len(allegati_ok), attesi, ", dopo la conferma" if richieste else ""))
    if note:
        testo = "\n".join(note) + ("\n\n" + testo if testo else "")
    return {
        "titolo": it.get("cntTitle", "Circolare"),
        "data": it.get("cntValidFrom", ""),
        "categoria": it.get("cntCategory", ""),
        "testo": testo,
        "allegati": allegati_ok,
    }


def segna_letta(s, it):
    url = "%s/%s/%s/noticeboard/read/%s/%s/101" % (BASE, s["percorso"], s["id"], it["evtCode"], it["pubId"])
    http("POST", url, s["h"], {})


# ---------------- Invio ----------------

def invia(conf, circolare):
    req = urllib.request.Request(
        conf["endpoint"],
        data=json.dumps(dict(circolare, token=conf["token"])).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=120) as r:  # Apps Script risponde dopo un redirect
        risposta = r.read().decode("utf-8", "replace")
    if '"ok":true' not in risposta.replace(" ", ""):
        raise RuntimeError("Ricevitore: " + risposta[:200])


# ---------------- Avvisi sul telefono ----------------

def avvisa(conf, titolo):
    """Notifica sul telefono e, se impostato, SMS. Richiede l'app Termux:API e il pacchetto termux-api."""
    import shutil
    import subprocess
    testo = "Nuova circolare ClasseViva: " + titolo
    if shutil.which("termux-notification"):
        subprocess.run(["termux-notification", "--title", "Nuova circolare ClasseViva",
                        "--content", titolo, "--id", "classeviva"], timeout=30)
    numero = conf.get("sms")
    if numero and shutil.which("termux-sms-send"):
        subprocess.run(["termux-sms-send", "-n", numero, testo[:300]], timeout=60)


# ---------------- Comandi ----------------

def chiedi_credenziali(conf):
    print("Credenziali ClasseViva (restano solo su questo telefono)")
    conf["user"] = input("Utente (codice o email): ").strip()
    conf["pass"] = getpass.getpass("Password (non si vede mentre scrivi): ")
    salva(CONF, conf)
    print("Salvate.")


def cmd_test(conf):
    s = login(conf)
    log("Accesso riuscito. Profilo: %s" % s["ident"][0])
    items = bacheca(s)
    log("Bacheca letta (percorso %s). Circolari: %d" % (s["percorso"], len(items)))
    for it in items[:5]:
        print("  - %s | %s | allegati: %d" % (it.get("cntValidFrom", ""), it.get("cntTitle", ""),
                                              len(it.get("attachments") or [])))


def cmd_run(conf):
    if not conf.get("endpoint"):
        raise SystemExit("Manca il ricevitore: esegui prima  python cv.py invio")
    viste = carica(STATO, None)
    primo = viste is None
    viste = viste or {}
    s = login(conf)
    items = bacheca(s)
    nuove = 0
    errori = 0
    for it in items:
        chiave = "%s_%s" % (it.get("evtCode"), it.get("pubId"))
        if viste.get(chiave) == 1:
            continue
        if primo:
            viste[chiave] = 1
            continue
        try:
            circ = prepara(s, it)
            invia(conf, circ)
        except Exception as e:
            # una circolare che non parte non deve bloccare le altre: si riprova al prossimo controllo
            errori += 1
            log("NON inviata (si riprova): %s -> %s" % (it.get("cntTitle", ""), e))
            continue
        viste[chiave] = 1
        salva(STATO, viste)
        nuove += 1
        log("Inviata: " + circ["titolo"] + (" [richiede: %s]" % ", ".join(azioni(it)) if azioni(it) else ""))
        # le circolari da firmare restano "da leggere" sull'app, così si vede che c'è da confermare
        if not azioni(it):
            segna_letta(s, it)
        try:
            avvisa(conf, circ["titolo"])
        except Exception as e:
            log("Avviso non riuscito: %s" % e)
    salva(STATO, viste)
    log("Primo avvio: segnate come già viste %d circolari." % len(items) if primo
        else "Controllo fatto. Circolari nuove: %d%s" % (nuove, ", non inviate: %d" % errori if errori else ""))


def cmd_ultima(conf):
    """Invia subito l'ultima circolare pubblicata, per provare tutta la catena."""
    if not conf.get("endpoint"):
        raise SystemExit("Manca il ricevitore: esegui prima  python cv.py invio")
    s = login(conf)
    items = bacheca(s)
    if not items:
        raise SystemExit("Bacheca vuota.")
    it = items[0]
    circ = prepara(s, it)
    invia(conf, circ)
    log("Inviata di prova: %s (allegati: %d)" % (circ["titolo"], len(circ["allegati"])))


def cmd_diagnosi(conf):
    viste = carica(STATO, {}) or {}
    s = login(conf)
    items = bacheca(s)
    log("Bacheca: %d circolari. Ultime 15:" % len(items))
    for it in items[:15]:
        chiave = "%s_%s" % (it.get("evtCode"), it.get("pubId"))
        stato = "inviata" if viste.get(chiave) == 1 else "NON inviata"
        print("  %-11s | %s | allegati %d | %s | %s" % (
            stato, it.get("cntValidFrom", ""), len(it.get("attachments") or []),
            ", ".join(azioni(it)) or "-", (it.get("cntTitle") or "")[:60]))


def cmd_recupera(conf, numeri):
    if not conf.get("endpoint"):
        raise SystemExit("Manca il ricevitore: esegui prima  python cv.py invio")
    viste = carica(STATO, {}) or {}
    s = login(conf)
    items = bacheca(s)
    limite = time.strftime("%Y-%m-%d", time.localtime(time.time() - 3 * 86400))
    scelte = []
    for it in items:
        chiave = "%s_%s" % (it.get("evtCode"), it.get("pubId"))
        if numeri:
            if numero(it) in numeri:
                scelte.append((chiave, it))
        elif viste.get(chiave) != 1 and (it.get("cntValidFrom") or "") >= limite:
            scelte.append((chiave, it))
    if not scelte:
        log("Nessuna circolare da recuperare.")
        return
    for chiave, it in scelte:
        try:
            invia(conf, prepara(s, it))
        except Exception as e:
            log("NON inviata: %s -> %s" % (it.get("cntTitle", ""), e))
            continue
        viste[chiave] = 1
        salva(STATO, viste)
        log("Recuperata: " + it.get("cntTitle", ""))


def main():
    conf = carica(CONF, {})
    cmd = sys.argv[1] if len(sys.argv) > 1 else "test"
    if cmd == "credenziali" or not conf.get("user"):
        chiedi_credenziali(conf)
        if cmd == "credenziali":
            return
    if cmd == "test":
        cmd_test(conf)
    elif cmd == "run":
        cmd_run(conf)
    elif cmd == "invio":
        if len(sys.argv) >= 4:
            conf["endpoint"], conf["token"] = sys.argv[2].strip(), sys.argv[3].strip()
        else:
            conf["endpoint"] = input("Indirizzo del ricevitore (URL): ").strip()
            conf["token"] = input("Codice segreto: ").strip()
        salva(CONF, conf)
        print("Ricevitore salvato.")
    elif cmd == "ultima":
        cmd_ultima(conf)
    elif cmd == "diagnosi":
        cmd_diagnosi(conf)
    elif cmd == "recupera":
        cmd_recupera(conf, [n.strip() for n in sys.argv[2:] if n.strip()])
    elif cmd == "sms":
        if len(sys.argv) >= 3 and sys.argv[2].strip().lower() != "off":
            conf["sms"] = sys.argv[2].strip()
            print("SMS attivo verso " + conf["sms"])
        else:
            conf.pop("sms", None)
            print("SMS disattivato.")
        salva(CONF, conf)
    elif cmd == "prova-avviso":
        avvisa(conf, "Prova avviso circolari")
        print("Avviso di prova inviato.")
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
