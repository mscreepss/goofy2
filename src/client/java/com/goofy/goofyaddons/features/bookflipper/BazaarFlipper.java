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

    // Öksüz parça tespit edilen ama mevcut siparişlerin (1to5/2to5) hâlâ bitmesini
    // beklediğimiz isimler. Bu isimlerden hiçbir YENİ sipariş (temizlik siparişi
    // hariç) açılmaz, ama mevcut çalışan siparişler kesilmeden bitene kadar sürer.
    private final Set<String> pendingCleanupNames = new HashSet<>();
    // Bir isim için şu an aktif olan temizlik siparişinin hangi Book olduğunu tutar.
    // Bu, "temizlik siparişi bitti mi yoksa sadece normal bir sipariş mi bitti"yi
    // ayırt etmek için gerekli.
    private final Map<String, Book> activeCleanupTask = new HashMap<>();

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
        pendingCleanupNames.clear();
        activeCleanupTask.clear();
        enabled = false;
        state = State.IDLE;
        lastState = null;
        flipItemsList.clear();
        activeBook = null;
        counter = 0;
        clickedOnce = false;
        clock.stop();
        bazaarMonitor.stop();
        bazaarMonitor.reset();
        isInventoryFull = false;
        didRemoveOrder = false;
        useSecondPage = false;
        secondPageCheck = false;

    }

    @Override
    public void pause() {
        enabled = false;
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


                List<Book> booksToAnvil = booksInState(BookState.ANVIL);
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

                    List<Integer> slots = new ArrayList<>();
                    slots.addAll(inventoryScanner.findLoreInv(bookToHandle.getRomanLevel(bookToHandle.level())));
                    if (!slots.isEmpty()) {
                        if (inventoryScanner.getEmptyContainerSlots() == 0) {
                            useSecondPage = true;
                            task.get(bookToHandle).setShouldCheckSecondPage(true);
                            minecraft.player.closeContainer();
                            return;
                        }

                        InventoryUtils.clickSlot(slots.getFirst(), true);
                        debug("storing " + bookToHandle.name() + " at slot " + slots.getFirst());
                        task.get(bookToHandle).addInInventory(-1);
                        task.get(bookToHandle).addInEnderChest(1);
                    }
                    if (slots.isEmpty()) {
                        if (task.get(bookToHandle).isEarlyAction()) {
                            editStateBook(bookToHandle, BookState.OUTBID);
                            task.get(bookToHandle).setEarlyAction(false);
                            return;
                        }

                        if (task.get(bookToHandle).isEarlyStore()) {
                            editStateBook(bookToHandle, BookState.SELECTED);
                            task.get(bookToHandle).setEarlyStore(false);
                            return;
                        }

                        editStateBook(bookToHandle, BookState.BUY_ORDER);
                        debug("slot is empty adding book to " + "BUY_ORDER");
                    }
                }
            }

            case ANVIL -> {
                Book bookToHandle = firstBookInState(BookState.ANVIL);

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
                    List<Integer> slots = new ArrayList<>();

                    slots.addAll(inventoryScanner.findLoreContainer(bookToHandle.getRomanLevel(bookToHandle.level())));

                    if (slots.size() > inventoryScanner.getEmptyInventorySlots()) {
                        state = State.COMBINE;
                        minecraft.player.closeContainer();
                        return;
                    }

                    debug("found " + slots.size() + " book slots in ender chest");
                    if (slots.isEmpty() && task.get(bookToHandle).isShouldCheckSecondPage() && !(task.get(bookToHandle).inInventory == task.get(bookToHandle).amountToOrder)) {
                        minecraft.player.closeContainer();
                        task.get(bookToHandle).setShouldCheckSecondPage(false);
                        return;
                    }

                    if (slots.isEmpty() || task.get(bookToHandle).inInventory == task.get(bookToHandle).amountToOrder) {
                        editStateBook(bookToHandle, BookState.COMBINE);
                        return;
                    }
                    debug("pulling slot " + slots.getFirst() + " from ender chest");
                    InventoryUtils.clickSlot(slots.getFirst(), true);
                    task.get(bookToHandle).addInInventory(1);
                    task.get(bookToHandle).addInEnderChest(-1);
                }
            }

            case COMBINE -> {

                Book bookToHandle = firstBookInState(BookState.COMBINE);

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
                        // ESKİ KOD: burada hiç kontrol etmeden direkt SELL'e geçiyordu.
                        // Sorun: "birleştirilecek çift yok" != "kitap satış seviyesinde hazır".
                        // Bazen tek bir kitap ender chest'te unutulmuş kalıyor ve bot onu asla
                        // bulamadan sonsuz döngüye giriyordu. Şimdi önce gerçekten envanterde
                        // satış seviyesindeki kitap var mı diye bakıyoruz.
                        if (!inventoryScanner.locate(bookToHandle.getRomanLevel(bookToHandle.sellLevel())).isEmpty()) {
                            debug("no pair to combine, sell-level copy confirmed in inventory, switching to SELL");
                            editStateBook(bookToHandle, BookState.SELL);
                        } else if (task.get(bookToHandle).inEnderChest > 0) {
                            debug("no pair to combine AND no sell-level copy found for " + bookToHandle.name() + ", sending back to ANVIL to recheck ender chest");
                            editStateBook(bookToHandle, BookState.ANVIL);
                        } else {
                            // ec=0, yani ender chest'te de kontrol edilecek bir şey kalmadı.
                            // ANVIL'e geri göndermek burada sonsuz bir COMBINE<->ANVIL döngüsü
                            // yaratıyordu (IDLE, ec=0 gördüğü için ANVIL ekranını hiç açmadan
                            // direkt tekrar COMBINE'a atıyordu). Bunun yerine görevi tamamen
                            // bırakıyoruz; kalan öksüz parçalar bir sonraki FETCHING turunda
                            // "tamamlama siparişi" mekanizması tarafından otomatik yakalanıp
                            // doğru miktarda yeni sipariş açılarak tamamlanacak.
                            ChatUtils.clientMessage(bookToHandle.name() + " icin bu siparis tikandi (kontrol edilecek yer kalmadi), birakiliyor - kalan parcalar bir sonraki turda tamamlama siparisiyle toplanacak.");
                            debug("dead end for " + bookToHandle.name() + ", removing task so leftover fragments get picked up by top-up logic next cycle");
                            task.remove(bookToHandle);
                            Book cleanupBookAtDeadEnd = activeCleanupTask.get(bookToHandle.name());
                            if (cleanupBookAtDeadEnd != null && cleanupBookAtDeadEnd.equals(bookToHandle)) {
                                activeCleanupTask.remove(bookToHandle.name());
                                pendingCleanupNames.remove(bookToHandle.name());
                                debug(bookToHandle.name() + " icin temizlik siparisi de tikandi, bayraklar sifirlaniyor ki bir sonraki tur bastan hesaplasin");
                            }
                        }
                        return;
                    }


                    List<Integer> book = inventoryScanner.findLoreInv(bookToHandle.getRomanLevel(level));

                    if (!book.isEmpty()) {
                        if (inventoryScanner.findMisMatch(bookToHandle.getRomanLevel(level))) {
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
                    combineConfirmClock.start(speedMode()); // İsterseniz buraya sabit bir milisaniye (örn: 500) yazabilirsiniz
                    combineConfirmPending = true;
                }
                
                // Onay bekleniyorsa ve saatin süresi dolduysa tıklama işlemini yap
                if (counter == 2 && combineConfirmPending && combineConfirmClock.shouldFire()) {
                    debug("counter==2, clicking anvil output slot 22 with normal click");
                    InventoryUtils.clickSlot(22, false);
                    
                    if (clickedOnce) {
                        clickedOnce = false;
                        counter = 0;
                        combineConfirmPending = false; // İşlem bittiğinde pending durumunu sıfırla
                        return;
                    }
                    clickedOnce = true;
                    // clickedOnce true olduktan sonra bir sonraki tick'te tekrar deneyebilmesi için saati yeniden başlat
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
                        slots.addAll(inventoryScanner.findContainer("SELL " + book.getRomanLevel(5)));
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
                            // ESKİ KOD: sadece "bookList.removeFirst()" yapıyordu. bookList burada
                            // her tick'te booksInState(...) ile yeniden oluşturulan GEÇİCİ bir liste,
                            // gerçek "task" haritası değil. Ondan silmek hiçbir şeyi değiştirmiyordu,
                            // bu yüzden aynı kitap her tick'te tekrar tekrar karşımıza çıkıp sonsuz
            // döngü yaratıyordu. Şimdi kitabın GERÇEK durumunu (task haritasındaki)
                            // ANVIL'e geri çekiyoruz ki eksik parça ender chest'te tekrar aransın.
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
                    debug("confirm prompt, clicking slot 13 and removing " + bookList.getFirst() + " from sell list");
                    InventoryUtils.clickSlot(13, false);
                    if (task.get(bookList.getFirst()).getAmountToOrder() < 0) {
                        task.get(bookList.getFirst()).addInInventory(-bookList.getFirst().getQtyAmount(bookList.getFirst().level()));
                        editStateBook(bookList.getFirst(), BookState.SELECTED);
                        return;
                    }
                    removeDuplicateBooks(task);
                    if (task.containsKey(bookList.getFirst())) {
                        Book soldBook = bookList.getFirst();
                        task.remove(soldBook);
                        // Az önce satılan kitap, o isim için çalışan temizlik siparişiyse
                        // (öksüz parçayı tamamlayan sipariş), artık temizlik bitti demektir -
                        // normal paralel çalışmaya (1to5 + 2to5) dönebilir. Sadece normal bir
                        // sipariş bittiyse (temizlik hâlâ bekliyorsa) bu bayraklara dokunmuyoruz
                        // ki bekleme devam etsin.
                        Book cleanupBook = activeCleanupTask.get(soldBook.name());
                        if (cleanupBook != null && cleanupBook.equals(soldBook)) {
                            activeCleanupTask.remove(soldBook.name());
                            pendingCleanupNames.remove(soldBook.name());
                            debug(soldBook.name() + " icin temizlik siparisi basariyla tamamlandi, normal calismaya donuluyor");
                        }
                    }
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

    private void lastStateCheck() {
        if (state != lastState) {
            debug("state changed: " + lastState + " -> " + state);
            clock.stop();
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

    private void removeDuplicateBooks(Map<Book, Task> tasks) {
        Map<String, Integer> counts = new HashMap<>();
        List<Book> stateBooks = new ArrayList<>();

        stateBooks.addAll(booksInState(BookState.SELL));

        for (Book book : stateBooks) {
            if (task.get(book).getAmountToOrder() < 0) continue;
            counts.merge(book.name(), 1, Integer::sum);
        }

        tasks.entrySet().removeIf(entry ->
                counts.getOrDefault(entry.getKey().name(), 0) > 1
        );
    }

    private boolean hasActiveTaskForName(String name) {
        for (Book b : task.keySet()) {
            if (b.name().equals(name)) return true;
        }
        return false;
    }

    /**
     * Bu isim için config'te tanımlı 1. seviye (level()==1) kitabı bulur - tamamlama
     * siparişi HER ZAMAN bu kitap üzerinden açılır, asla 2. seviyeden değil. Eğer
     * 1. seviye tanımlı değilse (olmamalı ama garanti olsun diye), config'te bulunan
     * en düşük seviyeli girişi döner.
     */
    private Book findBaseLevelEntry(String name) {
        Book lowest = null;
        for (Book b : GoofyConfig.INSTANCE.books) {
            if (!b.name().equals(name)) continue;
            if (b.level() == 1) return b;
            if (lowest == null || b.level() < lowest.level()) lowest = b;
        }
        return lowest;
    }

    /**
     * Envanterde (SADECE envanter, ender chest'e bakmaz) bu kitabın taban seviyesi
     * ile satış seviyesi arasında kalmış, eşi olmayan (tek/öksüz kalmış) parça var mı
     * diye bakar. Her seviyedeki bir parça, TABAN seviyeye göre şu kadar "birim"
     * değerinde: taban+1 = 2 birim, taban+2 = 4 birim, taban+3 = 8 birim...
     * (2^(seviye-taban)) — çünkü o seviyeye ulaşmak için tabandan o kadar kere
     * ikiye katlama (birleştirme) gerekti. Bulunan birimler toplanıp normal tam
     * miktardan (örn. 16) çıkarılır, kalan sayı kadar taban seviye kitabı sipariş
     * edilmesi gerekir. Öksüz parça yoksa null döner (normal tam sipariş açılmalı
     * demektir).
     */
    private Integer calculateTopUpAmount(Book book) {
        int fullAmount = book.getQtyAmount(book.level());
        int existingUnits = 0;
        boolean foundStray = false;

        for (int i = book.level() + 1; i < book.sellLevel(); i++) {
            int count = inventoryScanner.findLoreInv(book.getRomanLevel(i)).size();
            if (count > 0) {
                foundStray = true;
                existingUnits += count * (1 << (i - book.level()));
            }
        }

        if (!foundStray) return null;

        int needed = fullAmount - existingUnits;
        return Math.max(1, needed);
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

            // Bu isim daha önce "temizlik bekliyor" olarak işaretlenmediyse, öksüz
            // parça var mı diye bak. Varsa işaretle - bu turdan itibaren bu isimden
            // (temizlik siparişi hariç) yeni sipariş açılmayacak.
            if (!pendingCleanupNames.contains(book.name())) {
                Book baseEntryCheck = findBaseLevelEntry(book.name());
                if (baseEntryCheck != null && calculateTopUpAmount(baseEntryCheck) != null) {
                    pendingCleanupNames.add(book.name());
                    debug(book.name() + " icin envanterde oksuz parca tespit edildi, temizlik moduna alindi - mevcut siparisler bitene kadar yeni siparis acilmayacak");
                }
            }

            if (pendingCleanupNames.contains(book.name())) {
                if (hasActiveTaskForName(book.name())) {
                    // Bu ismin 1to5 ve/veya 2to5 siparişi hâlâ çalışıyor - onlar
                    // kesilmeden bitsin, şimdi hiçbir şey açmıyoruz.
                    debug(book.name() + " icin temizlik bekleniyor, mevcut siparis(ler) bitmeden yeni siparis acilmiyor");
                    continue;
                }

                // Bu isimden artık hiçbir aktif sipariş yok - tamamlama siparişini
                // aç, HER ZAMAN 1. seviyeden (asla 2. seviyeden).
                Book baseEntry = findBaseLevelEntry(book.name());
                if (baseEntry == null) baseEntry = book;

                Integer topUpAmount = calculateTopUpAmount(baseEntry);
                if (topUpAmount == null) {
                    // Öksüz parça artık yok (bir şekilde çözülmüş), temizlik modundan
                    // çık, bu turda normal akışa düşsün.
                    pendingCleanupNames.remove(book.name());
                } else {
                    FlipItem baseFlipItem = null;
                    for (FlipItem fi : flipItemsList) {
                        if (fi.book().equals(baseEntry)) {
                            baseFlipItem = fi;
                            break;
                        }
                    }
                    if (baseFlipItem == null) {
                        debug(baseEntry.name() + " icin fiyat verisi henuz yok, tamamlama siparisi bir sonraki tura birakiliyor");
                        continue;
                    }

                    double unitCost = baseFlipItem.totalCost() / baseEntry.getQtyAmount(baseEntry.level());
                    double actualCost = unitCost * topUpAmount;

                    if (purse < actualCost) continue;

                    purse -= actualCost;
                    debug("new purse = " + purse);
                    ChatUtils.clientMessage(baseEntry.name() + " icin envanterde eslenmemis parca bulundu, " + topUpAmount + " adetlik tamamlama siparisi (1. seviyeden) aciliyor.");
                    task.put(baseEntry, new Task(topUpAmount));
                    activeCleanupTask.put(baseEntry.name(), baseEntry);
                    debug("new cleanup task created for " + baseEntry.name());
                    continue;
                }
            }

            // Normal (temiz) durum - eskisi gibi tam sipariş.
            int fullAmount = book.getQtyAmount(book.level());
            if (purse < flipItem.totalCost()) continue;
            debug("User has enough money " + book.name());
            purse -= flipItem.totalCost();
            debug("new purse = " + purse);
            task.put(book, new Task(fullAmount));
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
        if (!didReceiveItems) {
            didReceiveItems = true;
        }
    }

    private void handleOutbid(Book book) {
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
        private boolean shouldCheckSecondPage = false;
        private boolean earlyAction = false;
        private boolean earlyStore = false;
        private boolean anvilRecheckAttempted = false;

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
