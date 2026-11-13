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

## D29 — Piper için kendi Türkçe fonem dönüştürücümüz
sherpa-onnx 2.0 espeak-ng'yi çıkardı; Piper sesi espeak-ng'nin IPA'sıyla eğitildi. Türkçe yazım neredeyse
fonetik olduğundan espeak-ng'nin Türkçe kuralları (ünlü varyantları, ğ, kalın l, ɾ, ince g, uzun ünsüzler,
vurgu ve istisnaları) Kotlin'de yeniden yazıldı (`core/tts/TurkishPhonemizer.kt`), sayılar yazıya
çevrilir. 30 cümlede espeak'e fonem uzaklığı %6; sentezlenen ses Whisper'a dinletildiğinde espeak'inki
kadar (ölçümde biraz daha iyi) anlaşıldı. Fonemler `GenerationConfig.phonemeCodepoints` ile verilir.

## D30 — Şifreleme: SQLCipher yerine AES-GCM ile mühürlenmiş dosyalar
Verimiz küçük JSON belgeleri (hafıza ≤ 500 kayıt, belge dizini, kitaplık, ayarlar, ağ kaydı). Room +
SQLCipher ~7 MB yerel kütüphane ve KSP getirir; aynı korumayı daha az parçayla sağlamak için her dosya
AES-256-GCM ile mühürlenir (doğrulamalı şifreleme: kurcalanan kayıt atılır). Anahtar telefonda Android
Keystore içinde üretilir ve dışarı çıkamaz; PC'de rastgele anahtar Windows DPAPI (CurrentUser) ile
mühürlü durur. Eski düz dosyalar okunur ve ilk yazışta şifrelenir.

## D31 — PC güvenlik duvarı: engel kuralları + süreli kapı
Windows'ta giden trafik varsayılan olarak serbest, engel kuralı izin kuralından önce gelir. Bu yüzden
Jarvis.exe, jarvis-kapi.exe ve bin altındaki her program için kalıcı ENGEL kuralı kurulur (tek UAC).
İndirmede onay = UAC: yükseltilmiş küçük bir yardımcı yalnızca kapi kuralını kapatır, oturum bitince ya
da en geç 10 dakikada geri açar; kapanmadan önce oturumda açılan yeni programları da engeller.
`jarvis-kapi.exe`, jpackage başlatıcısının kopyası + kendi `.cfg` dosyası (başka ana sınıf) olarak
uygulama görüntüsüne eklenir; yalnızca kilit dosyasındaki kimlikleri indirir, Jarvis özeti yeniden hesaplar.
Kuralları New-NetFirewallRule ile kuruyoruz (netsh çıktısı Windows diline göre değişiyor).

## D32 — halilozat/jarvis: internet gerektirenler yok
Örnek proje bulut LLM, RSS haberleri, piyasa/hava API'leri, YouTube, ElevenLabs ve Home Assistant
kullanıyor. Bunlar bölüm 1'in "varsayılan tamamen offline" ilkesiyle çelişir. Yerel karşılığı
olanların hepsi eklendi (el efektleri, yüz kilidi, kahve falı, ev simülasyonu, uyku/alkış, kitaplık,
Mark 2 — gün doğumu/batımı internetsiz hesaplanır, hava/piyasa "yok" yazar). İnternetli özellikler
istenirse ancak Kapı + her istekte onay ile ve izin listesi genişletilerek yapılabilir.

## D33 — Asistanın adı "Rick"
Kullanıcı, Jarvis adının kaldırılıp başka bir isim verilmesini istedi ve "Rick" adını seçti. Kullanıcının
gördüğü ve söylediği her yer değişti: uygulama adları, HUD (R.I.C.K), prompt'lar, cümleler, uyandırma sözü
ve Release dosya adları (`rick.apk`, `rick-kapi.apk`, `Rick-Setup-x.y.z.msi`, `rick-kapi.exe`). Ad tek yerde,
`core/AssistantName.kt` içinde tutulur. Paket adı (`io.github.ahmetdurmus1218.jarvis`), Android
applicationId, modül ve sınıf adları **değişmedi**: değişseydi güncelleme eski uygulamanın üstüne
kurulamaz ve modeller, hafıza ve ayarlar silinirdi. "Jarvis/Cervis" demeye devam eden de anlaşılır. PC'de
`%LOCALAPPDATA%\Jarvis` ilk açılışta `Rick` olarak taşınır, eski güvenlik duvarı kuralları yenileri
kurulurken silinir; MSI'ın upgrade UUID'si aynı kaldığı için yeni MSI eskisinin yerine geçer. Türkçe ekler
değişmez: Jarvis ve Rick'in son ünlüsü (i) ve son ünsüzü (sert) aynıdır ("Rick'e", "Rick'in", "Rick'ten").
Piper "ck"yi "k" okur. Sınır: Whisper bu tek heceli İngilizce adı Türkçe konuşmada çoğu zaman tanımıyor
(Piper'ın "Rick hazır" cümlesini "bir kazır" diye yazdı). Bu yüzden karşılama cümlesi "Merhaba efendim, ben
Rick. Her şey hazır." oldu (Whisper ile cihazda sınandı) ve komutlar adı söylemeden de çalışır; adla
seslenmek isteyenin sözü Whisper'ın yazdığı biçimlerle (rick, rik, …) eşleşir.

## D34 — İndirmeden önce cihazda ara
Güncellemeden ya da yeniden kurulumdan sonra modellerin tekrar indirilmemesi için yükleyici her
indirmeden önce aynı dosyayı cihazda arar. Eşleşme dosya adı (Ollama için `sha256-<özet>`) ve boyutla
bulunur, ama dosya **yalnızca SHA-256 özeti kilit listesiyle aynıysa** alınır; ağ isteği olmadığından onay
istenmez. PC'de bakılan yerler: eski `Jarvis` klasörü, İndirilenler, Masaüstü, Belgeler, Hugging Face /
LM Studio / Ollama klasörleri ve kullanıcının "Klasörde bul" ile eklediği klasörler (hatırlanır). Aynı
diskte dosya sabit bağlantıyla (hard link) alınır, yer kaplamaz; başka diskte kopyalanır ve kopya bir kez
daha doğrulanır. Telefonda depolama izni istenmez: kullanıcı bir klasörü (ör. Download) sistem seçicisiyle
bir kez seçer (SAF, kalıcı okuma izni), dosyalar oradan kopyalanırken doğrulanır.

## D35 — Modeli beklemeden cihaz komutları
Kullanıcı "her istediğimizi hızlıca yapabilmeli" dedi. Yaygın cihaz istekleri dil modeline gitmeden,
hazır komut olarak anında yapılır: fener, ses aç/kıs/kapat/en yüksek, telefonu titreşime alma, müzik
çal/durdur/sonraki/önceki, Wi-Fi / Bluetooth / mobil veri / konum / uçak modu / parlaklık / pil tasarrufu /
rahatsız etmeyin ayar panelleri, pil durumu, numara ya da rehberdeki kişiyi arama, mesaj yazma, yol
tarifi. Android uygulamaların Wi-Fi'yi kendisi açıp kapatmasına izin vermez; bu yüzden sistem paneli açılır
(tek dokunuş). Arama ve SMS **yalnızca hazırlanır**: arama ekranı / mesaj ekranı numara ve metinle açılır,
aramayı ya da gönderimi kullanıcı yapar. Rick'te CALL_PHONE ve SEND_SMS izni yoktur; bölüm 3.3 / 5.2'deki
"riskli işlemlerde onay" bu sistem ekranlarıyla sağlanır. Kişi adından numara bulmak için READ_CONTACTS
eklendi, yalnızca kullanıcı bir kişiyi aramak/mesaj atmak istediğinde sorulur; rehber cihazdan çıkmaz.
"X ara" hem "aramak" hem "telefon etmek" demek olduğundan, yer ya da kaynak bildiren cümleler
("Wikipedia'da kedi ara", "Ankara'da otel ara", "belgelerimde vergi ara") telefon araması sayılmaz. PC'de
ses ve medya tuşları Windows Script Host ile, ayar sayfaları `ms-settings:` ile açılır; programlar Başlat
menüsü kısayollarından bulunur; alarm ve zamanlayıcı Rick açıkken uygulama içinde çalar (önceden PC'de
desteklenmiyordu). Dil modelinin araçları (alarm, zamanlayıcı, uygulama açma) artık PC'de de var.

## D36 — "Deneme modu" yok
Kullanıcı ürünün deneme sürümü gibi görünmemesini istedi. Dil modeli kurulu değilken uygulama "sahte
model / deneme modu" demez: hazır komutlar, cihaz komutları ve kaynaklı Wikipedia / belge cevapları
çalışır, serbest soruda dürüstçe dil modelinin gerektiği söylenir ve sohbet ekranında tek dokunuşla
"Rick'in beynini kur" düğmesi çıkar. Sihirbazdaki "Şimdilik atla (deneme modu)" → "Sonra kur". Bütün
aşamalar tamamlandığı için sürüm 1.0.0 oldu.

## D37 — Kendini geliştirme: model ağırlıkları değil, Rick'e verilenler gelişir
Kullanıcı "kendi kendine araştırarak, veri verdikçe gelişen" bir sistem istedi. Araştırmada (Kasım 2026)
yerel ince ayarın (LoRA) llama.cpp'ye henüz birleşmediği, yalnızca CPU ve küçük (≤1B) modellerle
denendiği, Vulkan eğitiminin yalnızca bir çatalda olduğu, telefonda eğitimin araştırma prototiplerinde
kaldığı görüldü; ayrıca ince ayar bilgiyi güvenilir öğretmez, uydurmayı artırabilir. Bu yüzden gelişme
bilgi tarafında, tamamen cihazda yapılır:
- **Düzeltmelerden öğrenme:** "Hayır, doğrusu …" / "yanlış, …" ya da cevabın altındaki 👎'den sonraki
  mesaj, önceki sorunun düzeltmesi olarak saklanır (yeni soru diye modele gitmez). 👍 beğenilen cevabı
  örnek olarak saklar. Benzer sorularda (ortak kelime ≥2) bunlar sistem prompt'unun değişen kısmına
  "veri olarak" girer; düzeltme metni doğrulama kanıtına eklenir (kullanıcının kendi sözü). Dil modeli
  yokken aynı soru düzeltmeden cevaplanır.
- **Boşta araştırma defteri:** Rick 3 dakika kullanılmayınca (dinlemiyor, düşünmüyor, konuşmuyorken),
  dakikada en fazla bir kez, kullanıcının ilgi alanlarından (son soruları, kitaplığındaki kitaplar,
  hafızasındaki özel adlar) sıradaki konuyu internetsiz Wikipedia'da arar ve makalenin başını **birebir**
  başlığıyla not eder; özet ya da yorum yazmaz. Notlar benzer sorularda prompt'a ve kanıta girer,
  günlük brifingde "Boştayken okuduklarım" olarak listelenir. Okunan konu tekrar okunmaz.
- Hepsi şifreli dosyada (en fazla 200 ders, 300 not), Hafıza ekranında görülür, tek tek ya da toptan
  silinir; Hafıza → "Kendini geliştirme" anahtarıyla kapatılır. İnternet kullanılmaz.

## D38 — Kütüphanede yoksa internette bakmayı teklif etmek (her aramada onay)
Kullanıcı Rick'in gerektiğinde "veri setlerine çıkabilmesini, ama benden izin isteyerek" istedi. Bölüm 3.3
web aramasını varsayılan kapalı ve ayarlardan açılır tanımlıyordu; kullanıcının yeni isteğiyle değişti:
- Bilgi sorusunun cevabı internetsiz Wikipedia'da ve belgelerde yoksa Rick dürüstçe söyler ve **teklif eder**:
  "İsterseniz internette, Wikipedia'da bakayım; yalnızca "…" araması gider." Kullanıcı "evet" demeden ya da
  düğmeye basmadan hiçbir şey olmaz; "evet"ten sonra yine **biyometrik/PIN (PC'de UAC) onayı** istenir.
  Bir onay = tek arama. Ayar: "Kütüphanede yoksa internette bakmayı teklif et" (açık); "Tam offline mod"
  açıkken ya da Kapı yokken teklif hiç çıkmaz. Eski `webSearch` anahtarı artık kullanılmıyor.
- Tek kaynak **tr.wikipedia.org'un arama API'si** (ücretsiz, anahtarsız). Dışarı giden tek şey soru
  kelimelerinden ayıklanmış arama metnidir ("Atatürk kimdir?" → "Atatürk"); ekranda onaydan önce gösterilir.
  Hafıza, sohbet, belge gönderilmez.
- İsteği yine yalnızca Kapı yapar (telefonda Rick Kapı, PC'de rick-kapi.exe + geçici güvenlik duvarı kapısı).
  Kapı yalnızca `OnlineWikipedia.searchUrl` ile üretilmiş adresi kabul eder (başka API eylemi, başka alan adı,
  http yok), yönlendirme izlemez, yalnızca JSON alır, en fazla 200 KB. Sertifika sabitleme: DigiCert,
  GlobalSign ve Let's Encrypt kökleri (test edildi).
- Gelen metin Wikipedia makalesi gibi **veri**dir: enjeksiyon taranır, sonrasında yan etkili araç çalışmaz,
  sayı/alıntı sayfada birebir geçmeli; kaynak çipi "web". Dil modeli yoksa en iyi sayfanın ilk cümleleri
  aynen okunur. Her arama Ağ sekmesine yazılır.
- Rick 1.1 ile gelen Kapı 1.1 gerekir; eski Kapı bu isteği tanımaz (30 sn sonra "Kapı'yı güncelleyin").

## D39 — Hız ve konuşma: önce yükle, kısa tut, sor sonra yap
Kullanıcı cevapların çok geç geldiğini, sesin ancak "Sesi dene"den sonra çıktığını, konuşmanın robot gibi
olduğunu söyledi. Nedenler ve kararlar:
- **Model ilk soruda yükleniyordu** (telefonda Gemma 4, PC'de llama-server onlarca saniye). Artık uygulama
  açılınca arka planda yüklenir (`LlmEngine.warmUp`); Whisper da açılışta hazırlanır.
- **Telefonda GPU önceliği:** önce GPU (görme parçasıyla, sığmazsa yalnız metin), sonra CPU (yalnız metin).
  Önceden GPU+görme olmazsa CPU+görme seçiliyordu; bu her cevabı yavaşlatıyordu. Görme CPU'da yalnızca
  kameraya soru sorulunca yüklenir.
- **Daha kısa istem:** geçmişte asistan cevapları yalnızca söylenen cümlelerle (kart/detay/kaynak yok), son 4
  tur; hafızadan en yeni 30 bilgi; sohbette "detay" ve "kaynaklar" istenmez. PC'de `cache_prompt` ile sabit
  baş kısım yeniden kullanılır.
- **İkinci model çağrısı kaldırıldı:** her cevaptan sonra hafıza için modele ayrıca sorulması bir sonraki soruyu
  bekletiyordu. Artık yalnızca kullanıcının kendinden söz ettiği cümlelerde ("benim", "adım", "severim"…) ve
  Rick konuşmayı bitirince yapılır; yeni soru gelince iptal edilir.
- **Ses:** Piper sesi yüksek öncelikli kendi iş parçacığında üretilir (dil modeli bütün çekirdekleri
  kullanırken ses saniyelerce gecikiyor ya da hiç çıkmıyordu); konuşurken ses odağı alınır (müzik kısılır);
  medya sesi tamamen kısıksa Rick onu %40'a açar ve bunu söyler.
- **İnsancıl konuşma:** sistem istemi sıcak, doğal, kısa cümleli bir dost üslubuna çevrildi ("efendim" her
  cümlede değil, duyguya karşılık, gerekirse kısa soru).
- **Sor, sonra yap:** sohbet arasında söylenen ihtiyaçlar ("yarın 7'de kalkmam lazım", "burası çok karanlık",
  "canım sıkılıyor", "annemi aramam lazım", "artık yatıyorum") modelsiz ve anında bir teklife dönüşür ("Saat
  07:00 için alarm kurayım mı?"). "Evet / olur / tamam" ya da düğme ile yapılır, "hayır / gerek yok" ile
  yapılmaz, başka bir cümle teklifi kapatır. Arama yine yalnızca hazırlanır, tuşa kullanıcı basar. PC'de
  yapamadığı şeyleri (fener, arama) teklif etmez. Dil modeli de aynı kuralla eğitildi: ihtiyaçta önce sorar,
  açık istekte doğrudan yapar.

## D40 — Başka uygulamaya aktarma ve "internetten bak" komutları
Kullanıcı "YouTube'dan şu müziği aç" ve "online ol, onay verdim, bak" dediğinde Rick'in bir şey yapamadığını
söyledi. Nedenler: "YouTube'dan X aç" uygulama açma komutuna düşüp "youtubedan X" adında uygulama arıyordu;
"onay verdim, bak" gibi cümleler "evet" sayılmıyor, teklifi kapatıp yeni bir soru gibi modele gidiyordu;
Kapı kurulu değilse Rick internete çıkamadığını hiç söylemiyordu. Kararlar:
- **Aktarma (`DeviceAction.OpenIn`):** "YouTube'dan / Spotify'dan / YouTube Müzik'ten X aç / çal", "X şarkısını
  çal", "X videosunu aç", "Google'da X ara" aramayı o uygulamaya düz bir Intent ile verir (telefonda YouTube
  araması, Spotify / YouTube Music'te "aradığını çal"; uygulama yoksa aynı arama tarayıcıda; PC'de varsayılan
  tarayıcı). İnternete çıkan o uygulamadır; Kale'nin INTERNET izni yine yoktur, PC'de Rick'in kendi trafiği
  güvenlik duvarında kapalı kalır. Yalnızca kullanıcının kendi komutuyla olur (araç/model çıktısı tetikleyemez)
  ve Ağ sekmesine "uygulamaya aktarıldı" diye yazılır. Ne açılacağı söylenmediyse Rick "Hangisini?" diye sorar.
- **"İnternetten bak" komutları:** "internette X ara", "X'i internetten bul", "online ol, bak" (son soru için)
  doğrudan onay adımına gider; parmak izi/PIN (PC'de UAC) yine her aramada istenir (bölüm 3.3: bir onay = tek
  istek). Anlık bilgiler (hava, döviz, maç, haber) ansiklopedide olmadığı için tarayıcıda Google araması açılır.
- **Daha geniş "evet":** "onay verdim bak", "izin veriyorum", "online ol hadi" gibi yalnızca cevap sözcüklerinden
  oluşan cümleler evet sayılır; "tamam, alarm kur" gibi yeni bir komut sayılmaz.
- **Nedeni söyle, yolunu bul:** Kapı kurulu değilse ya da cevap vermezse Rick nedenini söyler ve aynı aramayı
  tarayıcıda açmayı teklif eder. Kütüphanede olmayan bilgi için Kapı yoksa da tarayıcı teklif edilir. Tam
  offline modda teklif yapılmaz.

## D41 — Aramayı gerçekten yapmak, ses seçimi
Kullanıcı "birine telefon et" dediğinde Rick'in arayamadığını, telefondaki her şeyi (bankalar hariç) kontrol
etmesini, kadın/erkek ses seçimini ve daha doğal sesi istedi.
- **Arama:** "Ahmet'i ara", "Ahmet'e telefon et", "annemi ara hemen", "babamı arayabilir misin" artık aramayı
  başlatır (`CALL_PHONE`, ilk aramada bir kez sorulur). Kullanıcının kendi açık komutu onaydır, tıpkı telefonun
  kendi asistanında olduğu gibi; model ya da araç çıktısı arama başlatamaz. İzin verilmezse numara çevirici
  ekranında hazır olur. Önceden "Ahmet'e telefon et" yer adı sanılıyordu ("-e/-te") ve "annemi" rehberdeki
  "Anne" ile eşleşmiyordu: ad biçimleri (hal ekleri, "-m" iyelik) sırayla denenir (`DeviceCommands.findContact`).
  Sohbet arasında söylenen "annemi aramam lazım" yine önce sorar ("Annem aransın mı?").
- **Mesaj** yalnızca hazırlanır (`SEND_SMS` yok): yanlış duyulan bir metin geri alınamaz; gönder tuşuna kullanıcı basar.
- **Her şeyi kontrol:** Android, sıradan bir uygulamanın Wi-Fi/Bluetooth'u kendisi açıp kapatmasına izin vermez
  (Android 10+); Rick ilgili paneli açar. Başka uygulamaların ekranlarını kullanmak Erişilebilirlik izni ister ve
  o izin her ekranı (bankalar dahil) okuyabilir; kullanıcı onaylamadan eklenmedi.
- **Ses seçimi:** açık kaynakta internetsiz Türkçe *kadın* Piper sesi yok (tr_TR sesleri: dfki, fahrettin, fettah,
  hepsi erkek). Telefonların kendi internetsiz Türkçe sesleri (Google / Samsung) daha doğal ve kadın sesi içerir.
  Ayarlar → "Rick'in sesi": Kadın / Erkek düğmeleri, her sesi ▶ ile dinleme ve seçme, "Telefona ses ekle" (Türkçe
  ses paketi). Yalnızca internet gerektirmeyen sesler listelenir; seçim `Settings.voiceId`. Motor cinsiyeti
  adında söylemiyorsa (ör. Google "tr-tr-x-…") kullanıcı dinleyip seçer. PC'de şimdilik yalnızca Rick'in sesi.

## D42 — Canlı yüz, ekranda Rick, alkışla uyanma ve günün haberleri
Kullanıcı karşısında biri varmış gibi hareket eden bir yüz, ekranda duran ve konuşunca tepki veren bir simge,
alkışla uyanıp günün haberlerini söyleyen bir asistan istedi; WhatsApp'tan mesaj gönderme ve bankacılık gibi
şeylerin olmamasını açıkça söyledi.
- **Canlı yüz (`RickFace`):** tek Canvas, tek saat. Kendi hızında göz kırpar (bazen iki kez), beklerken
  etrafa bakar ve kullanıcıya döner, dinlerken göz bebekleri büyür ve kaşlar kalkar (mikrofon seviyesiyle
  halka), düşünürken yukarı bakar ve üç nokta döner, konuşurken ağız hece ritminde açılır, uykuda gözler
  kapanır ve "z"ler yükselir. Ayarlar → Görünüm'den çekirdeğe dönülebilir; "Hareketi azalt" bakınmayı durdurur.
- **Ekranda Rick (Android):** `SYSTEM_ALERT_WINDOW` ile her uygulamanın üstünde küçük yüz; ön plan servisi
  (`microphone|specialUse`), bildirimden kapatılır. Dokun: konuş; basılı tut: Rick'i aç; sürükle: taşı.
  Sohbet uygulama ölçeğinde tek denetleyicide (`AppGraph.sharedController`), pencere de yüz de aynı sohbeti
  gösterir. Varsayılan kapalıdır; açarken izin ekranı ve mikrofon izni istenir.
- **Alkışla uyanma:** sessiz geçen süreden sonra (en az 2 dk) yüz uyur; izin verildiyse mikrofon yalnızca
  ses yüksekliği sıçramalarına bakar (`ClapDetector`, kayıt yok). İki alkış ya da dokunuş: "Günaydın…
  Günün haberlerini okuyayım mı?" ve dinler.
- **Günün haberleri:** tek ve sabit adres: BBC Türkçe RSS (`feeds.bbci.co.uk/turkce/rss.xml`). Kullanıcıdan
  hiçbir metin gitmez; yine her okumada parmak izi/PIN (PC'de UAC) ister, Ağ sekmesine yazılır, başlıklar
  oldukları gibi okunur (model yorum katmaz). Kapı ve rick-kapi yalnızca bu adresi ve Wikipedia aramasını
  kabul eder (`WebSources`); XML dışı yanıt, yönlendirme ve 600 KB üstü reddedilir. CDN'in sertifika
  otoritesi değişebildiği için yaygın kök anahtarlar sabitlendi (DigiCert, GlobalSign, Let's Encrypt,
  Sectigo, Amazon, Google); tanınmayan otorite yine kapalı kalır.
- **Kısıtlar:** Rick başka uygulamaların içinde işlem yapmaz: erişilebilirlik izni yok, WhatsApp/SMS
  göndermez (yalnızca hazırlar), bankacılık ve ödeme yok.
- **PC:** aynı yüz ana pencerede; "Ekranda Rick" her zaman üstte, kenarsız küçük bir pencere (tıkla: konuş,
  çift tıkla: Rick).

## D43 — Hava kontrolü: elle havada tıklama, kaydırma, döndürme
Kullanıcı kamera onu gösterirken elleriyle havada tıklamak, kaydırmak, döndürmek ve her komutu vermek istedi.
- **İmleç:** işaret parmağının ucu (kamera görüntüsüyle aynı eşlemeyle, yani ekranda gördüğü parmağın
  üstünde), titremeye karşı yumuşatılır. Çimdikte imleç başparmakla işaret parmağının buluştuğu yerdir.
- **Tıklama:** kısa çimdik (dokundurup ayırmak) ya da ☝️ parmağı bir düğmenin üstünde 0,9 sn tutmak (halka
  dolar). Düğmeler `airTarget` ile kendini kaydeder; havadaki tıklama imlecin altındaki en küçük hedefi
  çalıştırır, dokunmayla aynı iş olur.
- **Kaydırma:** çimdikleyip yukarı / aşağı çekmek (içerik eli izler); **döndürme:** çimdikteyken bileği
  çevirmek (gezegen döner); **savurma:** açık eli hızla sola / sağa (sonraki / önceki kart, Rick okur),
  yukarı / aşağı (sayfa kayar).
- **Komutlar:** "🎤 Konuş" (her komut sesle), "🤫 Sus" ve sık kullanılanlar (Haberler, Saat, Brifing, Müzik,
  Fener, Ses +) tek tıkla; Rick bir şey önerdiğinde Evet / Hayır havada tıklanır. Cevap kart kart gösterilir.
- Mantık `core` içinde (`AirPointer`), testli; kamera görüntüsü telefonda işlenir, kaydedilmez. Kamerasız
  demo eller hiçbir şeye tıklamaz. Efekt panelinden kapatılabilir.
- **Her yerde el ile yönetim (kullanıcının ikinci açıklaması):** el hareketleri yalnızca efekt ekranında değil.
  ☰ → "El ile yönet": köşede küçük kamera, uygulamanın tamamında imleç; 🤏 kısa çimdik imlecin altına gerçek
  bir dokunuş verir (yalnızca Rick'in kendi penceresine, `injectTap`), 🤏 sürükleme ve ✋ savurma sayfayı
  kaydırır. Müzik çalarken ✋ sola/sağa sonraki/önceki şarkı, ✋ bekletmek durdur/devam, 🤏 çevirmek ses;
  👍 bekletmek dinle, ✊ bekletmek sus (`HandNavigator`, `JarvisController.onHand`).
- **Başka uygulama öndeyken:** "Ekranda Rick" isteğe bağlı olarak kamerayı da tutar (ön plan servisi
  `camera` türü, açarken izin istenir); müzik ve ses komutları çalışır, yüz eli izler. Başka uygulamalara
  dokunma / tıklama yoktur (erişilebilirlik izni yok, bankacılık ve mesajlaşma dışarıda). Rick'in penceresi
  öndeyken servis kamerayı bırakır.
- **Asla takılı kalmamak:** her yerde aynı çıkışlar var. ✌️ bekletmek / "geri" / "kapat" / telefonun geri tuşu:
  açık olan en üstteki şeyi kapatır (onay ekranı reddedilir, kamera, ev, kitaplık, yapılacaklar, uyku; konuşuyor
  ya da dinliyorsa durur; sonra Sohbet sekmesi). 👋 el sallamak / ✋✋ iki açık el / "ana ekran": her şeyi kapatır,
  konuşmayı, dinlemeyi ve süren cevabı durdurur. "aşağı/yukarı kaydır" sesle sayfayı çevirir; bu komutlar
  ekrandaki cevabın üstüne yeni bir sohbet açmaz. Efekt ekranından da 👋 ile çıkılır. Ayrıca denetleyici kendini
  korur: 4 dakikadan uzun süren bir "düşünme" ve 1 dakikadan uzun açık kalan mikrofon kendiliğinden kapanır ve
  nedeni söylenir.


## D44 — Canlı simge, tam ekran el ile yönetim, kontrol ile efektin ayrılması (v1.3.1)

- **Yüz → canlı simge:** 1.3'teki yuvarlak "emoji" yüz kullanıcıya çocuksu geldi. Önce gerçek oranlı bir
  hologram insan başı denendi; kullanıcı onu da beğenmedi ("çok kötü") ve yüz yerine tepkilere göre insan gibi
  hareket eden, Claude simgesine benzer bir kıvılcım istedi. Anthropic'in logosu marka olduğu için
  kopyalanmadı; yerine kendi çizdiğimiz sıcak renkli, 12 ışınlı, el yapımı gibi düzensiz bir kıvılcım geldi.
  Beden dili: beklerken nefes alır, salınır, etrafa doğru yaslanır, arada hızlı bir büzülmeyle "göz
  kırpar"; dinlerken öne eğilir, büyür, ışınları sesle uzanır; düşünürken yavaşça döner, ışınları dalgalanır;
  konuşurken ışınlar hecelerle atar ve başını sallar gibi iner kalkar; uyurken küçülür, söner, yassılaşır.
  Çizim önce aynı motorla (Skia, Python) denenip bakıldı.
- **El ile yönet:** kamera sağ üstte 84×112 dp'lik bir kutudaydı. Artık varsayılan tam ekran: kamera Rick'in
  arkasını kaplar, sayfalar yarı saydam olur, görüntü 1,3 kat büyütülür ve imleç kullanıcının ekranda gördüğü
  parmak ucunun üstüne denk gelir (aynı dönüşüm: `toFullScreen`). Elin iskeleti de çizilir. "▢ küçük
  pencere" ile eskisine dönülür. Görüntünün saydam sayfaların arkasında durabilmesi ve büyütülebilmesi
  için Android önizlemesi TextureView (COMPATIBLE) kipine alındı.
- **Kamera ekranı:** imleç ile efektler aynı anda açıktı; kullanıcı bir düğmeye 🤏 ile tıklamaya
  çalışırken her seferinde gezegen, ateş ya da patlama çıkıyordu. Artık iki ayrı kip var: **KONTROL**
  (yalnız imleç; efekt ve basılı tutma eylemleri kapalı) ve **EFEKTLER** (imleç kapalı). Üstteki
  düğmeyle geçilir, seçim saklanır. İmleç yakındaki düğmeye "mıknatısla" yapışır (36 dp) ve ortasına
  atlar; kullanıcı tıklamanın neye gideceğini görür.

## D45 — Arka planda diğer uygulamaları elle yönetme ve güvenlik sıkılaştırması (v1.4)

- **Diğer uygulamalarda el kontrolü:** Kullanıcı, müzik YouTube'da çalarken Rick arka plandayken de
  uygulamayı elle yönetmek istedi (banka ve WhatsApp hariç). Medya tuşları (dur/devam, sonraki, ses) zaten
  her uygulamada çalışıyordu. Dokunma, kaydırma ve geri için Android Erişilebilirlik servisi
  (`RickAccessibility`) eklendi. Önceki "erişilebilirlik yok" kararı, şu sınırlarla değiştirildi:
  - Yalnız hareket (dispatchGesture, geri, ana ekran). `canRetrieveWindowContent=false`: ekrandaki yazı
    okunmaz. Alınan tek olay "öne bir pencere geldi"; ondan yalnız paket adı tutulur.
  - **İzin listesi:** Kullanıcı uygulamaları kendisi seçer (varsayılan: YouTube, YouTube Music, Spotify,
    Chrome, Firefox, Netflix). Banka, ödeme, cüzdan, mesajlaşma (WhatsApp dahil), e-posta, şifre, kripto ve
    ayar uygulamaları (`HandApps.neverAllowed`) listede hiç görünmez ve seçilmiş olsalar bile çalışmaz.
    "Yasaklı listesi" yerine izin listesi seçildi, çünkü bankaları adlarından güvenilir biçimde tanımak
    mümkün değil.
  - Telefon kilitliyken çalışmaz. Erişilebilirlik kapalıysa hiçbir şey yapmaz.
  - İmleç tam ekran, dokunulamayan bir katmanda; Android 12+ dokunmaları geçirsin diye opaklığı %80.
  - Rick'te INTERNET izni olmadığı için hiçbir şey dışarı gitmez.
- **Arama güvenliği (`CallSafety`):** Rehberdeki kişiler doğrudan aranır. Rehberde olmayan numara için
  yalnız çevirici açılır, aramayı kullanıcı başlatır. 0900'lü ve 3–6 haneli kısa servis numaraları
  reddedilir. Acil numaralar (112 vb.) çeviriciyle açılır.
- **Kilit ekranı (`LockPolicy`):** Telefon kilitliyken yalnız fener, ses, medya ve pil komutları çalışır.
  Arama, mesaj hazırlama, yol tarifi, uygulama açma ve ayar kilit açılınca yapılır.
- **Dokunma tuzağı:** Ana pencere `filterTouchesWhenObscured`. Başka bir uygulamanın üstüne çizdiği
  görünmez bir katmanın içinden gelen dokunmalar yok sayılır. Rick'in kendi balonu aynı uygulama olduğu
  için etkilenmez.
- **Arka plan kamerası:** 10 dakika el görülmezse kapanır; balona dokununca ya da uyanınca açılır.
  Bildirim "kamera açık" diye açıkça yazar.
- **İmza anahtarı:** Secret tanımlı değilse sürümler repodaki test anahtarıyla imzalanıyor. Bu anahtar
  herkese açık olduğu için başkası sahte bir "güncelleme" imzalayabilir. Çözüm: kullanıcıya özel anahtar
  (README'deki dört secret). Bunu yalnız kullanıcı GitHub ayarlarından ekleyebilir; anahtar değişince
  uygulama bir kez kaldırılıp yeniden kurulur.
- **Görünüm:** Kullanıcı yüzü beğenmedi ve önce resim görmek istedi. Beş seçenek resimle gösterildi;
  kullanıcı "hepsini yap" dedi. Beşi de Ayarlar → Görünüm'de canlı önizlemeyle seçilebilir: kıvılcım,
  gözlü kıvılcım, cam küre, ses halkası, parçacık küre. Varsayılan kıvılcım.

## D46 — Gerçek kullanımdan gelen düzeltmeler (v1.4.1)

- **Kendiliğinden tıklama ve fener:** İşaret parmağı bir düğmenin üstünde 0,9 sn durunca tıklama sayılıyordu.
  Kamera ekranının altındaki "🔦 Fener" düğmesi böyle tekrar tekrar tıklandı ve fener sürekli açıldı. Artık
  üstünde durmak hiçbir şeyi tıklamıyor (`AirPointer.dwellClick` varsayılan kapalı). Tıklama yalnız bilinçli bir
  🤏: parmak uçları en az 150 ms değmeli, 700 ms içinde açılmalı, el kaymamalı; iki tıklama arasında en az
  700 ms olmalı. Kamera ekranındaki telefona iş yaptıran kısayol düğmeleri (fener, ses, müzik, haberler…)
  kaldırıldı; komutlar sesle verilir, ekranda yalnız 🎤 Konuş ve 🤫 Sus kaldı.
- **Ekranı kaplayan kontroller:** Büyük KONTROL / EFEKTLER sekmeleri yerine üst çubukta küçük bir "✨" düğmesi.
  ⚙ efekt ayarları yalnız efekt açıkken, ☕ Fal yalnız efekt kipinde görünür.
- **Alkış:** Alkış yalnız Rick uyurken (5 dk sessizlikten sonra) dinleniyordu, kullanıcı da uyanıkken denedi.
  Artık Rick boştayken her zaman dinleniyor: uyanıkken iki alkış dinlemeyi başlatır, uyurken uyandırır.
  Algılama telefon mikrofonları için biraz gevşetildi (yükseliş ×2,5, eşik 6). Daha önce kaydedilmiş bir eşik
  varsa o kullanılır; Ayarlar'dan yeniden ayarlanabilir.
- **YouTube'da şarkı:** "YouTube'dan X aç" yalnız arama sayfasını açıyordu. Artık YouTube bu telefonda "aranan
  içeriği oynat" isteğini (MEDIA_PLAY_FROM_SEARCH) kabul ediyorsa doğrudan o kullanılıyor. Kabul etmiyorsa arama
  sayfası açılıyor ve Rick bunu açıkça söylüyor. Ekranı okuyup sonucu kendisi seçmek, "ekranı okumaz" sözünü
  bozacağı için yapılmadı.

## D47 — Büyük Wikipedia indirmesinin yarıda kalması (v1.4.2)

- Kullanıcı: "Wikipedia bir türlü inmiyor". Türkçe Wikipedia ZIM'i 3,7 GB.
- Önce yönlendirmeden şüphelenildi. CI'da ölçüldü: `mirror.download.kiwix.org` dosyayı doğrudan veriyor (200,
  yönlendirme yok). `download.kiwix.org` ise `lb.download.kiwix.org`'a yönlendiriyor; bu da izin listesinde
  (`*.download.kiwix.org`). Sertifika sabitlemesi de gerçek zincirle zaten test ediliyor. Sorun bunlar değil.
- Gerçek nedenler:
  1. Kapı indirirken işlemciyi ve Wi-Fi'yi uyanık tutmuyordu. Ekran kapanınca bağlantı 60 sn okuma sınırına
     takılıp kopuyordu.
  2. Rick kopan indirmeyi en çok 4 kez (3, 6, 9 sn arayla) deniyor, sonra vazgeçiyordu. 3,7 GB'ta bu kadar kopma
     olağan.
  3. Uzun indirme sırasında Rick'in kendi süreci arka planda dondurulabiliyor ya da kapatılabiliyordu; Kapı'nın
     "bitti" mesajı ona ulaşmayabiliyordu.
- Çözüm:
  - Kapı aktarım boyunca kısmi uyanıklık kilidi (en çok 6 saat) ve Wi-Fi kilidi tutar (`WAKE_LOCK`).
  - Rick, indirme ilerledikçe kaldığı yerden (Range) sınırsız sayılabilecek kadar (200 kez) devam eder. Arka
    arkaya 4 denemede tek bayt bile ilerlemezse durur.
  - Rick indirme sürerken ön plan bildirimi gösteren küçük bir servis (`DownloadKeeper`, dataSync) çalıştırır.
    İndirmeyi o yapmaz; Rick'te hâlâ INTERNET izni yok.

## D48 — Eller serbest dinleme ve el kontrolü ekranı (v1.5.0)

- Kullanıcı: mikrofona basmak zorunda kalmak istemiyor ("Rick" deyince dinlesin), alkış algılanmıyor, el kontrolü
  ekranı karışık. Gönderdiği ekran görüntülerinde başlık harf harf alt alta dökülmüş, efekt paneli diğer
  yazıların altında kalmıştı (335 dp genişlik, büyük yazı).
- Teşhis (`docs/RICK2-INCELEME.md`): sistem bas-konuş olarak yazılmıştı; alkış yalnızca uyku ekranında ve yüzen
  Rick'te dinleniyordu. Ekrandaki "61 FPS" kamera değil, ekran yenileme sayısıydı; el takibinin hızını göstermiyordu.
- Karar: ayrı bir uyanma-sözü modeli indirilmedi. Eldeki parçalarla:
  - `StandbyEar` (core): sürekli mikrofon akışından yalnızca ses yüksekliğine bakarak konuşma parçası keser.
    Sessizlikte hiçbir tanıyıcı çalışmaz. Bitmeyen ses (televizyon) yavaşça arka plan sayılır.
  - Parça Whisper'a verilir; `WakeCall` içinde Rick'in adı yoksa parça atılır (gösterilmez, saklanmaz).
  - Alkış aynı akıştan `ClapDetector` ile yakalanır; tanıyıcıya gitmez.
- Cevaptan sonra adsız dinleme varsayılan değil: odadaki konuşma ya da televizyon Rick'i sonsuz döngüye sokardı.
  Yalnızca Rick'in sorduğu soruda (teklif) ve "Sohbet modu" açıkken adsız dinler.
- Mikrofon tek akış: uyku ekranı ve alkış kalibrasyonu `MicUse.take()` ile alır, bekleme anında çekilir. Yüzen
  Rick artık kendi alkış dinleyicisini açmıyor, ortak denetleyiciyi kullanıyor (aynı mikrofonu iki yer açmıyor).
- Bekleme yalnızca Rick ekrandayken ya da kullanıcı yüzen Rick'i açtıysa çalışır. İzin kendiliğinden istenmez.
- Sesle söz kesme bu sürümde yok: Rick'in kendi sesi mikrofona geri girer; yankı engelleme telefona göre değişir
  ve gerçek cihazda denenmeden eklenmeyecek.
- El kontrolü ekranı: düğmeler tek satır, başlık ve durum ayrı satırlarda ve tek satıra sabit; efekt paneli
  ekranın geri kalanını tek başına kaplar; ipucu yalnızca el görünmezken; alt düğmeler tek satır.
- Emülatör testi 1.5.0 ile yavaşladı ve "günün haberleri" adımında takıldı. İlk tahmin (mikrofonun açılış sesi
  konuşma sayılıp tanıyıcıyı boşuna çalıştırıyor) ölçümle **doğrulanmadı**: beklerken mikrofon iş parçacığı
  işlemcinin %0–0,5'ini kullanıyor, tanıyıcı bekleme sırasında hiç çalışmadı; emülatörü asıl meşgul eden Google
  servisleriydi. Yine de şu önlemler kaldı, çünkü gerçek telefonda gürültülü odada işe yarar: mikrofon açıldıktan
  sonraki ilk 0,4 sn yok sayılır; hiç duraksamadan sınıra (12 sn) varan ses tanıyıcıya verilmez ve gerçek bir
  sessizliğe kadar yenisi başlamaz; adı geçmeyen her konuşmadan sonra bekleme uzar, kullanıcı bir şey yapınca
  sıfırlanır; mikrofon veri vermezse okuma döngüsü işlemciyi döndürmez; tanıyıcının çalışma süresi günlüğe
  yazılır (söylenen şey asla). Bekleme, Rick sustuktan 1,5 sn sonra başlar: cevabın kısa duraklarında mikrofon
  açılıp kapanmaz.
- Haber adımının gerçek nedeni ekran görüntüsünden bulundu ve sürekli dinlemeyle ilgisi yoktu: cevabın altındaki
  kaynak etiketleri (`SourceChips`) yan yana diziliyordu; uzun başlıklarda sonuncular bir harf genişliğinde,
  yüzlerce satır yüksekliğinde bir sütuna dönüşüyor, kartlar ekranın çok üstünde kalıyordu. Bu hata eskiden beri
  vardı; o günün uzun haber başlıklarıyla ortaya çıktı. Etiketler artık alt alta ve tek satır (testi var).
  Ders: emülatör ekran görüntüleri artifact kotasına takılsa bile küçük resim olarak günlüğe basılıyor.
- Sınırlar (gerçek cihazda ölçülecek): Whisper'ın "Rick" adını ne kadar doğru yazdığı, gürültülü odada pil
  tüketimi, bir parçanın çözülmesi sırasında (birkaç saniye) mikrofonun kapalı olması.

## D49 — Yeni ana ekran: cam küre ve çizgi simgeler (v1.5.0)

- Kullanıcı arayüzü ve simgeleri beğenmedi ("olmamış"), başka bir asistanın gösterdiği bilim kurgu görsellerine
  benzer bir görünüm istedi. Kural gereği önce çizim gösterildi: A cam küre, B HUD çekirdek, C dalga halkası;
  ardından robot yüz. Kullanıcı: "A olsun, hepsini ekle; robot göz ağız burun da eklenebilir."
- Dört yeni görünüm `RickFace` içinde, mevcut `LookState` ile (aynı ruh hâli animasyonları): `glass` (varsayılan),
  `hud`, `wave`, `robot`. Bulanıklık filtresi kullanılmıyor (ortak Compose çizimi): parıltı, üst üste yarı saydam
  çizgiler ve radyal geçişlerle veriliyor; parçacıklar sabit tohumla üretilmiş 240 nokta.
- Eski ayar dosyalarında `look = "spark"` kayıtlıydı; `design` alanıyla herkes bir kez yeni varsayılana (`glass`)
  taşınır, eski görünümler seçilebilir kalır.
- Emoji simgeler (💬 📦 🧠 🛡 ⚙ 📷 ☰ 🎤) telefondan telefona farklı ve "oyuncak" görünüyordu; `RickIcons.kt` içinde 24
  birimlik ızgarada çizilen çizgi simgeler geldi. Düğmeler artık adlarıyla bulunur (içerik açıklaması: "Menü",
  "Kamera", "Mikrofon", "Dinlemeyi bitir", "Geçmiş"); testler buna göre güncellendi.
- Sürekli dinleme düğmesi ışık + yazı + topuzlu bir anahtar oldu; telefonda "GEÇMİŞ" yazısı yer açmak için simge.
  Kürenin altında ses çubukları: konuşurken heceler, dinlerken mikrofon, beklerken hafif kıpırtı; cevap varken
  yalnızca ses olduğunda görünür (cevaba yer kalsın).

