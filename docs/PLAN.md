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
- [ ] `v0.1.0` etiketi ve Release (CI yeşil olunca).

## v0.2 — Gerçek LLM
- [ ] Android: LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android`, 0.19.0 doğrulandı) + Gemma 4 E4B, RAM < 12 GB ya da yükleme hatasında E2B; GPU → CPU geri dönüşü; akışlı çıktı.
- [ ] PC: llama.cpp `llama-server` (Vulkan, b12034 kilitli) alt süreç, `127.0.0.1`, OpenAI uyumlu uç nokta, kapanınca alt süreci kapat; Qwen 3.5 9B Q4_K_M (kilitli), alternatif Gemma 4 E4B GGUF; düşünme modu.
- [ ] Ayarlar'daki model seçiminin etkinleşmesi.

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
Oturum 1 (2026-11-02): Bölüm 0 ve v0.1 kodu yazıldı. Sıradaki adım: CI'ı yeşile çekmek, `v0.1.0` etiketini atmak ve Release'i doğrulamak; ardından v0.2 (LiteRT-LM + llama-server).
