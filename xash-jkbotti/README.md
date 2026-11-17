# jk_botti → Xash3D FWGS (Android arm64)

[jk_botti](https://github.com/Bots-United/jk_botti) Half-Life deathmatch botlarının,
**Xash3D FWGS**'in güncel Android sürümünde (paket `su.xash.engine`) çalışan
**arm64-v8a** portu. Hedef: Snapdragon 8 Elite gibi cihazlar, Android 17 (API 37);
Android 9+ (API 28) cihazlarda da çalışır.

| | |
|---|---|
| Bot | jk_botti `b8d4662` (Bots-United) + ARM64/Android yaması |
| Eklenti yöneticisi | [Metamod-FWGS](https://github.com/FWGS/metamod-fwgs) `7cb9a6c` + Android yaması |
| Oyun kodu | [hlsdk-portable](https://github.com/FWGS/hlsdk-portable) `89e83b9` (HL sunucu + istemci) |
| Motor | Xash3D FWGS (ayrıca kurulur, bu APK içinde değil) |
| ABI | arm64-v8a, 16 KB sayfa hizalı (Android 15+ cihazlar için zorunlu) |

## Nasıl çalışıyor

Android 10+ paylaşılan depolamadan (`/sdcard/...`) `.so` yüklemeye izin vermez. Bu yüzden
kütüphaneler ayrı bir **başlatıcı APK** içinde gelir ve Xash3D bunları APK'nın
`nativeLibraryDir`'inden yükler (Xash'in resmi "mod APK" mekanizması, `gamelibdir`):

```
Başlatıcı APK ──intent──▶ su.xash.engine/XashActivity
   gamedir=valve, gamelibdir=<APK lib dizini>
   argv="-dll @metamod -console -log"
   env: MM_GAMEDLL=<lib>/libhl_android_arm64.so
        MM_PLUGINS=<lib>/libjk_botti_mm_android_arm64.so

Xash3D ─▶ libmetamod_android_arm64.so ─▶ libhl_android_arm64.so      (Half-Life DM)
                                       └▶ libjk_botti_mm_android_arm64.so (botlar)
Xash3D ─▶ libclient_android_arm64.so                                 (HL istemcisi)
```

Botların ayar, sohbet, isim ve 26 standart harita waypoint dosyası ile ekran butonu
simgeleri eklentinin içine gömülüdür; ilk açılışta `valve/addons/jk_botti/` altına yazılır.
Var olan dosyaların üzerine yazılmaz (düzenlemeleriniz ve kaydedilen waypoint'ler korunur);
tek istisna, hiç değiştirilmemiş eski varsayılan `jk_botti.cfg`'dir — o yeni varsayılanla
değiştirilir.

## Kurulum (telefon)

1. [Xash3D FWGS](https://github.com/FWGS/xash3d-fwgs/releases/tag/continuous) Android APK'sını kurun.
2. Half-Life'ın `valve` klasörünü Xash'in oyun klasörüne kopyalayın (varsayılan `/sdcard/xash/valve`)
   ve Xash'e dosya erişim izni verin.
3. Bu klasördeki APK'yı (`dist/jk_botti-xash-*.apk` ya da GitHub Actions çıktısı) kurun.
4. **jk_botti (Xash3D)** uygulamasını açıp **Half-Life'ı başlat**'a basın.
5. Menüden **Çok Oyunculu → Sunucu Oluştur** ile bir harita açın.
6. Botları ekranın üst ortasındaki **+** / **−** butonlarıyla ekleyip çıkarın.

Android sürümünde botlar **kendiliğinden eklenmez** (asıl jk_botti ayarı her haritada 5 bot
ekler). Her haritada otomatik bot isterseniz `valve/addons/jk_botti/jk_botti.cfg` içindeki
`#addbot` satırlarının başındaki `#` işaretini kaldırın ya da `min_bots` / `max_bots` ayarlayın.

**+ / − butonları** yalnızca telefonda kurulan sunucuda (listen server) ve çok oyunculu oyunda
görünür; oyuna girdikten ~1 saniye sonra belirir. Geçicidirler, Xash'in dokunmatik düzen
dosyasına kaydedilmezler. (Xash `touch.cfg`'yi ilk oyun karesinde `touch_removeall` ile yüklediği
için butonlar giriş sonrası ilk 40 saniyede birkaç kez yeniden eklenir.) Dokunmatik düzenleyici
açıkken başka bir yere taşınabilirler; yeni haritada varsayılan yerlerine dönerler.

Uygulama Xash'in mod listesinde de görünür (`su.xash.engine.MOD`); oradan seçilirse
aynı parametrelerle başlar.

### Konsol komutları

| Komut | |
|---|---|
| `jk_botti addbot` | bot ekle (`addbot <model> <isim> <skill>`) — **+** butonu |
| `jk_botti kickbot` | rastgele bir botu at (`kickbot <isim>`: o botu) — **−** butonu |
| `jk_botti kickall` | tüm botları at |
| `jk_botti botskill 1..5` | varsayılan zorluk (1 en iyi, 5 en kötü) |
| `jk_botti min_bots N` / `max_bots N` | oyuncu sayısına göre otomatik bot |
| `jk_botti autowaypoint 1` | haritada gezerken waypoint öğrenme |
| `meta list` | Metamod'un yüklediği eklentiler |

Waypoint'i olmayan haritalarda `autowaypoint` sayesinde botlar oynadıkça yolu öğrenir.

## Derleme

Linux x86-64 üzerinde (NDK r28c ve Android SDK 37 yoksa kendisi indirir):

```bash
cd xash-jkbotti
./build.sh          # kaynaklar + yamalar + 4 kütüphane + APK  → dist/
./build.sh libs     # yalnızca .so dosyaları → dist/libs/arm64-v8a/
```

GitHub Actions: `.github/workflows/jk-botti.yml` bu klasör değiştiğinde çalışır, APK'yı ve
`.so` dosyalarını artifact olarak yükler. İmzalama anahtarı depoda tutulmaz:
`JK_KEYSTORE_B64` / `JK_KEYSTORE_PASSWORD` gizli değişkenleri yoksa APK debug anahtarıyla imzalanır.

## Port değişiklikleri

**jk_botti** (`patches/jk_botti/`)
* `sendto()` kancası x86 makine kodu yazıyordu (`jmp rel32`); x86 dışı mimarilerde
  devre dışı (yalnızca adanmış sunucudaki `bot_conntimes` özelliğini etkiler, listen
  serverda zaten kapalı).
* x87 `fsincos` ve `int $3` yerine taşınabilir C yolu / `__builtin_trap()`.
* Android'de gömülü varsayılan dosyaları `valve/addons/jk_botti/`'ye kurma; varsayılan
  cfg'de `addbot` satırları kapalı.
* Yeni `jk_botti kickbot [isim]` komutu (tek bot atar, `min_bots`'u gerekirse düşürür).
* Android listen server'da `touch_addbutton` ile geçici **+ / −** dokunmatik butonları.
* Derleme: `jk_botti-android/CMakeLists.txt` (x86 CPU varyantları / shim yerine tek kütüphane,
  Xash adlandırması `libjk_botti_mm_android_arm64.so`).

**Metamod-FWGS** (`patches/metamod-fwgs/`)
* Bionic'te olmayan `RTLD_DEEPBIND` ve `-lpthread` düzeltmeleri.
* `/proc/self/maps` 64-bit adres okuma hatası (`%x` → `%lx`).
* Android: oyun DLL'i `MM_GAMEDLL` ya da `XASH3D_GAMELIBDIR`'den; eklentiler
  `XASH3D_GAMELIBDIR`'den çözülür; `plugins.ini` yoksa `MM_PLUGINS` listesi kullanılır
  (harita değişiminde eklentiler düşürülmez).

## Test durumu

* 4 kütüphane NDK r28c ile arm64-v8a için uyarısız derleniyor; yalnızca `libc/libm/libdl/liblog`'a bağlı.
* Aynı yamalı kaynaklar Linux x86-64'te Xash3D FWGS adanmış sunucusuyla denendi:
  Metamod-FWGS 64-bit HL kütüphanesini yükledi, jk_botti `MM_PLUGINS` üzerinden bağlandı
  (`JK_Botti: plugin attaching`, `[jk_botti] Standard HL1DM assumed.`).
* Cihaz üzerinde oyun içi test (harita + botların hareketi) henüz yapılmadı — Valve oyun
  verisi gerektiriyor.

## Lisans

jk_botti, Metamod ve hlsdk-portable kendi lisanslarıyla dağıtılır (GPL / Half-Life SDK
lisansı); bu klasördeki betik ve başlatıcı kodu deponun lisansına tabidir. Half-Life oyun
verileri dahil değildir; kendi kopyanızı kullanmanız gerekir.
