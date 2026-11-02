# Jarvis — Plan ve durum

Kaynak görev: [`CLAUDE.md`](../CLAUDE.md). Her aşama çalışan bir GitHub Release ile biter.
İşaretler: `[x]` bitti · `[~]` kısmen / sonraki aşamaya taşındı · `[ ]` yapılmadı.

## Bölüm 0 — Hazırlık
- [x] Görev metni `CLAUDE.md` olarak kaydedildi.
- [x] Bu plan dosyası.
- [x] Kütüphane sürümleri resmi kaynaklardan (Maven Central, Google Maven, Gradle Plugin Portal) `tools/probe.py` ile CI'da doğrulandı ve `gradle/libs.versions.toml` dosyasına sabitlendi.
- [x] Model/ikili dosya kaynakları `tools/lock-models.py` ile çözüldü, `models.lock.json` CI'da üretilip commit'lendi.

## v0.1 — İskelet, CI, sahte LLM, sihirbaz ve onay
- [x] Repo yapısı: `:core` (KMP), `:ui` (Compose Multiplatform), `:androidApp` (Kale), `:gate` (Kapı), `:desktopApp` (Windows), `tools/`.
- [x] `:core`: Türkçe normalizasyon, hazır komut yönlendiricisi (saat, tarih, alarm, zamanlayıcı, uygulama açma, sus, hesap, birim), karmaşıklık puanı, sahne JSON modeli + akışlı ayrıştırıcı, doğrulama (TR/EN sayı yazımları, alıntılar, "kaynak yok"), güvenli hesap makinesi, birim çevirici, araç kayıt defteri (en fazla 5 tur, paralel), prompt-injection tarayıcı, sistem prompt'u (sabit kısım başta, tarih/hafıza sonda), hafıza arayüzü (500 kayıt sınırı, tekrar ayıklama), model kayıt defteri, indirme politikası (alan adı + uzantı + boyut + SHA-256), sahte LLM.
- [x] `:core` birim testleri (doğrulama, ayrıştırıcı, yönlendirici, araçlar, politika, asistan akışı).
- [x] `:ui`: HUD çekirdeği (nefes alan / dinlerken mikrofon seviyesine, konuşurken titreşen Canvas animasyonu), sahne kartları, altyazı, kaynak çipleri, Sohbet (yazı + bas-konuş), Kurulum sihirbazı, Onay ekranı, Modeller, Hafıza, Ağ kayıt defteri, Ayarlar.
- [x] Kale: INTERNET izni yok (manifest'te `tools:node="remove"`, CI'da `tools/check-no-internet.sh` APK'yı aapt2 ile denetler). Sistem STT (`createOnDeviceSpeechRecognizer`, tr-TR) ve sistem TTS (yalnızca ağ gerektirmeyen Türkçe ses). Biyometrik/PIN onayı (`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`). Alarm/zamanlayıcı/uygulama açma Intent'leri. "Dosyadan yükle" + SHA-256 kontrolü, kilitte yoksa iki ayrı onay.
- [x] Kapı: imza seviyesinde `GATE` izniyle korunan Messenger servisi, yalnızca kilitli öğeler, HTTPS + alan adı izin listesi (her yönlendirmede), OkHttp sertifika sabitleme, uzantı listesi, kesin boyut sınırı, Range ile devam, SHA-256. Dosya Kale'nin `ParcelFileDescriptor`'ına yazılır; Kale özeti kendisi yeniden hesaplar.
- [x] PC: Compose Desktop, gömülü JRE'li MSI (jpackage), `%LOCALAPPDATA%\Jarvis`, aynı politika ve sabitleme kurallarıyla yerleşik `jarvis-kapi` indiricisi, Windows SAPI (Türkçe ses kuruluysa).
- [x] CI: `ci.yml` (testler, lint, APK + MSI derleme, Kale izin denetimi, `:core` ağ API yasağı, kilit şema kontrolü), `release.yml` (`v*` etiketi → APK'lar + MSI + SHA256SUMS), `lock-models.yml`, `probe.yml`.
- [x] Sertleştirme turu (oturum 1, "max" inceleme): güvenilmeyen veriden sonra yan etkili araç engeli, araç hatalarına dayanıklılık, yazıyla sayılar ve nezaket sözcükleri, uygulama adı düzeltmesi (Spotify'ı), yarıda kesilen tur, JSON sohbet geçmişi, onay reddi sebebi, Kapı bağlantı zaman aşımı, iki ayrı URL ayrıştırıcısıyla host kontrolü, 206/416 işleme, kenardan kenara ekran/klavye boşlukları.
- [x] Testler: çekirdek (60+), Kapı indirme mantığı ve sertifika pinleri (gerçek kök sertifikalarla), PC indiricisi (yerel HTTP sunucusuyla), arayüz denetleyicisi, PC arayüzü baştan sona (ekran görüntülü), Android emülatöründe uçtan uca gerçek indirme.
- [x] CI tamamen yeşil: birim testleri, Android lint, APK'lar, "Kale'de İnternet yok" denetimi, PC arayüz gezintisi, Windows MSI ve Android emülatöründe uçtan uca test (Kapı üzerinden gerçek indirme + iki kez SHA-256).
- [x] `v0.1.0` yayımlandı: https://github.com/ahmetdurmus1218-ctrl/Deneme/releases/tag/v0.1.0 (jarvis.apk, jarvis-kapi.apk, Jarvis-Setup-0.1.0.msi, models.lock.json, SHA256SUMS.txt). Bu oturumdan etiket gönderimi engelli olduğu için sürüm `.github/release-request` dosyasıyla tetiklendi; sonraki sürümlerde bu dosyaya yeni etiketi yazmak yeterli.

## v0.1.1 — Gerçek telefonda bulunan indirme hataları
- [x] Grup başına tek indirme düğmesi, indirmeler sırayla (aynı dosya iki kez indirilmez — ENOENT hatası).
- [x] Bağlantı koptuğunda kaldığı yerden otomatik yeniden deneme (4 deneme).
- [x] Kapı indirme sırasında ön plan servisi (bildirimli), ekran kapansa da sürer.
- [x] `v0.1.1` yayımlandı: https://github.com/ahmetdurmus1218-ctrl/Deneme/releases/tag/v0.1.1

## v0.2 — Gerçek LLM
- [x] Android: LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android` 0.19.0) + Gemma 4 E4B, RAM < 12 GB ya da yükleme hatasında E2B; GPU → CPU geri dönüşü; akışlı çıktı; düşünme modu (desteklenmezse onsuz yeniden dener).
- [x] PC: llama.cpp `llama-server` (Vulkan, b12038 kilitli) alt süreç, `127.0.0.1` + boş port, OpenAI uyumlu akışlı uç nokta (SSE), pencere kapanınca alt süreç ağacı kapanır; Qwen 3.5 9B Q4_K_M (varsayılan), alternatif Gemma 4 E4B GGUF; düşünme modu (`enable_thinking`).
- [x] Ayarlar'daki model seçimi etkin; kurulu model yoksa sahte modele dönülür ve bu ekranda yazar.
- [x] Testler: motor seçici / akış biçimi (çekirdek), llama-server istemcisi yerel sahte sunucuyla, zip-slip reddi, PC model seçimi.
- [ ] Gerçek cihazda Gemma 4 ve RX 6600'de Qwen ile performans ölçümü (kullanıcı geri bildirimiyle).
- [x] `v0.2.0` yayımlandı: https://github.com/ahmetdurmus1218-ctrl/Deneme/releases/tag/v0.2.0

## v0.2.1 — Mikrofon düzeltmesi (gerçek telefondan geri bildirim)
- [x] Mikrofon düğmesi: dokun-konuş (sessizlikte kendisi durur) + basılı tutarak konuşma.
- [x] Android 13+: Türkçe cihaz içi ses paketi kontrolü, onaylı "Türkçe ses paketini indir" düğmesi (sistem indirir, ağ kaydına yazılır).
- [x] Anlaşılır Türkçe hata mesajları; meşgul/kopan tanıyıcıda otomatik yeniden deneme.

## v0.3 — Bilgi
- [ ] Android: java-libkiwix (`org.kiwix:libkiwix` 2.7.0 doğrulandı) ile Türkçe Wikipedia ZIM araması/okuma (`wiki_ara`, `wiki_oku`).
- [ ] PC: `kiwix-serve` (3.8.1 kilitli) alt süreç + arama API'si.
- [ ] Belge içe aktarma (PDF/TXT/MD), parçalama, yerel embedding ya da BM25, `belge_ara`.
- [ ] Platform aksiyon araçlarının (alarm_kur, zamanlayici, uygulama_ac) LLM'e açılması; araç çıktısı bağlamdayken riskli aksiyon için onay.

## v0.4 — Ses ve hafıza
- [ ] sherpa-onnx (Android AAR 2.0.1, Windows JNI 2.0.1) ile Whisper small (kilitli) + Piper tr_TR-dfki-medium (kilitli).
- [ ] Şifreli hafıza: Android Room + SQLCipher (anahtar Keystore'da), PC SQLCipher JDBC; WorkManager ile arka planda bilgi çıkarımı (FactExtractor).

## v0.5 — Son rötuşlar
- [ ] HUD animasyonlarının son hali.
- [ ] PC güvenlik duvarı: ilk açılışta UAC ile `netsh advfirewall` giden trafik engelleme kuralları (JVM, llama-server.exe, kiwix-serve.exe); ayrı `jarvis-kapi` başlatıcısı (`--add-launcher`) ve indirme süresince geçici izin kuralı (en çok 10 dk).
- [ ] Belgelerin son hali.

## Kaldığım yer
Oturum 1 (2026-11-02): Bölüm 0 ve v0.1 yazıldı, CI yeşil, "max" incelemesi yapıldı, v0.1.0 ve gerçek
telefondaki indirme hataları için v0.1.1 yayımlandı. v0.2 (gerçek LLM: Android LiteRT-LM + Gemma 4,
PC llama-server + Qwen 3.5 9B) yazıldı ve v0.2.0 olarak yayımlandı. Sıradaki adım: v0.3 (offline
Wikipedia: java-libkiwix / kiwix-serve, belge araması, araç çağırma ve kaynak çipleri).
