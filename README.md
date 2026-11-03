# Rick — çevrimdışı, ücretsiz, Türkçe kişisel asistan

Rick telefonunuzda (Android) ve bilgisayarınızda (Windows) **tamamen internetsiz** çalışan, Türkçe konuşan
bir yapay zekâ asistanıdır. Konuşmalarınız, hafızası ve belgeleriniz cihazınızdan çıkmaz. Abonelik, API
anahtarı ya da reklam yoktur.

> **Yeni adı Rick.** v0.5.0'a kadar adı "Jarvis"ti. Güncellemeyi eskisini kaldırmadan, üstüne kurun: telefonda
> indirdiğiniz modeller, hafıza ve ayarlar yerinde kalır; bilgisayarda `%LOCALAPPDATA%\Jarvis` klasörü
> ilk açılışta kendiliğinden `%LOCALAPPDATA%\Rick` olur. "Hey Jarvis" demeye devam ederseniz de anlar.

> **Durum:** v0.5.1. Bütün aşamalar tamam: gerçek yapay zekâ (telefonda Gemma 4, PC'de Qwen 3.5 9B), Rick'in kendi
> ses tanıması (Whisper) ve kendi Türkçe sesi (Piper), internetsiz Wikipedia ve belgeleriniz, şifreli hafıza, kamera
> ile el kontrolü ve efektler, ev simülasyonu, kitaplık, alkışla uyanma, Mark 2 ekran, bilgisayarda güvenlik duvarı.
> Model indirilmeden önce Rick "sahte model" ile deneme modunda cevap verir. Ayrıntılar: [`docs/PLAN.md`](docs/PLAN.md).

---

## 1. Hangi dosyayı indireceğim?

GitHub'da bu sayfanın sağındaki **Releases** bölümüne girin, en üstteki sürümü açın. Orada şunlar var:

| Dosya | Ne için |
|---|---|
| `rick.apk` | Telefon uygulaması (Rick). **İnternet izni yoktur.** |
| `rick-kapi.apk` | İsteğe bağlı. Modelleri telefondan indirmek için gereken küçük yardımcı ("Kapı"). |
| `Rick-Setup-x.y.z.msi` | Windows kurulum dosyası. |
| `SHA256SUMS.txt` | Dosyaların parmak izleri (isteyen indirdiği dosyayı kontrol edebilir). |

## 2. Telefona kurulum

1. Telefonda `rick.apk` dosyasını indirin ve açın.
2. Telefon "bilinmeyen kaynaklardan yükleme" izni isterse: **Ayarlar'a git → Bu kaynaktan izin ver** deyin
   (genellikle tarayıcı ya da Dosyalar uygulaması için sorulur), sonra geri dönüp **Yükle**'ye basın.
3. *(İsteğe bağlı)* Modelleri telefondan indirmek isterseniz aynı şekilde `rick-kapi.apk` dosyasını da kurun.
   Kurmazsanız modelleri bilgisayardan kopyalayabilirsiniz (bölüm 4).
4. Rick'i açın. **Kurulum sihirbazı** başlar:
   - Cihazınızı kontrol eder (bellek, boş alan),
   - önerilen paketi gösterir (dil modeli + ses tanıma + ses + Türkçe Wikipedia, toplam boyutla),
   - **tek onay** ister: onay ekranında ne indirileceği, hangi siteden, boyutu ve beklenen parmak izi yazar;
     "Onayla"dan sonra parmak izi / yüz tanıma ya da ekran kilidi PIN'iniz istenir,
   - indirir, her dosyanın SHA-256 özetini doğrular ve "Merhaba efendim, Rick hazır." der.
   - İsterseniz "Şimdilik atla" ile indirmeden deneme modunda kullanabilirsiniz.

**Kullanım:** alttaki mikrofon tuşuna **basılı tutup** konuşun, bırakınca Rick cevap verir. Ya da yazın.
Örnekler: "Saat kaç?", "Yarın sabah 7'de alarm kur", "5 dakikalık zamanlayıcı", "Kamerayı aç",
"12 artı 30 kaç eder", "5 km kaç mil", "Sus".

## 3. Bilgisayara (Windows 10/11) kurulum

1. `Rick-Setup-x.y.z.msi` dosyasını indirip çift tıklayın.
2. **"Windows bilgisayarınızı korudu"** (SmartScreen) mavi penceresi çıkarsa: **Ek bilgi**'ye tıklayın, sonra
   **Yine de çalıştır**'a basın. (Dosya imzasız olduğu için bu uyarı çıkar; Java kurmanıza gerek yoktur,
   içinde gelir.)
3. Başlat menüsünden **Rick**'i açın. İlk açılışta Windows **bir kez yönetici onayı (UAC)** ister: **Evet** deyin.
   Bu, Rick'in ve kullandığı programların internete çıkışını Windows güvenlik duvarında kapatır. Sonra kurulum
   sihirbazı çalışır. Her indirmede yine UAC sorulur; yalnızca ayrı indirme programı (`rick-kapi.exe`) en fazla
   10 dakikalığına geçebilir ve iş bitince kapı kendiliğinden kapanır. Durum: Ayarlar → **Güvenlik duvarı**.

4. **Mikrofon:** Sohbet ekranında 🎤'a tıklayın. İlk seferde **"Rick'in ses tanımasını indir (375 MB)"**
   düğmesi çıkar; onaylayın. Sonra 🎤'a tıklayıp konuşun, susunca Rick kendisi durur. Ses bilgisayarda çözülür.
   "Mikrofondan hiç ses gelmiyor" derse: Windows Ayarlar → Gizlilik ve güvenlik → Mikrofon →
   "Masaüstü uygulamalarının mikrofonunuza erişmesine izin verin" açık olmalı.
5. **Gerçek model:** Modeller ekranından **Qwen 3.5 9B** ve **llama.cpp (Vulkan)** indirin.

**Sohbet modu:** Üstteki "SOHBET MODU" düğmesine basarsanız Rick cevabını bitirince sizi yeniden dinler;
eller serbest konuşabilirsiniz. Susarsanız ya da "sus" derseniz durur.

Rick'in dosyaları: `%LOCALAPPDATA%\Rick` (Gezgin'in adres çubuğuna yazıp Enter'a basarak açabilirsiniz).

## 4. Modelleri tekrar indirmemek

Rick bir dosyayı indirmeden önce **bu cihazda zaten var mı** diye bakar. Varsa ve SHA-256 özeti kilit listesiyle
aynıysa onu alır, internete hiç çıkmaz:

- **Bilgisayarda** kendiliğinden bakılan yerler: eski `Jarvis` klasörü, **İndirilenler**, **Masaüstü**, **Belgeler**,
  Hugging Face / LM Studio / Ollama model klasörleri. Dosya başka bir yerdeyse (ör. harici disk): Modeller →
  **📂 Klasörde bul** → klasörü seçin; Rick o klasörü hatırlar. Aynı diskteyse dosya kopyalanmaz, yer kaplamaz.
- **Telefonda:** Modeller → **📂 Klasörde bul** → dosyaların olduğu klasörü (ör. **Download**) seçin ve izin verin.
  Rick o klasörü hatırlar; sonraki kurulumlarda da önce oraya bakar.
- Güncellemeyi **eskisini kaldırmadan** kurun: modeller zaten yerinde kalır.

## 4a. Modelleri bilgisayardan telefona taşımak (Kapı kurmadan)

1. Bilgisayarda Rick'i kurun ve Modeller ekranından telefon için olan dosyaları indirin
   *(ya da Releases sayfasındaki `models.lock.json` içindeki adreslerden tarayıcıyla indirin)*.
2. Dosyaları USB kablosuyla telefonun **İndirilenler** klasörüne kopyalayın.
3. Telefonda Rick → **Modeller** → **📂 Klasörde bul** → **Download** klasörünü seçin (hepsini birden bulur).
   Tek bir dosya için **Dosyadan yükle** de kullanılabilir.
4. Rick dosyanın SHA-256 özetini hesaplar; kilit listesindeki bir dosyayla eşleşirse kurar. Eşleşmezse kırmızı
   **"Doğrulanamadı"** uyarısı gösterir ve iki ayrı onay ister — emin değilseniz reddedin.

## 4b. Wikipedia ve belgeler

- **Wikipedia:** Modeller → "Türkçe Wikipedia (resimsiz)" → İndir (3,7 GB; Wi-Fi'de indirin). Kurulunca bilgi
  soruları kaynak gösterilerek cevaplanır.
- **Belgeleriniz:** Hafıza → "Belge ekle (PDF / TXT / MD)". Sonra "kira sözleşmemde depozito ne kadar?" gibi sorun.
  Belgeler yalnızca cihazınızda saklanır.

## 4c. Eğlenceli özellikler

- **El kontrolü:** 📷 → "Kamera · el kontrolü" (bilgisayarda "El kontrolü (demo)"). İlk seferde küçük el/yüz takibi
  paketini indirin. ✋ ateş, ✋✋ mühür halkaları, 🤏 gezegen ("Rick, Mars yap"), ✊→✋ patlatma, ☝️☝️ şimşek, ☝️ ışık izi.
  Basılı tutun: 👍 dinle, ✌️ "bu ne?", ✊ sus. ⚙ ile efektleri tek tek kapatın, adınızı yazın (yüz kilidi).
- **Kahve falı:** 📷 → "☕ Kahve falı" ile fincan fotoğrafını seçin (görebilen model gerekir).
- **☰ menüsü** (sohbet ekranı): günlük brifing, yapılacaklar, ev, kitaplık, el kontrolü, uyku modu.
- **Günlük brifing:** "Rick, brifing ver" ya da "aydınlat beni": tarih, gün ışığı, yapılacaklar, okuduğunuz kitap, ev.
- **Yapılacaklar (✅):** "not al: süt al", "yapılacaklarım neler?", "fatura tamam".
- **Temalar:** Ayarlar → Görünüm: yedi tema, yazı boyutu, "Hareketi azalt".
- **Ev (🏠):** ışıklar ve kahve makineleri uygulamanın içinde canlandırılır: "bana bol köpüklü bir Türk kahvesi yap".
- **Kitaplık (📚):** "Kitaplığıma Davranış'ı ekle", "Oblomov'da 120. sayfadayım", raf fotoğrafından ekleme.
- **Uyku:** 5 dakika boşta kalınca uyur; iki kez alkışlayıp "uyan Rick" deyin (ya da ekrana iki kez dokunun).
  Ayarlar → Uyanış'tan alkış eşiğini ayarlayabilir, kendi gizli ritminizi kaydedebilirsiniz.
- **Mark 2:** Ayarlar → Ekran → Mark 2: yoğun HUD (bilgisayarın geniş ekranında tam hali).

## 5. Sık sorunlar

- **Mikrofon çalışmıyor / "Türkçe'yi internetsiz desteklemiyor":** Birçok telefonun kendi ses tanıması Türkçe'yi
  internetsiz yapamıyor. Sohbet ekranında çıkan **"Rick'in ses tanımasını indir (375 MB)"** düğmesine basın
  (onay + parmak izi/PIN). Bundan sonra Rick kendi ses tanımasını (Whisper) kullanır: ses kaydı telefonda
  çözülür, hiçbir yere gönderilmez. Mikrofona bir kez dokunun, konuşun; susunca kendisi durur.
- **Rick yazıyor ama sesli okumuyor:** Sohbette çıkan **"🔊 Rick'in sesini indir"** düğmesine basın ya da Modeller
  ekranından **Piper Türkçe ses** paketini indirin (67 MB); Rick kendi sesiyle konuşur. O zamana kadar telefonun internetsiz Türkçe sesi kullanılır (Ayarlar → Erişilebilirlik →
  Metin okuma'dan indirilebilir). Rick internet gerektiren sesleri bilerek kullanmaz.
- **Alkışla uyanmıyor / kendiliğinden uyanıyor:** Ayarlar → Uyanış → Kalibre et: bir kez alkışlayınca nokta yanmalı,
  konuşunca yanmamalı. Gerekirse eşiği değiştirin.
- **Bilgisayarda indirme "UAC onayı verilmedi" diyor:** Güvenlik duvarı açıkken her indirme için Windows'un
  yönetici onayına **Evet** demeniz gerekir.
- **"Kapı yüklü değil":** `rick-kapi.apk`'yı kurun ya da bölüm 4'teki yolu kullanın.
- **Kapı kurulu ama "Kapı servisine bağlanılamadı":** İki APK'nın aynı sürümden olduğundan emin olun. Biri
  test anahtarıyla, diğeri kendi anahtarınızla imzalıysa birlikte çalışmazlar — ikisini de silip aynı sürümün
  ikisini birden kurun.
- **"Ekran kilidi ayarlı değil":** Güvenlik için onaylar parmak izi/PIN gerektirir. Telefonda bir ekran kilidi ayarlayın.
- **Güncellerken "uygulama yüklenmedi":** Önceki sürüm farklı bir anahtarla imzalanmış olabilir. Eskisini
  kaldırıp yenisini kurun. (Hafıza o telefonun anahtarıyla şifreli olduğundan kaldırınca silinir; modelleri
  yeniden indirmemek için önce bölüm 4'teki **Klasörde bul** yolunu kullanın.) Normalde güncellemeyi eskisini
  kaldırmadan kurun.
- **Yazılar ekrana sığmıyor:** Telefonda büyük yazı ayarı açıksa Rick düğmeleri yazı kutusunun altına alır.
  Rick'in kendi yazı boyutu: Ayarlar → Görünüm.

## 6. Gizlilik garantileri ve nasıl kontrol edilir

- **Telefondaki Rick'in internet izni yoktur.** Kontrol: Ayarlar → Uygulamalar → Rick → İzinler (ya da
  "Uygulama ayrıntıları" altındaki tüm izinler). Listede **"İnternet"/"Ağ erişimi" bulunmaz**. Bu kural her
  derlemede otomatik denetlenir (`tools/check-no-internet.sh`); izin sızarsa sürüm hiç oluşmaz.
- **İnternete çıkabilen tek parça Rick Kapı'dır.** Kendi başına hiçbir şey yapmaz; yalnızca siz Rick'te
  onay verdiğinizde, yalnızca listedeki sitelerden (huggingface.co, download.kiwix.org, github.com ve bunların
  indirme sunucuları), yalnızca model/veri dosyalarını (`.litertlm .onnx .gguf .zim .tar.bz2 .json .txt`)
  indirir. `.apk .exe .so .jar` gibi çalıştırılabilir dosyaları asla indirmez. Bağlantılar sertifika
  sabitlemeli HTTPS'tir. Kapı'yı kaldırırsanız Rick tamamen çevrimdışı çalışmaya devam eder.
- **Her indirilen dosya doğrulanır:** SHA-256 özeti, uygulamanın içine gömülü `models.lock.json` ile karşılaştırılır
  (Kapı bir kez, Rick bir kez daha). Uyuşmazsa dosya silinir ve kırmızı uyarı gösterilir.
- **Her ağ olayı kayıt altındadır:** Rick → **Ağ** sekmesi: zaman, alan adı, bayt, onay şekli.
- **Telemetri, analitik, reklam ve çökme raporu yoktur.** Yedekleme/cihazlar arası aktarım da kapalıdır.
- **Hafıza, belgeler, kitaplık, ayarlar ve ağ kaydı şifrelidir** (AES-256-GCM). Telefonda anahtar Android Keystore'da
  üretilir ve dışarı çıkamaz; bilgisayarda anahtar Windows hesabınıza bağlı (DPAPI). Kamera görüntüleri hiçbir yere
  kaydedilmez; yüz kilidi tanıma değil yalnızca yüz algılamadır.
- **Bilgisayarda güvenlik duvarı:** Rick.exe, llama-server, kiwix-serve ve diğer programlar için dışarı çıkış engeli.
  Kontrol: Başlat → "Windows Defender Güvenlik Duvarı ve Gelişmiş Güvenlik" → Giden Kurallar → "Rick - …" kuralları.
- **"Tam offline mod"** (Ayarlar) açıkken hiçbir indirme yapılmaz.

### Bu güvenceler nasıl denetleniyor?

Her değişiklikte GitHub otomatik olarak şunları yapar; biri bile başarısız olursa sürüm yayımlanmaz:
- Telefon uygulamasının izinlerini APK'dan okur; "İnternet" görürse durur.
- Gerçek bir Android emülatöründe iki uygulamayı kurar, Android'in verdiği izinleri okur, Rick'i açar,
  "saat kaç" diye sorar ve Kapı üzerinden en küçük modeli (Türkçe ses, 67 MB) gerçekten indirip iki kez
  doğrular.
- Bilgisayar arayüzünü baştan sona gezen bir test çalıştırır (sihirbaz, sohbet, onay ekranı, ağ kaydı).
- İndirme kurallarını (izinli siteler, yasak dosya türleri, boyut sınırı, sertifika sabitleme, yarıda kalan
  indirmeyi sürdürme, bozuk dosyayı silme) ve "dış içerik okunduktan sonra hafıza değiştirilemez" kuralını
  ayrı ayrı test eder.

## 7. İmza anahtarı (isteğe bağlı, bir kez yapılır)

Release'deki APK'lar, sizin anahtarınız tanımlanmadıysa depodaki **herkese açık test anahtarıyla**
(`ci-test.keystore`) imzalanır ve sürüm notunda "test anahtarıyla imzalı" yazar. Kendi anahtarınızı tanımlamak için:

1. Bilgisayarda bir anahtar dosyası oluşturun (Java'nın `keytool` aracı Android Studio ile gelir):
   `keytool -genkeypair -keystore jarvis.jks -alias jarvis -keyalg RSA -keysize 3072 -validity 10000`
   Sorulan şifreyi bir yere not edin.
2. Dosyayı metne çevirin. Windows PowerShell'de:
   `[Convert]::ToBase64String([IO.File]::ReadAllBytes("jarvis.jks")) | Set-Clipboard`
   (metin panoya kopyalanır).
3. GitHub'da depo sayfası → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**
   ile şu dördünü ekleyin:
   - `ANDROID_KEYSTORE_BASE64` → panodaki metin
   - `ANDROID_KEYSTORE_PASSWORD` → anahtar deposu şifresi
   - `ANDROID_KEY_ALIAS` → `jarvis`
   - `ANDROID_KEY_PASSWORD` → anahtar şifresi (aynıysa aynısını yazın)
4. Bundan sonraki sürümler sizin anahtarınızla imzalanır. `jarvis.jks` dosyasını ve şifreyi kaybetmeyin;
   kaybederseniz güncellemeler eski kurulumun üzerine kurulamaz.

## 8. Sürümü yayımlamak (tek seferlik tıklama)

Kurulum dosyaları her yeni sürüm etiketinde otomatik üretilir. Yeni bir sürüm yayımlamak için:
1. GitHub'da depo sayfası → sağdaki **Releases** → **Draft a new release** (ya da "Create a new release").
2. **Choose a tag** kutusuna `v0.1.0` yazın → **Create new tag: v0.1.0 on publish**.
3. **Target** olarak geliştirme dalını (`claude/new-session-2w03vz`) ya da birleştirildiyse `main`'i seçin.
4. **Publish release**'e basın. Birkaç dakika içinde `rick.apk`, `rick-kapi.apk`, `Rick-Setup-0.1.0.msi`,
   `models.lock.json` ve `SHA256SUMS.txt` bu sürüme kendiliğinden eklenir ve sürüm notu yazılır
   (Actions sekmesinde "release" iş akışını izleyebilirsiniz).

## 9. Lisanslar ve modeller

| Bileşen | Kaynak | Lisans |
|---|---|---|
| Gemma 4 E4B / E2B (LiteRT-LM) | `litert-community` (Hugging Face) | Apache-2.0 |
| Qwen 3.5 9B Q4_K_M (GGUF) | `unsloth/Qwen3.5-9B-GGUF` | Apache-2.0 |
| Gemma 4 E4B Q4_0 (GGUF) | `ggml-org/gemma-4-E4B-it-GGUF` | Apache-2.0 |
| Whisper small (sherpa-onnx) | `csukuangfj/sherpa-onnx-whisper-small` | MIT (OpenAI Whisper) |
| Piper `tr_TR-dfki-medium` | `k2-fsa/sherpa-onnx` `tts-models` (kaynak: `rhasspy/piper-voices`) | piper-voices deposu MIT; sesin eğitim verisi DFKI'nin Türkçe ses verisidir — ayrıntı için model kartına bakın: <https://huggingface.co/rhasspy/piper-voices/blob/main/tr/tr_TR/dfki/medium/MODEL_CARD> |
| Türkçe Wikipedia (ZIM) | Kiwix | CC BY-SA 4.0 |
| llama.cpp `llama-server` | `ggml-org/llama.cpp` | MIT |
| kiwix-tools | Kiwix | GPL-3.0 |
| MediaPipe el/yüz modelleri (hand_landmarker, BlazeFace) | Google (`storage.googleapis.com/mediapipe-models`) | Apache-2.0 |
| Görme eklentileri (mmproj GGUF) | `unsloth` / `ggml-org` | Apache-2.0 |

Kesin sürümler, adresler ve SHA-256 özetleri: [`models.lock.json`](models.lock.json).

---

**Geliştirme:** görev tanımı [`CLAUDE.md`](CLAUDE.md), plan [`docs/PLAN.md`](docs/PLAN.md), kararlar
[`docs/DECISIONS.md`](docs/DECISIONS.md), değişiklik notları [`docs/changelog/`](docs/changelog/).
