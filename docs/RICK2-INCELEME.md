# Rick 2.0 — etkileşim incelemesi ve teşhis (v1.4.2 kodu üzerinden)

Tarih: 2026-11-12. Bu rapor **kod okunarak** hazırlandı. Gerçek telefonda ölçüm yapılmadı; "kesin" denen
her madde kodda doğrudan görülen davranıştır, "olası" denenler gerçek cihazda doğrulanmalıdır.
Bu aşamada kod değiştirilmedi.

> **Düzeltme (aynı gün, ekran görüntüleri geldikten sonra):** Ekrandaki "FPS" sayısı kameranın değil, ekranın
> yenileme sayısıydı; el takibinin hızını göstermiyordu (v1.5.0'da gerçek kamera karesi sayılıyor). Ekran
> görüntüsünde "0 el" yazmasının nedeni kadrajda el olmamasıydı. Başlığın harf harf dökülmesi ve efekt panelinin
> diğer yazıların altında kalması gerçek hatalardı; v1.5.0'da düzeltildi (bkz. `docs/DECISIONS.md` D48).

## 1. Kısa sonuç

| Şikâyet | Teşhis | Kesinlik |
|---|---|---|
| Konuşmak için mikrofona basmak gerekiyor | Tasarım böyle: sistem "bas-konuş". Sürekli dinleme ve "Rick" diye seslenme hiç yazılmadı | Kesin |
| Alkış algılanmıyor | Alkış yalnızca iki yerde dinleniyor: uyku ekranında ve "yüzen Rick" açıkken. Uygulama açık ve uyanıkken alkışı dinleyen kod yok | Kesin |
| Uykudan alkışla uyanmıyor | Varsayılan ayar "alkış + söz": alkıştan sonra ayrıca "uyan" demek gerekiyor; Whisper kurulu değilse ya da kısa kelimeyi yanlış duyarsa uyanmıyor | Kesin (ayar), olası (yanlış duyma) |
| Rick konuşurken araya girilemiyor | Rick konuşurken mikrofon tamamen kapalı. Yankı engelleme yok | Kesin |
| "Bir şey duyamadım" diyor | 8 saniye içinde konuşma başlamazsa ya da Whisper boş metin döndürürse bu mesaj çıkıyor. Kısık ses ve gürültülü oda eşiği aşamıyor olabilir | Olası |
| Ekranda "0 el · 0 yüz" | Üç ihtimal, aşağıda 3.1 | Belirsiz, cihazdan bilgi gerekli |
| 🤏 tıklama tutarsız | Tıklama 150–700 ms'lik bir pencereye bağlı; kamera kare hızı düşükse pencere kaçıyor. Her karede gereksiz yere yüz arama da çalışıyor | Olası |
| Ekranda üst üste binen yazılar | El kontrolü ekranında başlık, durum satırı, cevap kartı, jest açıklaması ve düğmeler aynı anda duruyor | Kesin |

## 2. Ses sistemi neden kendiliğinden dinlemiyor

Akış bugün şöyle (`ui/.../JarvisController.kt`, `startListening()`):

1. Kullanıcı mikrofon düğmesine basar (ya da 👍 jesti, ya da yüzen Rick'e dokunma).
2. `WhisperSpeech` kayda başlar. `SpeechEndpointer` sesin yüksekliğine bakar: konuşma duyup ardından
   1,2 sn sessizlik olursa bitirir; 8 sn hiç konuşma olmazsa "Bir şey duyamadım" der; en fazla 30 sn.
3. Whisper metne çevirir, `send(text)` cevabı üretir, Piper seslendirir.
4. Cevap bitince yeniden dinleme **yalnızca "Sohbet modu" açıksa** olur (`maybeContinueConversation`).
   Bu ayar var ama varsayılan olarak kapalı ve üst çubukta küçük bir çip olarak duruyor.

Eksik olanlar:

- **Uyanma sözü yok.** "Rick" kelimesi yalnızca uyku ekranında, alkıştan sonra aranıyor (`WakePhrase`).
  Uyanıkken sürekli dinleyip "Rick" arayan hiçbir kod yok.
- **Açılışta dinleme yok.** Uygulama açıldığında mikrofon kapalı başlıyor.
- **Araya girme yok.** `SPEAKING` durumunda mikrofon açılmıyor; susturmanın yolu ekrana dokunmak ya da
  ✊ jesti.
- **Yankı engelleme yok.** Kayıt kaynağı `VOICE_RECOGNITION`; hoparlörden çıkan Rick sesi mikrofona
  geri girer. Bu çözülmeden "konuşurken dinle" yapılırsa Rick kendi sesine cevap verir.
- **Alkış uyanıkken dinlenmiyor.** `ClapDetector` yalnızca `SleepScreen` ve `BubbleService` içinde
  kullanılıyor. v1.4.1'deki "alkış uyanıkken de çalışır" düzeltmesi yalnızca yüzen Rick açıkken geçerli;
  ayar yazısı bunu açıkça söylemiyor.

Hazır olan ve yeniden kullanılacak parçalar: Whisper, Piper, ses yüksekliğiyle konuşma sonu bulma
(`SpeechEndpointer`), ham mikrofon akışı (telefonda `AndroidMicStream`, PC'de `DesktopMicStream`),
"Rick / Rik / Rich / Jarvis" yazımlarını tanıyan kalıp (`AssistantName.WAKE`), sohbet modu.

## 3. El takibi

### 3.1 "0 el · 0 yüz" ne demek
Kamera ekranının üstündeki satır her karede bulunan el ve yüz sayısını yazar. Sıfır görünmesinin üç
nedeni olabilir:

1. **El takibi dosyaları kurulu değil.** El ve yüz modelleri (8 MB) APK'nın içinde değil, sonradan
   indiriliyor. Kurulu değilse kamera çalışır ama sayı hep sıfırdır; ekranda indirme teklifi çıkar.
2. **Modeller yüklenemedi.** `AndroidCamera.Trackers.create` hata alırsa sessizce `null` döner; kullanıcı
   yalnızca "0 el" görür, neden olduğunu göremez. Bu gerçek bir eksik: hata ekrana yazılmalı.
3. **O anda kadrajda el yoktu.** Ekran görüntüsü el görünmeden alındıysa sıfır normaldir.

Hangisi olduğunu anlamak için aynı satırdaki **FPS** sayısı ve ekranda "el takibini indir" teklifinin
olup olmadığı yeterli.

### 3.2 Tıklamanın tutarsızlığı (olası nedenler)
- `AndroidCamera.analyze` her karede hem el hem **yüz** modelini çalıştırıyor. El ile yönetimde yüz
  sonucu hiç kullanılmıyor; işlemci zamanının kayda değer kısmı boşa gidiyor ve kare hızı düşüyor.
- MediaPipe işlemcide çalışıyor (GPU denenmiyor). Her karede tam görüntü döndürülüp yeni bitmap
  üretiliyor.
- `AirPointer` tıklamayı "parmaklar 150–700 ms birleşik kaldıysa" diye tanımlıyor. Saniyede 5–8 kare
  veren bir telefonda bu pencere bir iki kareye denk gelir; kısa birleştirme hiç görülmeyebilir.
- Jestler var ama dağınık: açık el, yumruk, başparmak, zafer, işaret, çimdik, sallama, iki el. Hepsi
  tek bir açıklama satırında anlatılıyor.

ChatGPT'nin önerdiği 8 jestin karşılığı bugün zaten var; sorun sayı değil, güvenilirlik ve anlatım:

| Jest | Bugün | Öneri |
|---|---|---|
| ☝️ İşaret | İmleç | Aynı |
| 🤏 Birleştir-aç | Tıkla | Aynı; süre penceresi kare hızına göre ayarlanacak |
| 🤏 Birleştirip sürükle | Kaydır | Aynı |
| ✋ Açık el, sabit | Durdur / devam | Aynı; Rick konuşuyorsa susturur |
| ✌️ Tutulu | Geri | Aynı |
| 👋 Salla | Çık | Aynı |
| ✋✋ İki el | Ana ekran | Aynı |
| 👍 Tutulu | Dinle | Sürekli dinleme gelince "onayla" olur |
| ✊ Tutulu | Sus | ✋ ile birleşir, kaldırılır |

## 4. Önerilen ses hattı ("Eller serbest")

Yeni model indirmeden, eldeki parçalarla:

```
mikrofon (16 kHz, sürekli, yalnızca uygulama ekrandayken)
   └─ ses yüksekliği ölçümü (SpeechEndpointer, çok hafif)
        ├─ sessizlik            → hiçbir şey çalışmaz
        └─ konuşma bitti        → Whisper bu parçayı metne çevirir
              ├─ içinde "Rick" yok          → atılır, hiçbir yere yazılmaz
              ├─ "Rick, saat kaç"           → adın arkasındaki kısım doğrudan komut olur
              └─ yalnızca "Rick"            → "Efendim?" der, 8 sn boyunca adsız komut bekler
cevap → Piper seslendirir → 20 sn "sohbet penceresi": bu sürede "Rick" demeden devam edilebilir
```

- **Neden ayrı bir uyanma-sözü modeli değil:** hazır küçük modeller İngilizce/Çince; yeni indirme,
  yeni kilit kaydı ve yeni onay gerektirir. Önce eldekiyle denenir; pil ya da hız kötü çıkarsa ikinci
  adım olarak sherpa-onnx anahtar kelime modeli eklenir.
- **Bedeli:** odada sürekli konuşma varsa (televizyon) Whisper sık çalışır, pil daha çok gider.
  Ölçülecek, gerekirse "yalnızca şarjdayken" seçeneği eklenir.
- **Araya girme:** kayıt kaynağı `VOICE_COMMUNICATION` yapılıp Android'in yankı engelleyicisi açılır;
  Rick konuşurken belirgin şekilde yüksek ve 300 ms'den uzun ses gelirse Piper susar ve dinleme başlar.
  Yankı engelleme telefondan telefona değişir; **bu en riskli parça**, gerçek cihazda denenmeden
  "çalışıyor" denmeyecek. Çalışmayan cihazda yedek: ekrana dokunmak ya da ✋.
- **Gizlilik:** mikrofon yalnızca Rick ekrandayken (ya da kullanıcı yüzen Rick'i açtıysa bildirimle)
  dinler. Ses dosyaya yazılmaz, "Rick" geçmeyen parçalar metin olarak da tutulmaz. Ekranda sürekli
  görünen bir "dinliyorum" göstergesi ve tek dokunuşla kapatma olur. Ayar: Eller serbest aç/kapa.
- **Alkış:** aynı mikrofon akışına bağlanır; böylece uygulama açıkken de çalışır.
- **PC:** mantık ortak modülde olduğu için aynı hat Windows'ta `DesktopMicStream` ile çalışır.

## 5. Yetenek sistemi ("her şeyi yapabilsin")

Bugün olanlar: hazır komutlar (model beklemeden), 9 araç (`hesapla`, `birim_cevir`, `tarih_saat`,
`hafiza_*`, `alarm_kur`, `zamanlayici`, `uygulama_ac`) ve Wikipedia / belge araması, kamera görüntüsünü
Gemma'ya sorma (telefon), ekran görüntüsü ve resim analizi (PC). Hesap zaten modelle değil, kesin
hesap motoruyla yapılıyor.

Öneri: ayrı bir "yetenek kayıt defteri" katmanı **şimdi yazılmasın.** Kullanıcıya görünen bir şey
katmaz, çalışan araç sistemini yeniden sarmalar. Onun yerine eksik yetenekler tek tek, mevcut araç
sistemine eklenir:

| Yetenek | Durum | Not |
|---|---|---|
| Hesap, birim, tarih | Var | — |
| Resim / kamera anlama | Var | Telefonda Gemma 4, PC'de yerel görsel analiz |
| Belge arama (PDF, TXT, MD) | Var | — |
| Fotoğraftan yazı okuma (OCR) | Yok | Küçük bir yerel model gerekir; ayrı iş |
| Grafik çizme | Yok | Model gerekmez; sayılardan çizilir. Yapılabilir |
| Kod yardımı | Kısmen | Model yazar; çalıştırma yok (bilinçli) |
| Resim üretme | Yok | Telefonda gerçekçi değil; PC'de 8 GB VRAM ile dil modeliyle aynı anda sığmaz. Önerilmez |

## 6. Arayüz

- Parçacık küre zaten var: Ayarlar → Görünüm → "Parçacık küre". İstenirse varsayılan yapılır.
- Ana ekran: ortada küre, altında cevap, en altta tek satır. Mikrofon düğmesi kalkmaz, küçülür
  (yedek olarak); onun yerine "dinliyorum / Rick deyin" göstergesi gelir.
- El kontrolü ekranı: üst satırdaki teknik yazı (el, yüz, FPS) küçük bir tanı simgesinin arkasına
  alınır; jest açıklaması yalnızca ilk açılışta ve "?" ile görünür; cevap kartı ile düğmeler üst üste
  binmeyecek şekilde yerleştirilir.
- **Kural:** görsel değişiklikler önce resim olarak kullanıcıya gösterilir, onaydan sonra yazılır.

## 7. Değişecek dosyalar (1. ve 2. öncelik için)

| Dosya | Değişiklik |
|---|---|
| `core/.../stt/HandsFree.kt` (yeni) | Durum makinesi: bekle → parça → ad kontrolü → komut → sohbet penceresi. Saf mantık, testli |
| `core/.../stt/SpeechEndpointer.kt` | Sürekli akışta parça ayırma (bugün tek seferlik) |
| `ui/.../JarvisController.kt` | Eller serbest döngüsü, konuşurken araya girme, alkışın ortak akışa bağlanması |
| `ui/.../Platform.kt` | `SpeechInput`: hazır ses parçasını metne çevirme; `MicStream`: yankı engelleme isteği |
| `androidApp/.../WhisperSpeech.kt`, `AndroidMicStream.kt` | Tek mikrofon sahibi, `VOICE_COMMUNICATION` + yankı engelleyici |
| `desktopApp/.../stt/*` | Aynı arayüzün PC karşılığı |
| `ui/.../Stores.kt`, `screens/OtherScreens.kt`, `ChatScreen.kt` | "Eller serbest" ayarı ve dinleme göstergesi |
| `androidApp/.../AndroidCamera.kt` | Yüz aramasını yalnızca gereken ekranda çalıştırma, GPU deneme, yükleme hatasını ekrana yazma |
| `core/.../vision/AirPointer.kt` | Tıklama penceresinin kare hızına uyması |
| `ui/.../screens/CameraScreen.kt`, `HandNav.kt` | Tanı satırı, sadeleştirme |

Dokunulmayacaklar: model motorları, Kapı ve indirme güvenliği, hafıza, araçlar, erişilebilirlik
servisi ve yasaklı uygulama listesi.

## 8. Test planı

Otomatik (CI, her gönderimde):
- `HandsFree` durum makinesi: "Rick saat kaç" tek parçada komut olur; adsız konuşma atılır; yalnızca
  "Rick" → 8 sn bekleme; sohbet penceresi süresi; Rick konuşurken kendi sesiyle tetiklenmeme (yapay ses
  düzeyleriyle).
- Sürekli akışta parça ayırma: gürültü, kısa tık, uzun konuşma.
- `AirPointer`: saniyede 5, 10 ve 30 karede aynı tıklama hareketi tek tıklama üretir.
- Mevcut testlerin tamamı ve emülatör duman testi aynen geçmeli; Kale'de internet izni denetimi aynen.

Gerçek telefonda (kullanıcı; her biri için "oldu / olmadı / ekranda ne yazdı"):
1. Uygulamayı aç, hiçbir yere dokunmadan "Rick, saat kaç" de. 10 denemede kaç kez cevap verdi?
2. Rick uzun bir cevap okurken "Rick dur" de. Sustu mu?
3. Televizyon açıkken 5 dakika bırak. Kendiliğinden kaç kez tetiklendi?
4. El kontrolü ekranında üstteki FPS sayısı kaç?
5. 10 kez 🤏 tıkla. Kaçı tuttu, kaç yanlış tıklama oldu?
6. İki alkış: uygulama açıkken ve uyku ekranında.

Bir sonraki önceliğe geçme koşulu: 1. madde en az 8/10, 3. madde en fazla 1 yanlış tetikleme,
5. madde en az 8/10 ve yanlış tıklama yok.

## 9. Önerilen sıra

1. **v1.5.0 — Eller serbest ses** (bölüm 4) ve alkışın uygulama açıkken çalışması.
2. **v1.5.1 — El takibi güvenilirliği** (bölüm 3) ve el kontrolü ekranının sadeleşmesi.
3. Ana ekran yeniden tasarımı: önce resim, onaydan sonra kod.
4. Grafik çizme ve OCR: ayrı ayrı, isteğe göre.
5. PC'de "İndirilenler klasörünü düzenle" görevi.

## 10. Kullanıcıdan gereken bilgi

- Telefonun markası, modeli ve RAM miktarı (Ayarlar → Telefon hakkında).
- El kontrolü ekranında üstte yazan satır aynen (ör. "1 el · 0 yüz · 12 FPS").
- Aynı ekranda "el takibini indir" gibi bir teklif çıkıyor mu?
- Whisper (ses tanıma) ve Piper (Rick'in sesi) indirildi mi; "Bir şey duyamadım" hangi durumda çıkıyor?
