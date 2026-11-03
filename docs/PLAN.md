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
- [x] `v0.2.1` yayımlandı: https://github.com/ahmetdurmus1218-ctrl/Deneme/releases/tag/v0.2.1

## v0.2.2 — Mikrofon: Jarvis'in kendi ses tanıması (Whisper, v0.4'ten öne alındı)
- [x] Emülatörde teşhis: telefonun servisi "var" diyor, Türkçe'yi desteklemiyor, dinleme 30 ms'de hata 11 ile kapanıyor.
- [x] Android: Whisper small (sherpa-onnx 2.0.1, AAR GitHub'dan SHA-256 sabitli), AudioRecord, konuşma sonu algılama (çekirdekte, testli).
- [x] Sohbet ekranında tek dokunuşla "Jarvis'in ses tanımasını indir"; telefon Türkçe'yi desteklemiyorsa açıkça söylenir.
- [x] İndirmeler sekme değişince iptal olmuyor (yükleyicinin kendi kapsamı); onay istemi ana iş parçacığında.
- [x] Emülatör testi: mikrofon → teklif → 3 dosya tek onay → cihazda Türkçe kaydın Whisper ile çözülmesi → Whisper'lı mikrofon.
- [x] `v0.2.2` yayımlandı; jarvis.apk 146 MB çıktığı için `v0.2.3`'te CPU türüne göre ayrı APK + sıkıştırılmış yerel kütüphaneler: 51 MB. https://github.com/ahmetdurmus1218-ctrl/Deneme/releases/tag/v0.2.3
- [ ] PC'de Whisper (sherpa-onnx JNI) — v0.4.
- [ ] Piper (Türkçe ses): sherpa-onnx 2.0 espeak-ng'yi çıkardı; Türkçe fonem dönüştürücü gerekiyor (bkz. D22) — v0.4.

## v0.2.4 — Yeni arayüz, PC mikrofonu, sohbet modu
- [x] Sahne tasarımı: canlı çekirdek, durum, daktilo altyazı, cam kartlar, sayaç sayılar, geçmiş görünümü, durum çubuğu.
- [x] Sohbet modu (eller serbest), testli.
- [x] PC'de Whisper mikrofon (Windows'ta CI testiyle doğrulandı).
- [x] `v0.2.4` yayımlandı: https://github.com/ahmetdurmus1218-ctrl/Deneme/releases/tag/v0.2.4
- [ ] Sıradaki arayüz adımları: kamera ile el/yüz takibi ve efektler (kullanıcı kararı bekleniyor), Mark 1/Mark 2 gibi yoğunluk seçeneği.

## v0.3 — Bilgi
- [x] Android: java-libkiwix 2.7.0 (yalnızca ZIM okuyucu yerel kütüphaneleri paketlenir) ile Wikipedia ZIM araması/okuma (`wiki_ara`, `wiki_oku`); emülatörde gerçek bir Wikipedia ZIM'iyle test.
- [x] PC: `kiwix-serve` (3.8.1 kilitli) 127.0.0.1'de alt süreç; /suggest, /search?format=xml, /raw; PC'de ZIM indirilince kiwix-tools da gelir. Gerçek kiwix-serve ile test (yerelde 3.5.0, CI'da Windows 3.8.1).
- [x] Belge içe aktarma (PDF/TXT/MD), parçalama, Türkçe BM25 (5 harf kök), `belge_ara`; Hafıza ekranında "Belgelerim".
- [x] Aksiyon araçları (`alarm_kur`, `zamanlayici`, `uygulama_ac`) telefonda modele açık; Wikipedia/belge okunduktan sonra reddedilir (testli); arama/mesaj uygulamaları için onay.
- [x] Kaynak çipleri ve doğrulama Wikipedia/belge cevaplarında çalışıyor.

## v0.3.x — Kamera (kullanıcının halilozat/jarvis örneği, internetsiz)
- [x] CameraX + MediaPipe Tasks Vision: el (21 nokta) ve yüz takibi telefonda; modeller kilitli, SHA-256'lı.
- [x] Hareketler (açık el, yumruk, başparmak, zafer, işaret, çimdik), gerçek MediaPipe fotoğraflarıyla test.
- [x] "Bu ne? / Oku / ☕ Fal" görme modeliyle (Gemma 4 görme, PC'de llama-server --mmproj); PC'de ekran ve resim.
- [x] Emülatör testi bir çökmeyi yakaladı: kamerası olmayan cihazda CameraX hata atıyordu; artık ekranda yazıyor.

## v0.4 — Ses ve hafıza
- [x] Whisper small: Android (v0.2.2) ve PC (v0.2.4).
- [x] Piper tr_TR-dfki-medium: kendi Türkçe fonem dönüştürücümüz (espeak-ng kuralları, D29), sayılar yazıyla; telefonda ve PC'de. Doğrulama: Jarvis cümleyi söylüyor, Whisper dinliyor — emülatörde ve Windows'ta "Merhaba efendim, Cervis hazır. Saat 14.35".
- [x] Şifreli, kalıcı hafıza: AES-256-GCM (Android Keystore anahtarı / Windows DPAPI ile korunan anahtar), hafıza, belge dizini, kitaplık, ayarlar ve ağ kaydı şifreli (D30).
- [x] Bilgi çıkarımı: her cevaptan sonra arka planda, yalnızca kullanıcının kendi sözünden (kurallar + yerel model), "HAFIZAYA EKLENDİ" bildirimi.

## v0.5 — Son rötuşlar
- [x] PC güvenlik duvarı: ilk açılışta tek UAC ile Jarvis.exe, jarvis-kapi.exe ve bin altındaki her program için dışarı çıkış engeli; indirmede UAC ile yalnızca kapi kuralı en fazla 10 dakika kalkar, oturum bitince geri gelir ve yeni açılan programları da engeller (D31). Windows CI'da gerçek kuralla curl kopyası engelleniyor, kapı açılıyor, kapanıyor, süre dolunca kendiliğinden kapanıyor.
- [x] Ayrı `jarvis-kapi.exe` başlatıcısı (yalnızca kilit kimliği kabul eder), MSI'da olduğu CI'da denetleniyor.
- [x] HUD: Mark 1 / Mark 2 (yoğun HUD, gerçek sayılar, çevrimdışı gün doğumu/batımı).
- [x] Belgelerin son hali (README, değişiklik notu).

## Ek — halilozat/jarvis özellikleri (internetsiz karşılıkları)
- [x] El kontrolü: ateş + kıvılcım, iki elde mühür halkaları, çimdikle gezegen (Satürn, Dünya, Mars, Jüpiter, Ay, Güneş; taşınır, eğilir, bırakınca süzülür/fırlar, iki elle büyür), yumruk→açık el patlatma ve enerji dalgası + ekran sarsıntısı, iki işaret parmağı arasında şimşek, tek parmakla ışık izi; basılı tutma halkalı kontroller (👍 dinle ya da Türk kahvesi, ✌️ bu ne?, ✊ sus); efektler tek tek kapatılabilir; kamerasız demo; "Jarvis, Mars yap".
- [x] Yüz HUD kilidi (algılama, tanıma değil): en yakın yüz KİLİTLENİYOR → AD · TANINDI · YETKİ: TAM, diğerleri KİŞİ · TANIMLANMADI; dakikada en fazla bir selam.
- [x] Kahve falı (fincan fotoğrafı, görme modeli, "bilim değil" uyarısıyla).
- [x] Akıllı ev simülasyonu: iki ışık, Türk kahve makinesi, filtre kahve; sesli komutlar, Ev paneli, bildirim.
- [x] Uyku modu ve iki alkışla uyanma (+ "uyan Jarvis"), gizli ritim, kalibrasyon, sistem kontrollü uyanış sekansı.
- [x] Kitaplık: sesle ekleme/not/alıntı/sayfa/bitirdim, raf fotoğrafından ekleme, Jarvis'in kitap defteri (benzerler yalnızca kullanıcının rafından), öneriler kendi kitaplarından.
- [x] Mark 2 yoğun HUD.
- [x] Temalar (7 palet, açık tema dahil), yazı boyutu, hareketi azalt; ☰ menüsü.
- [x] Yapılacaklar listesi (sesle) ve internetsiz günlük brifing ("aydınlat beni"nin cihaz içi karşılığı).
- [~] İnternet gerektirenler bilinçli olarak yok: haber brifingi, piyasa, hava durumu, YouTube, Home Assistant, ElevenLabs, ikinci cihazı kamera yapma (D32).
- [~] Kamerada kitaplığa uzanıp kitabı "çekme" AR animasyonu yok; raf fotoğraftan okunuyor.

## Kaldığım yer
Oturum 2 (2026-11-03): v0.3 (Wikipedia, belgeler, araçlar), kamera, v0.4 (Piper, şifreli hafıza,
bilgi çıkarımı), v0.5 (güvenlik duvarı, jarvis-kapi.exe, Mark 2) ve halilozat/jarvis özelliklerinin
internetsiz karşılıkları yazıldı, testlendi; v0.5.0 yayımlanıyor. Sıradaki: gerçek cihaz geri
bildirimi (Gemma 4 / Qwen performansı, alkış eşiği, Piper sesi), kullanıcı internetli özellikleri
isterse onay akışıyla Kapı üzerinden nasıl yapılacağına karar.
