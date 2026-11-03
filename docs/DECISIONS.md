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

## D21 — Mikrofon: dokun-konuş ve sistemin Türkçe ses paketi
Gerçek telefonda "bas-konuş" düğmesine kısa dokunmak dinlemeyi hemen kapatıyor ve her seferinde hata
veriyordu. Düğme artık iki şekilde çalışır: kısa dokunuş dinlemeyi başlatır (tanıyıcı sessizlikte kendisi
durur, ikinci dokunuş erken bitirir); 0,6 saniyeden uzun basılı tutmak klasik bas-konuş davranışıdır.
Android 13+ cihazlarda `checkRecognitionSupport` ile Türkçe cihaz içi paketin yüklü olup olmadığı
bakılır (ayrı, kısa ömürlü bir tanıyıcıyla; yanıt gelmezse 2,5 sn sonra beklemeden devam edilir). Paket
yoksa `triggerModelDownload` yalnızca onay ekranı + biyometrik/PIN onayından sonra çağrılır. İndirmeyi
Android sistemi yapar, Kale'nin INTERNET izni yine yoktur; olay Ağ kayıt defterine "sistem indirir"
olarak yazılır. Kalıcı çözüm v0.4'teki Whisper'dır (Jarvis'in kendi, kilitli ve hash'li modeli).

## D22 — Whisper öne alındı; sherpa-onnx AAR'ı GitHub'dan, SHA-256 sabitli
Gerçek telefonda ve emülatörde Android'in cihaz içi tanıyıcısı "kullanılabilir" görünüp Türkçe'yi
desteklemediğini bildiriyor ve dinleme başlar başlamaz hata 11 (servis bağlantısı koptu) veriyor. Bu
cihaz üreticisine bağlı bir sorun olduğu için v0.4'teki Whisper v0.2.2'ye alındı: ses kaydını Jarvis
kendisi alır (AudioRecord, engellemesiz okuma), konuşmanın bittiğini çekirdekteki `SpeechEndpointer`
anlar, Whisper small int8 (dil: tr) sherpa-onnx ile telefonda çözer. sherpa-onnx Android AAR'ı Maven'da
yok; yalnızca GitHub sürüm dosyası olarak yayımlanıyor. Gradle görevi onu bir kez indirip sabit SHA-256
ile karşılaştırır, uyuşmazsa derleme kırılır. Model dosyalarının Hugging Face kopyaları sherpa-onnx'in
GitHub `asr-models` paketindekilerle bayt bayt aynı (üç SHA-256 da eşleşiyor); bu sayede çözümleme
geliştirme ortamında da denendi ("Merhaba Cervis, bu gün hava nasıl?", 4,9 sn ses 2 sn'de). Whisper
"Jarvis"i çoğu zaman "Cervis/Carvis" yazdığı için uyandırma sözcüğü bu yazımları da tanır.
Not (v0.4 için): sherpa-onnx 2.0 espeak-ng'yi çıkardı; Piper sesleri artık fonemleri çağırandan
bekliyor. Türkçe yazım büyük ölçüde sesçil olduğu için kurallı bir Türkçe→IPA dönüştürücü yazılacak;
gerekirse espeak'li son sürüm (1.13.x) değerlendirilecek.

## D23 — İndirmeler ekranın değil yükleyicinin kapsamında
İndirmeler ekranın `rememberCoroutineScope`'unda başlatılıyordu; sekme değiştirince ya da sihirbaz
kapanınca iptal ediliyordu (Kapı aktarımı da iptal). Artık `ModelInstaller` kendi kapsamında çalıştırır
(`installInBackground`); biyometrik onay istemi kendisi ana iş parçacığına geçer.

## D24 — PC'de Whisper: sherpa-onnx Java API + Java Sound
PC mikrofonu Java Sound `TargetDataLine` ile 16 kHz mono kaydeder (desteklenmezse 48/44,1 kHz kaydedilip
çekirdekteki `Downsampler` ile 16 kHz'e indirilir), konuşma sonunu telefondaki aynı `UtteranceCapture`
bulur, Whisper small sherpa-onnx'in Java API'siyle (GitHub sürüm dosyaları: `sherpa-onnx-jvm` + Windows
x64 yerel kütüphaneleri, SHA-256 sabitli) bilgisayarda çözer. Linux yerel kütüphanesi yalnızca testlerde.
`DesktopWhisperTest` Türkçe kaydı sahte bir 48 kHz ses kartından gerçek yakalama + Whisper yoluna verir;
CI bunu hem Linux'ta hem Windows'ta (Windows DLL'leriyle) çalıştırır. Hiç ses gelmiyorsa (seviye hep 0)
Windows'un mikrofon gizlilik ayarı gösterilir.

## D25 — Arayüz: sahne + sohbet modu
Kullanıcı arayüzü "dijital biriyle konuşuyormuş gibi" istedi (örnek: halilozat/jarvis'in HUD'ı). Sohbet
listesi yerine tam ekran bir sahne: tek bir Canvas'ta, tek animasyon saatinden türetilen katmanlı bir
çekirdek (rememberInfiniteTransition — test saatine uyumlu), durum etiketi, son konuşma, konuşurken daktilo
altyazı ve kayarak gelen cam kartlar; geçmiş ayrı görünümde. Saat, her karede değil dakikada bir yeniden
çizilir (`derivedStateOf`). Sohbet modu: sesle sorulan bir tur tamamen cevaplanıp seslendirilince 350 ms
sonra yeniden dinlenir; sessizlik, "sus" ya da yazıyla soru döngüyü bitirir (denetleyici testleriyle).
Tasarım CI'daki ekran görüntülerinden (günlüğe base64 basılır) gözden geçirilir.

## D26 — Wikipedia: telefonda libzim, PC'de kiwix-serve
Arama mantığı çekirdekte (`Wikipedia`): sorudan sorgu (soru sözcükleri atılır), önce başlık araması (tam
eşleşen başlık öne), sonra tam metin, tekrarlar ayıklanır, en iyi makalenin ilk 2500 karakteri `wiki_ara`
çıktısına eklenir (küçük modellerin tek turda cevaplayabilmesi için); makaleler 6000 karakterlik parçalarla
okunur. Makale HTML'i çekirdekte düz metne çevrilir (betikler, dipnot işaretleri atılır; bilgi kutusu
satırları korunur; Kaynakça/Dış bağlantılar'da durulur). Android'de java-libkiwix 2.7.0'ın yalnızca ZIM
okuyucu kütüphaneleri (`c++_shared`, `zim`, `zim_wrapper`) yüklenir ve paketlenir; Kiwix sunucu kısmı
(`libkiwix.so`, CPU türü başına ~28 MB) dışarıda bırakıldı. libkiwix'in manifesti `allowBackup=true`
getiriyordu: Kale `false` kalır ve CI birleşik manifesti denetler. PC'de kilitli kiwix-tools'un
`kiwix-serve`'ü 127.0.0.1'de, rastgele portta çalışır; `/suggest`, `/search?format=xml`, `/raw/...`
kullanılır. ZIM ya da GGUF indirilirken PC'de onu çalıştıran program (kiwix-tools / llama.cpp) aynı
indirmeye eklenir. Testler: emülatörde gerçek bir (İngilizce, 14 MB) Wikipedia ZIM'i libzim ile açılır;
PC'de gerçek kiwix-serve (yerelde Debian 3.5.0, CI'da kilitli Windows 3.8.1).

## D27 — Belgeler: BM25 + 5 harflik Türkçe kök
Yerel embedding modeli ek indirme ve bellek gerektirdiği için ilk sürümde BM25 seçildi (görev metni
"bulunamazsa BM25" diyor). Türkçe eklemeli bir dil olduğundan terimler normalleştirilip ilk 5 harfe
kesilir ("Ankara'nın", "Ankara'da" → "ankar"); bu Türkçe bilgi erişiminde bilinen, sözlüksüz bir
yöntemdir. Parçalar paragraf/cümle sınırında en fazla 700 karakter. Dizin uygulamanın özel dosyalarında
JSON olarak tutulur (şifreleme v0.4'te). PDF metni Android'de PdfBox-Android 2.0.27.0 (BouncyCastle
dışarıda: şifreli PDF'ler açık bir mesajla reddedilir), PC'de Apache PDFBox 3.0.8 ile çıkarılır.

## D28 — Aksiyon araçları ve güvenlik
`alarm_kur`, `zamanlayici`, `uygulama_ac` hızlı komutlarla aynı platform yürütücüsünü kullanır; yan etkili
araç oldukları için soruda güvenilmeyen veri (Wikipedia, belge, web) okunduktan sonra reddedilir. Bir
makaleye gömülü "alarm kur" talimatının cihaza ulaşamadığı bir testle doğrulanır.
