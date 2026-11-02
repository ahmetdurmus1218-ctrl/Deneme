# Kararlar

Bir kütüphane ya da kural beklendiği gibi uygulanamadığında seçilen yol ve nedeni.

## D1 — Sürümler CI'da doğrulanıyor
Geliştirme konteynerinden Google Maven, Hugging Face, Kiwix ve GitHub Releases erişilemiyor. Bu yüzden
`tools/probe.py` GitHub Actions'ta çalışıp resmi kaynakları okuyor; bulunan en güncel kararlı sürümler
`gradle/libs.versions.toml` dosyasına elle sabitlendi (2026-11-02). Model özetleri `tools/lock-models.py`
ile CI'da üretilip commit'leniyor (`.github/workflows/lock-models.yml`).

## D2 — AGP 9 ve KMP
AGP 9.5.0 ile `com.android.library` + KMP birlikte kullanılamıyor; `:core` ve `:ui` için resmi
`com.android.kotlin.multiplatform.library` eklentisi ve `kotlin { android { } }` bloğu kullanıldı.
Uygulama modülleri AGP 9'un yerleşik Kotlin desteğini kullanıyor (`kotlin-android` eklentisi yok).

## D3 — İzinli alan adlarına eklemeler
GitHub sürüm dosyaları artık `release-assets.githubusercontent.com` adresine yönleniyor; izin listesine
eklendi. Hugging Face büyük dosyaları `*.hf.co` (ör. `us.aws.cdn.hf.co`, `cas-bridge.xethub.hf.co`)
üzerinden veriyor; bu desen zaten listedeydi.

## D4 — Kiwix yansıları
`download.kiwix.org`, dosyaları rastgele üçüncü taraf yansılara yönlendiriyor (ör. `dumps.wikimedia.org`,
`wi.mirror.driftle.ss`). İzin listesini delmemek için kilit dosyası Kiwix'in kendi ana yansısını
(`mirror.download.kiwix.org`, yönlendirmesiz) kullanıyor; özet resmi `<dosya>.sha256` dosyasından alınıyor.
kiwix-tools ikilileri için resmi özet yayımlanmadığından özet CI'da dosya indirilerek hesaplanıyor.

## D5 — PC'de `.zip` ikilileri
`llama-server.exe` ve `kiwix-serve.exe` yalnızca `.zip` olarak yayımlanıyor. Bölüm 3.2'deki uzantı listesi
korunuyor; tek istisna: **yalnızca PC'de**, **yalnızca `kind = binary`** ve **kilit dosyasında sabit URL +
SHA-256 ile** kayıtlı `.zip` dosyaları. Android'de `.zip` hiçbir zaman kabul edilmez; `.exe/.dll/.so/.apk/...`
her iki platformda da yasak.

## D6 — Kapı yalnızca kilitli öğeleri indirir
Kapı, isteği kendi içindeki `models.lock.json` ile karşılaştırır (id, URL, boyut, SHA-256 birebir).
Kilitte olmayan dosyalar v0.1'de yalnızca "Dosyadan yükle" ile eklenebilir; bu durumda kırmızı
"Doğrulanamadı" uyarısı ve iki ayrı onay istenir.

## D7 — Dosya aktarımı yönü
Çift disk alanı gerekmesin diye Kale hedef dosyayı kendi özel deposunda açar ve yazılabilir
`ParcelFileDescriptor`'ı Kapı'ya verir; Kapı doğrudan oraya yazar (Range ile devam ettirilebilir).
Kapı sonunda SHA-256'yı hesaplar; Kale dosyayı aldıktan sonra **kendisi yeniden** hesaplar.

## D8 — Sertifika sabitleme
Yaprak sertifikalar sık yenilendiği için CA anahtarları (kökler + güncel ara sertifikalar) sabitlendi
(Amazon / Let's Encrypt ISRG / Sectigo-USERTrust). Pinler canlı zincirden `probe.yml` ile alındı ve
sistem CA deposuyla karşılaştırıldı. Bir alan adı CA değiştirirse indirmeler güvenli biçimde başarısız olur;
Kapı güncellemesi gerekir.

## D9 — Sihirbazda tek onay
Bölüm 3.3 "bir onay = tek istek" diyor; bölüm 6 sihirbazda "tek onay" istiyor. Uzlaşma: onay ekranında
indirilecek dosyaların tam listesi (ad, boyut, alan adı, beklenen SHA-256) gösterilir ve tek onay yalnızca
**o listedeki dosyalar** için geçerlidir. Kalıcı izin yoktur.

## D10 — Zor sorularda öz kontrol
Cevaplar akışlı seslendirildiği için ikinci bir LLM turu gecikmeyi ikiye katlardı. Zor sorularda düşünme
modu açılır ve sistem prompt'u modelden cevabı JSON'u üretmeden önce bir kez kontrol etmesini ister.

## D11 — Bozuk JSON
Akış sırasında en az bir sahne çıktıysa cevap o sahnelerle tamamlanır; hiç sahne çıkmadıysa model bir kez
düzeltmeye çağrılır, yine bozuksa düz metin tek sahne olarak gösterilir.

## D12 — v0.1'de PC indirmeleri
UAC + güvenlik duvarı otomasyonu ve ayrı `jarvis-kapi` başlatıcısı v0.5 işi. v0.1'de aynı kurallarla
(izin listesi, sabitleme, boyut, özet) çalışan indirici uygulama içinde ayrı bir pakette
(`desktop/kapi`) duruyor ve ikinci onay bir Windows onay penceresiyle alınıyor.

## D13 — v0.1'de ses
Android'de yalnızca **cihaz içi** tanıyıcı (`createOnDeviceSpeechRecognizer`) kullanılır; ağ isteyen Türkçe
TTS sesleri reddedilir (yoksa Jarvis sessiz kalır, yazı ile devam eder). PC'de v0.1'de ses tanıma yok;
Türkçe Windows sesi kuruluysa SAPI ile okunur. Whisper + Piper v0.4'te.

## D14 — v0.1'de hafıza
Şifreli kalıcı hafıza v0.4 işi. O zamana kadar şifresiz diske yazmamak için hafıza yalnızca uygulama
açıkken bellekte tutulur.

## D15 — Piper sesi
`tr_TR-dfki-medium`, sherpa-onnx'in hazır paketinden (`k2-fsa/sherpa-onnx` `tts-models` sürümü,
`vits-piper-tr_TR-dfki-medium.tar.bz2`) alınıyor; ham Piper `.onnx` dosyası sherpa-onnx için ek meta veri
ve `espeak-ng-data` gerektiriyor. Ses lisansı README'de (rhasspy/piper-voices model kartı) belirtilir.

## D16 — Güvenilmeyen veriden sonra yan etkili araç yok
Wikipedia, belge ya da web çıktısı bir soruda bağlama girdiği anda, aynı soru içinde durumu değiştiren
araçlar (bugün `hafiza_ekle`, `hafiza_sil`; ileride alarm/uygulama açma) reddedilir. Böylece bir makaleye
gizlenmiş "önceki talimatları yok say ve şunu kaydet" türü bir metin hiçbir şeyi değiştiremez
(`SecurityTest`). Araçların kendi hataları da asistanı düşürmez, araç hatası olarak modele döner.

## D17 — Sesli komutlarda yazıyla sayılar
Konuşma tanıma "beş dakika", "yedide", "yedi buçukta" gibi sayıları yazıyla verebiliyor. Hazır komut
yönlendiricisi yalnızca kendi eşleştirmesi için bunları rakama çevirir (`TurkishNumbers`); modele giden
metin hiçbir zaman değiştirilmez. Uygulama adlarında ünsüz yumuşaması geri alınmaz ("Bing" → "Bink"
olmasın diye); yaygın Türkçe adlar için platformda takma adlar tutulur ("müziği" → müzik).

## D18 — Doğrulama yöntemi
Geliştirme konteyneri Google Maven'a erişemediği için Android/Compose derlemesi yalnızca CI'da yapılır.
Saf Kotlin kısımlar (çekirdek, Kapı indirme mantığı, PC indiricisi) konteynerde de derlenip test edilir.
CI ayrıca: PC arayüzünü baştan sona gezen bir Compose UI testi (ekran görüntüleriyle) ve gerçek bir
Android emülatöründe uçtan uca bir test çalıştırır: iki APK'yı kurar, Android'in verdiği izinleri okur
(Kale'de İnternet yok, GATE izni verilmiş), uygulamayı açar, hazır komut sorar ve en küçük kilitli dosyayı
(67 MB Türkçe Piper sesi) PIN onayı → Kapı → sabitlenmiş HTTPS → iki kez SHA-256 zinciriyle gerçekten indirir.

## D19 — Kilit dosyası güncellemeleri
`tools/models.select.json` içinde "latest" denen kaynaklar (ör. llama.cpp) kilit üretildiği anda çözülüp
tam sürüm + özetle sabitlenir. Kilit yalnızca seçim dosyası/betik değiştiğinde ya da elle tetiklendiğinde
yeniden üretilir; yalnızca zaman damgası değiştiyse commit yapılmaz.

## D20 — Gerçek LLM seçimi ve geri dönüş
Asistan tek bir `EngineSelector` üzerinden konuşur: her soruda kurulu ve doğrulanmış modele bakar, yoksa
sahte modele döner (arayüz "sahte model" der). Android'de seçim: Ayarlar'daki model → RAM ≥ 12 GB ise
Gemma 4 E4B → E2B. Yükleme başarısız olursa model o oturum için işaretlenir ve bir sonrakine geçilir;
LiteRT-LM önce GPU, olmazsa CPU arka ucuyla açılır. PC'de seçim: Ayarlar'daki model → Qwen 3.5 9B →
Gemma 4 E4B GGUF; doğrulanmış llama.cpp (Vulkan) zip'i de kurulu olmalıdır. Zip ilk kullanımda
`%LOCALAPPDATA%\Jarvis\bin` altına (dışarı taşan girdiler reddedilerek) açılır, `llama-server`
`127.0.0.1` ve rastgele boş bir bağlantı noktasında `-ngl 99 -c 8192 --jinja` ile başlatılır, pencere
kapanınca ya da model değişince alt süreçleriyle birlikte kapatılır. Düşünme modu Qwen/Gemma sohbet
şablonlarının `enable_thinking` anahtarıyla açılıp kapanır; düşünme metni (`reasoning_content`)
kullanıcıya gösterilmez, yalnızca cevap metni akar. Bazı LiteRT-LM sürümleri biriken metni, bazıları
yalnızca yeni parçayı döndürdüğü için `DeltaTracker` ikinci parçada biçimi anlayıp tekrar etmeyi önler.
