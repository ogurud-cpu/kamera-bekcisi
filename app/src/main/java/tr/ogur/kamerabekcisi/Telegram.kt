package tr.ogur.kamerabekcisi

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Telegram bot ile bildirim gönderimi. */
object Telegram {
    private val exec = Executors.newSingleThreadExecutor()

    fun configured(): Boolean = Store.tgToken.isNotBlank() && Store.tgChat.isNotBlank()

    fun sendText(text: String) {
        if (!configured()) return
        val token = Store.tgToken; val chat = Store.tgChat
        exec.execute { retry { postForm(token, "sendMessage", mapOf("chat_id" to chat, "text" to text)) } }
    }

    fun sendPhoto(file: File, caption: String) {
        if (!configured()) return
        val token = Store.tgToken; val chat = Store.tgChat
        exec.execute { retry { postPhoto(token, chat, file, caption) } }
    }

    /** Bota en son mesaj atan sohbetin kimliğini bulur (bloklayan çağrı). */
    fun findChatId(token: String): String? {
        val txt = get(token, "getUpdates")
        val arr = JSONObject(txt).optJSONArray("result") ?: return null
        for (i in arr.length() - 1 downTo 0) {
            val u = arr.getJSONObject(i)
            for (k in listOf("message", "edited_message", "channel_post", "my_chat_member")) {
                val m = u.optJSONObject(k) ?: continue
                val chat = m.optJSONObject("chat") ?: continue
                if (chat.has("id")) return chat.get("id").toString()
            }
        }
        return null
    }

    /** Test mesajı (bloklayan). Hata varsa açıklama döner, başarılıysa null. */
    fun testBlocking(token: String, chat: String): String? = try {
        postForm(token, "sendMessage", mapOf("chat_id" to chat, "text" to "✅ Kamera Bekçisi bağlantı testi başarılı."))
        null
    } catch (e: Exception) {
        e.message ?: "Bilinmeyen hata"
    }

    private fun retry(block: () -> Unit) {
        for (i in 0 until 3) {
            try { block(); return } catch (e: Exception) {
                Hub.log("Telegram hatası: ${e.message?.take(80)}")
                try { Thread.sleep(3000L * (i + 1)) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun open(token: String, method: String): HttpURLConnection {
        val c = URL("https://api.telegram.org/bot${token.trim()}/$method").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        return c
    }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) {
            val desc = try { JSONObject(txt).optString("description") } catch (_: Exception) { txt }
            throw IOException("($code) $desc")
        }
        return txt
    }

    private fun get(token: String, method: String): String = read(open(token, method))

    private fun postForm(token: String, method: String, params: Map<String, String>): String {
        val c = open(token, method)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        val body = params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        c.outputStream.use { it.write(body.toByteArray()) }
        return read(c)
    }

    private fun postPhoto(token: String, chat: String, file: File, caption: String) {
        val boundary = "----kb" + System.currentTimeMillis()
        val c = open(token, "sendPhoto")
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.outputStream.use { os ->
            fun field(name: String, value: String) {
                os.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("chat_id", chat)
            field("caption", caption)
            os.write(("--$boundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"olay.jpg\"\r\n" +
                "Content-Type: image/jpeg\r\n\r\n").toByteArray())
            file.inputStream().use { it.copyTo(os) }
            os.write("\r\n--$boundary--\r\n".toByteArray())
        }
        read(c)
    }
}
