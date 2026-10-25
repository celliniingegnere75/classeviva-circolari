package it.cellini.classepiu

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voti della classe: per ogni alunno una casella con le righe orale, scritto/grafico, test (solo quelle che hanno voti).
 * I voti scorrono in orizzontale; toccandone uno si apre data e annotazioni per la famiglia, modificabili.
 */
class VotiScheda : Scheda() {
    private val azzurro = 0xFF69BCE8.toInt()
    private lateinit var lista: LinearLayout
    private var tipo = ""
    private var classeId = ""
    private var primoGiorno: java.util.Calendar = java.util.Calendar.getInstance().apply { // se le lezioni non si leggono: 1 settembre
        val o = java.util.Calendar.getInstance(); set(if (o.get(java.util.Calendar.MONTH) >= java.util.Calendar.SEPTEMBER) o.get(java.util.Calendar.YEAR) else o.get(java.util.Calendar.YEAR) - 1, java.util.Calendar.SEPTEMBER, 1)
    }
    /** Livello di voto della tabella di classeviva: codice, scritta mostrata e valore decimale. */
    private class Livello(val codice: String, val scritta: String, val decimale: Double)
    private var livelli: List<Livello> = emptyList()
    /** Componenti (scritto/orale/pratico) per periodo, come in evaluations/components, e codice del periodo per periodPos ("Q1", "Q3"). */
    private var componenti: List<org.json.JSONObject> = emptyList()
    private var codiciPeriodo: Map<Int, String> = emptyMap()
    private var mioId = ""
    private var elenco: List<Studente> = emptyList()
    private var tutti: Map<String, List<Voto>> = emptyMap()
    private lateinit var titolo: TextView
    private lateinit var linguette: LinearLayout
    private var nomeClasse = ""
    private var quadrimestre = ""
    /** Materia della linguetta aperta e linguette disegnate (materia, vista). */
    private var materiaScelta = ""
    private var schede: List<Pair<String, TextView>> = emptyList()
    private val memoria by lazy { requireContext().getSharedPreferences("voti_modificati", android.content.Context.MODE_PRIVATE) }

    override fun crea(savedInstanceState: Bundle?) {
        memoria.edit().clear().apply() // copie locali delle versioni precedenti: non servono più
        primoGiorno = Scuola.primoGiorno(requireContext()) // poi si aggiorna con i periodi letti da classeviva
        val nome = intent.getStringExtra("nome") ?: ""
        tipo = intent.getStringExtra("tipo") ?: ""
        classeId = intent.getStringExtra("id") ?: ""
        val ctx = requireContext()

        val chiudi = TextView(ctx).apply { text = "✕"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setOnClickListener { finish() } }
        nomeClasse = nome
        titolo = TextView(ctx).apply {
            textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        }
        // finché classeviva non dà i periodi: da settembre a gennaio primo quadrimestre, poi secondo
        val mese = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)
        quadrimestre = if (mese >= java.util.Calendar.SEPTEMBER || mese == java.util.Calendar.JANUARY) "I° quadrimestre" else "II° quadrimestre"
        aggiornaTitolo()
        val barra = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(chiudi, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(titolo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(android.view.View(ctx), LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        lista = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(16 + SPAZIO_BARRA)) }
        // linguette delle materie, ferme in alto; i voti scorrono sotto
        linguette = LinearLayout(ctx).apply { setPadding(dp(14), dp(14), dp(14), dp(6)) }
        val barraMaterie = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(linguette) }
        val pannello = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(barraMaterie, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(ScrollView(ctx).apply { addView(lista) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            background = GradientDrawable().apply { setColor(0xFFF0F0F0.toInt()); val r = dp(28).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            clipToOutline = true
        }
        setContentView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(azzurro)
            addView(barra)
            addView(pannello, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        })
        carica()
    }

    private fun carica() {
        testo("carico i voti…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val c = Impostazioni(requireContext()).client()
                    mioId = c.idDocente()
                    c.studentiGrezzi(tipo, classeId) to c.votiGrezzi(tipo, classeId)
                }
            }
            // tabella dei voti ammessi e periodi (primo giorno di scuola = inizio del primo periodo)
            launch(Dispatchers.IO) {
                runCatching {
                    val t = Impostazioni(requireContext()).client().componentiPrimaMia(tipo, classeId) ?: return@runCatching
                    val o = org.json.JSONObject(t)
                    o.optJSONArray("levels")?.let { a ->
                        livelli = (0 until a.length()).mapNotNull { i ->
                            val l = a.optJSONObject(i) ?: return@mapNotNull null
                            Livello(l.optString("levelCode"), l.optString("displayValue"), l.optDouble("decimalValue", Double.NaN))
                        }.filter { it.scritta.isNotEmpty() }
                    }
                    val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ITALIAN)
                    // componenti: oggetti con componentId ovunque nella risposta (Q1:S1_2:ORL:0 = orale del 1° quadrimestre)
                    val trovati = ArrayList<org.json.JSONObject>()
                    fun cerca(x: Any?, liv: Int) {
                        if (liv > 5) return
                        when (x) {
                            is org.json.JSONObject -> if (x.has("componentId") && x.has("componentName")) trovati.add(x) else x.keys().forEach { cerca(x.opt(it), liv + 1) }
                            is org.json.JSONArray -> for (k in 0 until x.length()) cerca(x.opt(k), liv + 1)
                        }
                    }
                    cerca(o, 0)
                    componenti = trovati
                    o.optJSONArray("periods")?.let { a ->
                        codiciPeriodo = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.has("periodPos") }
                            .associate { it.optInt("periodPos") to it.optString("periodCode") }
                    }
                    o.optJSONArray("periods")?.let { a ->
                        // come l'app ufficiale: periodi con dateStart/dateEnd; "isFinal" è vero anche per il 2° quadrimestre (periodo dello scrutinio finale)
                        val periodi = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                            .filter { it.optString("dateStart").length >= 10 }.sortedBy { it.optString("dateStart") }
                        periodi.firstOrNull()?.let {
                            primoGiorno.time = f.parse(it.optString("dateStart").take(10))!!
                            context?.let { c -> Scuola.salva(c, primoGiorno) } // le altre schede non vanno prima di questo giorno
                        }
                        // periodo in corso: quello che contiene oggi; fuori dalle date resta il più vicino già iniziato
                        val oggi = f.format(java.util.Date())
                        val i = periodi.indexOfFirst { it.optString("dateStart").take(10) <= oggi && it.optString("dateEnd").let { e -> e.length < 10 || oggi <= e.take(10) } }
                            .takeIf { it >= 0 } ?: periodi.indexOfLast { it.optString("dateStart").take(10) <= oggi }
                        if (i >= 0) {
                            val p = periodi[i]
                            // posizione per ordine di date: il codice del 2° quadrimestre è "Q3", quindi non si usa il codice
                            val pos = i + 1
                            val nome = p.optString("periodDesc").lowercase().replace(Regex("[^a-zàèéìòù ]"), " ").trim()
                                .split(" ").lastOrNull { it.isNotEmpty() } ?: ""
                            quadrimestre = listOf("I°", "II°", "III°", "IV°").getOrElse(pos - 1) { "$pos°" } + " " + nome.ifEmpty { "quadrimestre" }
                            titolo.post { aggiornaTitolo() }
                        }
                    }
                }
            }
            val (st, vt) = r.getOrElse { testo("non riuscito: ${it.message}"); return@launch }
            val studenti = if (st.first == 200) leggiStudenti(st.second) else emptyList()
            if (vt.first != 200) { testo("classeviva non ha dato i voti (http ${vt.first}).", vt.second); return@launch }
            val voti = leggiVoti(vt.second)
            if (studenti.isEmpty()) { testo("nessuno studente riconosciuto.", st.second); return@launch }
            if (voti.isEmpty()) { testo("nessun voto riconosciuto nella risposta.", vt.second); return@launch }
            elenco = studenti; tutti = voti
            creaLinguette(materieMie(vt.second))
            mostra(studenti, voti)
        }
    }

    private fun aggiornaTitolo() {
        val t = android.text.SpannableString(if (quadrimestre.isEmpty()) "voti $nomeClasse" else "voti $nomeClasse - $quadrimestre")
        // il quadrimestre più piccolo, accanto al nome della classe
        if (quadrimestre.isNotEmpty()) t.setSpan(android.text.style.RelativeSizeSpan(0.65f), t.length - quadrimestre.length, t.length, 0)
        titolo.text = t
    }

    /** Materie del docente nella classe, in ordine di risposta; ed. civica ha la sua riga, quindi non ha linguetta (salvo sia l'unica). */
    private fun materieMie(risposta: String): List<String> {
        val tutte = runCatching {
            val a = org.json.JSONArray(risposta)
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("subjectDesc")?.takeIf { s -> s.isNotEmpty() } }.distinct()
        }.getOrDefault(emptyList())
        return tutte.filterNot { it.contains("civic", ignoreCase = true) }.ifEmpty { tutte }
    }

    private fun creaLinguette(materie: List<String>) {
        linguette.removeAllViews()
        if (materiaScelta !in materie) materiaScelta = materie.firstOrNull() ?: ""
        // due linguette riempiono lo schermo; dalla terza in poi si scorre e la terza si vede appena
        val larga = ((resources.displayMetrics.widthPixels - dp(28)) / 2.3).toInt()
        schede = materie.map { m ->
            val t = TextView(requireContext()).apply {
                text = m.lowercase(); textSize = 14f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { materiaScelta = m; coloraLinguette(); mostra(elenco, tutti) }
            }
            val lp = if (materie.size <= 2) LinearLayout.LayoutParams(0, dp(40), 1f) else LinearLayout.LayoutParams(larga, dp(40))
            linguette.addView(t, lp.apply { rightMargin = dp(8) })
            m to t
        }
        coloraLinguette()
    }

    private fun coloraLinguette() {
        schede.forEach { (m, t) ->
            val scelta = m == materiaScelta
            t.setTextColor(if (scelta) Color.WHITE else 0xFF555555.toInt())
            t.background = GradientDrawable().apply { setColor(if (scelta) azzurro else 0xFFFFFFFF.toInt()); cornerRadius = dp(20).toFloat() }
        }
    }

    private fun mostra(studenti: List<Studente>, tuttiVoti: Map<String, List<Voto>>) {
        lista.removeAllViews()
        val voti = tuttiVoti
        val ctx = requireContext()
        // righe sempre in questo ordine; altri tipi di prova (es. pratico) seguono con il proprio nome
        fun gruppo(t: String) = when {
            t.lowercase().contains("oral") -> "orale"
            t.lowercase().contains("scritt") || t.lowercase().contains("grafic") -> "scritto"
            t.lowercase().contains("pratic") -> "pratico"
            t.lowercase().contains("test") -> "test"
            else -> t.lowercase()
        }
        val ordine = listOf("orale", "scritto", "pratico", "test")
        studenti.forEach { s ->
            val dello = voti[s.id] ?: emptyList()
            // ed. civica: riga a parte con i voti di tutti i docenti
            val civica = dello.filter { it.materia.contains("civic", ignoreCase = true) }
            // solo i voti della materia della linguetta aperta (se è ed. civica, la riga civica basta)
            val suoi = (dello - civica.toSet()).filter { materiaScelta.isEmpty() || it.materia.equals(materiaScelta, ignoreCase = true) }.groupBy { gruppo(it.tipo) }
            val scatola = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply { setColor(0xFFFCFCFC.toInt()); cornerRadius = dp(16).toFloat() }
                addView(TextView(ctx).apply { text = s.nome; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF111111.toInt()); setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END })
            }
            val righe = ordine.filter { suoi.containsKey(it) } + suoi.keys.filter { it !in ordine }
            if (righe.isEmpty() && civica.isEmpty()) scatola.addView(TextView(ctx).apply { text = "nessun voto"; textSize = 13f; setTextColor(0xFF9A9A9A.toInt()); setPadding(0, dp(4), 0, 0) })
            fun riga(etichetta: String, colore: Int, elenco: List<Voto>) {
                // intestazione fissa a sinistra, voti che scorrono a destra sulla stessa riga
                val chip = LinearLayout(ctx)
                elenco.sortedBy { it.data }.forEach { v ->
                    chip.addView(chipVoto(modificato(v)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { rightMargin = dp(8) })
                }
                scatola.addView(LinearLayout(ctx).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(ctx).apply { text = etichetta; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(colore) },
                        LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chip) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
            righe.forEach { g -> riga(g, colorePerRiga(g), suoi[g].orEmpty()) }
            if (civica.isNotEmpty()) riga("civica", 0xFFE57399.toInt(), civica)
            lista.addView(scatola, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
    }

    /** 5½ = 5,5; 5+ = 5,25; 6- = 5,75; i numeri semplici restano tali. */
    private fun numeroDa(v: String): Double? {
        livelli.firstOrNull { it.scritta == v.trim() }?.let { return it.decimale.takeIf { d -> !d.isNaN() && d >= 0 } }
        val t = v.trim().replace(',', '.')
        t.toDoubleOrNull()?.let { return it }
        val base = t.trimEnd('+', '-', '½').toDoubleOrNull() ?: return null
        return base + when (t.last()) { '½' -> 0.5; '+' -> 0.25; '-' -> -0.25; else -> 0.0 }
    }

    private fun colorePerRiga(g: String) = when (g) { "orale" -> 0xFF4CAF7D.toInt(); "scritto" -> azzurro; "pratico" -> 0xFF9B7FD4.toInt(); "test" -> 0xFFF0A040.toInt(); else -> 0xFF8E8E8E.toInt() }

    private fun coloreVoto(n: Double?) = when {
        n == null -> 0xFF8E8E8E.toInt()
        n < 5 -> 0xFFE53935.toInt()
        n < 6 -> 0xFFF0A040.toInt()
        else -> 0xFF2EB82E.toInt()
    }

    private fun chipVoto(v: Voto) = TextView(requireContext()).apply {
        text = v.valore; textSize = 16f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0); minWidth = dp(46)
        val colore = coloreVoto(v.numero)
        // i voti che non fanno media (peso 0) hanno il bordo e lo sfondo chiaro
        if (v.faMedia) { setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(colore); cornerRadius = dp(12).toFloat() } }
        else { setTextColor(colore); background = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); setStroke(dp(2), colore); cornerRadius = dp(12).toFloat() } }
        // modificabile solo se l'ha inserito il docente che guarda (io); se non si sa chi l'ha inserito, sola lettura
        setOnClickListener { apriVoto(v, mioId.isNotEmpty() && v.autore == mioId) }
    }

    /**
     * Si mostra sempre il voto com'è su classeviva: le modifiche non salvate non restano più sul telefono
     * (le vecchie copie salvate dalle versioni precedenti vengono cancellate all'apertura).
     */
    private fun modificato(v: Voto): Voto = v

    private fun apriVoto(v: Voto, modificabile: Boolean) {
        val ctx = requireContext()
        val fIt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.ITALIAN)
        val fApi = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ITALIAN)
        val tipi = listOf("scritto/grafico", "orale", "pratico")
        var valoreScelto = v.valore
        var tipoScelto = v.tipo.lowercase()
        var mediaScelta = v.faMedia
        var scelta: java.util.Date? = runCatching { fApi.parse(v.data.take(10)) }.getOrNull()
        lateinit var finestra: androidx.appcompat.app.AlertDialog
        lateinit var txtValore: TextView
        lateinit var txtTipo: TextView
        lateinit var txtData: TextView
        lateinit var media: android.widget.CheckBox

        fun testoModificabile(t: String) = if (modificabile) "$t ▾" else t
        // solo i voti numerici e la "i" possono fare media: per gli altri la spunta non compare
        fun puoFareMedia(t: String) = t.trim() == "i" || numeroDa(t) != null
        fun aggiornaValore() {
            txtValore.text = testoModificabile(valoreScelto); txtValore.setTextColor(coloreVoto(numeroDa(valoreScelto)))
            val puoMedia = puoFareMedia(valoreScelto)
            media.visibility = if (puoMedia) android.view.View.VISIBLE else android.view.View.GONE
            if (!puoMedia) { mediaScelta = false; media.isChecked = false }
        }
        fun aggiornaTipo() { txtTipo.text = testoModificabile(tipoScelto); txtTipo.setTextColor(colorePerRiga(if (tipoScelto.contains("scritt")) "scritto" else tipoScelto)) }
        fun aggiornaData() { txtData.text = scelta?.let { fIt.format(it) } ?: "data" }
        fun sceltaValore() {
            // voti ammessi: la tabella di classeviva; se non si legge, una scala numerica di ripiego
            val voci = livelli.map { it.scritta }.ifEmpty { (1..10).flatMap { n -> listOf("$n-", "$n", "$n+", "$n½").filter { n < 10 || it == "10" || it == "10-" } }.drop(1) }
            MaterialAlertDialogBuilder(ctx).setTitle("voto")
                .setItems(voci.toTypedArray()) { _, i -> valoreScelto = voci[i]; aggiornaValore() }
                .show()
        }
        fun sceltaTipo() {
            MaterialAlertDialogBuilder(ctx).setTitle("tipo")
                .setItems(tipi.toTypedArray()) { _, i -> tipoScelto = tipi[i]; aggiornaTipo() }
                .show()
        }
        fun sceltaData() {
            val c = java.util.Calendar.getInstance().apply { time = scelta ?: java.util.Date() }
            android.app.DatePickerDialog(ctx, { _, a, m, g ->
                scelta = java.util.Calendar.getInstance().apply { set(a, m, g) }.time
                aggiornaData()
            }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).apply {
                datePicker.minDate = primoGiorno.timeInMillis // non prima del primo giorno di scuola
            }.show()
        }

        // riga 1: voto - materia - data
        txtValore = TextView(ctx).apply {
            textSize = 22f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(2), dp(6), dp(8), dp(6))
            if (modificabile) { setOnClickListener { sceltaValore() } }
        }
        val txtMateria = TextView(ctx).apply {
            text = v.materia.lowercase(); textSize = 14f; setTextColor(0xFF555555.toInt()); setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        txtData = TextView(ctx).apply {
            textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF333333.toInt()); setPadding(dp(8), dp(6), dp(2), dp(6))
            if (modificabile) { setOnClickListener { sceltaData() } }
        }
        // tipo (scritto/grafico, orale, pratico): occupa lo spazio in mezzo tra voto e data
        txtTipo = TextView(ctx).apply {
            textSize = 16f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), dp(6), dp(8), dp(6))
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
            // se il tipo è lungo il testo si rimpicciolisce fino a 11 sp, poi si tronca con i puntini
            if (Build.VERSION.SDK_INT >= 26) setAutoSizeTextTypeUniformWithConfiguration(11, 16, 1, TypedValue.COMPLEX_UNIT_SP)
            if (modificabile) { setOnClickListener { sceltaTipo() } }
        }
        val riga1 = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(txtValore)
            addView(txtTipo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(txtData)
        }

        // riga 2: materia e spunta "media" (spuntata = il voto fa media)
        media = android.widget.CheckBox(ctx).apply {
            text = "media"; textSize = 15f; isChecked = mediaScelta; isEnabled = modificabile
            buttonTintList = android.content.res.ColorStateList.valueOf(azzurro)
            setOnCheckedChangeListener { _, c -> mediaScelta = c }
        }
        val riga2 = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(txtMateria, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(media)
        }

        // riga 3: cancella (solo sui propri voti)
        // pulsante rosso al centro, stessa grandezza di carattere di annulla e salva; il riquadro segue la scritta con pochi px in più
        val cancella = TextView(ctx).apply {
            text = "cancella"; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            includeFontPadding = false; gravity = Gravity.CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply { setColor(0xFFE53935.toInt()); cornerRadius = dp(10).toFloat() }
            if (modificabile) {
                setOnClickListener {
                    MaterialAlertDialogBuilder(ctx).setMessage("cancellare questo voto da classeviva?")
                        .setNegativeButton("no", null)
                        .setPositiveButton("cancella") { _, _ -> finestra.dismiss(); cancellaSuClasseviva(v) }
                        .show()
                }
            } else {
                visibility = android.view.View.GONE
            }
        }
        val riga3 = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(cancella, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(4) })
        }

        // annotazioni: etichetta quasi attaccata al campo di testo
        val etichetta = TextView(ctx).apply { text = "annotazioni per la famiglia"; textSize = 12f; setTextColor(0xFF777777.toInt()); setPadding(0, dp(28), 0, 0) }
        val nota = EditText(ctx).apply {
            setText(v.nota); textSize = 15f; minLines = 3; gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(4), dp(2), dp(4), dp(4)); isEnabled = modificabile
        }

        fun bottone(t: String, azione: () -> Unit) = TextView(ctx).apply {
            text = t; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(azzurro); setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { azione() }
        }
        // annulla all'estremo sinistro, salva all'estremo destro della finestra
        val pulsanti = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(bottone(if (modificabile) "annulla" else "chiudi") { finestra.dismiss() })
            addView(android.view.View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            if (modificabile) {
                addView(bottone("salva") {
                    val dataApi = scelta?.let { fApi.format(it) } ?: v.data
                    salvaSuClasseviva(v, valoreScelto, tipoScelto, mediaScelta, dataApi, nota.text.toString())
                    finestra.dismiss()
                })
            }
        }

        val corpo = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(18), dp(22), dp(8))
            addView(riga1); addView(riga2); addView(riga3)
            if (!modificabile) {
                addView(TextView(ctx).apply { text = "prof. " + v.docente.ifEmpty { "altro docente" }; textSize = 13f; setTextColor(0xFF777777.toInt()); setPadding(dp(2), dp(8), 0, 0) })
            }
            addView(etichetta); addView(nota)
            addView(pulsanti, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        aggiornaValore(); aggiornaTipo(); aggiornaData()
        finestra = MaterialAlertDialogBuilder(ctx).setView(corpo).create()
        finestra.show()
    }

    /** Prova a scrivere la modifica su classeviva; se non riesce resta salvata sul telefono e lo si dice. */
    private fun salvaSuClasseviva(v: Voto, valore: String, tipo: String, media: Boolean, data: String, nota: String) {
        if (v.grezzo.isEmpty()) return
        lifecycleScope.launch {
            val ctx = requireContext()
            val livello = livelli.firstOrNull { it.scritta == valore }
            val vecchio = runCatching { org.json.JSONObject(v.grezzo) }.getOrNull()
            // componente del nuovo tipo nello stesso periodo del voto
            val periodo = codiciPeriodo[vecchio?.optInt("periodPos") ?: 0]
            val componente = componenti.firstOrNull { it.optString("componentName").lowercase() == tipo && (periodo == null || it.optString("periodCode") == periodo) }
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val c = Impostazioni(ctx).client()
                    val esito = c.salvaVoto(v.grezzo, valore, livello?.codice, livello?.decimale?.takeIf { !it.isNaN() && it >= 0 }, tipo, media, data, nota, componente)
                    // controllo: si rilegge il voto da classeviva e si guarda se il tipo è cambiato davvero
                    val dopo = if (esito.first in 200..299) runCatching {
                        val id = vecchio?.opt("evtId")?.toString()
                        val tutto = c.votiGrezzi(tipo = this@VotiScheda.tipo, classeId = classeId).second
                        Regex("\\{[^{}]*\"evtId\":\\s*$id[,}][^{}]*\\}").find(tutto)?.value
                    }.getOrNull() else null
                    Triple(esito.first, esito.second, dopo)
                }
            }
            val (codice, risposta, dopo) = r.getOrElse {
                android.widget.Toast.makeText(ctx, "non riuscito: ${it.message}; il voto non è stato modificato", android.widget.Toast.LENGTH_LONG).show(); return@launch
            }
            val tipoDopo = dopo?.let { runCatching { org.json.JSONObject(it).optString("componentDesc").lowercase() }.getOrNull() }
            // si ricarica comunque: si vede quello che c'è davvero su classeviva
            if (isAdded) carica()
            if (codice in 200..299 && (tipoDopo == null || tipoDopo == tipo)) {
                android.widget.Toast.makeText(ctx, "salvato su classeviva", android.widget.Toast.LENGTH_LONG).show()
            } else {
                // non accettato, o accettato ma il tipo non è cambiato: si può copiare tutto per sistemare la scrittura
                val messaggio = if (codice in 200..299) "classeviva ha risposto ok, ma il voto risulta ancora \"$tipoDopo\" invece di \"$tipo\"."
                    else "classeviva non ha accettato (http $codice): il voto non è stato modificato."
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setMessage(messaggio)
                    .setNegativeButton("chiudi", null)
                    .setPositiveButton("copia la risposta") { _, _ ->
                        ctx.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("risposta",
                            "HTTP $codice\n$risposta\n\nprima:\n${v.grezzo}\n\ncomponente scelta:\n$componente\n\ndopo:\n$dopo"))
                    }.show()
            }
        }
    }

    /** Cancella il voto su classeviva e ricarica l'elenco se ci riesce. */
    private fun cancellaSuClasseviva(v: Voto) {
        if (v.grezzo.isEmpty()) return
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Impostazioni(requireContext()).client().cancellaVoto(v.grezzo) } }
            val codice = r.getOrNull()?.first ?: 0
            val msg = if (codice in 200..299) "voto cancellato" else "non cancellato (http $codice): ${r.getOrNull()?.second?.take(80) ?: r.exceptionOrNull()?.message}"
            android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_LONG).show()
            if (codice in 200..299) carica()
        }
    }

    private fun testo(s: String, corpo: String = "") {
        lista.removeAllViews()
        lista.addView(TextView(requireContext()).apply { text = s; textSize = 15f; setTextColor(0xFF555555.toInt()); setPadding(dp(8), dp(24), dp(8), 0) })
        if (corpo.isNotEmpty()) lista.addView(TextView(requireContext()).apply {
            text = "mostra la risposta di classeviva (tocca per copiare)"; textSize = 14f; setTextColor(azzurro); setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener {
                requireContext().getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("risposta", corpo))
                android.widget.Toast.makeText(context, "copiata.", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
    }
}
