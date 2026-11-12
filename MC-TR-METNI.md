# MC-TR ana konu açıklaması

**DyMarket**

**Ekonomi, müzayede ve emlak tek hafif eklentide — Türkçe komut destekli**

Merhaba MC-TR ailesi,

**DyMarket**, survival, SMP, Towny/Earth ve Skyblock sunucuları için geliştirilmiş bir ekonomi eklentisidir. Çoklu para birimi, web sitenizden satılan krediler için güvenli bir **kredi köprüsü**, sandık menülü bir **müzayede evi** ve DyClaim arazilerini satma ya da kiraya verme (**emlak**) tek bir JAR içinde gelir.

**Zero bloat.** Veritabanı sunucusu yok, gömülü kütüphane yok: bütün veriler tek bir SQLite dosyasında durur, disk işlemleri ana iş parçacığını hiç yormaz. Bukkit, Spigot, Paper, Purpur ve **Folia** aynı dosyayla çalışır.

**Güncel sürüm: 1.0.0-BETA**

**İndirme:** [Modrinth üzerinden DyMarket](https://modrinth.com/plugin/dymarket)

> **BETA ne demek?** Ekonomi veri biçimi 1.0.0'dan önce değişebilir. DyMarket her açılışta veri dosyasını otomatik yedekler (son 7 yedek tutulur) ve sonraki sürümler dosyayı kendiliğinden dönüştürür.

---

**Öne Çıkan Özellikler**

**Çoklu Para Birimi ve Güvenli Ekonomi**

- Varsayılan olarak **para** (`money`) ve **kredi** (`credits`) gelir; `config.yml` içinden yeni para birimleri eklenebilir.
- Her para biriminin kendi adı, simgesi, ondalık sayısı, başlangıç ve en yüksek bakiyesi vardır.
- **format-locale: tr-TR** ile tutarlar Türk biçiminde gösterilir: **1.250,50**. Tutar yazarken **1k**, **2.5m** gibi kısaltmalar da kabul edilir.
- Hiçbir işlem bakiyeyi eksiye düşüremez veya en yüksek bakiyeyi aşamaz; başarısız işlem bütünüyle iptal olur.
- Her para hareketi silinmeyen bir işlem kaydına yazılır. **/dymarket kayit <oyuncu>** ile görüntülenir, **/dymarket gerial <no>** ile geri alınır (eksiye düşürecekse reddedilir; **zorla** ile sıfırda durur).
- **/ode**, **/kredi gonder** ve müzayede alımlarında dakika başına sınır, büyük ödemelerde onay (**/ode <oyuncu> <miktar> onayla**) ve yüksek tutarlı transferlerde oyun içinde ve konsolda yetkililere uyarı.
- Vault desteği: DyMarket'in parası Vault ekonomisi olarak sunulur; **DyHomes**, **DyRTP** ve diğer Vault eklentileri ücretleri bununla alır.

**Kredi Köprüsü — Web Mağazası ve RCON**

Türk sunucularında oyuncular krediyi genelde web sitesinden alır. DyMarket bu akışa doğrudan oturur. Mağazanızın veya panelinizin teslimat komutuna şunu yazmanız yeterli:

```text
dymarket give {player} {amount} credits {transaction_id}
```

- **İşlem numarası** sayesinde aynı satın alma **asla iki kez yüklenmez.** Mağaza komutu tekrar gönderirse DyMarket "zaten uygulandı" der, hiçbir şeyi değiştirmez ve hata vermez; RCON betikleri boşuna tekrar denemez.
- RCON, Tebex ve komut çalıştırabilen her web paneliyle çalışır. LeaderOS için ayrı bir adaptör ileride gelecek.
- **/kredi gonder <oyuncu> <miktar>** ile oyuncular birbirine kredi gönderebilir.
- **/kredi cevir <miktar>** ile kredi oyun parasına çevrilir (varsayılan: 1 kredi = 1000 para).
- İsteğe bağlı **yükleme bonusu** (`credits.topup-bonus-percent`) ve yükleme duyurusu.

**Müzayede Evi**

- **/pazar** (veya **/muzayede**, **/ah**) sandık menüsünü açar: sayfalar, sıralama, ilanlarım ve toplama kutusu.
- Menüdeki her işlemin komutu da vardır; menü kullanmayan oyuncular da her şeyi komutla yapabilir.
- **/pazar sat <fiyat> [para birimi] [adet]** elinizdeki eşyayı sabit fiyatla ilana koyar; ilanlar para veya kredi ile satılabilir.
- Süre (varsayılan 48 saat), vergi (varsayılan %5), fiyat sınırları, yasaklı eşya listesi ve oyuncu başına ilan sınırı (varsayılan 7, **0** = sınırsız) ayarlanabilir. Vergi, eşya ilana konduğu anda sabitlenir; sonradan ayarı değiştirmek açık ilanları etkilemez.
- Alım onayı açıkken (**auction.confirm-purchase**) menüde onay penceresi çıkar, komutla **/pazar satinal <no> onayla** yazılır.
- **Dupe'a karşı:** eşya ilan kaydedilmeden önce elinizden alınır, bir ilan yalnızca bir kez satılır, ödeme adımı başarısız olursa para iade edilir.
- Süresi dolan, iptal edilen, yetkilinin kaldırdığı veya envanter dolu olduğu için teslim edilemeyen eşyalar **/pazar topla** ile alınır. Çevrim dışıyken satılan ilanların haberi bir sonraki girişte gelir.
- Rütbeye göre ilan sınırı (**dymarket.ah.limit.<sayı>**, en yüksek geçerli) ve vergi oranı (**dymarket.ah.tax.<yüzde>**, en düşük geçerli).

**Emlak — DyClaim Arazisini Sat veya Kirala**

**DyClaim 1.3.1 veya üzeri** gerekir. DyClaim yoksa ya da eskiyse emlak modülü konsola tek satır yazıp kapanır; ekonominin geri kalanı çalışmaya devam eder.

- **/emlak sat <fiyat>** bulunduğunuz chunk'ın çevresindeki birbirine bitişik claim'lerinizi satışa koyar (en çok 64 chunk). Sonuna **burasi** (veya **here**) yazarsanız yalnızca o chunk ilana girer. **dymarket.estate.limit.<sayı>** verilmedikçe emlak ilanı sınırı yoktur; emlak vergisi ilan anında sabitlenir.
- Satın alındığında bütün claim'ler alıcıya devredilir. Bir devir başarısız olursa claim'ler geri döner ve para iade edilir.
- **/emlak kirala <fiyat> <gün>** araziyi kiraya verir. **/emlak tut** ile kiralayan oyuncu süreli DyClaim güveni alır (inşa, sandık, kapı, kızıltaş gibi haklar ayarlanabilir) ve ilan kira süresince başkalarına kapanır.
- **/emlak satinal**, **/emlak tut** ve **/emlak ayril** her zaman önce onay ister; sonuna **onayla** yazılır, menüde onay penceresi çıkar.
- Yeni kiralar otomatik yenileme açık başlar; **/emlak otoyenile** ile kapatılıp açılır. Ödenmezse ek süre (varsayılan 24 saat) başlar, sonunda güven kaldırılır ve kira biter. İki taraf da haberdar edilir, çevrim dışıysa bir sonraki girişte.
- **/emlak ayril** kiracının kirayı iadesiz bitirmesi, **/emlak tahliye** ev sahibinin ödenen süre sonunda kiracıyı çıkarmasıdır.
- Ev sahibi kiradaki ilanı kaldırırsa ya da claim DyClaim'de silinir veya devredilirse kira biter ve kalan sürenin iadesini (**refund-on-owner-cancel-percent**) ev sahibi öder. İadeyi ödeyemeyen ev sahibi ilanı kaldıramaz.
- **/emlak liste** sandık menüsünde ilanları gösterir (satılık için çimen bloğu, kiralık için meşe kapı).

**Kolay Geçiş**

- **/dymarket aktar essentials** EssentialsX bakiyelerini kopyalar.
- **/dymarket aktar vault** şu an Vault'ta kayıtlı ekonominin bakiyelerini kopyalar.
- DyMarket'te zaten sıfır olmayan hiçbir bakiyenin üzerine yazılmaz.

**Türkçe Komutlar ve Türkçe Destek**

DyMarket yalnızca Türkçe mesaj sunmaz; **Türkçe komutları ve alt komut kelimelerini de destekler.** Örneğin **/para**, **/ode**, **/zenginler**, **/kredi cevir**, **/pazar topla**, **/emlak kirala**, **/emlak tut** ve **/dymarket yenile** kullanılabilir. Türkçe harfli ve harfsiz yazım aynıdır (**yardım** = **yardim**). İngilizce karşılıklar da her zaman çalışır.

- Mesajlar **Türkçe, İngilizce, Almanca, İspanyolca ve Rusça**; dil, oyuncunun Minecraft dilinden otomatik seçilir.
- **/dymarket dil tr** ile kişisel dil seçilir, **/dymarket dil auto** ile otomatiğe dönülür.
- `&` renk kodları her yerde, MiniMessage etiketleri Paper, Purpur ve Folia'da çalışır.

**Bedrock Formları Yakında**

Bedrock oyuncuları için formlar (Floodgate) sonraki sürümlerde geliyor. O zamana kadar Bedrock oyuncuları müzayede ve emlak menüsündeki her işlemi komutla yapabilir. Teklifli müzayede, MySQL ve sunucular arası destek de planlananlar arasında.

---

**Komutlar**

| Komut | Açıklama |
|---|---|
| **/para [oyuncu] [para birimi]** | Bakiyenizi veya başka oyuncunun bakiyesini gösterir. (**/bakiye**, **/balance**) |
| **/ode <oyuncu> <miktar> [para birimi] [onayla]** | Oyuncuya para gönderir; büyük tutarda onay ister. (**/gonder**, **/pay**) |
| **/zenginler [para birimi] [sayfa]** | En zengin oyuncular. (**/paratop**, **/baltop**) |
| **/kredi** | Kredinizi gösterir. (**/credits**) |
| **/kredi gonder <oyuncu> <miktar>** | Oyuncuya kredi gönderir. |
| **/kredi cevir <miktar>** | Krediyi paraya çevirir. |
| **/pazar** | Müzayede evini açar. (**/muzayede**, **/ah**) |
| **/pazar sat <fiyat> [para birimi] [adet]** | Elinizdeki eşyayı ilana koyar. |
| **/pazar liste [sayfa]** | Aktif ilanlar. |
| **/pazar ara <metin>** | İlanlarda arar. |
| **/pazar bilgi <numara>** | Bir ilanı gösterir. |
| **/pazar satinal <numara> [onayla]** | İlanı satın alır. |
| **/pazar benim** | İlanlarınız. |
| **/pazar iptal <numara>** | İlanınızı iptal eder. |
| **/pazar topla** | Toplama kutusunu açar. |
| **/emlak sat <fiyat> [para birimi] [burasi]** | Arazinizi satışa koyar. (**/estate**) |
| **/emlak kirala <fiyat> <gün> [para birimi] [burasi]** | Arazinizi kiraya verir. |
| **/emlak kaldir [numara]** | İlanınızı kaldırır. |
| **/emlak liste [sayfa]** | Emlak ilanları. |
| **/emlak bilgi [numara]** | İlanı gösterir. |
| **/emlak satinal [numara] [onayla]** | Araziyi satın alır. |
| **/emlak tut [numara] [onayla]** | Araziyi kiralar. |
| **/emlak otoyenile [numara]** | Otomatik kira yenilemeyi açar veya kapatır. |
| **/emlak ayril [numara] [onayla]** | Kiranızı şimdi, iadesiz bitirir. |
| **/emlak tahliye [numara]** | Kiracınızı ödenen süre sonunda çıkarır. |
| **/emlak benim** | İlanlarınız ve kiralarınız. |
| **/dymarket dil <oto/en/tr/de/es/ru>** | Dil seçimi. |
| **/dymarket yardim** | Kullanabileceğiniz komutları gösterir. |

**Yönetici komutları:** **/dymarket ver**, **al**, **ayarla** (bakiye verme, alma, ayarlama), **kayit**, **gerial <no> [zorla]**, **birlestir**, **aktar**, **ah sil <no>**, **ah temizle <oyuncu>**, **estate kaldir <no>** ve **yenile**. Kısayollar: **/dym** ve **/dyma**.

---

**Yetkiler**

**Oyuncu yetkileri — varsayılan olarak herkese açık:** **dymarket.balance**, **dymarket.pay**, **dymarket.baltop**, **dymarket.credits**, **dymarket.credits.send**, **dymarket.credits.exchange**, **dymarket.ah**, **dymarket.ah.sell**, **dymarket.estate**, **dymarket.estate.sell**, **dymarket.estate.rent**.

**Yönetici yetkileri — varsayılan OP:** **dymarket.balance.others**, **dymarket.admin**, **dymarket.admin.economy**, **dymarket.admin.ah**, **dymarket.admin.estate**, **dymarket.admin.alerts** (şüpheli transfer ve güncelleme bildirimleri), ayrıca **dymarket.ah.limit.unlimited**, **dymarket.estate.limit.unlimited** ve **dymarket.bypass.ratelimit**.

**Rütbe yetkileri:** **dymarket.ah.limit.<sayı>** (en yüksek geçerli), **dymarket.estate.limit.<sayı>** (yoksa emlakta sınır yok), **dymarket.ah.tax.<yüzde>** (en düşük geçerli).

**Varsayılan olarak kimsede olmayanlar:** **dymarket.bypass.tax** (müzayede ve emlak vergisi yok) ve **dymarket.baltop.hidden** (oyuncuyu **/zenginler** listesinden gizler).

---

**İsteğe Bağlı Entegrasyonlar**

- **Vault:** DyMarket'in parasını diğer eklentilere sunmak veya başka bir ekonomiyi kullanmak.
- **DyClaim 1.3.1+:** Emlak modülü.
- **PlaceholderAPI:** `%dymarket_balance%`, `%dymarket_balance_formatted%`, `%dymarket_balance_<birim>%`, `%dymarket_balance_<birim>_formatted%`, `%dymarket_credits%`, `%dymarket_baltop_<n>_name%`, `%dymarket_baltop_<n>_amount%`, `%dymarket_baltop_<birim>_<n>_name%`, `%dymarket_baltop_<birim>_<n>_amount%`, `%dymarket_ah_listings%`, `%dymarket_ah_mine%` ve `%dymarket_estate_listings%`.
- **DyDiscord:** Tanınır, ancak DyDiscord'un henüz yetkili uyarısı API'si yok; bu yüzden uyarılar şimdilik oyunda ve konsolda kalır.

---

**Uyumluluk ve Kurulum**

**Sunucu platformları:** Bukkit, Spigot, Paper, Purpur ve **Folia** — tek JAR.

**Minecraft sürüm aralığı:** 1.20–26.3.

**Java:** Derleme hedefi Java 17'dir; sunucunuzun istediği Java sürümünü kullanın.

1. **DyMarket-1.0.0-BETA.jar** dosyasını **plugins** klasörüne koyun (Vault ve emlak için DyClaim 1.3.1+ ile birlikte) ve sunucuyu başlatın.
2. Başka bir ekonomi eklentisinden geçiyorsanız, onu kaldırmadan önce bir kez **/dymarket aktar essentials** veya **/dymarket aktar vault** çalıştırın.
3. Web mağazanızın teslimat komutunu `dymarket give {player} {amount} credits {transaction_id}` olarak ayarlayın.
4. **plugins/DyMarket/config.yml** içinden para birimlerini, müzayede vergisini ve kira ayarlarını düzenleyip **/dymarket yenile** çalıştırın.

Eksik ayar ve mesajlar açılışta eklenir, sizin değiştirdiklerinize dokunulmaz. Geçersiz bir ayar yenilemeyi reddeder ve çalışan ayarlar kalır. DyMarket toplu kullanım istatistikleri için **bStats** kullanır; **metrics.enabled** ile kapatılabilir.

---

**Bağlantılar**

- **Resmî site:** [Dynasty Plugins — DyMarket](https://dynastyplugins.com/dymarket)
- **Modrinth:** [DyMarket](https://modrinth.com/plugin/dymarket)
- **Hangar:** [DyMarket](https://hangar.papermc.io/Claww/DyMarket)
- **CurseForge:** [DyMarket](https://www.curseforge.com/minecraft/bukkit-plugins/dymarket)
- **GitHub:** [Kaynak kodu, sürümler ve hata bildirimleri](https://github.com/DyPlugin/DyMarket)
- **Discord:** [DyHub](https://discord.gg/dyhub)

DyMarket ücretsiz kullanılabilir. Kaynak kodu, **DyMarket Source-Available License 1.0** koşullarıyla incelenebilir. Kullanım ve dağıtım koşulları için depodaki lisans metnine bakabilirsiniz.

Hata bildirimlerinizi ve önerilerinizi GitHub veya Discord üzerinden paylaşabilirsiniz.

**Claww / DyPlugin**
