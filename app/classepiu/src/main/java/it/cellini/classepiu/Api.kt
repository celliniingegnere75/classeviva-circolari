package it.cellini.classepiu

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Una circolare della bacheca ClasseViva. */
data class Circolare(
    val evtCode: String,
    val pubId: String,
    val titolo: String,
    val data: String,
    val categoria: String,
    val testo: String,
    val letta: Boolean,
    val firma: Boolean,
    val risposta: Boolean,
    val file: Boolean,
    val adesione: Boolean,
    val allegati: List<Allegato>,
) {
    val chiave get() = "${evtCode}_$pubId"

    /** Azioni richieste dalla circolare (es. "Conferma e firma" sull'app ufficiale). */
    val azioni: List<String>
        get() = buildList {
            if (firma) add("conferma e firma")
            if (risposta) add("risposta")
            if (file) add("caricamento di un file")
        }

    /** Numero di protocollo all'inizio del titolo ("251 - ..."), se presente. */
    val numero: String
        get() = titolo.trim().substringBefore(' ').substringBefore('-').trim().takeIf { it.all(Char::isDigit) && it.isNotEmpty() } ?: ""
}

data class Allegato(val numero: Int, val nome: String)

data class FileScaricato(val nome: String, val dati: ByteArray)

class ClasseVivaException(msg: String) : Exception(msg)

/**
 * Client minimo per l'interfaccia REST di ClasseViva (web.spaggiari.eu/rest/v1),
 * la stessa usata dallo script cv.py di Termux.
 */
class ClasseViva(private val utente: String, private val password: String) {

    private var token: String = ""
    private var id: String = ""
    var ident: String = ""
        private set
    private var percorso: String = ""

    private fun intestazioni(): Map<String, String> = buildMap {
        put("User-Agent", "CVVS/std/4.2.3 Android/12")
        put("Z-Dev-Apikey", "Tg1NWEwNGIgIC0K")
        put("Content-Type", "application/json")
        if (token.isNotEmpty()) put("Z-Auth-Token", token)
    }

    private class Risposta(val codice: Int, val corpo: ByteArray) {
        val testo get() = String(corpo, Charsets.UTF_8)
    }

    private fun http(metodo: String, url: String, corpo: JSONObject? = null): Risposta {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = metodo
        c.connectTimeout = 30_000
        c.readTimeout = 60_000
        intestazioni().forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (corpo != null) {
            c.doOutput = true
            c.outputStream.use { it.write(corpo.toString().toByteArray()) }
        }
        val codice = c.responseCode
        val flusso = if (codice in 200..299) c.inputStream else c.errorStream
        val out = ByteArrayOutputStream()
        flusso?.use { it.copyTo(out) }
        c.disconnect()
        return Risposta(codice, out.toByteArray())
    }

    fun login() {
        if (utente.isBlank() || password.isBlank()) throw ClasseVivaException("utente e password classeviva non impostati (vai in opzioni).")
        token = ""
        fun prova(profilo: String?): JSONObject {
            val body = JSONObject().put("ident", profilo ?: JSONObject.NULL).put("pass", password).put("uid", utente)
            val r = http("POST", "$BASE/auth/login", body)
            if (r.codice != 200) throw ClasseVivaException("accesso non riuscito (http ${r.codice}): ${r.testo.take(200)}")
            return JSONObject(r.testo)
        }
        var j = prova(null)
        if (j.has("choices") && j.optString("token").isEmpty()) {
            val scelte = j.getJSONArray("choices")
            j = prova(scelte.getJSONObject(0).optString("ident"))
        }
        token = j.getString("token")
        ident = j.getString("ident")
        id = ident.filter(Char::isDigit)
    }

    fun bacheca(): List<Circolare> {
        if (token.isEmpty()) login()
        val errori = mutableListOf<String>()
        for (p in listOf("students", "teachers")) { // stesso ordine di cv.py
            val r = http("GET", "$BASE/$p/$id/noticeboard")
            if (r.codice == 200) {
                percorso = p
                val items = JSONObject(r.testo).optJSONArray("items") ?: JSONArray()
                return (0 until items.length()).map { leggi(items.getJSONObject(it)) }
            }
            errori += "$p: HTTP ${r.codice}"
        }
        throw ClasseVivaException("bacheca non leggibile (" + errori.joinToString(", ") + ")")
    }

    private fun leggi(o: JSONObject): Circolare {
        val att = o.optJSONArray("attachments") ?: JSONArray()
        val allegati = (0 until att.length()).map { i ->
            val a = att.getJSONObject(i)
            Allegato(a.optInt("attachNum", i + 1).takeIf { it > 0 } ?: (i + 1), a.optString("fileName").ifBlank { "allegato_${i + 1}.pdf" })
        }
        return Circolare(
            evtCode = o.optString("evtCode"),
            pubId = o.optString("pubId"),
            titolo = o.optString("cntTitle").ifBlank { "circolare" },
            data = o.optString("cntValidFrom"),
            categoria = o.optString("cntCategory"),
            testo = o.optString("cntText"),
            letta = o.optBoolean("readStatus", false),
            firma = o.optBoolean("needSign", false),
            risposta = o.optBoolean("needReply", false),
            file = o.optBoolean("needFile", false),
            adesione = o.optBoolean("needJoin", false),
            allegati = allegati,
        )
    }

    /** Scarica un allegato. Restituisce null se ClasseViva non lo rende (ancora) disponibile. */
    fun allegato(c: Circolare, a: Allegato): FileScaricato? {
        if (percorso.isEmpty()) bacheca()
        val r = http("GET", "$BASE/$percorso/$id/noticeboard/attach/${c.evtCode}/${c.pubId}/${a.numero}")
        if (r.codice == 200 && r.corpo.isNotEmpty() && !r.testo.startsWith("{")) return FileScaricato(a.nome, r.corpo)
        if (r.testo.contains(RITIRATA)) throw CircolareRitirata()
        return null
    }

    /**
     * Classi del docente: "mine" = le sue classi, "all/students1" = tutte le classi della scuola.
     * Restituisce il codice HTTP e il testo grezzo della risposta (letto da leggiClassi).
     */
    fun classiGrezze(tutte: Boolean): Pair<Int, String> {
        if (token.isEmpty()) login()
        val percorsoClassi = if (tutte) "classes/all/students1" else "classes/mine"
        val r = http("GET", "$BASE/teachers/$id/$percorsoClassi")
        return r.codice to r.testo
    }

    /** Elenco degli studenti di una classe (risposta grezza, letta da leggiStudenti). */
    fun studentiGrezzi(tipo: String, classeId: String): Pair<Int, String> {
        if (token.isEmpty()) login()
        val r = http("GET", "$BASE/teachers/$id/students/list/${tipo.ifEmpty { "ordinary" }}/$classeId")
        return r.codice to r.testo
    }

    /** Presenze della classe in un giorno (yyyyMMdd): risposta grezza, letta da leggiStato. */
    fun statoGrezzo(tipo: String, classeId: String, giorno: String): Pair<Int, String> {
        if (token.isEmpty()) login()
        val r = http("GET", "$BASE/teachers/$id/students/status2/${tipo.ifEmpty { "ordinary" }}/$classeId/$giorno")
        return r.codice to r.testo
    }

    /**
     * Voti della classe, come l'app ufficiale Classeviva Docenti: elenco materie (subjects/list) e per ciascuna
     * evaluations/grades2/{tipo}/{classe}/{materia}. Le risposte delle materie leggibili vengono riunite in un solo array JSON.
     */
    fun votiGrezzi(tipo: String, classeId: String): Pair<Int, String> {
        if (token.isEmpty()) login()
        val t = tipo.ifEmpty { "ordinary" }
        val m = http("GET", "$BASE/teachers/$id/subjects/list/$t/$classeId")
        if (m.codice != 200) return m.codice to "subjects/list -> ${m.testo.take(300)}"
        val radice = try { org.json.JSONTokener(m.testo).nextValue() } catch (e: Exception) { return 500 to "subjects/list non leggibile: ${m.testo.take(300)}" }
        val materie = trovaArrayGenerico(radice, listOf("subjects", "items", "data")) ?: return 404 to "nessuna materia: ${m.testo.take(300)}"
        val unite = JSONArray()
        val esiti = StringBuilder()
        val ceMia = (0 until materie.length()).any { materie.optJSONObject(it)?.optBoolean("isMine", false) == true }
        for (i in 0 until materie.length()) {
            val o = materie.optJSONObject(i) ?: continue
            if (ceMia && !o.optBoolean("isMine", false)) continue // solo i voti delle materie del docente
            val sid = listOf("subjectId", "id", "subject_id").firstNotNullOfOrNull { k -> o.opt(k)?.takeIf { it != JSONObject.NULL && it !is JSONObject && it !is JSONArray }?.toString() } ?: continue
            val r = http("GET", "$BASE/teachers/$id/evaluations/grades2/$t/$classeId/$sid")
            // ogni risposta porta con sé la materia, così i voti si possono separare (es. educazione civica)
            if (r.codice == 200) unite.put(JSONObject().put("subjectId", sid).put("subjectDesc", o.optString("subjectDesc")).put("data", try { org.json.JSONTokener(r.testo).nextValue() } catch (e: Exception) { continue }))
            else esiti.append("materia $sid -> HTTP ${r.codice} ${r.testo.take(120)}\n")
        }
        return if (unite.length() > 0) 200 to unite.toString() else 404 to esiti.toString().ifEmpty { "nessuna materia con voti" }
    }

    /** Componenti e livelli di voto di ogni materia della classe (evaluations/components), risposte grezze una dopo l'altra. */
    fun componentiGrezzi(tipo: String, classeId: String): String {
        if (token.isEmpty()) login()
        val t = tipo.ifEmpty { "ordinary" }
        val m = http("GET", "$BASE/teachers/$id/subjects/list/$t/$classeId")
        val sb = StringBuilder("subjects/list HTTP ${m.codice}\n${m.testo.take(3000)}\n")
        val radice = try { org.json.JSONTokener(m.testo).nextValue() } catch (e: Exception) { return sb.toString() }
        val materie = trovaArrayGenerico(radice, listOf("subjects", "items", "data")) ?: return sb.toString()
        for (i in 0 until materie.length()) {
            val o = materie.optJSONObject(i) ?: continue
            if (!o.optBoolean("isMine", false)) continue // le componenti esistono solo per le materie del docente (le altre: 404 "wrong subject-id")
            val sid = listOf("subjectId", "id", "subject_id").firstNotNullOfOrNull { k -> o.opt(k)?.takeIf { it != JSONObject.NULL && it !is JSONObject && it !is JSONArray }?.toString() } ?: continue
            val r = http("GET", "$BASE/teachers/$id/evaluations/components/$t/$classeId/$sid")
            sb.append("\ncomponents/$sid HTTP ${r.codice}\n${r.testo.take(12000)}\n")
        }
        return sb.toString()
    }

    /** Note disciplinari della classe nell'intervallo (yyyyMMdd), risposta grezza. */
    fun noteGrezze(tipo: String, classeId: String, da: String, a: String): Pair<Int, String> {
        if (token.isEmpty()) login()
        val r = http("GET", "$BASE/teachers/$id/notes/range/${tipo.ifEmpty { "ordinary" }}/$classeId/$da/$a")
        return r.codice to r.testo
    }

    /** Lezioni della classe in un giorno (yyyyMMdd), con i docenti che le hanno firmate: risposta grezza. */
    fun lezioniClasseGrezze(tipo: String, classeId: String, giorno: String): Pair<Int, String> {
        if (token.isEmpty()) login()
        val r = http("GET", "$BASE/teachers/$id/lessons2/students/${tipo.ifEmpty { "ordinary" }}/$classeId/$giorno")
        return r.codice to r.testo
    }

    /** Lezioni del docente nell'intervallo (yyyyMMdd), risposta grezza. */
    fun lezioniGrezze(da: String, a: String): Pair<Int, String> {
        if (token.isEmpty()) login()
        val r = http("GET", "$BASE/teachers/$id/lessons/mine/$da/$a")
        return r.codice to r.testo
    }

    /** Identificativo del docente collegato (serve a riconoscere i propri voti tra quelli di altri insegnanti). */
    fun idDocente(): String { if (token.isEmpty()) login(); return id }

    /** Risposta di evaluations/components della prima materia del docente (livelli di voto, periodi, componenti), o null. */
    fun componentiPrimaMia(tipo: String, classeId: String): String? {
        if (token.isEmpty()) login()
        val t = tipo.ifEmpty { "ordinary" }
        val m = http("GET", "$BASE/teachers/$id/subjects/list/$t/$classeId")
        if (m.codice != 200) return null
        val radice = try { org.json.JSONTokener(m.testo).nextValue() } catch (e: Exception) { return null }
        val materie = trovaArrayGenerico(radice, listOf("subjects", "items", "data")) ?: return null
        for (i in 0 until materie.length()) {
            val o = materie.optJSONObject(i) ?: continue
            if (!o.optBoolean("isMine", false)) continue
            val sid = o.opt("subjectId")?.toString() ?: continue
            val r = http("GET", "$BASE/teachers/$id/evaluations/components/$t/$classeId/$sid")
            if (r.codice == 200) return r.testo
        }
        return null
    }

    /** Salva un voto modificato (data e annotazioni per la famiglia) rimandando l'oggetto del voto così come l'ha dato classeviva. */
    fun salvaVoto(grezzo: String, valore: String, livelloCodice: String?, decimale: Double?, tipo: String, media: Boolean, data: String, nota: String,
                  componente: JSONObject? = null): Pair<Int, String> {
        if (token.isEmpty()) login()
        val o = JSONObject(grezzo)
        // valore e livello scelti dalla tabella di classeviva; "non fa media" è decimalValue assente
        if (valore.isNotBlank()) {
            o.put("displayValue", valore.trim())
            if (livelloCodice != null) {
                if (o.has("levelCode")) o.put("levelCode", livelloCodice)
                if (o.has("levelId")) o.put("levelId", "$livelloCodice:0")
            }
        }
        if (!media) o.put("decimalValue", JSONObject.NULL) else if (decimale != null) o.put("decimalValue", decimale)
        componentiTipo[tipo]?.let { (desc, pos) -> o.put("componentDesc", desc); o.put("componentPos", pos) }
        // il voto letto non ha componentId: senza, classeviva può ignorare il cambio di tipo; si mette quello del periodo giusto
        if (componente != null) {
            o.put("componentId", componente.optString("componentId"))
            o.put("componentCode", componente.optString("componentCode"))
            o.put("componentPos", componente.optInt("position", o.optInt("componentPos")))
            o.put("componentDesc", componente.optString("componentName"))
        }
        if (data.isNotBlank()) o.put("evtDate", data)
        o.put("notesForFamily", nota)
        // classeviva vuole un elenco: {"grades": [voto]} (412 "missing grades" con il voto da solo)
        val r = http("PUT", "$BASE/teachers/$id/evaluations/grades2", JSONObject().put("grades", JSONArray().put(o)))
        return r.codice to r.testo
    }

    /**
     * Modifica una mia firma (lezione): rimanda l'oggetto della lezione con il nuovo argomento, come fa l'app ufficiale
     * (PUT su lessons/sigs). Le presenze degli alunni non fanno parte della firma e vengono tolte. Da verificare sui dati veri.
     */
    /** Un tipo di lezione ammesso (Lezione, Supplenza, Compresenza...): [codice] come lo vuole classeviva, [nome] da mostrare. */
    data class TipoLezione(val codice: String, val nome: String)

    /**
     * Tipi di lezione per la firma, come nella tendina dell'app ufficiale (_st/lessontypes e _st/sctelessontypes).
     * Risposta: {"lessontypes":[{"id":1,"usrCode":"Lezione"},...]} e {"sctelessontypes":[...]} (PCTO/FSL sicurezza, orientamento). [grezzo] serve da copiare.
     */
    fun tipiLezione(): Pair<List<TipoLezione>, String> {
        if (token.isEmpty()) login()
        fun JSONObject.t(vararg ks: String): String {
            for (k in ks) { val v = opt(k); if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray && v.toString().isNotBlank()) return v.toString().trim() }
            return ""
        }
        val tipi = LinkedHashMap<String, TipoLezione>()
        val grezzo = StringBuilder()
        for (percorso in listOf("lessontypes", "sctelessontypes")) {
            val r = http("GET", "$BASE/teachers/$id/_st/$percorso")
            grezzo.append("$percorso: HTTP ${r.codice}\n${r.testo}\n\n")
            if (r.codice != 200) continue
            val radice = try { org.json.JSONTokener(r.testo).nextValue() } catch (e: Exception) { continue }
            val a = trovaArrayGenerico(radice, listOf("lessonTypes", "lessontypes", "sctelessontypes", "items", "data")) ?: continue
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i)
                val tipo = if (o == null) a.optString(i).takeIf { it.isNotBlank() }?.let { TipoLezione(it, it) } else {
                    // forma vera: {"id": 1, "usrCode": "Lezione"}
                    val nome = o.t("usrCode", "lessonTypeDesc", "desc", "description", "lessonType", "name", "code")
                    val codice = o.t("id", "lessonTypeId", "lessonTypeCode", "code").ifEmpty { nome }
                    nome.takeIf { it.isNotEmpty() }?.let { TipoLezione(codice, it) }
                }
                if (tipo != null && tipo.nome !in tipi) tipi[tipo.nome] = tipo
            }
        }
        return tipi.values.toList() to grezzo.toString()
    }

    fun salvaFirma(grezzo: String, argomento: String, tipo: TipoLezione? = null): Pair<Int, String> {
        if (token.isEmpty()) login()
        val o = JSONObject(grezzo)
        o.remove("studentStatus")
        o.put("lessonArg", argomento)
        // classeviva risponde 412 "missing evtNumH": è il numero di ore della firma, che nella lezione letta si chiama evtDuration
        if (!o.has("evtNumH")) o.put("evtNumH", o.optInt("evtDuration", 1))
        if (tipo != null) {
            // il nome va in lessonType; il codice nei campi che la lezione ha già (non si sa ancora quale usa classeviva)
            o.put("lessonType", tipo.nome)
            for (k in listOf("lessonTypeCode", "lessonTypeId", "lessonTypeDesc")) if (o.has(k)) o.put(k, if (k == "lessonTypeDesc") tipo.nome else tipo.codice)
        }
        val r = http("PUT", "$BASE/teachers/$id/lessons/sigs", o)
        return r.codice to r.testo
    }

    /** Cancella un voto: la risposta è l'oggetto del voto così come l'ha dato classeviva. */
    fun cancellaVoto(grezzo: String): Pair<Int, String> {
        if (token.isEmpty()) login()
        val r = http("DELETE", "$BASE/teachers/$id/evaluations/grades2", JSONObject().put("grades", JSONArray().put(JSONObject(grezzo))))
        return r.codice to r.testo
    }

    /** Segna la circolare come letta (come quando la si apre sull'app). */
    fun segnaLetta(c: Circolare, aderisci: Boolean = false): Boolean {
        if (percorso.isEmpty()) bacheca()
        // ClasseViva vuole {"join": false}: con il corpo vuoto risponde 400
        val r = http("POST", "$BASE/$percorso/$id/noticeboard/read/${c.evtCode}/${c.pubId}/101", JSONObject().put("join", aderisci))
        return r.codice == 200
    }

    /**
     * Scarica tutti gli allegati. Molte volte ClasseViva li rende scaricabili solo dopo
     * che la circolare è stata aperta: se ne manca qualcuno la si apre e si riprova.
     */
    fun tuttiGliAllegati(c: Circolare, tentativi: Int = 3, apriSeServe: Boolean = true): List<FileScaricato> {
        val presi = sortedMapOf<Int, FileScaricato>()
        for (t in 0 until tentativi) {
            if (t > 0) Thread.sleep(2000)
            c.allegati.forEachIndexed { i, a -> if (i !in presi) allegato(c, a)?.let { presi[i] = it } }
            if (presi.size == c.allegati.size) break
            if (t == 0 && apriSeServe) {
                segnaLetta(c)
                c.allegati.forEachIndexed { i, a -> if (i !in presi) allegato(c, a)?.let { presi[i] = it } }
                if (presi.size == c.allegati.size) break
            }
        }
        return presi.values.toList()
    }

    /**
     * Email registrata sul profilo ClasseViva. Non c'è nella REST API: si entra nel portale web
     * (sessione a cookie, non documentato) e si legge data.emailAddress da OtpApi.php?a=recStatus.
     * Se Spaggiari cambia qualcosa torna "" e il destinatario si scrive a mano.
     */
    fun emailRegistrata(): String = runCatching {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        val browser = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        val login = URL("https://web.spaggiari.eu/auth-p7/app/default/AuthApi4.php?a=aLoginPwd").openConnection() as HttpURLConnection
        login.requestMethod = "POST"
        login.connectTimeout = 30_000
        login.readTimeout = 30_000
        login.instanceFollowRedirects = false
        login.setRequestProperty("User-Agent", browser)
        login.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        login.doOutput = true
        login.outputStream.use { it.write("cid=&uid=${enc(utente)}&pwd=${enc(password)}&pin=&target=".toByteArray()) }
        login.responseCode
        val biscotti = login.headerFields.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }
            .values.flatten().map { it.substringBefore(';') }.filter { it.contains('=') }
        login.disconnect()
        if (biscotti.isEmpty()) return@runCatching ""

        val stato = URL("https://web.spaggiari.eu/auth/app/default/OtpApi.php?a=recStatus").openConnection() as HttpURLConnection
        stato.connectTimeout = 30_000
        stato.readTimeout = 30_000
        stato.setRequestProperty("User-Agent", browser)
        stato.setRequestProperty("Cookie", biscotti.joinToString("; "))
        val testo = (if (stato.responseCode in 200..299) stato.inputStream else stato.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
        stato.disconnect()
        val j = JSONObject(testo)
        (j.optJSONObject("data")?.optString("emailAddress") ?: j.optString("emailAddress")).trim().takeIf { it.contains('@') }.orEmpty()
    }.getOrDefault("")

    class CircolareRitirata : Exception("circolare ritirata dalla scuola")

    companion object {
        const val BASE = "https://web.spaggiari.eu/rest/v1"
        const val RITIRATA = "no more active"
    }
}

data class Studente(val id: String, val nome: String)

private fun trovaArrayGenerico(v: Any?, chiavi: List<String>, liv: Int = 0): JSONArray? {
    if (v is JSONArray) return v
    if (v is JSONObject && liv < 3) {
        for (k in chiavi) v.optJSONArray(k)?.let { return it }
        for (k in v.keys()) trovaArrayGenerico(v.opt(k), chiavi, liv + 1)?.let { return it }
    }
    return null
}

private fun idDi(o: JSONObject): String {
    val s = o.optJSONObject("student") ?: o
    for (k in listOf("studentId", "id", "student_id", "pupilId")) {
        val v = s.opt(k) ?: o.opt(k)
        if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray) return v.toString()
    }
    return ""
}

/** Studenti di una classe dalla risposta di ClasseViva (campi dedotti, lettura tollerante). */
fun leggiStudenti(grezzo: String): List<Studente> {
    val radice = try { org.json.JSONTokener(grezzo).nextValue() } catch (e: Exception) { return emptyList() }
    val a = trovaArrayGenerico(radice, listOf("students", "items", "data", "pupils")) ?: return emptyList()
    fun JSONObject.t(vararg ks: String): String {
        for (k in ks) { val v = opt(k); if (v is String && v.isNotBlank()) return v.trim() }
        return ""
    }
    val lista = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val s = o.optJSONObject("student") ?: o
        val cognome = s.t("lastName", "surname", "cognome", "studentLastName")
        val nome = s.t("firstName", "name", "nome", "studentFirstName")
        val intero = s.t("studentName", "fullName", "completeName")
        val r = listOf(cognome, nome).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { intero }
        if (r.isEmpty()) null else Studente(idDi(o), r.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })
    }
    return lista.distinctBy { it.nome }.sortedBy { it.nome }
}

/**
 * Stato di presenza per id studente: -1 nessuna lezione/nessun dato, 0 presente, 1 ritardo/uscita, 2 assente.
 * L'app ufficiale usa gli stati present, absent, late, shortLate, leaving, noLesson, presentInternship, presentEducationalTrip.
 */
fun leggiStato(grezzo: String): Map<String, Int> {
    val radice = try { org.json.JSONTokener(grezzo).nextValue() } catch (e: Exception) { return emptyMap() }
    val a = trovaArrayGenerico(radice, listOf("students", "items", "data", "status", "pupils")) ?: return emptyMap()
    fun stringhe(v: Any?, out: MutableList<String>) {
        when (v) {
            is String -> out.add(v.lowercase())
            is JSONObject -> v.keys().forEach { stringhe(v.opt(it), out) }
            is JSONArray -> for (i in 0 until v.length()) stringhe(v.opt(i), out)
        }
    }
    val m = HashMap<String, Int>()
    val senzaCodice = ArrayList<String>()
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        val id = idDi(o)
        if (id.isEmpty()) continue
        // codici di ClasseViva: AT00 presente, ABA0 assente, ABU0/ABR* uscita/ritardo
        val ss = mutableListOf<String>().also { stringhe(o, it) }
        val codici = ss.map { it.uppercase() }.filter { it.length == 4 && it[0].isLetter() }
        if (codici.isEmpty()) { senzaCodice.add(id); continue }
        val v = when {
            codici.any { it.startsWith("ABA") } -> 2
            codici.any { it.startsWith("AB") } -> 1
            codici.any { it.startsWith("AT") } -> 0
            else -> -1
        }
        m[id] = maxOf(m[id] ?: -1, v)
    }
    // Senza segnalazioni lo studente è presente, ma solo se quel giorno c'è lezione:
    // se nella classe nessuno ha un codice (domenica, festivo, giorno futuro) restano tutti senza dato.
    val giornoDiLezione = m.isNotEmpty()
    for (id in senzaCodice) m[id] = if (giornoDiLezione) 0 else -1
    return m
}

/** Componenti dei voti: tipo della pagina -> nome e posizione come li dà classeviva. */
private val componentiTipo = mapOf("scritto/grafico" to ("Scritto/Grafico" to 1), "orale" to ("Orale" to 2), "pratico" to ("Pratico" to 3))

/** Una classe, come nell'elenco dell'app ufficiale (senza le materie). */
data class Classe(val chiave: String, val nome: String, val descrizione: String, val codice: String)

/**
 * Legge l'elenco delle classi dalla risposta di ClasseViva. Prova più nomi di campo possibili,
 * perché la risposta non è documentata; le classi uguali (una riga per materia) si contano una volta sola.
 */
fun leggiClassi(grezzo: String): List<Classe> {
    val radice = try { org.json.JSONTokener(grezzo).nextValue() } catch (e: Exception) { return emptyList() }
    fun trovaArray(v: Any?, livello: Int = 0): JSONArray? {
        if (v is JSONArray) return v
        if (v is JSONObject && livello < 3) {
            for (k in listOf("classes", "classrooms", "items", "data", "classi")) v.optJSONArray(k)?.let { return it }
            for (k in v.keys()) trovaArray(v.opt(k), livello + 1)?.let { return it }
        }
        return null
    }
    val array = trovaArray(radice) ?: return emptyList()
    fun JSONObject.testo(vararg chiavi: String): String {
        for (k in chiavi) {
            val v = opt(k)
            if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray) {
                val t = v.toString().trim()
                if (t.isNotEmpty() && t != "null") return t
            }
        }
        return ""
    }
    val visti = LinkedHashMap<String, Classe>()
    for (i in 0 until array.length()) {
        val o = array.optJSONObject(i) ?: continue
        val c = o.optJSONObject("classroom") ?: o.optJSONObject("class") ?: o
        fun t(vararg k: String) = c.testo(*k).ifEmpty { o.testo(*k) }
        val livello = t("classLevel", "classRealLevel", "level")
        val indirizzo = t("classAddr", "classAddress", "address")
        val sezione = t("classSection", "section")
        var nome = t("className", "classroomName", "name", "classCode")
        if (nome.isEmpty()) nome = livello + indirizzo + sezione
        // il nome può arrivare come "4ETA ELETTRONICA": la sigla è la parte prima del primo spazio, il resto è descrizione
        val nomeIntero = nome
        nome = siglaClasse(nome)
        val descrizione = t("classDesc", "classroomDescription", "description", "desc", "classDescription")
            .ifEmpty { if (nomeIntero != nome) nomeIntero else "" }
        var codice = t("classCode", "classroomCode", "code", "classCodeDescription", "schoolClassCode")
        if (codice == nome) codice = ""
        if (nome.isEmpty() && descrizione.isEmpty()) continue
        val id = t("classId", "classroomId", "id").ifEmpty { nome }
        val tipo = t("classType", "classroomType", "type")
        visti.getOrPut("$tipo|$id|$nome") { Classe("$tipo|$id|$nome", nome.uppercase(), descrizione.uppercase(), codice.uppercase()) }
    }
    return visti.values.sortedWith(compareBy({ it.nome.firstOrNull()?.digitToIntOrNull() ?: 9 }, { it.nome }))
}

/** Una classe con una materia che il docente vi insegna (una riga di classes/mine). */
data class Insegnamento(val classe: String, val materia: String) {
    val chiave get() = "$classe|$materia"
}

/**
 * Coppie classe/materia dalla risposta di "le mie classi": ClasseViva ripete la classe una volta per materia.
 * I nomi dei campi della materia non sono documentati: si provano i più probabili, anche dentro "subject".
 */
fun leggiInsegnamenti(grezzo: String): List<Insegnamento> {
    val radice = try { org.json.JSONTokener(grezzo).nextValue() } catch (e: Exception) { return emptyList() }
    val array = trovaArrayGenerico(radice, listOf("classes", "classrooms", "items", "data", "classi")) ?: return emptyList()
    fun JSONObject.testo(vararg chiavi: String): String {
        for (k in chiavi) {
            val v = opt(k)
            if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray) {
                val t = v.toString().trim()
                if (t.isNotEmpty() && t != "null") return t
            }
        }
        return ""
    }
    val campiMateria = arrayOf("subjectDesc", "subjectDescription", "subjectName", "subjectCode", "materia", "description", "desc", "name")
    fun materieDi(o: JSONObject): List<String> {
        val dirette = o.testo("subjectDesc", "subjectDescription", "subjectName", "subject", "materia", "subjectCode")
        if (dirette.isNotEmpty()) return listOf(dirette)
        o.optJSONObject("subject")?.let { s -> s.testo(*campiMateria).takeIf { it.isNotEmpty() }?.let { return listOf(it) } }
        val elenco = o.optJSONArray("subjects") ?: return emptyList()
        return (0 until elenco.length()).mapNotNull { i ->
            when (val v = elenco.opt(i)) {
                is JSONObject -> v.testo(*campiMateria).ifEmpty { null }
                is String -> v.trim().ifEmpty { null }
                else -> null
            }
        }
    }
    val visti = LinkedHashSet<Insegnamento>()
    for (i in 0 until array.length()) {
        val o = array.optJSONObject(i) ?: continue
        val c = o.optJSONObject("classroom") ?: o.optJSONObject("class") ?: o
        fun t(vararg k: String) = c.testo(*k).ifEmpty { o.testo(*k) }
        var nome = t("className", "classroomName", "name", "classCode")
        if (nome.isEmpty()) nome = t("classLevel", "classRealLevel", "level") + t("classAddr", "classAddress", "address") + t("classSection", "section")
        nome = siglaClasse(nome)
        if (nome.isEmpty()) continue
        val materie = materieDi(o).ifEmpty { if (c !== o) materieDi(c) else emptyList() }
        if (materie.isEmpty()) visti += Insegnamento(nome.uppercase(), "")
        else materie.forEach { visti += Insegnamento(nome.uppercase(), it) }
    }
    return visti.sortedWith(compareBy({ it.classe.firstOrNull()?.digitToIntOrNull() ?: 9 }, { it.classe }, { it.materia }))
}

/** Solo la sigla della classe: il testo prima del primo spazio ("4ETA ELETTRONICA" -> "4ETA"). */
fun siglaClasse(nome: String): String = nome.trim().substringBefore(' ')

/** Un voto di uno studente: tipo di prova (orale, scritto/grafico, test…), valore mostrato, data e annotazioni per la famiglia. */
data class Voto(val chiave: String, val studente: String, val tipo: String, val valore: String, val numero: Double?, val data: String, val nota: String, val grezzo: String = "", val materia: String = "", val autore: String = "", val faMedia: Boolean = true, val docente: String = "")

/** Voti per id studente dalla risposta di ClasseViva (campi dedotti, lettura tollerante, sia voti annidati nello studente sia elenco piatto). */
fun leggiVoti(grezzo: String): Map<String, List<Voto>> {
    val radice = try { org.json.JSONTokener(grezzo).nextValue() } catch (e: Exception) { return emptyMap() }
    val out = LinkedHashMap<String, MutableList<Voto>>()
    fun JSONObject.t(vararg ks: String): String {
        for (k in ks) { val v = opt(k); if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray && v.toString().isNotBlank()) return v.toString().trim() }
        return ""
    }
    var materiaCorrente = ""
    fun cammina(v: Any?, idStudente: String, livello: Int) {
        if (livello > 6) return
        when (v) {
            is JSONArray -> for (i in 0 until v.length()) cammina(v.opt(i), idStudente, livello + 1)
            is JSONObject -> {
                val valore = v.t("displayValue", "decimalValue", "grade", "value", "gradeValue")
                val èVoto = valore.isNotEmpty() && v.opt("grades") == null
                // un voto ha un proprio "id": per lo studente valgono solo i campi esplicitamente suoi
                val propri = if (èVoto) v.t("studentId", "student_id", "pupilId") else idDi(v)
                if (èVoto) {
                    val sid = propri.ifEmpty { idStudente }
                    // voti annullati (canceled) non si mostrano
                    if (sid.isNotEmpty() && v.opt("canceled") != true) {
                        val tipo = v.t("componentDesc", "gradeTypeDesc", "typeDesc", "gradeType", "type", "kind", "component").ifEmpty { "voto" }
                        val data = v.t("evtDate", "date", "gradeDate", "day")
                        val num = v.t("decimalValue").toDoubleOrNull() ?: valore.replace(',', '.').toDoubleOrNull()
                        val id = v.t("evtId", "gradeId", "id").ifEmpty { "${out[sid]?.size ?: 0}" }
                        out.getOrPut(sid) { ArrayList() }.add(Voto("$sid|$id|$data", sid, tipo, valore, num, data, v.t("notesForFamily", "note", "notes", "annotations"), v.toString(), materiaCorrente.ifEmpty { v.t("subjectDesc") }, v.t("authorId", "teacherId", "teacher_id"), // non fa media: peso 0 oppure valore decimale assente (nei dati di classeviva sono i voti "blu", per esempio superati da un recupero)
            (v.t("weightFactor").toDoubleOrNull()?.let { it != 0.0 } ?: true) && v.opt("decimalValue") != JSONObject.NULL, v.t("teacherName", "authorName")))
                    }
                } else {
                    val sid = propri.ifEmpty { idStudente }
                    for (k in v.keys()) cammina(v.opt(k), sid, livello + 1)
                }
            }
        }
    }
    if (radice is JSONArray && (0 until radice.length()).all { radice.optJSONObject(it)?.has("data") == true && radice.optJSONObject(it)?.has("subjectId") == true }) {
        for (i in 0 until radice.length()) {
            val w = radice.getJSONObject(i)
            materiaCorrente = w.optString("subjectDesc")
            cammina(w.opt("data"), "", 0)
        }
    } else cammina(radice, "", 0)
    return out
}
