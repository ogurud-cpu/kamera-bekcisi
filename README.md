# Kamera Bekçisi

ttec DVR (8 kanal) için yapay zeka destekli, alan bazlı uyarı uygulaması (Android).

- Evde sabit duran bir Android telefon/tablette 7/24 çalışır.
- Kayıt cihazındaki kameraların görüntüsünü RTSP ile okur (kayıt cihazına dokunmaz).
- YOLO11 ile İnsan / Araç / Hayvan ayırt eder.
- Çizdiğiniz alanlarda, kişinin **ayak noktası** alanın içindeyse ve belirlediğiniz süre kadar kaldıysa uyarı verir.
- Telegram'a fotoğraflı bildirim gönderir, olayları arşivler.
- Elektrik kesilip telefon yeniden açılınca kendiliğinden başlar.

## APK nasıl alınır

1. GitHub'da yeni bir depo açın (ör. `kamera-bekcisi`, özel/private olabilir).
2. Bu klasörün içeriğini depoya yükleyin (`.github` klasörü dahil).
3. **Actions** sekmesinde "APK Derle" otomatik çalışır (~10 dk).
4. Bitince işin sayfasındaki **KameraBekcisi-apk** dosyasını indirip telefona kurun.

## İlk kurulum (uygulama içinde)

1. **DVR bağlantısı** → "Ağda kayıt cihazını ara" → cihazı seçin → kullanıcı adı/şifre → "Bağlantıyı test et".
   Uygulama yaygın RTSP adres biçimlerini sırayla dener ve kanalları (1–8) tarar. Kamera isimlerini verip kaydedin.
2. **Alanlar** → kamerayı seçin, görüntüye dokunarak alanın köşelerini işaretleyin, türü (İnsan/Araç/Hayvan), saat aralığını ve süreyi seçip kaydedin.
3. **Telegram** → @BotFather ile bot açın, anahtarı yapıştırın, bota "merhaba" yazın, "Sohbet kimliğini bul" → "Test mesajı gönder".
4. Ana ekranda **Pil kısıtlamasını kaldır** → **İzlemeyi başlat**.

## Notlar

- Görüntü gelmezse: DVR menüsünde RTSP'nin açık olduğundan ve portun 554 olduğundan emin olun.
  "Özel RTSP adresi" alanına kendi adres biçiminizi `{ip} {port} {user} {pass} {ch}` yer tutucularıyla yazabilirsiniz.
- Hassasiyet: Düşük = daha az yanlış alarm, Yüksek = uzaktaki küçük hedefleri de yakalar.
