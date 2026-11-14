# Rick'in gözü — üretim betikleri

`assets/rick-eye/` içindeki dosyalar bu betiklerle iki fotoğraftan üretildi (kaynaklar: `assets/rick-eye/CREDITS.txt`).
Betikler numpy, scipy ve Pillow ister; uygulamanın derlenmesi için gerekmez, yalnızca görseli yeniden üretmek için.

1. `geom.py` — açık göz fotoğrafını (030608 Pupil.jpg, `src.jpg` adıyla) 1152 piksel genişliğe getirir, göz bebeğini bulur.
2. `closed_geom.py` — kapalı göz fotoğrafının kirpik çizgisini bulur (`m.npy`).
3. `eyeanim.py` — kirpikleri ayırır, göz bebeği büyütme/küçültmesini ve kornea yansımasının maskesini kurar.
4. `blink.py` — açık gözü gerçek kapalı kapakla kapatır (kapak kenarı gözün çizgisini izler, renk eşlenir).
5. `export.py` — uygulamanın çizdiği katmanları yazar: `eye_base.jpg`, `eye_reflect.webp`, 20 kapak karesi, `eye.json`.

Uygulama göz bebeğini canlı çizer (halkalar hâlinde, `ui/.../components/RealEye.kt`); kapak kareleri arasında geçiş yapar.

Two-eye version (v1.5.3): `export_lower.py` writes the raised lower lid (`eye_low_N.webp`, a smile; uses
`lower.py`), then `bake_fade.py assets/rick-eye` multiplies every lid layer's alpha by the edge fade once, so the
app draws the eyes without erasing the edges on every frame. Run it once after each export.
