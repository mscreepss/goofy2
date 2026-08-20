package com.goofy.goofyaddons.features.bookflipper;

import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.event.ChatHook;
import com.goofy.goofyaddons.features.Feature;
import com.goofy.goofyaddons.features.bookflipper.helper.BazaarMonitor;
import com.goofy.goofyaddons.features.bookflipper.helper.Book;
import com.goofy.goofyaddons.features.bookflipper.helper.FlipCalculator;
import com.goofy.goofyaddons.features.bookflipper.helper.FlipItem;
import com.goofy.goofyaddons.utils.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;

import java.lang.reflect.Field;
import java.util.*;


/**
 * BazaarFlipper - tick tabanlı bazaar kitap flip makrosu.
 *
 * ÖKSÜZ PARÇA HAKKINDA (eski "temizlik modu" TAMAMEN kaldırıldı):
 * pendingCleanupNames / activeCleanupTask / calculateTopUpAmount / findBaseLevelEntry /
 * pruneStaleCleanupFlags mekanizmasının tamamı silindi. Onun yerine öksüz parçayı
 * ÜRETEN sebepler kapatıldı:
 *
 *  1) SAYIM DİSİPLİNİ (mal sahipliği): Kitaplar fiziksel olarak birbirinden ayırt
 *     edilemediği için sahiplik ADETLE tutulur. STORE artık envanterdeki TÜM
 *     eşleşen kitapları değil, yalnızca o görevin kendi sayısı (inInventory) kadar
 *     kitabı depolar; ANVIL de depodan yalnızca kendi sayısı (inEnderChest) kadar
 *     kitap çeker. Eskiden 2to5 görevi STORE'a düştüğünde, 1to5 zincirinin taze
 *     ürettiği Wisdom II'leri de kendi stoğu sanıp ender chest'e gömüyordu; zincir
 *     yarıda kalıyor ve o kitaplar öksüz kalıyordu.
 *     ÖNEMLİ: İki zincir birbirini BEKLEMEZ. 16xI tek başına 1 adet V, 8xII tek
 *     başına 1 adet V eder - her iki havuz da tek başına tam 2'nin kuvvetidir,
 *     dolayısıyla 1to5, 2to5'in siparişinin dolmasını beklemek zorunda değildir.
 *  2) ZİNCİR SIRASINDA YENİ SİPARİŞ YOK: Zincirdeki bir isim için processData
 *     yeni görev açmaz; yoksa gelen taban seviye kitaplar havuzu tek sayıya
 *     düşürür ve her katta bir öksüz doğurur.
 *  3) DOĞRU SİPARİŞ MİKTARI: Elde zaten duran ara seviye kitaplar (config'te kendi
 *     girdisi olmayan seviyeler, ör. sellLevel=5 ve config {1,2} iken III/IV)
 *     "birim" olarak sayılıp sipariş miktarından düşülür (III = 4 birim, IV = 8).
 *     Böylece havuz toplamı her zaman tam 2'nin kuvveti olur ve birleştirme
 *     sonunda artık kalmaz. Bu bir mod değil, sadece sipariş miktarı aritmetiği.
 *  4) SAYAÇ KAYMASI: COMBINE sayaçları her state değişiminde ve pause'da sıfırlanır
 *     (çekiçte unutulan kitap = havuzda eksik = öksüz), "Claimed" mesajı sadece
 *     OUTBID penceresinde dinlenir, eski sipariş kayıtlarından gelen outbid
 *     uyarıları zinciri kesemez, removeDuplicateBooks sadece SELL'deki görevleri
 *     siler (eskiden alım fazındaki kardeş görevi de siliyordu).
 */
public class BazaarFlipper implements Feature {
    private enum State {
        START,
        STARTUP_CHECK,
        IDLE,
        FETCHING,
        BAZAAR_NAVIGATION,
        OUTBID,
        STORE,
        ANVIL,
        COMBINE,
        SELL,
        REPLACE_SELL
    }

    private enum BookState {
        SELECTED,
        BUY_ORDER,
        OUTBID,
        STORE,
        ANVIL,
        COMBINE,
        SELL
    }

    /** Alım fazı: bu fazdayken o isim için birleştirme zinciri BAŞLAMAZ. */
    private static final EnumSet<BookState> BUY_PHASE = EnumSet.of(
            BookState.SELECTED, BookState.BUY_ORDER, BookState.OUTBID, BookState.STORE);

    /** Birleştirme/satış zinciri: bu fazdayken o isim için YENİ sipariş açılmaz. */
    private static final EnumSet<BookState> CHAIN_PHASE = EnumSet.of(
            BookState.ANVIL, BookState.COMBINE, BookState.SELL);

    public boolean enabled = false;


    private Clock clock = new Clock();
    private State state = State.IDLE;
    private State lastState = null;
    private List<FlipItem> flipItemsList = new ArrayList<>();
    private FlipCalculator flipCalculator = new FlipCalculator();
    private ScoreboardUtils scoreboardUtils = new ScoreboardUtils();
    private InventoryScanner inventoryScanner = new InventoryScanner();
    private Minecraft minecraft = Minecraft.getInstance();
    private BazaarMonitor bazaarMonitor = new BazaarMonitor();
    private int counter = 0;
    private boolean clickedOnce = false;
    private Book activeBook = null;
    private SplittableRandom splittableRandom = new SplittableRandom();
    private List<String> sellOrderName = new ArrayList<>();
    private boolean notEnoughCash = false;
    private boolean isInventoryFull = false;
    private boolean didRemoveOrder = false;
    private boolean claimedItems = false;
    private boolean didReceiveItems = false;
    private boolean firstStartUp = false;
    private int counterBazaar = 0;
    private boolean useSecondPage = false;
    private boolean secondPageCheck = false;
    private final Clock combineConfirmClock = new Clock();
    private boolean combineConfirmPending = false;


    private final Map<Book, Task> task = new LinkedHashMap<>();

    private void debug(String msg) {
        ChatUtils.debugMessage("[" + state + "] " + msg);
    }

    private void dumpTasks() {
        debug("----- TASK DUMP -----");
        for (Map.Entry<Book, Task> e : task.entrySet()) {
            Task t = e.getValue();
            debug(e.getKey().getRomanLevel(e.getKey().level())
                    + " state=" + t.getBookState()
                    + " remaining=" + t.getAmountToOrder()
                    + " inv=" + t.inInventory
                    + " ec=" + t.inEnderChest
                    + " credit=" + t.unitCredit
                    + " early=" + t.earlyAction);
        }
        debug("---------------------");
    }


    @Override
    public void start() {
        ChatUtils.clientMessage("BazaarFlipper: Started");
        if (minecraft.screen != null) {
            minecraft.player.closeContainer();
            debug("Container is open, closing");
        }
        firstStartUp = true;
        enabled = true;
        state = State.START;
    }

    public BazaarFlipper() {
        ChatHook.onMessage("filled", this::handleFilledMessage);
        ChatHook.onMessage("Claimed", this::handleClaimedMessage);
        bazaarMonitor.hook(this::handleOutbid);
    }

    @Override
    public String name() {
        return "BazaarFlipper";
    }

    @Override
    public void stop() {

        ChatUtils.clientMessage("BazaarFlipper: Stopped");

        task.clear();
        enabled = false;
        state = State.IDLE;
        lastState = null;
        flipItemsList.clear();
        activeBook = null;
        resetCombineCounters();
        clock.stop();
        bazaarMonitor.stop();
        bazaarMonitor.reset();
        isInventoryFull = false;
        didRemoveOrder = false;
        claimedItems = false;
        didReceiveItems = false;
        useSecondPage = false;
        secondPageCheck = false;

    }

    @Override
    public void pause() {
        enabled = false;
        // Duraklatma (ör. ScheduledReboot) sırasında çekiçte yarım kalmış bir
        // birleştirme olabilir; sayaçlar devam eden tura sızarsa bot 1 kitapla
        // "2 kitap kondu" sanıp çıktı slotuna basıyor ve o kitap havuzdan düşüyor.
        resetCombineCounters();
    }

    @Override
    public void resume() {
        enabled = true;
    }

    @Override
    public void onTick() {

        if (!enabled) return;

        bazaarMonitor.onTick();
        lastStateCheck();

        switch (state) {
            case START -> {
                debug("[START] Refreshing flipCalculator");
                flipCalculator.Refresh();
                ChatUtils.clientMessage("BazaarFlipper: [START] Switching to FETCHING");
                state = State.FETCHING;
                bazaarMonitor.start();
            }

            case STARTUP_CHECK -> {
                if (!isContainerOpen()) clock.start(randomizer());
                if (!isContainerOpen() && clock.shouldFire()) {
                    ChatUtils.clientMessage("BazaarFlipper: [STARTUP_CHECK] Started checks");
                    if (secondPageCheck) {
                        openEnderChest(true);
                        return;
                    }
                    openEnderChest(false);
                }

                if (containerCheck("Ender Chest") || containerCheck("Jumbo Backpack") || containerCheck("Greater Backpack")) clock.start(randomizer());
                if ((containerCheck("Ender Chest") || containerCheck("Jumbo Backpack") || containerCheck("Greater Backpack")) && clock.shouldFire()) {
                    List<Book> bookList = new ArrayList<>();
                    bookList.addAll(booksInState(BookState.SELECTED));

                    // Depoda (bu sayfada) duran, hiçbir görevin stoğu olmayan ara seviye
                    // artıkları birim olarak sipariş miktarından düş. Envanterdeki
                    // artıklar processData'da zaten düşüldüğü için burada SADECE
                    // container taranır - çift sayım olmasın.
                    creditLeftoverUnitsFromContainer(bookList);

                    for (Book book : bookList) {
                        debug("BazaarFlipper: [STARTUP_CHECK] book: " + book.name());
                        List<Integer> size = inventoryScanner.findLoreContainer(book.getRomanLevel(book.level()));
                        debug("BazaarFlipper: [STARTUP_CHECK] Found book: " + book.name() + " Amount: " + size.size() + "In Container");
                        task.get(book).addInEnderChest(size.size());
                        if (!secondPageCheck) {
                            size = inventoryScanner.findLoreInv(book.getRomanLevel(book.level()));
                            debug("BazaarFlipper: [STARTUP_CHECK] Found book: " + book.name() + " Amount: " + size.size() + "In Inventory");
                            task.get(book).addInInventory(size.size());
                        } else {
                            task.get(book).setShouldCheckSecondPage(true);
                        }


                        if (task.get(book).isCompleted()) {
                            editStateBook(book, BookState.ANVIL);
                            continue;
                        }

                        if (task.get(book).shouldStore()) {
                            editStateBook(book, BookState.STORE);
                            task.get(book).setEarlyStore(true);
                        }
                    }

                    if (secondPageCheck) {
                        debug("BazaarFlipper: [STARTUP_CHECK] Switching to IDLE, firstStartup = false");
                        firstStartUp = false;
                        state = State.IDLE;
                        minecraft.player.closeContainer();
                        return;
                    }
                    secondPageCheck = true;
                    minecraft.player.closeContainer();

                }
            }

            case IDLE -> {

                if (firstStartUp) {
                    debug("BazaarFlipper: [IDLE] switching to Startup checks");
                    state = State.STARTUP_CHECK;
                    return;
                }

                if (notEnoughCash) {
                    debug("notEnoughCash is true");
                    if (!task.isEmpty()) {
                        debug("BazaarFlipper: [IDLE] task isn't empty");
                        notEnoughCash = false;
                        return;
                    }
                    debug("Starting clock");
                    clock.start(60000);
                    if (clock.shouldFire()) {
                        debug("1 Minute clock ended, switching to REPLACE_SELL");
                        state = State.REPLACE_SELL;
                    }
                    return;
                }

                Book outbidBook = firstBookInState(BookState.OUTBID);
                if (outbidBook != null && !isInventoryFull) {
                    debug("Found outbid books, switching to OUTBID");
                    state = State.OUTBID;
                    didRemoveOrder = false;
                    didReceiveItems = false;
                    claimedItems = false;
                    counterBazaar = 0;
                    return;
                }

                Book selectedBook = firstBookInState(BookState.SELECTED);
                if (selectedBook != null) {
                    debug("Found selected books, switching to BAZAAR_NAVIGATION");
                    activeBook = selectedBook;
                    debug("Active book set to: " + activeBook);
                    state = State.BAZAAR_NAVIGATION;
                    return;
                }

                Book bookToStore = firstBookInState(BookState.STORE);
                if (bookToStore != null) {

                    state = State.STORE;
                    isInventoryFull = false;
                    useSecondPage = false;
                    return;
                }


                // Zincir, kardeş görevin (ör. 2to5) siparişinin dolmasını BEKLEMEZ.
                // Sadece o isme ait kitaplar şu anda envanterde depolanmayı bekliyorsa
                // (BookState.STORE) birkaç tick geciktirilir; yoksa birleştirme sırasında
                // üretilen ara seviye kitaplarla fiziksel olarak karışırlar. IDLE zaten
                // STORE'u ANVIL'den önce işlediği için bu gecikme birkaç tıklama sürer.
                List<Book> booksToAnvil = chainReadyBooksInState(BookState.ANVIL);
                if (booksToAnvil.isEmpty() && !booksInState(BookState.ANVIL).isEmpty()) {
                    debug("ANVIL kisa sureli bekliyor: ayni isimden depolanmayi bekleyen kitap var");
                    return;
                }

                if (!booksToAnvil.isEmpty()) {
                    isInventoryFull = false;
                    boolean shouldCheck = false;
                    for (Book book : booksToAnvil) {
                        if (task.get(book).shouldCheckEnderChest()) {
                            shouldCheck = true;
                            continue;
                        }

                        editStateBook(book, BookState.COMBINE);
                    }
                    if (shouldCheck) {
                        state = State.ANVIL;
                    } else {
                        state = State.COMBINE;
                    }

                }

            }

            case FETCHING -> {

                if (!flipItemsList.isEmpty()) {
                    processData();
                    state = State.IDLE;
                }

                clock.start(5000);
                if (clock.shouldFire()) flipItemsList = flipCalculator.getFlipItemsList();
            }

            case BAZAAR_NAVIGATION -> {
                if (!isContainerOpen()) clock.start(randomizer());
                if (!isContainerOpen() && clock.shouldFire()) {
                    debug("no container open, opening bazaar for " + activeBook.name());
                    openBazaar(activeBook.name().replace("Ultimate", ""));
                }

                if (containerCheck("Bazaar")) clock.start(randomizer());
                if (containerCheck("Bazaar") && clock.shouldFire()) {
                    List<Integer> slots = inventoryScanner.findContainer(activeBook.getRomanLevel(activeBook.level()));
                    debug("Bazaar open, clicking slot " + slots + " for " + activeBook.getRomanLevel(activeBook.level()));
                    if (slots.isEmpty()) return;
                    InventoryUtils.clickSlot(slots.getFirst(), false);
                }

                if (containerCheck(activeBook.name())) clock.start(randomizer());
                if (containerCheck(activeBook.name()) && clock.shouldFire()) {
                    debug("book container open, clicking slot 15");
                    InventoryUtils.clickSlot(15, false);
                }

                if (containerCheck("How many do you want")) clock.start(randomizer());
                if (containerCheck("How many do you want") && clock.shouldFire()) {
                    debug("qty prompt open, clicking slot 16");
                    InventoryUtils.clickSlot(16, false);
                }
                if (minecraft.screen instanceof SignEditScreen) clock.start(randomizer());
                if (minecraft.screen instanceof SignEditScreen && clock.shouldFire()) {
                    debug("sign screen detected, handling sign");
                    handleSign();
                }

                if (containerCheck("How much do you want to pay")) clock.start(randomizer());
                if (containerCheck("How much do you want to pay") && clock.shouldFire()) {
                    debug("clicking slot 12 to confirm price, book=" + activeBook);
                    bazaarMonitor.add(activeBook, inventoryScanner.getUnitPrice(12), false);
                    InventoryUtils.clickSlot(12, false);
                }

                if (containerCheck("Confirm")) clock.start(randomizer());
                if (containerCheck("Confirm") && clock.shouldFire()) {
                    debug("confirming buy order for " + activeBook);
                    InventoryUtils.clickSlot(13, false);
                    if (shouldStore(activeBook)) {
                        editStateBook(activeBook, BookState.STORE);
                        state = State.IDLE;
                        return;
                    }
                    editStateBook(activeBook, BookState.BUY_ORDER);
                    state = State.IDLE;

                }

            }

            case OUTBID -> {
                if (!isContainerOpen()) clock.start(randomizer());
                if (!isContainerOpen() && clock.shouldFire()) {
                    debug("no container, opening bazaar for Wise");
                    openBazaar("Wise");
                }

                if (containerCheck("Wise")) clock.start(randomizer());
                if (containerCheck("Wise") && clock.shouldFire()) {
                    debug("Wise open, clicking slot 50");
                    InventoryUtils.clickSlot(50, false);
                }

                if (containerCheck("Bazaar")) clock.start(randomizer());
                if (containerCheck("Bazaar") && clock.shouldFire()) {

                    Book bookToHandle = firstBookInState(BookState.OUTBID);

                    if (bookToHandle == null) {
                        minecraft.player.closeContainer();
                        state = State.IDLE;
                        return;
                    }

                    if (claimedItems) {
                        if (didReceiveItems) {
                            claimedItems = false;
                            didReceiveItems = false;
                            return;
                        }
                        return;
                    }


                    List<Integer> slots = inventoryScanner.findContainer("BUY " + bookToHandle.getRomanLevel(bookToHandle.level()));
                    debug("found " + slots.size() + " slots for " + bookToHandle);

                    if (slots.isEmpty()) {
                        if (!task.get(bookToHandle).isCompleted() && !didRemoveOrder && counterBazaar < 3) {
                            counterBazaar++;
                            return;
                        }

                        // Sipariş ortada yok (doldu ya da iptal edildi): izlemeyi bırak.
                        // Eskiden monitor kaydı kalıyordu ve saatler sonra sahte bir
                        // outbid uyarısı gönderip görevi birleştirme zincirinin
                        // ortasında OUTBID'e atıyordu - havuz bölünüp öksüz doğuyordu.
                        bazaarMonitor.finish(bookToHandle);

                        editStateBook(bookToHandle, task.get(bookToHandle).isCompleted() ? BookState.ANVIL : BookState.SELECTED);
                        didRemoveOrder = false;
                        counterBazaar = 0;
                        return;

                    }


                    if (!slots.isEmpty()) {
                        int amount = inventoryScanner.checkOrder(slots.getFirst());
                        debug("order amount=" + amount + ", clicking slot " + slots.getFirst());
                        if (amount > inventoryScanner.getEmptyInventorySlots()) {
                            task.get(bookToHandle).setEarlyAction(true);
                            editStateBook(bookToHandle, BookState.STORE);
                            state = State.STORE;
                            isInventoryFull = true;
                            minecraft.player.closeContainer();
                            return;
                        }
                        InventoryUtils.clickSlot(slots.getFirst(), false);
                        if (amount == 0) {
                            debug("amount=0, returning early");
                            return;
                        }

                        claimedItems = true;


                        task.get(bookToHandle).addInInventory(amount);
                    }
                }

                if (containerCheck("Order")) clock.start(randomizer());
                if (containerCheck("Order") && clock.shouldFire()) {
                    didRemoveOrder = true;
                    List<Integer> slot = inventoryScanner.findContainer("Cancel Order");
                    if (slot.isEmpty()) return;
                    debug("Order screen open, clicking slot " + slot.getFirst());
                    InventoryUtils.clickSlot(slot.getFirst(), false);

                }
            }

            case STORE -> {
                if (!isContainerOpen()) clock.start(randomizer());
                if (!isContainerOpen() && clock.shouldFire()) {
                    debug("no container, opening ender chest");
                    if (useSecondPage) {
                        openEnderChest(true);
                        return;
                    }
                    openEnderChest(false);

                }

                    if (containerCheck("Ender Chest") || containerCheck("Jumbo Backpack") || containerCheck("Greater Backpack")) clock.start(speedMode());
                    if ((containerCheck("Ender Chest") || containerCheck("Jumbo Backpack") || containerCheck("Greater Backpack")) && clock.shouldFire()) {
                    Book bookToHandle = firstBookInState(BookState.STORE);

                    if (bookToHandle == null) {
                        minecraft.player.closeContainer();
                        state = State.IDLE;
                        return;
                    }

                    Task storeTask = task.get(bookToHandle);
                    List<Integer> slots = new ArrayList<>();
                    slots.addAll(inventoryScanner.findLoreInv(bookToHandle.getRomanLevel(bookToHandle.level())));

                    // SAYIM DİSİPLİNİ: envanterdeki her eşleşen kitabı değil, SADECE bu
                    // görevin kendi sayısı kadarını depola. ESKİ KOD hepsini gömüyordu;
                    // 1to5 zinciri Wisdom II üretmişken 2to5 görevi STORE'a düşerse o
                    // kitaplar da depoya gidiyor, zincir yarıda kalıyor ve öksüz parça
                    // doğuyordu. Kitaplar birbirinin aynı olduğu için hangi fiziksel
                    // kitabın taşındığı önemsiz; önemli olan ADEDİN doğru olması.
                    if (!slots.isEmpty() && storeTask.inInventory > 0) {
                        if (inventoryScanner.getEmptyContainerSlots() == 0) {
                            useSecondPage = true;
                            storeTask.setShouldCheckSecondPage(true);
                            minecraft.player.closeContainer();
                            return;
                        }

                        InventoryUtils.clickSlot(slots.getFirst(), true);
                        debug("storing " + bookToHandle.name() + " at slot " + slots.getFirst() + " (kalan kendi payi: " + (storeTask.inInventory - 1) + ")");
                        storeTask.addInInventory(-1);
                        storeTask.addInEnderChest(1);
                        return;
                    }

                    // Kendi payımız bitti (ya da envanterde bu kitaptan kalmadı).
                    if (storeTask.isEarlyAction()) {
                        editStateBook(bookToHandle, BookState.OUTBID);
                        storeTask.setEarlyAction(false);
                        return;
                    }

                    if (storeTask.isEarlyStore()) {
                        editStateBook(bookToHandle, BookState.SELECTED);
                        storeTask.setEarlyStore(false);
                        return;
                    }

                    editStateBook(bookToHandle, BookState.BUY_ORDER);
                    debug("kendi payimiz depolandi, BUY_ORDER'a geciliyor");
                }
            }

            case ANVIL -> {
                Book bookToHandle = chainReadyBookInState(BookState.ANVIL);

                if (bookToHandle == null) {
                    minecraft.player.closeContainer();
                    state = State.COMBINE;
                    return;
                }

                if (!containerCheck("Ender Chest") && !containerCheck("Jumbo Backpack") && !containerCheck("Greater Backpack")) clock.start(randomizer());
                if (!containerCheck("Ender Chest") && !containerCheck("Jumbo Backpack") && !containerCheck("Greater Backpack") && clock.shouldFire()) {
                    debug("no ender chest, opening it");
                    if (task.get(bookToHandle).isShouldCheckSecondPage()) {
                        openEnderChest(true);
                        return;
                    }
                    openEnderChest(false);

                }

                    if (containerCheck("Ender Chest") || containerCheck("Jumbo Backpack") || containerCheck("Greater Backpack")) clock.start(speedMode());
                    if ((containerCheck("Ender Chest") || containerCheck("Jumbo Backpack") || containerCheck("Greater Backpack")) && clock.shouldFire()) {
                    Task currentTask = task.get(bookToHandle);
                    List<Integer> slots = new ArrayList<>();

                    slots.addAll(inventoryScanner.findLoreContainer(bookToHandle.getRomanLevel(bookToHandle.level())));

                    // Depoda kaç tane olduğu değil, BİZİM kaç tane çekeceğimiz önemli:
                    // slots listesi bir önceki turdan kalmış ya da başka bir göreve ait
                    // kitapları da içerebilir, hepsini sığdırmaya çalışıp boşuna
                    // COMBINE'a kaçmayalım.
                    int booksToPull = Math.min(slots.size(), Math.max(0, currentTask.inEnderChest));

                    if (booksToPull > inventoryScanner.getEmptyInventorySlots()) {
                        state = State.COMBINE;
                        minecraft.player.closeContainer();
                        return;
                    }

                    debug("found " + slots.size() + " book slots in ender chest, kendi payimiz: " + booksToPull);

                    // SAYIM DİSİPLİNİ: depodaki her eşleşen kitabı değil, SADECE bu
                    // görevin kendi sayısı (inEnderChest) kadarını çek. Aksi halde
                    // kardeş görevin (ör. 2to5) depodaki stoğu da havuza karışıyor,
                    // toplam 2'nin kuvveti olmaktan çıkıyor ve zincir sonunda artık
                    // (öksüz parça) kalıyordu.
                    if (!slots.isEmpty() && currentTask.inEnderChest > 0) {
                        debug("pulling slot " + slots.getFirst() + " from ender chest (kalan kendi payi: " + (currentTask.inEnderChest - 1) + ")");
                        InventoryUtils.clickSlot(slots.getFirst(), true);
                        currentTask.addInInventory(1);
                        currentTask.addInEnderChest(-1);
                        return;
                    }

                    // Taban seviye bitti. Depoda bu isme ait, hiçbir görevin stoğu
                    // olmayan ara seviye artık varsa (III/IV) onları da envantere al -
                    // birleştirme havuzuna katılsınlar, depoda çürümesinler. Sayaçlara
                    // dokunulmaz, çünkü bu artıklar sipariş miktarından zaten düşüldü.
                    List<Integer> leftovers = leftoverContainerSlots(bookToHandle);
                    if (!leftovers.isEmpty() && inventoryScanner.getEmptyInventorySlots() > 0) {
                        debug("pulling leftover intermediate book from slot " + leftovers.getFirst());
                        InventoryUtils.clickSlot(leftovers.getFirst(), true);
                        return;
                    }

                    // Bu sayfada bir şey kalmadı ama depoda hâlâ bekleyen kitap var:
                    // diğer sayfaya geç. (ESKİ KOD burada "inInventory == amountToOrder"
                    // eşitliğine bakıyordu; sayaç hedefi bir kez aşınca bu eşitlik asla
                    // tutmuyor ve bot depoyu boşaltana kadar kitap çekiyordu - havuz
                    // tek sayıya kayıp öksüz parça üretiyordu.)
                    if (currentTask.isShouldCheckSecondPage() && currentTask.inEnderChest > 0) {
                        minecraft.player.closeContainer();
                        currentTask.setShouldCheckSecondPage(false);
                        return;
                    }

                    editStateBook(bookToHandle, BookState.COMBINE);
                }
            }

            case COMBINE -> {

                Book bookToHandle = chainReadyBookInState(BookState.COMBINE);

                if (bookToHandle == null) {
                    state = State.SELL;
                    minecraft.player.closeContainer();
                    return;
                }

                int level = 0;
                for (int i = bookToHandle.level(); i < bookToHandle.sellLevel(); i++) {
                    if (inventoryScanner.locate(bookToHandle.getRomanLevel(i)).size() >= 2) {
                        level = i;
                        break;
                    }
                }

                if (!containerCheck("Anvil")) clock.start(randomizer());
                if (!containerCheck("Anvil") && clock.shouldFire()) {
                    debug("no anvil open, opening it");
                    openAnvil();
                }

                if (containerCheck("Anvil") && counter < 2) clock.start(speedMode());
                if (containerCheck("Anvil") && counter < 2 && clock.shouldFire()) {
                    if (level == 0) {
                        // "Birleştirilecek çift yok" != "kitap satış seviyesinde hazır".
                        // Önce gerçekten envanterde satış seviyesinde kitap var mı bak.
                        if (!inventoryScanner.locate(bookToHandle.getRomanLevel(bookToHandle.sellLevel())).isEmpty()) {
                            debug("no pair to combine, sell-level copy confirmed in inventory, switching to SELL");
                            editStateBook(bookToHandle, BookState.SELL);
                        } else if (task.get(bookToHandle).inEnderChest > 0) {
                            debug("no pair to combine AND no sell-level copy found for " + bookToHandle.name() + ", sending back to ANVIL to recheck ender chest");
                            editStateBook(bookToHandle, BookState.ANVIL);
                        } else {
                            // Buraya normalde HİÇ düşülmemeli: havuz her zaman 2'nin
                            // kuvveti olacak şekilde sipariş ediliyor ve zincir kilidi
                            // sayesinde ortasından mal çekilmiyor. Yine de düşüldüyse
                            // fiziksel bir kayıp var demektir (relog, manuel müdahale,
                            // envanter dolduğu için yarım kalan zincir...). Sonsuz
                            // döngüye girmemek için görevi bırakıyoruz; kalan parçalar
                            // makro yeniden başlatıldığında STARTUP_CHECK tarafından
                            // birim olarak sayılıp sipariş miktarından düşülecek.
                            ChatUtils.clientMessage(bookToHandle.name() + " icin havuz eksik kaldi, gorev birakiliyor. Kalan ara seviye kitaplar makro yeniden baslatildiginda siparis miktarindan dusulecek.");
                            debug("dead end for " + bookToHandle.name() + " - physical shortage, dropping task");
                            task.remove(bookToHandle);
                        }
                        return;
                    }


                    List<Integer> book = inventoryScanner.findLoreInv(bookToHandle.getRomanLevel(level));

                    if (!book.isEmpty()) {
                        if (inventoryScanner.findMisMatch(bookToHandle.getRomanLevel(level))) {
                            // Çekiçten çıkıyoruz: içeride yarım kalmış kitap olabilir.
                            // Sayaçları sıfırla ki bir sonraki turda "2 kitap kondu"
                            // sanıp tek kitapla çıktıya basmayalım.
                            resetCombineCounters();
                            minecraft.player.closeContainer();
                            return;
                        }
                        counter++;
                        InventoryUtils.clickSlot(book.getFirst(), true);
                        return;
                    } else {
                        List<Integer> bookInContainer = inventoryScanner.findLoreContainer(bookToHandle.getRomanLevel(level));
                        if (bookInContainer.size() >= 2) counter++;
                    }
                }

                // Sayıcı 2'ye ulaştıysa (2 kitap da konduysa) ve onay beklenmiyorsa saati başlat
                if (counter == 2 && !combineConfirmPending) {
                    combineConfirmClock.start(speedMode());
                    combineConfirmPending = true;
                }

                // Onay bekleniyorsa ve saatin süresi dolduysa tıklama işlemini yap
                if (counter == 2 && combineConfirmPending && combineConfirmClock.shouldFire()) {
                    debug("counter==2, clicking anvil output slot 22 with normal click");
                    InventoryUtils.clickSlot(22, false);

                    if (clickedOnce) {
                        clickedOnce = false;
                        counter = 0;
                        combineConfirmPending = false;
                        return;
                    }
                    clickedOnce = true;
                    combineConfirmClock.start(speedMode());
                }
            }

            case SELL -> {
                List<Integer> slots = new ArrayList<>();
                List<Book> bookList = (booksInState(BookState.SELL));
                if (bookList.isEmpty()) {
                    debug("bookstoSell empty, switching to IDLE");
                    state = State.FETCHING;
                    return;
                }
                if (!isContainerOpen()) clock.start(randomizer());
                if (!isContainerOpen() && clock.shouldFire()) {
                    debug("no container, opening bazaar for tomato");
                    openBazaar("tomato");
                }

                if (containerCheck("tomato")) clock.start(randomizer());
                if (containerCheck("tomato") && clock.shouldFire()) {
                    debug("tomato bazaar open, clicking slot 50");
                    InventoryUtils.clickSlot(50, false);
                }

                if (containerCheck("Bazaar")) clock.start(randomizer());
                if (containerCheck("Bazaar") && clock.shouldFire()) {

                    for (Book book : bookList) {
                        // ESKİ KOD: getRomanLevel(5) sabitti; sellLevel 5 değilse satış
                        // slotu hiç bulunamıyor ve kitap sonsuza kadar elde kalıyordu.
                        slots.addAll(inventoryScanner.findContainer("SELL " + book.getRomanLevel(book.sellLevel())));
                    }
                    debug("found " + slots.size() + " sell slots");

                    if (!slots.isEmpty()) {
                        debug("clicking sell slot " + slots.getFirst());
                        InventoryUtils.clickSlot(slots.getFirst(), false);
                    }
                    if (slots.isEmpty()) {
                        debug("no slots found, clicking on: " + bookList.getFirst().name());
                        List<Integer> slot = inventoryScanner.findLoreInv(bookList.getFirst().getRomanLevel(bookList.getFirst().sellLevel()));
                        if (slot.isEmpty()) {
                            // bookList her tick'te yeniden üretilen GEÇİCİ bir liste;
                            // ondan silmek gerçek durumu değiştirmiyordu ve aynı kitap
                            // sonsuz döngüye giriyordu. Gerçek durumu ANVIL'e çekiyoruz.
                            debug("sell-level copy not found for " + bookList.getFirst().name() + ", sending back to ANVIL to recheck ender chest");
                            editStateBook(bookList.getFirst(), BookState.ANVIL);
                            bookList.removeFirst();
                            return;
                        }
                        InventoryUtils.clickSlot(slot.getFirst(), false);
                    }
                }

                if (containerCheck("Order")) clock.start(randomizer());
                if (containerCheck("Order") && clock.shouldFire()) {
                    List<Integer> slot = inventoryScanner.findContainer("Cancel Order");
                    if (slot.isEmpty()) return;
                    debug("Order screen open, clicking slot " + slot.getFirst());
                    InventoryUtils.clickSlot(slot.getFirst(), false);
                }

                if (!bookList.isEmpty() && containerCheck(bookList.getFirst().name())) clock.start(randomizer());
                if (!bookList.isEmpty() && containerCheck(bookList.getFirst().name()) && clock.shouldFire()) {
                    debug("book screen open, clicking slot 16");
                    InventoryUtils.clickSlot(16, false);
                }

                if (containerCheck("At what price are you selling")) clock.start(randomizer());
                if (containerCheck("At what price are you selling") && clock.shouldFire()) {
                    debug("price prompt, clicking slot 12");
                    InventoryUtils.clickSlot(12, false);
                }

                if (containerCheck("Confirm")) clock.start(randomizer());
                if (containerCheck("Confirm") && clock.shouldFire()) {
                    Book soldBook = bookList.getFirst();
                    debug("confirm prompt, clicking slot 13 and removing " + soldBook + " from sell list");
                    InventoryUtils.clickSlot(13, false);

                    Task soldTask = task.get(soldBook);
                    if (soldTask != null && soldTask.getAmountToOrder() < 0) {
                        soldTask.addInInventory(-soldBook.getQtyAmount(soldBook.level()));
                        editStateBook(soldBook, BookState.SELECTED);
                        return;
                    }

                    // Aynı isimden birden fazla görev aynı anda satış seviyesine
                    // ulaştıysa (1to5 + 2to5 havuzu birlikte birleştiği için normal),
                    // tek satış emri hepsini kapsar; o görevleri toplu kaldır.
                    removeDuplicateBooks(task);
                    task.remove(soldBook);
                    bookList.removeFirst();

                }
            }

            case REPLACE_SELL -> {
                if (!isContainerOpen()) clock.start(randomizer());
                if (!isContainerOpen() && clock.shouldFire()) {
                    debug("no container, opening bazaar for tomato");
                    openBazaar("tomato");
                }

                if (containerCheck("tomato")) clock.start(randomizer());
                if (containerCheck("tomato") && clock.shouldFire()) {
                    debug("tomato bazaar open, clicking slot 50");
                    InventoryUtils.clickSlot(50, false);
                }

                if (containerCheck("Bazaar")) clock.start(randomizer());
                if (containerCheck("Bazaar") && clock.shouldFire()) {
                    List<Integer> slots = new ArrayList<>();

                    slots.addAll(inventoryScanner.getSellOrder());
                    if (slots.isEmpty()) {
                        List<Integer> slot = new ArrayList<>();
                        for (String string : sellOrderName) {
                            slot.addAll(inventoryScanner.findLoreInv(string));
                        }

                        if (!slot.isEmpty()) {
                            InventoryUtils.clickSlot(slot.getFirst(), false);
                            return;
                        }

                        state = State.FETCHING;
                        minecraft.player.closeContainer();
                        return;

                    }

                    sellOrderName.add(inventoryScanner.getName(slots.getFirst()).replace("SELL ", ""));

                    InventoryUtils.clickSlot(slots.getFirst(), false);

                }

                if (containerCheck("Order")) clock.start(randomizer());
                if (containerCheck("Order") && clock.shouldFire()) {
                    List<Integer> slot = inventoryScanner.findContainer("Cancel Order");
                    if (slot.isEmpty()) return;
                    debug("Order screen open, clicking slot " + slot.getFirst());
                    InventoryUtils.clickSlot(slot.getFirst(), false);
                }

                if (!sellOrderName.isEmpty() && containerCheck(sellOrderName.getFirst())) clock.start(randomizer());
                if (!sellOrderName.isEmpty() && containerCheck(sellOrderName.getFirst()) && clock.shouldFire()) {
                    debug("book screen open, clicking slot 16");
                    InventoryUtils.clickSlot(16, false);
                }

                if (containerCheck("At what price are you selling")) clock.start(randomizer());
                if (containerCheck("At what price are you selling") && clock.shouldFire()) {
                    debug("price prompt, clicking slot 12");
                    InventoryUtils.clickSlot(12, false);
                }

                if (containerCheck("Confirm")) clock.start(randomizer());
                if (containerCheck("Confirm") && clock.shouldFire()) {
                    debug("confirm prompt, clicking slot 13 and removing " + sellOrderName.getFirst() + " from sell list");
                    InventoryUtils.clickSlot(13, false);
                    sellOrderName.clear();
                    state = State.FETCHING;

                }


            }
        }
    }

    public String getStateName() {
        return state.name();
    }

    public String getActiveBookName() {
        return activeBook != null ? activeBook.getRomanLevel(activeBook.level()) : "-";
    }

    public List<String> getTaskSummary() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            Book book = entry.getKey();
            Task t = entry.getValue();
            lines.add(book.getRomanLevel(book.level()) + ": " + t.getBookState()
                    + " (remaining=" + t.getAmountToOrder() + ")");
        }
        return lines;
    }

    private boolean shouldStore(Book book) {
        return task.get(book).shouldStore();
    }

    private void resetCombineCounters() {
        counter = 0;
        clickedOnce = false;
        combineConfirmPending = false;
        combineConfirmClock.stop();
    }

    private void lastStateCheck() {
        if (state != lastState) {
            debug("state changed: " + lastState + " -> " + state);
            clock.stop();
            // COMBINE yarıda kesildiyse (SELL'e geçiş, outbid, mismatch, reboot...)
            // sayaçlar bir sonraki tura sızmasın.
            resetCombineCounters();
            lastState = state;
        }
    }

    private List<Book> booksInState(BookState target) {
        List<Book> result = new ArrayList<>();
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            if (entry.getValue().getBookState() == target) result.add(entry.getKey());
        }
        return result;
    }

    private List<Book> booksInState(BookState target, BookState target2) {
        List<Book> result = new ArrayList<>();
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            if (entry.getValue().getBookState() == target) result.add(entry.getKey());
        }

        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            if (entry.getValue().getBookState() == target2) result.add(entry.getKey());
        }
        return result;
    }


    private void editStateBook(Book book, BookState target) {
        Task t = task.get(book);
        if (t == null) {
            debug("Attempted state change for missing task: " + book);
            return;
        }
        BookState old = t.getBookState();
        t.setBookState(target);
        debug("Book state changed: " + book + " | " + old + " -> " + target
                + " remaining=" + t.getAmountToOrder()
                + " inv=" + t.inInventory
                + " ec=" + t.inEnderChest);
        dumpTasks();
    }

    private Book firstBookInState(BookState target) {
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            if (entry.getValue().getBookState() == target) return entry.getKey();
        }
        return null;
    }

    /**
     * Birleştirme zinciri, KARDEŞ GÖREVİN SİPARİŞİNİ BEKLEMEZ - her görev tek başına
     * zaten tam 2'nin kuvveti kadar birim toplar (16xI -> 1 adet V, 8xII -> 1 adet V).
     * Tek beklediği şey, o isme ait kitapların envanterde depolanmayı bekliyor
     * olmaması: aksi halde birleştirme sırasında üretilen ara seviye kitaplarla
     * fiziksel olarak karışırlar. IDLE zaten STORE'u ANVIL'den önce işlediği için
     * bu bekleme birkaç tıklama sürer, sipariş dolmasını beklemez.
     */
    private Book chainReadyBookInState(BookState target) {
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            if (entry.getValue().getBookState() != target) continue;
            if (hasUnstoredBooksForName(entry.getKey().name())) continue;
            return entry.getKey();
        }
        return null;
    }

    private List<Book> chainReadyBooksInState(BookState target) {
        List<Book> result = new ArrayList<>();
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            if (entry.getValue().getBookState() != target) continue;
            if (hasUnstoredBooksForName(entry.getKey().name())) continue;
            result.add(entry.getKey());
        }
        return result;
    }

    /** Bu isme ait, şu an envanterde depolanmayı bekleyen kitap var mı? */
    private boolean hasUnstoredBooksForName(String name) {
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            if (!entry.getKey().name().equals(name)) continue;
            if (entry.getValue().getBookState() != BookState.STORE) continue;
            if (entry.getValue().inInventory > 0) return true;
        }
        return false;
    }

    /** Bu isim şu an birleştirme/satış zincirinde mi? */
    private boolean isNameInChain(String name) {
        for (Map.Entry<Book, Task> entry : task.entrySet()) {
            if (!entry.getKey().name().equals(name)) continue;
            if (CHAIN_PHASE.contains(entry.getValue().getBookState())) return true;
        }
        return false;
    }

    private void removeDuplicateBooks(Map<Book, Task> tasks) {
        Map<String, Integer> counts = new HashMap<>();
        List<Book> stateBooks = new ArrayList<>();

        stateBooks.addAll(booksInState(BookState.SELL));

        for (Book book : stateBooks) {
            if (task.get(book).getAmountToOrder() < 0) continue;
            counts.merge(book.name(), 1, Integer::sum);
        }

        // ESKİ KOD: isim sayısı >1 ise o isimden TÜM görevleri siliyordu - alım
        // fazındaki (kitapları çoktan satın alınmış) kardeş görev de siliniyor ve
        // o kitaplar sahipsiz kalıyordu. Artık sadece SELL'deki görevler silinir.
        tasks.entrySet().removeIf(entry ->
                entry.getValue().getBookState() == BookState.SELL
                        && counts.getOrDefault(entry.getKey().name(), 0) > 1
        );
    }

    /**
     * Config'te bu isim için tanımlı seviyeler (ör. "Ultimate Wise" -> {1, 2}).
     * Bu seviyelerdeki kitaplar bir görevin meşru stoğudur, artık değildir.
     */
    private Set<Integer> configuredLevelsFor(String name) {
        Set<Integer> levels = new HashSet<>();
        for (Book b : GoofyConfig.INSTANCE.books) {
            if (!b.name().equals(name)) continue;
            levels.add(b.level());
        }
        return levels;
    }

    /** Bu kitap, ismi için config'te tanımlı EN DÜŞÜK seviye mi? (artık kredisi ona yazılır) */
    private boolean isLowestConfiguredLevel(Book book) {
        for (Book b : GoofyConfig.INSTANCE.books) {
            if (!b.name().equals(book.name())) continue;
            if (b.level() < book.level()) return false;
        }
        return true;
    }

    /**
     * Taban ile satış seviyesi arasında kalan ve config'te KENDİ girdisi olmayan
     * seviyeler. Bu seviyelerdeki kitaplar hiçbir görevin stoğu değildir; önceki
     * turlardan kalmışlardır (sellLevel=5 ve config {1,2} iken: 3 ve 4).
     */
    private List<Integer> leftoverLevels(Book book) {
        Set<Integer> configured = configuredLevelsFor(book.name());
        List<Integer> levels = new ArrayList<>();
        for (int i = book.level() + 1; i < book.sellLevel(); i++) {
            if (configured.contains(i)) continue;
            levels.add(i);
        }
        return levels;
    }

    /** Envanterdeki artıkların TABAN SEVİYE cinsinden birim değeri (III = 4, IV = 8...). */
    private int leftoverUnitsInInventory(Book book) {
        int units = 0;
        for (int i : leftoverLevels(book)) {
            int count = inventoryScanner.findLoreInv(book.getRomanLevel(i)).size();
            if (count == 0) continue;
            units += count * (1 << (i - book.level()));
        }
        return units;
    }

    /** Depoda (açık olan sayfada) duran artık kitapların slotları. */
    private List<Integer> leftoverContainerSlots(Book book) {
        List<Integer> slots = new ArrayList<>();
        for (int i : leftoverLevels(book)) {
            slots.addAll(inventoryScanner.findLoreContainer(book.getRomanLevel(i)));
        }
        return slots;
    }

    /**
     * Depodaki artıkları birim olarak sipariş miktarından düşer. Sadece o ismin
     * en düşük seviyeli görevine yazılır ki iki paralel görev aynı artığı iki kez
     * saymasın. Envanterdeki artıklar processData'da düşüldüğü için burada
     * yalnızca container taranır.
     */
    private void creditLeftoverUnitsFromContainer(List<Book> bookList) {
        for (Book book : bookList) {
            if (!isLowestConfiguredLevel(book)) continue;
            Task t = task.get(book);
            if (t == null) continue;

            int units = 0;
            for (int i : leftoverLevels(book)) {
                int count = inventoryScanner.findLoreContainer(book.getRomanLevel(i)).size();
                if (count == 0) continue;
                units += count * (1 << (i - book.level()));
            }
            if (units == 0) continue;

            t.addUnitCredit(units);
            debug(book.name() + " icin depoda " + units + " birimlik ara seviye kitap bulundu, siparis miktarindan dusuldu");
        }
    }

    private void processData() {
        if (flipItemsList.isEmpty()) return;
        debug("item check passed");
        double purse = scoreboardUtils.getPurse();
        debug("purse = " + purse);

        double cost = flipItemsList.stream().mapToDouble(FlipItem::totalCost).min().orElse(-1);

        if (cost != -1) {
            if (cost > purse) {
                notEnoughCash = true;
            }
        }

        for (FlipItem flipItem : flipItemsList) {
            Book book = flipItem.book();
            debug("Checking Flipitem " + book.name());

            if (task.containsKey(book)) continue;

            // Bu isim birleştirme/satış zincirindeyken YENİ sipariş açma: gelen taban
            // seviye kitaplar zincirin ortasına karışıp havuzu tek sayıya düşürüyor,
            // her katta bir öksüz parça doğuyordu.
            if (isNameInChain(book.name())) {
                debug(book.name() + " zincirde (birlestirme/satis), yeni siparis acilmiyor");
                continue;
            }

            int fullAmount = book.getQtyAmount(book.level());

            // Elde zaten duran ara seviye artıklar birim olarak düşülür ki toplam
            // havuz tam 2'nin kuvveti olsun ve zincir sonunda artık kalmasın.
            int credit = isLowestConfiguredLevel(book) ? leftoverUnitsInInventory(book) : 0;
            int amount = Math.max(0, fullAmount - credit);

            double unitCost = flipItem.totalCost() / fullAmount;
            double actualCost = unitCost * amount;

            if (amount > 0 && purse < actualCost) continue;

            debug("User has enough money " + book.name());
            purse -= actualCost;
            debug("new purse = " + purse);

            task.put(book, new Task(amount));

            if (credit > 0) {
                ChatUtils.clientMessage(book.name() + " icin elde " + credit + " birim ara seviye kitap var, siparis " + fullAmount + " yerine " + amount + " adet aciliyor.");
            }

            if (amount <= 0) {
                // Sipariş gerekmiyor, elde yeterli birim var: doğrudan zincire gir.
                editStateBook(book, BookState.ANVIL);
            }

            debug("new task created size:" + task.size());
        }

    }

    private void openBazaar(String name) {
        if (containerCheck("bazaar")) return;
        debug("sending command for " + name);
        minecraft.player.connection.sendCommand("bz " + name);
    }

    private void openAnvil() {
        if (containerCheck("Anvil")) return;
        debug("openAnvil");
        minecraft.player.connection.sendCommand("Anvil");
    }

    private void openEnderChest(boolean useSecondPage) {
        if (containerCheck("Ender Chest") || containerCheck("Jumbo Backpack") || containerCheck("Greater Backpack")) return;
        debug("openEnderChest");
        if (useSecondPage) {
            minecraft.player.connection.sendCommand(GoofyConfig.INSTANCE.secondPage);
            return;
        }
        minecraft.player.connection.sendCommand(GoofyConfig.INSTANCE.firstPage);
    }

    private void handleSign() {
        String amountToOrder = String.valueOf(task.get(activeBook).getAmountToOrder());
        if (minecraft.screen instanceof AbstractSignEditScreen signScreen) {
            debug("writing amount=" + amountToOrder + " for book=" + activeBook);
            try {
                Field messagesField = AbstractSignEditScreen.class.getDeclaredField("messages");
                messagesField.setAccessible(true);
                String[] messages = (String[]) messagesField.get(signScreen);
                messages[0] = amountToOrder;
                minecraft.setScreen(null);
            } catch (Exception e) {
                debug("reflection failed - " + e.getMessage());
                e.printStackTrace();
            }
        }
    }


    private boolean containerCheck(String name) {
        if (minecraft.screen == null) return false;
        String title = minecraft.screen.getTitle().getString();
        return title.toLowerCase().contains(name.toLowerCase());
    }

    private boolean isContainerOpen() {
        if (minecraft.screen == null) return false;
        return true;
    }

    private void handleClaimedMessage(String string) {
        // ESKİ KOD: HER "Claimed" mesajı didReceiveItems'i açıyordu - satış claim'i,
        // başka bir kitabın claim'i, her şey. OUTBID'deki kilit erken açılıp aynı
        // sipariş iki kez okunuyor, sayaç şişiyor ve görev fiziksel olarak eksikken
        // "tamamlandı" sayılıp birleştirmeye giriyordu (garanti öksüz parça).
        if (state != State.OUTBID || !claimedItems) return;
        didReceiveItems = true;
    }

    private void handleOutbid(Book book) {
        Task t = task.get(book);
        if (t == null || !BUY_PHASE.contains(t.getBookState())) {
            // Görev artık alım fazında değil: eski bir sipariş kaydından gelen bu
            // uyarı, birleştirme zincirini ortasından keserdi.
            debug("stale outbid ignored for " + book);
            bazaarMonitor.finish(book);
            return;
        }
        debug("Found outbid:" + book.getRomanLevel(book.level()));
        editStateBook(book, BookState.OUTBID);
    }


    private void handleFilledMessage(String string) {
        List<Book> booksInState = new ArrayList<>();
        booksInState.addAll(booksInState(BookState.BUY_ORDER, BookState.STORE));

        String stripped = string
                .replace("[Bazaar] Your Buy Order for ", "")
                .replace(" was filled!", "");

        stripped = stripped.substring(stripped.indexOf(' ') + 1);

        debug("stripped=" + stripped);

        for (Book book : booksInState) {
            if (!stripped.equals(book.getRomanLevel(book.level()))) continue;
            editStateBook(book, BookState.OUTBID);
            bazaarMonitor.finish(book);
        }
    }

    private int randomizer() {
        int result = splittableRandom.nextInt(GoofyConfig.INSTANCE.minActionDelay, GoofyConfig.INSTANCE.maxActionDelay);

        if (result > 50) {
            return result;
        }

        return 500;
    }


    private int speedMode() {
        if (GoofyConfig.INSTANCE.speedMode) return GoofyConfig.INSTANCE.speedModeDelay;
        return randomizer();
    }


    private class Task {
        private BookState bookState = BookState.SELECTED;
        private int amountToOrder;
        private int inEnderChest;
        private int inInventory;
        /** Elde zaten duran ara seviye kitapların taban seviye cinsinden birim değeri. */
        private int unitCredit;
        private boolean shouldCheckSecondPage = false;
        private boolean earlyAction = false;
        private boolean earlyStore = false;

        private boolean isShouldCheckSecondPage() {
            return shouldCheckSecondPage;
        }

        private void setShouldCheckSecondPage(boolean shouldCheckSecondPage) {
            this.shouldCheckSecondPage = shouldCheckSecondPage;
        }

        private boolean isEarlyAction() {
            return earlyAction;
        }

        private void setEarlyAction(boolean earlyAction) {
            this.earlyAction = earlyAction;
        }

        private Task(int amountToOrder) {
            this.amountToOrder = amountToOrder;
        }

        private BookState getBookState() {
            return bookState;
        }

        private void setBookState(BookState bookState) {
            this.bookState = bookState;
        }

        private void addInEnderChest(int inEnderChest) {
            this.inEnderChest += inEnderChest;
        }

        private void addInInventory(int inInventory) {
            this.inInventory += inInventory;
        }

        private void addUnitCredit(int units) {
            this.unitCredit += units;
            this.amountToOrder = Math.max(0, this.amountToOrder - units);
        }

        private int getAmountToOrder() {
            return amountToOrder - (inEnderChest + inInventory);
        }

        private boolean shouldCheckEnderChest() {
            return inEnderChest > 0;
        }

        private boolean isCompleted() {
            return getAmountToOrder() <= 0;
        }

        private boolean shouldStore() {
            return inInventory > 0;
        }

        private boolean isEarlyStore() {
            return earlyStore;
        }

        private void setEarlyStore(boolean earlyStore) {
            this.earlyStore = earlyStore;
        }
    }
}
