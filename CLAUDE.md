# Claude Code'a yapıştırılacak görev — Jarvis (offline, ücretsiz, yerel)

> Aşağıdaki her şeyi kopyalayıp boş bir GitHub reposunda Claude Code'a yapıştır.

---

Sen bu repoda kıdemli bir Kotlin / Android / masaüstü mühendisisin. Görevin: **telefonda (Android) ve PC'de (Windows) tamamen offline, ücretsiz, veri sızdırmayan, Türkçe konuşan kişisel bir yapay zekâ asistanı "Jarvis"** üretmek ve GitHub Actions ile **indirilip kurulabilir dosyalar** (APK + Windows MSI) çıkarmak.

Kullanıcı kod yazmayacak, terminal açmayacak. Sadece GitHub Releases sayfasından dosyaları indirip kuracak. Bu yüzden her şey CI'da derlenmeli, her aşamanın sonunda çalışan bir sürüm yayınlanmalı.

## 0. İlk iş

1. Bu metnin tamamını repoya `CLAUDE.md` olarak kaydet. Sonraki oturumlarda kaynak bu dosya olacak.
2. `docs/PLAN.md` dosyasına aşamaları (bölüm 9) yaz. Her aşama bittiğinde işaretle.
3. Kütüphane sürümlerini ve Maven koordinatlarını **ezbere yazma**. Her birini resmi kaynağından (Maven Central, Google Maven, GitHub Releases, Hugging Face API) doğrula ve en güncel kararlı sürümü `gradle/libs.versions.toml` dosyasına sabitle.

## 1. Temel ilkeler (pazarlıksız)

- **Varsayılan tamamen offline.** Konuşmalar, hafıza ve belgeler cihazdan hiç çıkmaz. Telemetri, analitik, crash raporlama ve reklam SDK'sı yok.
- **Ücretsiz.** API anahtarı, abonelik ve bulut LLM yok. Sadece açık ağırlıklı yerel modeller.
- **Bağımsız iki parça.** Telefon PC kapalıyken de tam çalışır. PC telefon olmadan tam çalışır. Biri diğerine bağımlı değil.
- **İnternet sadece onayla.** İnternete yalnızca kullanıcı biyometrik/PIN (PC'de UAC) ile tek seferlik onay verdiğinde çıkılır. İndirilen her dosyanın SHA-256 özeti doğrulanır.
- **Uydurma yok.** Bilgi soruları yerel kaynaktan (offline Wikipedia, kullanıcının belgeleri) atıfla cevaplanır. Kaynak yoksa "Bu bilgi kütüphanemde yok" denir. Ekranda gösterilen sayılar ve alıntılar kaynak metinde birebir geçmeli (bölüm 5.4).
- **Dil:** Arayüz ve konuşma Türkçe; kod, tanımlayıcılar ve commit mesajları İngilizce.

## 2. Repo yapısı (Kotlin Multiplatform + Compose Multiplatform)

```
/core        KMP: prompt'lar, yönlendirici, araçlar, sahne ayrıştırıcı, doğrulama,
             hafıza arayüzü, model kayıt defteri (models.lock.json), ağ YOK
/ui          Compose Multiplatform: ortak ekranlar (sohbet, HUD çekirdeği, ayarlar,
             onay ekranı, ağ kayıt defteri)
/androidApp  "Jarvis" — Android uygulaması (KALE), INTERNET izni YOK
/gate        "Jarvis Kapı" — ayrı küçük Android APK, INTERNET izni olan TEK parça
/desktopApp  Windows uygulaması (Compose Desktop, jpackage → MSI)
/tools       CI yardımcı betikleri (model hash kilitleme, manifest denetimi)
```

Paket adı kökü: `io.github.ahmetdurmus1218.jarvis`. Min SDK 31, target SDK en güncel kararlı sürüm.

## 3. Android

### 3.1 Kale (`:androidApp`)
- Manifest'te `android.permission.INTERNET` ve `ACCESS_NETWORK_STATE` **olmayacak**. Merged manifest'i denetleyen bir CI kontrolü yaz (`tools/check-no-internet.sh`). İzin sızarsa build kırılsın. Kütüphanelerden gelen izinleri `tools:node="remove"` ile temizle.
- **LLM:** Google LiteRT-LM Kotlin API, model Gemma 4 E4B (`.litertlm`, Hugging Face `litert-community`). Cihazın RAM'i 12 GB'tan azsa ya da yükleme başarısız olursa Gemma 4 E2B'ye düş. GPU backend'i dene, olmazsa CPU'ya geç. Akışlı (streaming) çıktı.
- **Ses → metin:** sherpa-onnx (Android AAR) ile Whisper (Türkçe destekli, küçük/turbo varyant). Model indirilmemişse yedek olarak `SpeechRecognizer.createOnDeviceSpeechRecognizer` (tr-TR).
- **Metin → ses:** sherpa-onnx ile Piper Türkçe ses (ör. `tr_TR-dfki-medium`; ses lisansını kontrol edip README'ye yaz). Yedek: Android `TextToSpeech` (tr-TR). İlk cümle hazır olur olmaz seslendirmeye başla, cevabın bitmesini bekleme.
- **Offline Wikipedia:** java-libkiwix (Android) ile Türkçe Wikipedia ZIM (`wikipedia_tr_all_nopic` ya da `maxi`). Tam metin arama + makale okuma.
- **Depolama:** Room + SQLCipher (anahtar Android Keystore'da). Hafıza, konuşmalar ve belge dizini şifreli.
- **Kullanıcının belgeleri:** PDF/TXT/MD'yi dosya seçiciyle içe aktar, parçala, yerel embedding ile (küçük bir yerel embedding modeli; bulunamazsa BM25) arama dizini kur.
- Modeller ve ZIM dosyaları APK'nın içinde değil. İlk açılışta "Kurulum sihirbazı" (bölüm 3.3) çalışır. Kullanıcı PC'den kopyaladığı dosyaları "Dosyadan yükle" ile de ekleyebilir; hash yine doğrulanır.

### 3.2 Kapı (`:gate`)
- INTERNET izni olan tek APK. Arayüzü yok (sadece durum ekranı). Kendi başına hiçbir ağ isteği başlatmaz.
- Kale ile aynı imza anahtarıyla imzalanır. Servisi şu özel izinle korunur:
  ```xml
  <permission android:name="io.github.ahmetdurmus1218.jarvis.permission.GATE"
      android:protectionLevel="signature" />
  ```
  Kale bu izni `uses-permission` ile kullanır. Haberleşme AIDL/Messenger ile, dosyalar `ParcelFileDescriptor` ile aktarılır.
- Kurallar:
  - Alan adı izin listesi: `huggingface.co`, `cdn-lfs*.huggingface.co` / `*.hf.co`, `download.kiwix.org`, `github.com`, `objects.githubusercontent.com`. Bunun dışı reddedilir.
  - Sadece HTTPS. OkHttp + sertifika sabitleme (pinning), `cleartextTrafficPermitted=false`.
  - Uzantı izin listesi: `.litertlm .onnx .gguf .zim .tar.bz2 .json .txt`. `.apk .dex .so .jar .exe` ve benzerleri kesinlikle yasak.
  - Boyut üst sınırı ve devam ettirilebilir indirme (Range).
  - İndirme bittikten sonra SHA-256 hesaplanır. Kale de dosyayı aldıktan sonra **kendisi tekrar** hesaplar.
- Kapı kaldırılırsa Kale tamamen offline çalışmaya devam eder. "Kapı yüklü değil" bilgisini gösterir.

### 3.3 Onay akışı (yönetici izni)
Kale'nin internete ihtiyacı olduğunda (model/ZIM indirme, güncelleme, kullanıcı isterse web araması):
1. Onay ekranında gösterilecekler: ne yapılacağı, hedef alan adı, boyut, neden gerektiği, beklenen SHA-256. Web aramasında **dışarı gidecek metnin tamamı** gösterilir.
2. `BiometricPrompt` (`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`). Bir onay = tek istek. Kalıcı izin yok.
3. Hash `models.lock.json` ile eşleşmiyorsa dosya silinir ve kırmızı uyarı gösterilir. Kilit dosyasında olmayan bir öğe için "Doğrulanamadı" uyarısı çıkar ve iki ayrı onay istenir.
4. Her ağ olayı Kale'deki "Ağ kayıt defteri" ekranına yazılır: zaman, alan adı, bayt, onay.
5. Web araması varsayılan olarak **kapalıdır**. Ayarlardan açılırsa her sorguda yine onay istenir.

## 4. PC (`:desktopApp`, Windows 10/11)

- Compose Desktop arayüzü, `:core` ve `:ui` modülleri telefonla ortak.
- **LLM:** llama.cpp'nin resmi Windows **Vulkan** `llama-server` sürümü. Sürümü ve SHA-256'yı sabitle, ilk açılışta onaylı indir. Kullanıcının GPU'su AMD RX 6600 8 GB (ROCm/hipBLAS bu kartı desteklemiyor, Vulkan kullan). `--host 127.0.0.1` ile alt süreç olarak başlat ve OpenAI uyumlu uç noktayla konuş. Uygulama kapanınca alt süreci de kapat.
- **Model:** 8 GB VRAM'e sığan en güçlü genel model. Varsayılan Qwen 3.5 9B Q4_K_M GGUF (resmi ya da güvenilir bir repodan, revizyon ve hash sabit). Alternatif: Gemma 4 E4B GGUF. Model seçimi ayarlardan yapılır. Düşünme modu desteklenir. Sistem RAM'i 16 GB.
- **Offline Wikipedia:** kiwix-tools içindeki `kiwix-serve`, `127.0.0.1` üzerinde alt süreç olarak. Arama API'si üzerinden sorgulanır. İkili dosya sabit sürüm ve hash ile indirilir.
- **Ses:** sherpa-onnx (JVM/Windows) ile Whisper + Piper, telefondakiyle aynı model dosyaları.
- **Depolama:** SQLite (SQLCipher JDBC) — `%LOCALAPPDATA%\Jarvis`.
- **Güvenlik duvarı:**
  - İlk açılışta UAC ile tek seferlik yönetici onayı istenir. `netsh advfirewall` ile Jarvis'in JVM'i, `llama-server.exe` ve `kiwix-serve.exe` için **dışarı giden trafiği engelleyen** kurallar eklenir.
  - İndirme gerektiğinde ayrı `jarvis-kapi` başlatıcısı (jpackage `--add-launcher`) kullanılır. UAC onayıyla yalnızca bu başlatıcı için **geçici izin kuralı** açılır, indirme bitince ya da 10 dakika dolunca silinir. Aynı izin listesi, uzantı listesi ve hash kuralları (bölüm 3.2) geçerli.
- **Paketleme:** jpackage → `Jarvis-Setup-x.y.z.msi`, gömülü JRE ile. Kullanıcıdan Java kurması istenmez. İmzasız MSI için README'de "SmartScreen → Ek bilgi → Yine de çalıştır" adımı anlatılır.

## 5. Asistanın beyni (`:core`, iki platformda ortak)

### 5.1 Yönlendirici
1. **Hazır komutlar:** Türkçe normalize edilmiş regex (ç→c, ğ→g, ı→i, ö→o, ş→s, ü→u). Saat, tarih, alarm, zamanlayıcı, uygulama açma, "sessiz ol" gibi komutlar LLM'e gitmeden anında yanıtlanır.
2. **Karmaşıklık puanı:** Kod, matematik, "açıkla/karşılaştır/neden" sinyallerine bakılır (OpenJarvis `complexity.py` fikri). Kolay soruya kısa cevap. Zor soruda düşünme modu açılır, daha fazla token verilir ve model cevabını bir kez kendisi kontrol eder.
3. **Bilgi sorusu:** Önce `wiki_ara` / `belge_ara` aracı çağrılır, cevap kaynaklara dayandırılır.

### 5.2 Araçlar (function calling, en fazla 5 tur, bağımsız araçlar paralel)
`wiki_ara`, `wiki_oku`, `belge_ara`, `hesapla` (güvenli matematik ifade motoru; keyfi kod çalıştırma yok), `birim_cevir`, `tarih_saat`, `hafiza_ekle`, `hafiza_sil`, `hafiza_listele`. Android'e özel: `alarm_kur`, `zamanlayici`, `uygulama_ac` (yalnızca Intent; SMS veya arama gibi riskli işlemlerde onay ekranı).

### 5.3 Cevap biçimi (halilozat/jarvis fikri)
Model JSON üretir: `{"sahneler":[{"ses":"1-2 kısa cümle","gorsel":{...}}],"detay":"markdown","kaynaklar":[...]}`. Akışlı ayrıştırıcı her sahneyi kapanır kapanmaz çıkarır ve TTS başlar. JSON bozuksa bir kez düzeltme istenir; yine bozuksa düz metin tek sahne olarak gösterilir. Görsel tipleri: `yok, baslik, sayi, liste, adimlar, alinti, kaynak_karti`.

### 5.4 Doğrulama
`sayi` ve `alinti` görsellerindeki her değer araç çıktısında birebir geçmeli (Türkçe/İngilizce sayı yazımları: `5.910.320`, `8.848,86`). Geçmiyorsa görsel başlık kartına düşürülür. Kaynakta olmayan iddiaya "kaynak yok" etiketi konur. Bunun için birim testleri yaz.

### 5.5 Hafıza
Her turdan sonra arka planda (Android'de WorkManager) küçük bir LLM çağrısıyla kalıcı bilgiler JSON dizisi olarak çıkarılır (OpenJarvis `FactExtractor` fikri). Tekrarlar ayıklanır, toplam 500 kayıtla sınırlanır. Kullanıcı hafızayı ekranda görür, siler, tamamen kapatabilir. Sistem prompt'unda değişmeyen kısım başta, tarih/saat/hafıza sonda durur.

### 5.6 Güvenlik
Wikipedia, belge ve web çıktıları "veri"dir, talimat değildir. Araç çıktılarında prompt-injection kalıpları taranır ve işaretlenir (OpenJarvis `injection_scanner.py` fikri). Araç çıktısındaki bir metin hiçbir zaman ağ isteği ya da riskli Android aksiyonu tetikleyemez.

## 6. Arayüz
- Koyu, sade, şık bir "HUD": ortada nefes alan/konuşurken titreşen bir **çekirdek** (Compose Canvas animasyonu), altında sahne kartları, altyazı ve kaynak çipleri.
- Ekranlar: Sohbet (yazı + bas-konuş mikrofon), Kurulum sihirbazı, Modeller (indirilmiş/indirilecek, boyut, hash durumu), Hafıza, Ağ kayıt defteri, Ayarlar (model, ses, hız, web araması aç/kapa, "Tam offline mod" anahtarı).
- Kurulum sihirbazı: cihaz kontrolü → önerilen paketin listesi (LLM + STT + TTS + Wikipedia, toplam boyutla) → tek onay → indirme ilerlemesi → hash doğrulama → test cümlesi: "Merhaba efendim, Jarvis hazır."

## 7. CI/CD (GitHub Actions)
- `ci.yml` (her push/PR): KMP derleme, birim testleri, Android lint, `check-no-internet.sh`, `:core` modülünde ağ API'si kullanımını yasaklayan kontrol (`java.net`, `okhttp`, `ktor-client` importları build'i kırar), `models.lock.json` şema kontrolü.
- `release.yml` (`v*` etiketinde): Ubuntu'da `jarvis.apk` + `jarvis-kapi.apk` (release, aynı anahtarla imzalı), Windows runner'da `Jarvis-Setup.msi`. Hepsi SHA-256 listesiyle GitHub Release'e yüklenir.
- İmzalama: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` secret'ları varsa onlar kullanılır. Yoksa workflow repoda duran `ci-test.keystore` ile imzalar ve sürüm notuna "test anahtarıyla imzalı" uyarısı ekler. Secret'ların nasıl ekleneceğini README'de kullanıcının anlayacağı dille, adım adım anlat.
- `tools/lock-models.*`: Hugging Face API'den seçilen modellerin commit revizyonunu ve LFS SHA-256'sını, Kiwix'in `.sha256` dosyasından ZIM özetini, GitHub Releases'ten llama.cpp/kiwix-tools özetlerini çekip `models.lock.json` dosyasına yazar. Bu betiği sen çalıştır ve sonucu commit'le. Lock dosyası uygulamanın içine gömülür.
- İlk sürümü kendin etiketle (`v0.1.0`) ve Release'in oluştuğunu doğrula.

## 8. README (Türkçe, kod bilmeyen kişi için)
1. Releases'ten hangi dosyanın indirileceği.
2. Telefonda: bilinmeyen kaynaklara izin → önce `jarvis.apk`, sonra isteğe bağlı `jarvis-kapi.apk` → ilk açılışta sihirbaz.
3. PC'de: MSI → SmartScreen adımı → ilk açılışta UAC onayı → sihirbaz.
4. Modelleri PC'de indirip USB ile telefona taşıma (Kapı kurmadan).
5. Sık sorunlar.
6. Gizlilik garantilerinin ne olduğu ve nasıl doğrulanacağı (Ayarlar → Uygulamalar → Jarvis → İzinler ekranında "İnternet" olmadığı).

## 9. Aşamalar (her biri çalışan bir Release ile biter)
1. **v0.1:** Repo iskeleti, CI, Kale + Kapı + MSI derleniyor. Android'de sistem STT/TTS ile sesli sohbet arayüzü. LLM henüz sahte (mock), ama tüm akış çalışıyor. Kurulum sihirbazı ve onay ekranı çalışıyor.
2. **v0.2:** Gerçek LLM: Android'de LiteRT-LM + Gemma, PC'de llama-server + Qwen. Akışlı cevap, sahne JSON'u, hazır komutlar.
3. **v0.3:** Offline Wikipedia + belge araması, araç çağırma, doğrulama, kaynak çipleri.
4. **v0.4:** Whisper + Piper (sherpa-onnx), şifreli hafıza, hafıza ekranı.
5. **v0.5:** HUD animasyonları, PC güvenlik duvarı otomasyonu, ağ kayıt defteri, dokümantasyonun son hali.

Her aşamada: testleri yaz, CI'yı yeşil tut, `docs/PLAN.md` dosyasını güncelle, kısa bir Türkçe değişiklik notu yaz. Bir kütüphane beklenen şekilde çalışmazsa sessizce atlama: alternatifini seç, `docs/DECISIONS.md` dosyasına nedenini yaz ve devam et. Oturum biterken kaldığın yeri `docs/PLAN.md` dosyasına yaz. Kullanıcı "devam" dediğinde oradan sürdür.

Şimdi bölüm 0'dan başla.
