#!/data/data/com.termux/files/usr/bin/bash
# Installazione su Termux (Android) dello script circolari ClasseViva
set -e
REPO="https://raw.githubusercontent.com/famcellini/classeviva-circolari/main"
DIR="$HOME/classeviva"

echo "== Installo i programmi necessari (può volerci qualche minuto) =="
pkg update -y >/dev/null 2>&1 || true
pkg install -y python cronie termux-services >/dev/null

echo "== Scarico lo script =="
mkdir -p "$DIR"
curl -fsSL "$REPO/cv.py" -o "$DIR/cv.py"

if crontab -l 2>/dev/null | grep -q 'classeviva/cv.py'; then
  echo "== Pianificazione già presente: la lascio com'è =="
else
  echo "== Pianifico il controllo (lun-sab, due volte al giorno) =="
  CMD="python $DIR/cv.py run >> $DIR/log.txt 2>&1"
  ( crontab -l 2>/dev/null ; \
    echo "48 7 * * 1,3,5 $CMD" ; \
    echo "9 14 * * 1,3,5 $CMD" ; \
    echo "14 7 * * 2,4,6 $CMD" ; \
    echo "52 13 * * 2,4,6 $CMD" ) | crontab -
fi

echo "== Avvio automatico all'accensione del telefono (serve Termux:Boot) =="
mkdir -p "$HOME/.termux/boot"
cat > "$HOME/.termux/boot/classeviva.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock
crond
EOF
chmod +x "$HOME/.termux/boot/classeviva.sh"
termux-wake-lock || true
pgrep crond >/dev/null || crond

echo
echo "== Installazione completata =="
echo "Ora inserisci le credenziali ClasseViva con:"
echo "  python ~/classeviva/cv.py credenziali"
echo "e prova l'accesso con:"
echo "  python ~/classeviva/cv.py test"
