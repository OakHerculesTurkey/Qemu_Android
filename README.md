# QEMU → Android

Bu depo, **QEMU**'yu (sanal makine emülatörü) Android NDK ile **Android'de doğrudan
çalışan yerel (native) programlar** olarak çapraz derler. Derleme bir Linux
makinede (veya bu depodaki GitHub Actions iş akışında) yapılır; çıkan paket
telefona/tablete kopyalanıp `adb shell` ya da Termux içinden çalıştırılır.

| | |
|---|---|
| QEMU | 11.1.3 (`qemu-system-aarch64`, `qemu-system-x86_64`, `qemu-img`, `qemu-nbd`, …) |
| Android ABI | `arm64-v8a` (varsayılan), `x86_64` |
| En düşük Android | API 28 — Android 9 |
| Hızlandırma | TCG (yazılım JIT). `/dev/kvm` olan cihazlarda KVM de derlenmiştir |
| Ekran | VNC (`-vnc :0`) ve seri konsol (`-nographic`) |
| Ağ | libslirp ile kullanıcı modu ağ (`-netdev user`, port yönlendirme) |
| Bağımlılıklar | GLib 2.88, pixman 0.46, libslirp 4.9, libffi, pcre2, zlib — hepsi statik |

Ortaya çıkan ikili dosyalar sadece Android sistem kütüphanelerine (`libc.so`,
`libm.so`) bağlıdır; ayrıca kütüphane kopyalamaya gerek yoktur.

## Android uygulaması (APK)

`android-app/` — QEMU'yu dokunmatik arayüzle kullanmak için uygulama
(Android 9+, hedef Android 17 / API 37, arm64-v8a; Snapdragon 8 Elite gibi
cihazlar için). PC gerekmez: APK'yı telefona indirip kurmanız yeterli.

* **Sanal makine listesi** — başlat / durdur / düzenle / kopyala / sil, çalışma durumu.
* **Şablonlar** — Linux ARM64 (UEFI, ekranlı), Linux ARM64 (seri konsol),
  x86_64 Linux (BIOS), Windows 7/Vista (q35, IDE, e1000, PS/2 + USB 1.1 tablet),
  Windows XP/2000/98 (`pc` makinesi, IDE, Cirrus, RTL8139), x86_64 UEFI, boş.
* **Klavye/fare** — USB 3 (modern sistemler) veya PS/2 klavye + USB 1.1 tablet
  (xHCI sürücüsü olmayan Windows 7 ve öncesi) veya sadece PS/2.
* **Ayarlar** — mimari, makine, CPU, çekirdek, bellek, firmware (UEFI/BIOS/yok),
  disk (+ arayüz: VirtIO/NVMe/IDE/USB), ISO, önyükleme sırası, doğrudan kernel/initrd,
  ekran kartı, ağ (NAT + port yönlendirme, NIC modeli), TCG ayarları, ek QEMU
  parametreleri ve **oluşan komutun önizlemesi**.
* **Dosyalar** — ISO/disk seçme (uygulamaya kopyalanır), uygulama içinden yeni qcow2
  disk oluşturma (`qemu-img`), isteğe bağlı "Tüm dosyalara erişim" ile `/sdcard/...`
  yollarını kopyalamadan kullanma.
* **Ekran** — dahili VNC görüntüleyici (QEMU'ya uygulamaya özel unix soketiyle bağlanır,
  dışarıya port açmaz). İki fare modu: *Dokunma* (dokunduğun yere tıklar, uzun bas = sağ
  tık, sürükle, iki parmak = kaydırma) ve *Touchpad* (göreli imleç). Ekran klavyesi,
  donanım klavye/fare, Ctrl/Alt/Shift/Win (yapışkan), Esc, Tab, oklar, F1–F12,
  Ctrl+Alt+Del.
* **Güç** — duraklat / devam / reset / ACPI kapat / zorla kapat (QMP).
* **Seri konsol**, **QEMU monitörü** (HMP komutları: `info status`, `change cd0 ...`,
  `savevm` …) ve **Log** ekranı; log'u tek tuşla paylaşma (hata ayıklamak için).
* **Sistem bilgisi / tanılama** — cihaz, SoC, sayfa boyutu, `/dev/kvm`, QEMU sürümü.
* VM'ler bir ön plan servisinde çalışır; uygulamadan çıkınca kapanmaz
  (bildirimden "Hepsini kapat").

APK derleme:

```sh
./build.sh                 # QEMU (arm64-v8a)
./scripts/build-apk.sh     # Android SDK'yı kurar, dist/qemu-android-<sürüm>.apk üretir
```

Uygulamanın QEMU'yu süren saf Java çekirdeği (komut oluşturucu, VNC/RFB istemcisi,
QMP istemcisi) gerçek QEMU Android derlemesine karşı test edilir:
`./scripts/test-app-core.sh` (önce `ANDROID_ABI=x86_64 ./build.sh` ve
`./scripts/smoke-test.sh`).

`./scripts/test-arm64-pac.sh` — **arm64** derlemesini, QEMU'nun kendi aarch64 kullanıcı
modu emülatöründe `-cpu max` (PAC, BTI, …) ve gerçek Android bionic ile çalıştırır;
diskli VM'ler açar. Telefon olmadan PAC/iş parçacığı hatalarını yakalar.

### Sorun giderme

* **Windows XP: STOP 0x000000A5 mavi ekranı** — makine `q35` olamaz; `pc` kullanın
  (XP şablonu bunu kendisi ayarlar).
* **Windows 7/XP'de fare/klavye çalışmıyor** — "Klavye ve fare" ayarını
  "PS/2 klavye + USB 1.1 tablet" yapın (bu sürümlerde USB 3 sürücüsü yoktur).
* **QEMU kod 137 ile kapanıyor** — Android süreci öldürdü (bellek ya da Android 12+
  "phantom process killer"). Geliştirici seçenekleri → *Alt süreç kısıtlamalarını devre
  dışı bırak*'ı açın veya VM belleğini azaltın.
* **Siyah ekran / TCG hatası** — Ayarlar → Ek parametreler'e `-accel tcg,split-wx=on`
  yazmayı deneyin (bazı cihazlar yazılabilir+çalıştırılabilir belleğe izin vermez).
* **Yavaşlık** — ARM64 misafir kullanın (x86 emülasyonu çok daha yavaştır);
  CPU `max,pauth-impdef=on` (varsayılan) ve "Çok iş parçacıklı (MTTCG)" seçin.
* Hata olursa: VM → ⋮ → Log → **Paylaş** ile log'u gönderin.

## Hızlı başlangıç (derleme)

Debian/Ubuntu üzerinde:

```sh
./scripts/setup-host.sh          # derleme araçları (meson, ninja, cmake, ...)
./build.sh                       # arm64-v8a
ANDROID_ABI=x86_64 ./build.sh    # x86_64 (Android emülatörü / Chromebook)
```

Çıktı: `dist/qemu-11.1.3-android-<abi>.tar.gz` (~27 MB).

`build.sh` şu adımları sırayla çalıştırır; her biri ayrı da çalıştırılabilir:

| Betik | Ne yapar |
|---|---|
| `scripts/fetch.sh` | Android NDK r28c'yi ve tüm kaynakları indirir (`work/`) |
| `scripts/build-deps.sh` | zlib, libffi, pcre2, GLib, pixman, libslirp → statik kütüphaneler |
| `scripts/build-qemu.sh` | `patches/qemu/` yamalarını uygular, QEMU'yu yapılandırır ve derler |
| `scripts/package.sh` | Kurulumu toplar, gereksiz firmware dosyalarını ayıklar, `.tar.gz` üretir |

Ayarlanabilir değişkenler (`scripts/env.sh`): `ANDROID_ABI`, `ANDROID_API`,
`QEMU_TARGETS` (ör. `aarch64-softmmu,x86_64-softmmu,riscv64-softmmu`),
`QEMU_VERSION`, `NDK_VERSION`, `JOBS`, `QEMU_EXTRA_CONFIGURE`,
`QEMU_PRUNE_BLOBS=0` (tüm firmware dosyalarını tut).

### Telefon olmadan test

`x86_64` derlemesi, gerçek bir Android sistem imajından çıkarılan bionic
`linker64` + `libc.so` ile bu Linux makinede doğrudan çalıştırılabilir:

```sh
ANDROID_ABI=x86_64 ./build.sh
./scripts/smoke-test.sh
```

Test; `--version`, `qemu-img` ile qcow2 oluşturma/okuma, TCG ile çıplak-metal
bir aarch64 misafirin açılıp UART'a yazması (`tests/baremetal/`) ve
`qemu-system-x86_64` içinde SeaBIOS'un başlamasını kontrol eder.

GitHub'a her push'ta `.github/workflows/build.yml` iki ABI'yi de derleyip
paketleri *artifact* olarak yükler.

## Android cihazda çalıştırma

```sh
adb push dist/qemu-11.1.3-android-arm64-v8a.tar.gz /data/local/tmp/
adb shell
cd /data/local/tmp
tar xzf qemu-11.1.3-android-arm64-v8a.tar.gz
cd qemu-android-arm64-v8a
. ./qemu-env.sh                   # PATH ve TMPDIR ayarlar
```

Termux'ta da aynı şekilde açılabilir (dosyaları Termux'un ev dizinine koyun).

### Örnek: seri konsolda bir Linux çekirdeği

```sh
qemu-img create -f qcow2 disk.qcow2 8G
qemu-system-aarch64 -M virt -cpu max -smp 4 -m 2G -nographic \
  -kernel Image -initrd initramfs.cpio.gz -append "console=ttyAMA0" \
  -drive file=disk.qcow2,if=virtio \
  -netdev user,id=n0,hostfwd=tcp::2222-:22 -device virtio-net-pci,netdev=n0
```

### Örnek: UEFI ile bir ISO kurmak (VNC ekranı)

```sh
qemu-system-aarch64 -M virt -cpu max -smp 4 -m 3G \
  -bios share/qemu/edk2-aarch64-code.fd \
  -device virtio-gpu-pci -device qemu-xhci -device usb-kbd -device usb-tablet \
  -cdrom alpine-virt-aarch64.iso -drive file=disk.qcow2,if=virtio \
  -netdev user,id=n0 -device virtio-net-pci,netdev=n0 \
  -vnc :0
```

Ardından telefondaki herhangi bir VNC istemcisiyle `127.0.0.1:5900`'e bağlanın.

x86 misafirler için (ör. Windows/Linux x86 ISO) `qemu-system-x86_64` kullanılır;
TCG ile çalıştığı için masaüstüne göre belirgin şekilde yavaştır.

## Android'e özgü yamalar

`patches/qemu/0001-android-build-fixes.patch`:

* **`shm_open` yok** — bionic POSIX paylaşımlı bellek sunmaz. `qemu_shm_alloc()`
  Android'de anonim `memfd` kullanır; meson'daki `librt` zorunluluğu kaldırıldı.
* **`statx` (API < 30)** — bionic `statx()`'i API 30'dan itibaren bildirir;
  daha düşük API'de O_DIRECT hizalama sorgusu atlanır (QEMU zaten başka yöntemlere düşer).
* **`ivshmem-server` / `ivshmem-test`** — `shm_open` gerektirdiği için Android'de derlenmez.
* **Coroutine'ler ve PAC (SIGILL düzeltmesi)** — bionic'te `makecontext/swapcontext` yok,
  bu yüzden QEMU `sigaltstack` arka ucunu seçiyordu. Asıl sorun: Android 12+ her iş
  parçacığına ayrı PAC anahtarı verir (`__pthread_start` → `prctl(PR_PAC_RESET_KEYS)`),
  bionic'in `sigsetjmp`'ı dönüş adresini bu anahtarla imzalar; QEMU bir coroutine'i başka
  bir iş parçacığında sürdürdüğünde `siglongjmp` içindeki `autiasp` başarısız olur ve
  pointer authentication'lı CPU'larda (ör. Snapdragon 8 Elite) disk erişimi olan her VM
  **SIGILL** (kod 132) ile çöker. Android'de `ucontext` arka ucu, imzalamayan küçük bir
  assembly bağlam değiştirici (arm64 + x86_64) ile kullanılır.
* **Çökme raporu** — `util/android-crash.c`: ölümcül sinyallerde sinyal, PC, komut,
  modül+ofset, geri izleme ve HWCAP bilgisini stderr'e (uygulamanın log'una) yazar.

`patches/libslirp/0001-android-resolv-conf.patch`:

* Android'de `/etc/resolv.conf` yoktur; libslirp misafirin DNS isteklerini
  `SLIRP_RESOLV_CONF` ortam değişkenindeki dosyaya göre yönlendirir. Uygulama bu
  dosyayı her başlatmada aktif ağın DNS sunucularıyla yazar.

## Sınırlamalar / sonraki adımlar

* Ses (AAudio/OpenSL) ve 3D hızlandırma (virgl) yok; ekran VNC çerçeve arabelleğidir.
* Çoğu telefonda uygulamalar `/dev/kvm`'ye erişemez; hızlandırma TCG (JIT) ile olur.
* `linux-user` (qemu-aarch64 gibi kullanıcı modu emülatörler) derlenmedi.
* Uygulama henüz gerçek cihazda test edilmedi; derleme ortamında çekirdek mantık
  (VNC, QMP, seri konsol) gerçek QEMU Android derlemesine karşı test edildi.
