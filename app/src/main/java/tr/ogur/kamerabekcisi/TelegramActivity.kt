package tr.ogur.kamerabekcisi

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout

class TelegramActivity : Activity() {
    private lateinit var tokenE: EditText
    private lateinit var chatE: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root: LinearLayout = page("Telegram Bildirimi")

        root.card().text(
            "Kurulum (bir kere yapılır):\n\n" +
                "1) Telegram'da @BotFather'ı açın, /newbot yazın, bota bir isim verin.\n" +
                "2) BotFather'ın verdiği uzun anahtarı (token) aşağıya yapıştırın.\n" +
                "3) Kendi Telegram'ınızdan yeni botunuzu bulun ve ona 'merhaba' yazın.\n" +
                "4) 'Sohbet kimliğini bul'a basın, sonra 'Test mesajı gönder'.\n\n" +
                "Aile grubuna da gönderebilirsiniz: botu gruba ekleyin, grupta bir mesaj yazın ve 'Sohbet kimliğini bul'a basın.",
            13f
        )
        tokenE = root.field("Bot anahtarı (token)", Store.tgToken)
        chatE = root.field("Sohbet kimliği (chat id)", Store.tgChat)

        root.btn("Sohbet kimliğini bul") { findChat() }
        root.btn("Test mesajı gönder") { test() }
        root.btn("Kaydet") {
            Store.tgToken = tokenE.text.toString().trim()
            Store.tgChat = chatE.text.toString().trim()
            toast("Kaydedildi")
            finish()
        }
        root.text("Bildirimler, telefon uygulaması kapalıyken de doğrudan Telegram'dan gelir.", 12f, false, Color.GRAY)
    }

    private fun findChat() {
        val token = tokenE.text.toString().trim()
        if (token.isEmpty()) { toast("Önce bot anahtarını yapıştırın."); return }
        Thread {
            val res = try { Telegram.findChatId(token) } catch (e: Exception) { "HATA:" + (e.message ?: "") }
            runOnUiThread {
                when {
                    res == null -> toast("Mesaj bulunamadı. Botunuza Telegram'dan 'merhaba' yazıp tekrar deneyin.")
                    res.startsWith("HATA:") -> toast("Telegram hatası: ${res.removePrefix("HATA:")}")
                    else -> { chatE.setText(res); toast("Bulundu: $res") }
                }
            }
        }.start()
    }

    private fun test() {
        val token = tokenE.text.toString().trim()
        val chat = chatE.text.toString().trim()
        if (token.isEmpty() || chat.isEmpty()) { toast("Anahtar ve sohbet kimliği gerekli."); return }
        Thread {
            val err = Telegram.testBlocking(token, chat)
            runOnUiThread {
                if (err == null) {
                    Store.tgToken = token; Store.tgChat = chat
                    toast("Gönderildi ✅ Telegram'ı kontrol edin.")
                } else toast("Gönderilemedi: $err")
            }
        }.start()
    }
}
