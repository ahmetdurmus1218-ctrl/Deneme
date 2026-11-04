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

