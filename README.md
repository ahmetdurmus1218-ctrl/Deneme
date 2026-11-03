# Jarvis — çevrimdışı, ücretsiz, Türkçe kişisel asistan

Jarvis telefonunuzda (Android) ve bilgisayarınızda (Windows) **tamamen internetsiz** çalışan, Türkçe konuşan
bir yapay zekâ asistanıdır. Konuşmalarınız, hafızası ve belgeleriniz cihazınızdan çıkmaz. Abonelik, API
anahtarı ya da reklam yoktur.

> **Durum:** v0.3.0. Gerçek yapay zekâ modeli ve Jarvis'in kendi Türkçe ses tanıması (Whisper) çalışıyor: telefonda Gemma 4 (LiteRT-LM), PC'de Qwen 3.5 9B
> (llama.cpp, Vulkan). Model indirilmeden önce Jarvis "sahte model" ile deneme modunda cevap verir.
> Offline Wikipedia ve belge araması v0.3'te gelecek. Ayrıntılar: [`docs/PLAN.md`](docs/PLAN.md).

---

## 1. Hangi dosyayı indireceğim?

GitHub'da bu sayfanın sağındaki **Releases** bölümüne girin, en üstteki sürümü açın. Orada şunlar var:

| Dosya | Ne için |
|---|---|
| `jarvis.apk` | Telefon uygulaması (Jarvis). **İnternet izni yoktur.** |
| `jarvis-kapi.apk` | İsteğe bağlı. Modelleri telefondan indirmek için gereken küçük yardımcı ("Kapı"). |
| `Jarvis-Setup-x.y.z.msi` | Windows kurulum dosyası. |
| `SHA256SUMS.txt` | Dosyaların parmak izleri (isteyen indirdiği dosyayı kontrol edebilir). |

## 2. Telefona kurulum

1. Telefonda `jarvis.apk` dosyasını indirin ve açın.
2. Telefon "bilinmeyen kaynaklardan yükleme" izni isterse: **Ayarlar'a git → Bu kaynaktan izin ver** deyin
   (genellikle tarayıcı ya da Dosyalar uygulaması için sorulur), sonra geri dönüp **Yükle**'ye basın.
3. *(İsteğe bağlı)* Modelleri telefondan indirmek isterseniz aynı şekilde `jarvis-kapi.apk` dosyasını da kurun.
   Kurmazsanız modelleri bilgisayardan kopyalayabilirsiniz (bölüm 4).
4. Jarvis'i açın. **Kurulum sihirbazı** başlar:
   - Cihazınızı kontrol eder (bellek, boş alan),
   - önerilen paketi gösterir (dil modeli + ses tanıma + ses + Türkçe Wikipedia, toplam boyutla),
   - **tek onay** ister: onay ekranında ne indirileceği, hangi siteden, boyutu ve beklenen parmak izi yazar;
     "Onayla"dan sonra parmak izi / yüz tanıma ya da ekran kilidi PIN'iniz istenir,
   - indirir, her dosyanın SHA-256 özetini doğrular ve "Merhaba efendim, Jarvis hazır." der.
   - İsterseniz "Şimdilik atla" ile indirmeden deneme modunda kullanabilirsiniz.

**Kullanım:** alttaki mikrofon tuşuna **basılı tutup** konuşun, bırakınca Jarvis cevap verir. Ya da yazın.
Örnekler: "Saat kaç?", "Yarın sabah 7'de alarm kur", "5 dakikalık zamanlayıcı", "Kamerayı aç",
"12 artı 30 kaç eder", "5 km kaç mil", "Sus".

## 3. Bilgisayara (Windows 10/11) kurulum

1. `Jarvis-Setup-x.y.z.msi` dosyasını indirip çift tıklayın.
2. **"Windows bilgisayarınızı korudu"** (SmartScreen) mavi penceresi çıkarsa: **Ek bilgi**'ye tıklayın, sonra
   **Yine de çalıştır**'a basın. (Dosya imzasız olduğu için bu uyarı çıkar; Java kurmanıza gerek yoktur,
   içinde gelir.)
3. Başlat menüsünden **Jarvis**'i açın. İlk açılışta kurulum sihirbazı çalışır; indirme için onay penceresi
   çıkar. *(v0.5'te bu adım Windows'un yönetici onayı (UAC) ve güvenlik duvarı kurallarıyla güçlenecek.)*

4. **Mikrofon:** Sohbet ekranında 🎤'a tıklayın. İlk seferde **"Jarvis'in ses tanımasını indir (375 MB)"**
   düğmesi çıkar; onaylayın. Sonra 🎤'a tıklayıp konuşun, susunca Jarvis kendisi durur. Ses bilgisayarda çözülür.
   "Mikrofondan hiç ses gelmiyor" derse: Windows Ayarlar → Gizlilik ve güvenlik → Mikrofon →
   "Masaüstü uygulamalarının mikrofonunuza erişmesine izin verin" açık olmalı.
5. **Gerçek model:** Modeller ekranından **Qwen 3.5 9B** ve **llama.cpp (Vulkan)** indirin.

**Sohbet modu:** Üstteki "SOHBET MODU" düğmesine basarsanız Jarvis cevabını bitirince sizi yeniden dinler;
eller serbest konuşabilirsiniz. Susarsanız ya da "sus" derseniz durur.

Jarvis'in dosyaları: `%LOCALAPPDATA%\Jarvis` (Gezgin'in adres çubuğuna yazıp Enter'a basarak açabilirsiniz).

## 4. Modelleri bilgisayardan telefona taşımak (Kapı kurmadan)

1. Bilgisayarda Jarvis'i kurun ve Modeller ekranından telefon için olan dosyaları indirin
   *(ya da Releases sayfasındaki `models.lock.json` içindeki adreslerden tarayıcıyla indirin)*.
2. Dosyaları USB kablosuyla telefonun **İndirilenler** klasörüne kopyalayın.
3. Telefonda Jarvis → **Modeller** → **Dosyadan yükle** → dosyayı seçin.
4. Jarvis dosyanın SHA-256 özetini hesaplar; kilit listesindeki bir dosyayla eşleşirse kurar. Eşleşmezse kırmızı
   **"Doğrulanamadı"** uyarısı gösterir ve iki ayrı onay ister — emin değilseniz reddedin.

## 4b. Wikipedia ve belgeler

- **Wikipedia:** Modeller → "Türkçe Wikipedia (resimsiz)" → İndir (3,7 GB; Wi-Fi'de indirin). Kurulunca bilgi
  soruları kaynak gösterilerek cevaplanır.
- **Belgeleriniz:** Hafıza → "Belge ekle (PDF / TXT / MD)". Sonra "kira sözleşmemde depozito ne kadar?" gibi sorun.
  Belgeler yalnızca cihazınızda saklanır.

## 5. Sık sorunlar

- **Mikrofon çalışmıyor / "Türkçe'yi internetsiz desteklemiyor":** Birçok telefonun kendi ses tanıması Türkçe'yi
  internetsiz yapamıyor. Sohbet ekranında çıkan **"Jarvis'in ses tanımasını indir (375 MB)"** düğmesine basın
  (onay + parmak izi/PIN). Bundan sonra Jarvis kendi ses tanımasını (Whisper) kullanır: ses kaydı telefonda
  çözülür, hiçbir yere gönderilmez. Mikrofona bir kez dokunun, konuşun; susunca kendisi durur.
- **Jarvis yazıyor ama sesli okumuyor:** Ayarlar → Erişilebilirlik → Metin okuma'dan Türkçe sesi indirin.
  Jarvis internet gerektiren sesleri bilerek kullanmaz.
- **"Kapı yüklü değil":** `jarvis-kapi.apk`'yı kurun ya da bölüm 4'teki yolu kullanın.
- **Kapı kurulu ama "Kapı servisine bağlanılamadı":** İki APK'nın aynı sürümden olduğundan emin olun. Biri
  test anahtarıyla, diğeri kendi anahtarınızla imzalıysa birlikte çalışmazlar — ikisini de silip aynı sürümün
  ikisini birden kurun.
- **"Ekran kilidi ayarlı değil":** Güvenlik için onaylar parmak izi/PIN gerektirir. Telefonda bir ekran kilidi ayarlayın.
- **Güncellerken "uygulama yüklenmedi":** Önceki sürüm farklı bir anahtarla imzalanmış olabilir. Eskisini
  kaldırıp yenisini kurun (v0.1'de hafıza zaten kalıcı değil).

## 6. Gizlilik garantileri ve nasıl kontrol edilir

- **Telefondaki Jarvis'in internet izni yoktur.** Kontrol: Ayarlar → Uygulamalar → Jarvis → İzinler (ya da
  "Uygulama ayrıntıları" altındaki tüm izinler). Listede **"İnternet"/"Ağ erişimi" bulunmaz**. Bu kural her
  derlemede otomatik denetlenir (`tools/check-no-internet.sh`); izin sızarsa sürüm hiç oluşmaz.
- **İnternete çıkabilen tek parça Jarvis Kapı'dır.** Kendi başına hiçbir şey yapmaz; yalnızca siz Jarvis'te
  onay verdiğinizde, yalnızca listedeki sitelerden (huggingface.co, download.kiwix.org, github.com ve bunların
  indirme sunucuları), yalnızca model/veri dosyalarını (`.litertlm .onnx .gguf .zim .tar.bz2 .json .txt`)
  indirir. `.apk .exe .so .jar` gibi çalıştırılabilir dosyaları asla indirmez. Bağlantılar sertifika
  sabitlemeli HTTPS'tir. Kapı'yı kaldırırsanız Jarvis tamamen çevrimdışı çalışmaya devam eder.
- **Her indirilen dosya doğrulanır:** SHA-256 özeti, uygulamanın içine gömülü `models.lock.json` ile karşılaştırılır
  (Kapı bir kez, Jarvis bir kez daha). Uyuşmazsa dosya silinir ve kırmızı uyarı gösterilir.
- **Her ağ olayı kayıt altındadır:** Jarvis → **Ağ** sekmesi: zaman, alan adı, bayt, onay şekli.
- **Telemetri, analitik, reklam ve çökme raporu yoktur.** Yedekleme/cihazlar arası aktarım da kapalıdır.
- **"Tam offline mod"** (Ayarlar) açıkken hiçbir indirme yapılmaz.

### Bu güvenceler nasıl denetleniyor?

Her değişiklikte GitHub otomatik olarak şunları yapar; biri bile başarısız olursa sürüm yayımlanmaz:
- Telefon uygulamasının izinlerini APK'dan okur; "İnternet" görürse durur.
- Gerçek bir Android emülatöründe iki uygulamayı kurar, Android'in verdiği izinleri okur, Jarvis'i açar,
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
4. **Publish release**'e basın. Birkaç dakika içinde `jarvis.apk`, `jarvis-kapi.apk`, `Jarvis-Setup-0.1.0.msi`,
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

Kesin sürümler, adresler ve SHA-256 özetleri: [`models.lock.json`](models.lock.json).

---

**Geliştirme:** görev tanımı [`CLAUDE.md`](CLAUDE.md), plan [`docs/PLAN.md`](docs/PLAN.md), kararlar
[`docs/DECISIONS.md`](docs/DECISIONS.md), değişiklik notları [`docs/changelog/`](docs/changelog/).
