package bookcycle;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.gui.screens.inventory.GrindstoneScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BookCycle implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(BookCycle.class);
    private static boolean sendingClick;
    private static LocalPlayer eatingPlayer;

    public static boolean isEating(Player player) {
        return eatingPlayer == player && player.isAlive()
                && Minecraft.getInstance().gui.screen() == null && safeFood(player.getUseItem());
    }

    public static boolean isSendingClick() {
        return sendingClick;
    }
    private static final int TIMEOUT_TICKS = 80;
    private static final int OPEN_DELAY_TICKS = 1;
    private static final int MAX_EXTRA_DELAY_TICKS = 20;
    private static final int MAX_AFTER_ATTACK_DELAY_TICKS = 40;
    private static final int RECOVERY_DELAY_TICKS = 20;
    private static final int MAX_RECOVERY_ATTEMPTS = 3;
    private static final int ENCHANT_RETRY_TICKS = 20;
    private static final int LOW_FREE_SLOTS = 4;
    private static final int LOOT_CRAFT_RETRY_TICKS = 20;
    private static final int XP_WAIT_WARN_TICKS = 200;
    private static final long XP_BOTTLE_RETRY_DELAY = TimeUnit.SECONDS.toNanos(5);
    private static final int TABLE_BOOK = 0;
    private static final int TABLE_LAPIS = 1;
    private static final int TARGET_OFFER = 2;
    private static final int TARGET_LEVEL = 30;
    private static final int TARGET_LAPIS = TARGET_OFFER + 1;
    private static final int LAPIS_REFILL_TARGET = 64;
    private static final double[] AIM_FRACTIONS = {0.5, 0.08, 0.92};
    private static final int ENTITY_AIM_GRID = 9;
    private static final long EAT_RETRY_DELAY = TimeUnit.SECONDS.toNanos(5);
    private static final long RECONNECT_DELAY = TimeUnit.SECONDS.toNanos(10);
    private static final long RESUME_TIMEOUT = TimeUnit.SECONDS.toNanos(10);
    private static final int MAX_RECONNECT_ATTEMPTS = 30;
    private static final List<String> ROMAN_TIERS = List.of("", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX");
    private static final Pattern COMPRESSED_LAPIS = Pattern.compile(
            "(?:多重)?圧縮ラピスラズリブロック\\s+([IVX]+)(?:\\s*\\([0-9,]+個分\\))?");
    private static final Set<Item> FISH = Set.of(
            Items.COD, Items.SALMON, Items.TROPICAL_FISH, Items.PUFFERFISH,
            Items.COOKED_COD, Items.COOKED_SALMON);
    private static final Item SHARD = Items.PRISMARINE_SHARD;
    private static final Item CRYSTALS = Items.PRISMARINE_CRYSTALS;
    private static final Item PRISMARINE = Items.PRISMARINE;

    private record LootRecipe(Item output, int amount, int width, int height, List<Item> ingredients) {}

    private static final LootRecipe LANTERN_RECIPE = new LootRecipe(Items.SEA_LANTERN, 1, 3, 3,
            List.of(SHARD, CRYSTALS, SHARD, CRYSTALS, CRYSTALS, CRYSTALS, SHARD, CRYSTALS, SHARD));
    private static final LootRecipe PRISMARINE_RECIPE = new LootRecipe(PRISMARINE, 1, 2, 2,
            List.of(SHARD, SHARD, SHARD, SHARD));
    private static final LootRecipe SLAB_RECIPE = new LootRecipe(Items.PRISMARINE_SLAB, 6, 3, 1,
            List.of(PRISMARINE, PRISMARINE, PRISMARINE));

    private enum Step {
        OPEN_TABLE, ENCHANT, TAKE_ENCHANTED, OPEN_GRINDSTONE, GRIND,
        OPEN_BARREL, TAKE_SUPPLIES, CRAFT_LAPIS, OPEN_CRAFTING_TABLE, CRAFT_LOOT, ARMOR_STAND, WAIT_FOR_XP, SWING,
        PREPARE_XP_BOTTLE, USE_XP_BOTTLE, PREPARE_FOOD, EAT, RESTORE_FOOD, PREPARE_CYCLE_ITEMS
    }

    private ClientLevel level;
    private BlockPos grindstone;
    private BlockPos table;
    private BlockHitResult pendingUse;
    private BlockPos openingTarget;
    private LocalPlayer player;
    private Step step;
    private AbstractContainerMenu activeMenu;
    private final Set<BlockPos> checkedBarrels = new HashSet<>();
    private int bookSlot;
    private ItemStack tableBook = ItemStack.EMPTY;
    private int bookMoveState;
    private int lapisMoveState;
    private int enchantSeed;
    private ItemStack lapisBeforeEnchant = ItemStack.EMPTY;
    private boolean retriedEnchant;
    private int ticksLeft;
    private int actionDelay;
    private int extraDelayTicks;
    private int afterAttackDelayTicks;
    private boolean showControls;
    private boolean autoReconnect;
    private ServerData cycleServer;
    private ItemStack cycleItem = ItemStack.EMPTY;
    private boolean initialDisenchant;
    private record ReconnectCycle(ServerData server, ResourceKey<Level> dimension, BlockPos table,
                                  BlockPos grindstone, boolean guardians, int hotbarSlot,
                                  ItemStack item, int completed, boolean initialDisenchant) {}
    private ReconnectCycle reconnectCycle;
    private DisconnectedScreen retryScreen;
    private int reconnectAttempts;
    private long reconnectAt;
    private long resumeDeadline;
    private record MenuAction(AbstractContainerMenu menu, BooleanSupplier ready, Runnable send,
                              BooleanSupplier confirmed, Runnable finish, Runnable retry) {}
    private MenuAction menuAction;
    private boolean actionSent;
    private int recoveryAttempts;
    private int completed;
    private boolean recoveringBook;
    private boolean guardianMode;
    private int xpNeeded;
    private LivingEntity aimedXpMob;
    private Vec3 aimedXpPoint;
    private boolean nextXpTargetGuardian;
    private int craftTier;
    private boolean bulkLapisInput;
    private int expectedLapisCount = -1;
    private LootRecipe lootRecipe;
    private boolean takingLootResult;
    private boolean skipLootCrafting;
    private int lootCraftRetryAtTick;
    private boolean lootCleanupOnly;
    private int xpWaitTicks;
    private ItemStack pickedIngredient = ItemStack.EMPTY;
    private int ingredientSource;
    private boolean warnedNoCraftingTable;
    private int selectedBeforeEating = -1;
    private int mealHotbarSlot;
    private Step mealResumeStep;
    private InteractionHand foodHand;
    private ItemStack eatingStack = ItemStack.EMPTY;
    private long lastFailedMeal;
    private int selectedBeforeXpBottle = -1;
    private int xpBottleHotbarSlot = -1;
    private int xpBottleBeforeCount;
    private int xpBottleBeforeLevel;
    private float xpBottleBeforeProgress;
    private boolean xpBottleUseSent;
    private boolean xpBottleUsedThisWait;
    private long lastFailedXpBottle;

    private int tickCounter;
    private int cycleStartTick;
    private long cycleStartNanos;
    private String currentPhase;
    private int phaseStartTick;
    private final java.util.Map<String, Integer> currentPhaseTicks = new java.util.LinkedHashMap<>();
    private final java.util.List<Integer> recentCycleTicks = new java.util.ArrayList<>();

    @Override
    public void onInitializeClient() {
        KeyMapping cycle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.book_cycle.control", InputConstants.KEY_F8, KeyMapping.Category.GAMEPLAY));
        KeyMapping guardian = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.book_cycle.guardian", InputConstants.KEY_F9, KeyMapping.Category.GAMEPLAY));
        KeyMapping controls = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.book_cycle.controls", InputConstants.KEY_F10, KeyMapping.Category.GAMEPLAY));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?>)) return;
            ScreenKeyboardEvents.allowKeyPress(screen).register((current, event) -> {
                if (step == null || !controls.matches(event)) return true;
                showControls = true;
                return false;
            });
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (level != client.level) {
                if (step != null && autoReconnect && cycleServer != null && !cycleServer.isRealm()
                        && player.isAlive() && client.level == null
                        && client.gui.screen() instanceof DisconnectedScreen) {
                    reconnectCycle = new ReconnectCycle(cycleServer, level.dimension(), table, grindstone,
                            guardianMode, bookSlot, cycleItem, completed, initialDisenchant);
                    retryScreen = null;
                    reconnectAttempts = 0;
                    resumeDeadline = 0;
                }
                level = client.level;
                reset();
            }
            if (reconnectCycle != null) {
                tickReconnect(client);
                return;
            }
            if (consumeClicks(controls) && client.player != null && client.gui.screen() == null) showControls = true;
            if (showControls && step != Step.EAT && client.player != null && client.gui.screen() == null
                    && client.player.containerMenu == client.player.inventoryMenu) {
                showControls = false;
                client.gui.setScreen(new ControlsScreen());
                return;
            }
            boolean cyclePressed = consumeClicks(cycle);
            boolean guardianPressed = consumeClicks(guardian);
            if ((cyclePressed || guardianPressed) && client.player != null && client.gui.screen() == null) {
                if (step == null) {
                    start(client, guardianPressed);
                    return;
                }
                stop("stopped");
            }
            if (step != null) {
                tick(client);
            }
        });
    }

    private final class ControlsScreen extends Screen {
        private ControlsScreen() {
            super(Component.literal("Book Cycle"));
        }

        @Override
        protected void init() {
            int x = (width - 220) / 2;
            int y = (height - 138) / 2;
            addDelayField(x, y, "book_cycle.delay", extraDelayTicks, MAX_EXTRA_DELAY_TICKS,
                    ticks -> extraDelayTicks = ticks);
            addDelayField(x, y + 26, "book_cycle.attack_delay", afterAttackDelayTicks, MAX_AFTER_ATTACK_DELAY_TICKS,
                    ticks -> afterAttackDelayTicks = ticks);
            Button normal = addRenderableWidget(Button.builder(Component.translatable("book_cycle.start_normal"),
                    button -> startFromControls(false)).bounds(x, y + 52, 106, 20).build());
            Button guardians = addRenderableWidget(Button.builder(Component.translatable("book_cycle.start_guardian"),
                    button -> startFromControls(true)).bounds(x + 114, y + 52, 106, 20).build());
            Button stopButton = addRenderableWidget(Button.builder(Component.translatable("book_cycle.stop"), button -> {
                if (step != null) stop("stopped");
                onClose();
            }).bounds(x, y + 78, 106, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                    .bounds(x + 114, y + 78, 106, 20).build());
            addRenderableWidget(Button.builder(reconnectLabel(), button -> {
                autoReconnect = !autoReconnect;
                button.setMessage(reconnectLabel());
            }).bounds(x, y + 104, 220, 20).build());
            normal.active = guardians.active = step == null && minecraft.player != null;
            stopButton.active = step != null;
        }

        private void addDelayField(int x, int y, String label, int initialValue, int max,
                                   IntConsumer update) {
            EditBox delay = addRenderableWidget(new EditBox(font, x + 172, y, 48, 20, Component.translatable(label)));
            delay.setMaxLength(2);
            delay.setValue(Integer.toString(initialValue));
            delay.setResponder(value -> {
                int ticks = value.matches("[0-9]{1,2}") ? Integer.parseInt(value) : -1;
                boolean valid = ticks >= 0 && ticks <= max;
                delay.setTextColor(valid ? 0xFFFFFFFF : 0xFFFF5555);
                if (valid) update.accept(ticks);
            });
        }

        private void startFromControls(boolean guardians) {
            onClose();
            if (step == null && minecraft.player != null) start(minecraft, guardians);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
            int y = (height - 138) / 2;
            graphics.centeredText(font, title, width / 2, y - 35, 0xFFFFFFFF);
            graphics.centeredText(font, Component.translatable(step == null ? "book_cycle.idle" : "book_cycle.paused", completed),
                    width / 2, y - 20, 0xFFCCCCCC);
            graphics.text(font, Component.translatable("book_cycle.delay"), (width - 220) / 2, y + 6, 0xFFFFFFFF);
            graphics.text(font, Component.translatable("book_cycle.attack_delay"), (width - 220) / 2, y + 32, 0xFFFFFFFF);
        }
    }

    private Component reconnectLabel() {
        return Component.translatable("book_cycle.reconnect_toggle",
                Component.translatable(autoReconnect ? "options.on" : "options.off"));
    }

    private static boolean consumeClicks(KeyMapping key) {
        boolean pressed = false;
        while (key.consumeClick()) pressed = true;
        return pressed;
    }

    private void start(Minecraft client, boolean withGuardians) {
        LocalPlayer current = client.player;
        if (current.isSpectator() || current.isShiftKeyDown() || client.getCameraEntity() != current) {
            message(current, "stance");
            return;
        }
        if (current.containerMenu != current.inventoryMenu || !current.inventoryMenu.getCarried().isEmpty()) {
            message(current, "cursor_occupied");
            return;
        }
        table = nearestVisible(client, Blocks.ENCHANTING_TABLE);
        grindstone = nearestVisible(client, Blocks.GRINDSTONE);
        if (table == null || grindstone == null) {
            message(current, "reach");
            return;
        }
        Inventory inventory = current.getInventory();
        int source = -1;
        boolean cursed = false;
        for (int index = 0; index < Inventory.INVENTORY_SIZE; index++) {
            ItemStack stack = inventory.getItem(index);
            if (!isTargetItem(stack)) continue;
            if (isPlainTargetItem(stack)) {
                source = index;
                break;
            }
            if (stack.get(DataComponents.ENCHANTMENTS).keySet().stream().anyMatch(e -> e.is(EnchantmentTags.CURSE))) {
                cursed = true;
            } else if (source < 0) {
                source = index;
            }
        }
        if (source < 0) {
            message(current, cursed ? "cursed_item" : "book");
            return;
        }
        ItemStack stack = inventory.getItem(source);
        initialDisenchant = isEnchantedTargetItem(stack);
        ItemStack item = findCycleItem(inventory, stack) == source ? stack.copy() : ItemStack.EMPTY;
        prepareItems(client, withGuardians, source, -1, item, 0, withGuardians ? "guardian_started" : "started");
    }

    private void prepareCycle(Minecraft client, boolean withGuardians, int slot, ItemStack item, int count) {
        player = client.player;
        cycleServer = client.getCurrentServer();
        cycleItem = item;
        bookSlot = slot;
        completed = count;
        recoveryAttempts = 0;
        recoveringBook = false;
        skipLootCrafting = false;
        lootCraftRetryAtTick = 0;
        lootCleanupOnly = false;
        xpWaitTicks = 0;
        warnedNoCraftingTable = false;
        guardianMode = withGuardians;
        xpNeeded = withGuardians ? TARGET_LEVEL : 1;
        nextXpTargetGuardian = true;
        tickCounter = 0;
        recentCycleTicks.clear();
        currentPhaseTicks.clear();
        currentPhase = null;
    }

    private void tickReconnect(Minecraft client) {
        ReconnectCycle saved = reconnectCycle;
        Screen screen = client.gui.screen();
        long now = System.nanoTime();
        if (screen instanceof DisconnectedScreen disconnected && client.level == null) {
            if (disconnected != retryScreen) {
                retryScreen = disconnected;
                reconnectAt = now + RECONNECT_DELAY;
                resumeDeadline = 0;
            }
            if (now - reconnectAt < 0) return;
            if (reconnectAttempts == MAX_RECONNECT_ATTEMPTS) {
                reconnectCycle = null;
                retryScreen = null;
                LOGGER.info("Book Cycle stopped trying to reconnect after {} attempts", reconnectAttempts);
                return;
            }
            LOGGER.info("Book Cycle reconnect attempt {}/{}", ++reconnectAttempts, MAX_RECONNECT_ATTEMPTS);
            Screen serverList = new JoinMultiplayerScreen(new TitleScreen());
            ConnectScreen.startConnecting(serverList, client, ServerAddress.parseString(saved.server().ip),
                    saved.server(), false, null);
            return;
        }
        if (screen instanceof ConnectScreen || screen instanceof LevelLoadingScreen) return;
        ServerData server = client.getCurrentServer();
        if (client.level == null || client.player == null || server == null || !server.ip.equals(saved.server().ip)) {
            // Leaving the disconnect screen or cancelling vanilla's connection discards the pending run.
            LOGGER.info("Book Cycle cancelled its pending reconnect on {}",
                    screen == null ? "no screen" : screen.getClass().getSimpleName());
            reconnectCycle = null;
            retryScreen = null;
            return;
        }

        if (resumeDeadline == 0) resumeDeadline = now + RESUME_TIMEOUT;
        LocalPlayer current = client.player;
        boolean ready = screen == null && client.gameMode != null && current.isAlive() && !current.isSpectator()
                && !current.isShiftKeyDown() && client.getCameraEntity() == current
                && current.containerMenu == current.inventoryMenu && current.inventoryMenu.getCarried().isEmpty()
                && client.level.dimension().equals(saved.dimension())
                && visibleHit(client, saved.table(), Blocks.ENCHANTING_TABLE) != null
                && visibleHit(client, saved.grindstone(), Blocks.GRINDSTONE) != null;
        int source = ready ? findCycleItem(current.getInventory(), saved.item()) : -1;
        if (source < 0 && now - resumeDeadline < 0) return;
        reconnectCycle = null;
        retryScreen = null;
        if (source < 0) {
            String reason = ready ? "reconnect_item" : "reconnect_context";
            LOGGER.info("Book Cycle could not resume after reconnecting ({})", reason);
            message(current, reason);
            return;
        }
        resumeCycle(client, saved, source);
    }

    static int findCycleItem(Inventory inventory, ItemStack expected) {
        if (expected.isEmpty()) return -1;
        int match = -1;
        var expectedPatch = expected.getComponentsPatch().forget(type -> type == DataComponents.ENCHANTMENTS);
        for (int index = 0; index < Inventory.INVENTORY_SIZE; index++) {
            ItemStack stack = inventory.getItem(index);
            if (!isTargetItem(stack)) continue;
            // The server can bind new item defaults when the client joins again.
            if (!stack.getComponentsPatch().forget(type -> type == DataComponents.ENCHANTMENTS).equals(expectedPatch)) {
                continue;
            }
            if (match >= 0) return -1;
            match = index;
        }
        return match;
    }

    private void resumeCycle(Minecraft client, ReconnectCycle saved, int source) {
        table = saved.table();
        grindstone = saved.grindstone();
        initialDisenchant = saved.initialDisenchant()
                && isEnchantedTargetItem(client.player.getInventory().getItem(source));
        prepareItems(client, saved.guardians(), source, saved.hotbarSlot(), saved.item(), saved.completed(),
                "reconnect_resumed");
    }

    private void prepareItems(Minecraft client, boolean withGuardians, int source, int preferred,
                              ItemStack item, int count, String status) {
        Inventory inventory = client.player.getInventory();
        int sword = -1;
        if (withGuardians) {
            sword = inventory.getSelectedSlot();
            if (!inventory.getItem(sword).is(ItemTags.SWORDS)) {
                sword = 0;
                while (sword < Inventory.INVENTORY_SIZE && !inventory.getItem(sword).is(ItemTags.SWORDS)) sword++;
                if (sword == Inventory.INVENTORY_SIZE) {
                    message(client.player, "sword");
                    return;
                }
            }
        }
        int chestDestination = source < Inventory.getSelectionSize() ? source
                : preparationHotbarSlot(inventory, preferred, sword >= 0 && sword < Inventory.getSelectionSize()
                        ? sword : inventory.getSelectedSlot());
        int swordSource = sword;
        int swordDestination = sword < Inventory.getSelectionSize() ? sword
                : preparationHotbarSlot(inventory, inventory.getSelectedSlot(), chestDestination);
        prepareCycle(client, withGuardians, chestDestination, item, count);
        advance(Step.PREPARE_CYCLE_ITEMS);
        moveToHotbar(client, source, chestDestination, () -> {
            Runnable begin = () -> {
                if (swordDestination >= 0) inventory.setSelectedSlot(swordDestination);
                beginWithItem(client, status);
            };
            if (swordSource >= 0) moveToHotbar(client, swordSource, swordDestination, begin);
            else begin.run();
        });
    }

    private static int preparationHotbarSlot(Inventory inventory, int preferred, int protectedSlot) {
        if (preferred >= 0 && preferred != protectedSlot && inventory.getItem(preferred).isEmpty()) return preferred;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (slot != protectedSlot && inventory.getItem(slot).isEmpty()) return slot;
        }
        return preferred >= 0 && preferred != protectedSlot ? preferred : protectedSlot == 0 ? 1 : 0;
    }

    private void moveToHotbar(Minecraft client, int source, int destination, Runnable finish) {
        if (source == destination) {
            finish.run();
            return;
        }
        click(client, player.inventoryMenu, InventoryMenu.INV_SLOT_START + source - Inventory.getSelectionSize(),
                destination, ContainerInput.SWAP, () -> true, finish);
    }

    private void beginWithItem(Minecraft client, String status) {
        if (isEnchantedTargetItem(player.getInventory().getItem(bookSlot))) {
            beginCycleTiming("OPEN_GRINDSTONE");
            if (!open(client, Step.OPEN_GRINDSTONE, grindstone, Blocks.GRINDSTONE)) return;
        } else {
            startNextCycle(client);
        }
        if (step != null) message(player, status);
    }

    private void tick(Minecraft client) {
        tickCounter++;
        if (player != client.player || !player.isAlive() || client.gameMode == null) {
            reset();
            return;
        }
        if (client.gui.screen() instanceof ControlsScreen) return;
        if (activeMenu != null && (player.containerMenu != activeMenu || client.gui.screen() == null)) {
            stop("stopped");
            return;
        }
        if (player.isShiftKeyDown() || client.getCameraEntity() != player) {
            stop("stance");
            return;
        }
        if (menuAction == null && !player.containerMenu.getCarried().isEmpty()
                && (step != Step.CRAFT_LAPIS || !holdsPickedIngredient(activeMenu))) {
            stop("cursor_occupied");
            return;
        }
        if (step != Step.WAIT_FOR_XP && step != Step.SWING && --ticksLeft == 0) {
            menuAction = null;
            if (step == Step.ARMOR_STAND) {
                finishArmorStandAttempt();
            } else if (step == Step.OPEN_BARREL && client.gui.screen() == null) refillFromBarrel(client);
            else if (step == Step.CRAFT_LAPIS) {
                if (activeMenu.getCarried().isEmpty()) closeMenu();
                stop("craft_result");
            } else if (step == Step.CRAFT_LOOT) {
                skipLootCrafting(client, (CraftingMenu) activeMenu);
            } else if (step == Step.OPEN_CRAFTING_TABLE && client.gui.screen() == null) {
                skipLootCrafting = true;
                lootCraftRetryAtTick = tickCounter + LOOT_CRAFT_RETRY_TICKS;
                lootCleanupOnly = false;
                message(player, "loot_craft_skipped");
                open(client, Step.OPEN_TABLE, table, Blocks.ENCHANTING_TABLE);
            } else if (step == Step.PREPARE_XP_BOTTLE || step == Step.USE_XP_BOTTLE) {
                failXpBottle(client, "timeout");
            } else if (step == Step.PREPARE_FOOD || step == Step.EAT || step == Step.RESTORE_FOOD) {
                failMeal(client, "timeout");
            } else if (!recoverAfterTimeout(client)) {
                stop(switch (step) {
                    case ENCHANT -> hasLapis(activeMenu) ? "timeout" : "lapis_sync_timeout";
                    case TAKE_ENCHANTED -> "enchant_timeout";
                    case PREPARE_CYCLE_ITEMS -> "prepare_items";
                    default -> "timeout";
                });
            }
            return;
        }
        if (actionDelay > 0 && --actionDelay > 0) return;
        if (menuAction != null) {
            if (!processMenuAction(client) || step == null || actionDelay > 0) return;
            if (menuAction != null) {
                processMenuAction(client);
                return;
            }
        }
        if (pendingUse != null) {
            useTarget(client);
            return;
        }
        switch (step) {
            case OPEN_TABLE -> loadBook(client);
            case ENCHANT -> enchant(client, (EnchantmentMenu) activeMenu);
            case TAKE_ENCHANTED -> takeEnchantedBook(client, (EnchantmentMenu) activeMenu);
            case OPEN_GRINDSTONE -> loadGrindstone(client);
            case GRIND -> grind(client, (GrindstoneMenu) activeMenu);
            case OPEN_BARREL -> loadBarrel(client);
            case TAKE_SUPPLIES -> takeSupplies(client, (ChestMenu) activeMenu);
            case CRAFT_LAPIS -> craftLapis(client, (InventoryMenu) activeMenu);
            case OPEN_CRAFTING_TABLE -> loadCraftingTable(client);
            case CRAFT_LOOT -> craftLoot(client, (CraftingMenu) activeMenu);
            case ARMOR_STAND -> tickArmorStand(client);
            case WAIT_FOR_XP, SWING -> tickXpFarm(client);
            case PREPARE_XP_BOTTLE -> failXpBottle(client, "prepare state lost");
            case USE_XP_BOTTLE -> tickXpBottle(client);
            case PREPARE_FOOD, RESTORE_FOOD -> stop("food_sync");
            case PREPARE_CYCLE_ITEMS -> stop("prepare_items");
            case EAT -> tickEating(client);
        }
        if (menuAction != null && !actionSent && actionDelay == 0) processMenuAction(client);
    }

    private void loadBook(Minecraft client) {
        if (!(client.gui.screen() instanceof EnchantmentScreen screen)) {
            if (client.gui.screen() != null) stop("changed");
            return;
        }
        EnchantmentMenu menu = screen.getMenu();
        if (!waitForContents(menu)) return;
        activeMenu = menu;

        ItemStack heldBook = player.getInventory().getItem(bookSlot);
        if (isEnchantedTargetItem(heldBook)) {
            if (menu.getSlot(TABLE_BOOK).hasItem()) {
                stop("changed");
                return;
            }
            LOGGER.warn("Book Cycle found the enchanted netherite chestplate already recovered while the enchanting table was open; resuming at the grindstone");
            recoveringBook = false;
            recordPhase("OPEN_GRINDSTONE");
            open(client, Step.OPEN_GRINDSTONE, grindstone, Blocks.GRINDSTONE);
            return;
        }

        if (recoveringBook && isSingleBook()) {
            if (!initialDisenchant) completed++;
            initialDisenchant = false;
            recoveryAttempts = 0;
            recoveringBook = false;
        }
        if (!isSingleBook()) return;
        if (menu.getSlot(TABLE_BOOK).hasItem() || menu.getSlot(TABLE_LAPIS).hasItem()) {
            stop("changed");
            return;
        }
        if (expectedLapisCount >= 0) {
            if (countPlainItem(menu, Items.LAPIS_LAZULI, TABLE_LAPIS + 1, menu.slots.size()) != expectedLapisCount) {
                closeMenu();
                stop("craft_result");
                return;
            }
            expectedLapisCount = -1;
        }
        if (showControls) {
            closeMenu();
            open(client, Step.OPEN_TABLE, table, Blocks.ENCHANTING_TABLE);
            return;
        }
        if (tryEat(client)) return;
        if (!player.hasInfiniteMaterials() && player.experienceLevel < xpNeeded) {
            waitForXp(xpNeeded, "xp");
            return;
        }
        boolean needsLapis = !player.hasInfiniteMaterials()
                && countPlainItem(menu, Items.LAPIS_LAZULI, TABLE_LAPIS, menu.slots.size()) < TARGET_LAPIS;
        int freeSlots = freeInventorySlots();
        boolean inventoryPressure = freeSlots <= LOW_FREE_SLOTS && hasCleanupCandidate(menu);
        boolean emergencyPressure = freeSlots == 0 && hasEmergencyGuardianLoot(menu);
        if (needsLapis || inventoryPressure || emergencyPressure) {
            sortLoot(client, menu, needsLapis);
            return;
        }
        tableBook = player.getInventory().getItem(bookSlot).copy();
        recordPhase("PUT_BOOK");
        putBookOnTable(client, menu);
        lapisMoveState = -1;
        advance(Step.ENCHANT);
    }

    private boolean hasLapis(AbstractContainerMenu menu) {
        ItemStack lapis = menu.getSlot(TABLE_LAPIS).getItem();
        return player.hasInfiniteMaterials() || (lapis.is(Items.LAPIS_LAZULI)
                && lapis.getComponentsPatch().isEmpty() && lapis.getCount() >= TARGET_LAPIS);
    }

    private void moveLapis(Minecraft client, EnchantmentMenu menu) {
        if (hasLapis(menu) || menu.getStateId() == lapisMoveState) return;
        int index = findItem(menu, Items.LAPIS_LAZULI, TABLE_LAPIS + 1, menu.slots.size());
        if (index < 0) return;

        if (lapisMoveState != -1) LOGGER.info("Book Cycle is retrying the lapis move after a menu update");
        lapisMoveState = menu.getStateId();
        int before = menu.getSlot(TABLE_LAPIS).getItem().getCount();
        click(client, menu, index, 0, ContainerInput.QUICK_MOVE,
                () -> hasLapis(menu) || menu.getSlot(TABLE_LAPIS).getItem().getCount() > before,
                () -> {
                    if (!hasLapis(menu)) {
                        lapisMoveState = -1;
                        moveLapis(client, menu);
                    }
                });
    }

    private void putBookOnTable(Minecraft client, EnchantmentMenu menu) {
        bookMoveState = menu.getStateId();
        swap(client, menu, TABLE_BOOK);
    }

    private boolean tableHasBook(Minecraft client, EnchantmentMenu menu) {
        ItemStack input = menu.getSlot(TABLE_BOOK).getItem();
        if (ItemStack.matches(input, tableBook)) return true;
        if (!input.isEmpty()) {
            stop("table_occupied");
        } else if (menu.getStateId() != bookMoveState
                && ItemStack.matches(player.getInventory().getItem(bookSlot), tableBook)) {
            // A server correction can put the netherite chestplate back after a locally predicted swap.
            LOGGER.info("Book Cycle is retrying the netherite chestplate move after a correction during {}", step);
            putBookOnTable(client, menu);
        }
        return false;
    }

    private static int findItem(AbstractContainerMenu menu, Item item, int start, int end) {
        for (int index = start; index < end; index++) {
            var stack = menu.getSlot(index).getItem();
            if (stack.is(item) && stack.getComponentsPatch().isEmpty()) return index;
        }
        return -1;
    }

    private static int countMatching(AbstractContainerMenu menu, ItemStack expected, int start, int end) {
        int count = 0;
        for (int index = start; index < end; index++) {
            ItemStack stack = menu.getSlot(index).getItem();
            if (ItemStack.isSameItemSameComponents(stack, expected)) count += stack.getCount();
        }
        return count;
    }

    private static int countPlainItem(AbstractContainerMenu menu, Item item, int start, int end) {
        int count = 0;
        for (int index = start; index < end; index++) {
            ItemStack stack = menu.getSlot(index).getItem();
            if (stack.is(item) && stack.getComponentsPatch().isEmpty()) count += stack.getCount();
        }
        return count;
    }

    private static int lapisBlockTier(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.LAPIS_BLOCK)) return -1;
        if (stack.getComponentsPatch().isEmpty()) return 0;
        // shortcut: recognize the server's display name until its plugin metadata is available.
        String name = Normalizer.normalize(stack.getHoverName().getString(), Normalizer.Form.NFKC).strip();
        var match = COMPRESSED_LAPIS.matcher(name);
        return match.matches() ? ROMAN_TIERS.indexOf(match.group(1)) : -1;
    }

    private static int findLapisBlock(AbstractContainerMenu menu, int start, int end) {
        int best = -1;
        int bestTier = 10;
        for (int index = start; index < end; index++) {
            int tier = lapisBlockTier(menu.getSlot(index).getItem());
            if (tier == 0) return index;
            if (tier > 0 && tier < bestTier) {
                best = index;
                bestTier = tier;
            }
        }
        return best;
    }

    private void sortLoot(Minecraft client, EnchantmentMenu menu, boolean needsLapis) {
        if (skipLootCrafting && tickCounter >= lootCraftRetryAtTick) {
            skipLootCrafting = false;
        }

        int keepFish = -1;
        for (int index = TABLE_LAPIS + 1; index < menu.slots.size(); index++) {
            ItemStack stack = menu.getSlot(index).getItem();
            if (FISH.contains(stack.getItem()) && safeFood(stack)
                    && (keepFish < 0 || stack.getCount() > menu.getSlot(keepFish).getItem().getCount())) {
                keepFish = index;
            }
        }
        for (int index = TABLE_LAPIS + 1; index < menu.slots.size(); index++) {
            var stack = menu.getSlot(index).getItem();
            boolean discard = stack.is(Items.SEA_LANTERN) || (index != keepFish && FISH.contains(stack.getItem()));
            if (!stack.isEmpty() && discard && stack.getComponentsPatch().isEmpty()) {
                click(client, menu, index, 1, ContainerInput.THROW);
                return;
            }
        }
        if (!skipLootCrafting && nextLootRecipe() != null) {
            BlockPos craftingTable = nearestVisible(client, Blocks.CRAFTING_TABLE);
            if (craftingTable != null) {
                lootCleanupOnly = !needsLapis;
                open(client, Step.OPEN_CRAFTING_TABLE, craftingTable, Blocks.CRAFTING_TABLE);
                return;
            }
            if (!warnedNoCraftingTable) {
                message(player, "loot_crafting_table");
                warnedNoCraftingTable = true;
            }
        }

        if (freeInventorySlots() == 0 && throwEmergencyGuardianLoot(client, menu)) {
            return;
        }

        if (needsLapis) {
            restockLapis(client, menu, TABLE_LAPIS + 1);
        }
    }

    private int freeInventorySlots() {
        int free = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) free++;
        }
        return free;
    }

    private boolean hasCleanupCandidate(EnchantmentMenu menu) {
        int keepFish = -1;
        for (int index = TABLE_LAPIS + 1; index < menu.slots.size(); index++) {
            ItemStack stack = menu.getSlot(index).getItem();
            if (FISH.contains(stack.getItem()) && safeFood(stack)
                    && (keepFish < 0 || stack.getCount() > menu.getSlot(keepFish).getItem().getCount())) {
                keepFish = index;
            }
        }
        for (int index = TABLE_LAPIS + 1; index < menu.slots.size(); index++) {
            ItemStack stack = menu.getSlot(index).getItem();
            if (!stack.isEmpty() && stack.getComponentsPatch().isEmpty()
                    && (stack.is(Items.SEA_LANTERN) || (index != keepFish && FISH.contains(stack.getItem())))) {
                return true;
            }
        }
        return (!skipLootCrafting || tickCounter >= lootCraftRetryAtTick) && nextLootRecipe() != null;
    }

    private boolean hasEmergencyGuardianLoot(EnchantmentMenu menu) {
        for (int index = TABLE_LAPIS + 1; index < menu.slots.size(); index++) {
            ItemStack stack = menu.getSlot(index).getItem();
            if (!stack.isEmpty() && stack.getComponentsPatch().isEmpty()
                    && (stack.is(SHARD) || stack.is(CRYSTALS) || stack.is(PRISMARINE))) {
                return true;
            }
        }
        return false;
    }

    private boolean throwEmergencyGuardianLoot(Minecraft client, EnchantmentMenu menu) {
        for (int index = TABLE_LAPIS + 1; index < menu.slots.size(); index++) {
            ItemStack stack = menu.getSlot(index).getItem();
            if (stack.isEmpty() || !stack.getComponentsPatch().isEmpty()) continue;
            if (stack.is(SHARD) || stack.is(CRYSTALS) || stack.is(PRISMARINE)) {
                LOGGER.warn("Book Cycle is discarding {} x{} to recover from a full inventory",
                        stack.getHoverName().getString(), stack.getCount());
                click(client, menu, index, 1, ContainerInput.THROW);
                return true;
            }
        }
        return false;
    }

    private void restockLapis(Minecraft client, AbstractContainerMenu menu, int start) {
        checkedBarrels.clear();
        if (findLapisBlock(menu, start, menu.slots.size()) >= 0) startCraft(client);
        else refillFromBarrel(client);
    }

    private LootRecipe nextLootRecipe() {
        int shards = 0;
        int crystals = 0;
        int prismarine = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty() || !stack.getComponentsPatch().isEmpty()) continue;
            if (stack.is(SHARD)) shards += stack.getCount();
            if (stack.is(CRYSTALS)) crystals += stack.getCount();
            if (stack.is(PRISMARINE)) prismarine += stack.getCount();
        }
        if (shards >= 4 && crystals >= 5) return LANTERN_RECIPE;
        if (shards >= 4) return PRISMARINE_RECIPE;
        return prismarine >= 3 ? SLAB_RECIPE : null;
    }

    private void loadCraftingTable(Minecraft client) {
        if (!(client.gui.screen() instanceof CraftingScreen screen)) {
            if (client.gui.screen() != null) stop("changed");
            return;
        }
        CraftingMenu menu = screen.getMenu();
        if (!waitForContents(menu)) return;
        activeMenu = menu;
        lootRecipe = null;
        takingLootResult = false;
        advance(Step.CRAFT_LOOT);
    }

    private void craftLoot(Minecraft client, CraftingMenu menu) {
        var grid = menu.getInputGridSlots();
        int inventoryStart = grid.size() + 1;
        ItemStack result = menu.getResultSlot().getItem();
        if (!isSingleBook()) {
            stop("changed");
            return;
        }
        if (lootRecipe == null) {
            if (grid.stream().anyMatch(Slot::hasItem)) {
                stop("craft_occupied");
                return;
            }
            if (!result.isEmpty()) return;
            lootRecipe = nextLootRecipe();
            if (lootRecipe == null) {
                if (lootCleanupOnly) {
                    lootCleanupOnly = false;
                    open(client, Step.OPEN_TABLE, table, Blocks.ENCHANTING_TABLE);
                } else {
                    restockLapis(client, menu, inventoryStart);
                }
                return;
            }
            RecipeDisplayId recipeId = findLootRecipe(client, lootRecipe);
            if (recipeId == null || player.getInventory().getNonEquipmentItems().stream().anyMatch(stack ->
                    lootRecipe.ingredients().contains(stack.getItem()) && !stack.getComponentsPatch().isEmpty())) {
                skipLootCrafting(client, menu);
                return;
            }
            reserve(menu, () -> menu.getCarried().isEmpty() && grid.stream().noneMatch(Slot::hasItem),
                    () -> client.gameMode.handlePlaceRecipe(menu.containerId, recipeId, true),
                    () -> menu.getResultSlot().getItem().is(lootRecipe.output())
                            && grid.stream().anyMatch(Slot::hasItem), () -> {}, null);
            advance(Step.CRAFT_LOOT);
            return;
        }
        var input = grid.stream().map(Slot::getItem).filter(stack -> !stack.isEmpty()).toList();
        if (takingLootResult && input.isEmpty() && result.isEmpty()) {
            lootRecipe = null;
            takingLootResult = false;
            advance(Step.CRAFT_LOOT);
            return;
        }
        if (!result.is(lootRecipe.output()) || result.getCount() != lootRecipe.amount()
                || !result.getComponentsPatch().isEmpty()) return;
        if (!input.stream().map(ItemStack::getItem).toList().equals(lootRecipe.ingredients())) return;
        int sets = input.getFirst().getCount();
        if (input.stream().anyMatch(stack -> stack.getCount() != sets || !stack.getComponentsPatch().isEmpty())) return;

        boolean discard = lootRecipe != PRISMARINE_RECIPE;
        if (!discard && availableRoom(menu, inventoryStart, result) < sets * result.getCount()) {
            skipLootCrafting(client, menu);
            return;
        }
        if (takingLootResult) return;
        takingLootResult = true;
        click(client, menu, menu.getResultSlot().index, discard ? 1 : 0,
                discard ? ContainerInput.THROW : ContainerInput.QUICK_MOVE);
        advance(Step.CRAFT_LOOT);
    }

    private static int availableRoom(CraftingMenu menu, int start, ItemStack output) {
        int room = 0;
        for (int index = start; index < menu.slots.size(); index++) {
            Slot slot = menu.getSlot(index);
            ItemStack stack = slot.getItem();
            if (slot.mayPlace(output) && (stack.isEmpty() || ItemStack.isSameItemSameComponents(stack, output))) {
                room += Math.max(0, slot.getMaxStackSize(output) - stack.getCount());
            }
        }
        return room;
    }

    private RecipeDisplayId findLootRecipe(Minecraft client, LootRecipe recipe) {
        var context = SlotDisplayContext.fromLevel(client.level);
        for (var collection : player.getRecipeBook().getCollections()) {
            for (var entry : collection.getRecipes()) {
                if (!(entry.display() instanceof ShapedCraftingRecipeDisplay shaped)
                        || shaped.width() != recipe.width() || shaped.height() != recipe.height()) continue;
                ItemStack result = shaped.result().resolveForFirstStack(context);
                if (result.is(recipe.output()) && result.getCount() == recipe.amount()
                        && result.getComponentsPatch().isEmpty()
                        && shaped.ingredients().stream().map(slot -> slot.resolveForFirstStack(context).getItem())
                                .toList().equals(recipe.ingredients())) return entry.id();
            }
        }
        return null;
    }

    private void skipLootCrafting(Minecraft client, CraftingMenu menu) {
        skipLootCrafting = true;
        lootCraftRetryAtTick = tickCounter + LOOT_CRAFT_RETRY_TICKS;
        LOGGER.info("Book Cycle skipped loot crafting during {}; retrying in {} ticks",
                step, LOOT_CRAFT_RETRY_TICKS);
        message(player, "loot_craft_skipped");
        if (lootCleanupOnly) {
            lootCleanupOnly = false;
            open(client, Step.OPEN_TABLE, table, Blocks.ENCHANTING_TABLE);
        } else {
            restockLapis(client, menu, menu.getInputGridSlots().size() + 1);
        }
    }

    private void enchant(Minecraft client, EnchantmentMenu menu) {
        if (!tableHasBook(client, menu)) return;
        if (!hasLapis(menu)) {
            moveLapis(client, menu);
            return;
        }
        int cost = menu.costs[TARGET_OFFER];
        if (cost <= 0 || menu.enchantClue[TARGET_OFFER] < 0 || menu.levelClue[TARGET_OFFER] <= 0) {
            return;
        }
        if (cost < TARGET_LEVEL) {
            recoverBook(client, menu, TABLE_BOOK, () -> {
                closeMenu();
                stop("bookshelves", cost);
            });
            return;
        }
        if (!player.hasInfiniteMaterials()) {
            if (player.experienceLevel < cost) {
                waitForOfferXp(client, menu, cost);
                return;
            }
        }
        enchantSeed = menu.getEnchantmentSeed();
        lapisBeforeEnchant = menu.getSlot(TABLE_LAPIS).getItem().copy();
        retriedEnchant = false;
        recordPhase("ENCHANT");
        pressEnchant(client, menu);
        advance(Step.TAKE_ENCHANTED);
    }

    private void takeEnchantedBook(Minecraft client, EnchantmentMenu menu) {
        if (!isEnchantedTargetItem(menu.getSlot(TABLE_BOOK).getItem())) {
            return;
        }
        if (visibleHit(client, grindstone, Blocks.GRINDSTONE) == null) {
            stop("reach");
            return;
        }
        recordPhase("TAKE_ENCHANTED");
        recoverBook(client, menu, TABLE_BOOK, () -> {
            recordPhase("OPEN_GRINDSTONE");
            open(client, Step.OPEN_GRINDSTONE, grindstone, Blocks.GRINDSTONE);
        });
    }

    private void retryEnchant(Minecraft client, EnchantmentMenu menu) {
        // shortcut: allow one retry after a second, adjust if longer server delays are observed.
        if (retriedEnchant || ticksLeft > TIMEOUT_TICKS - ENCHANT_RETRY_TICKS
                || menu.getEnchantmentSeed() != enchantSeed
                || !ItemStack.matches(menu.getSlot(TABLE_BOOK).getItem(), tableBook)
                || !ItemStack.matches(menu.getSlot(TABLE_LAPIS).getItem(), lapisBeforeEnchant)) return;

        int cost = menu.costs[TARGET_OFFER];
        if (cost < TARGET_LEVEL || menu.enchantClue[TARGET_OFFER] < 0 || menu.levelClue[TARGET_OFFER] <= 0) return;
        if (!player.hasInfiniteMaterials() && player.experienceLevel < cost) {
            waitForOfferXp(client, menu, cost);
            return;
        }
        retriedEnchant = true;
        LOGGER.info("Book Cycle is retrying the level-30 enchant offer after no result");
        pressEnchant(client, menu);
    }

    private void waitForOfferXp(Minecraft client, EnchantmentMenu menu, int cost) {
        if (guardianMode) recoverBook(client, menu, TABLE_BOOK, () -> waitForXp(cost, "xp_offer", cost));
        else waitForXp(cost, "xp_offer", cost);
    }

    private void pressEnchant(Minecraft client, EnchantmentMenu menu) {
        reserve(menu, () -> menu.getCarried().isEmpty() && ItemStack.matches(menu.getSlot(TABLE_BOOK).getItem(), tableBook)
                        && ItemStack.matches(menu.getSlot(TABLE_LAPIS).getItem(), lapisBeforeEnchant)
                        && menu.getEnchantmentSeed() == enchantSeed && menu.costs[TARGET_OFFER] >= TARGET_LEVEL
                        && (player.hasInfiniteMaterials() || player.experienceLevel >= menu.costs[TARGET_OFFER]),
                () -> client.gameMode.handleInventoryButtonClick(menu.containerId, TARGET_OFFER),
                () -> isEnchantedTargetItem(menu.getSlot(TABLE_BOOK).getItem()), () -> {},
                retriedEnchant ? null : () -> retryEnchant(client, menu));
    }

    private boolean recoverAfterTimeout(Minecraft client) {
        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) return false;
        if (step == Step.OPEN_TABLE || step == Step.OPEN_GRINDSTONE) {
            if (client.gui.screen() != null || player.containerMenu != player.inventoryMenu) return false;
            ItemStack book = player.getInventory().getItem(bookSlot);
            if (step == Step.OPEN_TABLE ? !isPlainTargetItem(book) : !isEnchantedTargetItem(book)) return false;
            LOGGER.info("Book Cycle retrying {} after no response ({}/{})", step, ++recoveryAttempts, MAX_RECOVERY_ATTEMPTS);
            open(client, step, openingTarget, openingBlock());
        } else if (step == Step.ENCHANT || step == Step.TAKE_ENCHANTED) {
            ItemStack input = activeMenu.getSlot(TABLE_BOOK).getItem();
            Runnable reopen = () -> {
                actionDelay = RECOVERY_DELAY_TICKS;
                open(client, Step.OPEN_TABLE, table, Blocks.ENCHANTING_TABLE);
            };
            if (ItemStack.matches(input, tableBook) && !tableBook.isEmpty()) {
                LOGGER.info("Book Cycle recovering the netherite chestplate after {} timed out ({}/{})",
                        step, ++recoveryAttempts, MAX_RECOVERY_ATTEMPTS);
                recoverBook(client, activeMenu, TABLE_BOOK, reopen);
                return true;
            } else if (step == Step.TAKE_ENCHANTED && isEnchantedTargetItem(input)) {
                recoveryAttempts++;
                takeEnchantedBook(client, (EnchantmentMenu) activeMenu);
                return true;
            } else if (step == Step.TAKE_ENCHANTED && input.isEmpty()
                    && isEnchantedTargetItem(player.getInventory().getItem(bookSlot))) {
                recoveryAttempts++;
                open(client, Step.OPEN_GRINDSTONE, grindstone, Blocks.GRINDSTONE);
                return true;
            } else if (tableBook.isEmpty() || !input.isEmpty()
                    || !ItemStack.matches(player.getInventory().getItem(bookSlot), tableBook)) {
                return false;
            }
            LOGGER.info("Book Cycle reopening enchanting table after {} timed out ({}/{})",
                    step, ++recoveryAttempts, MAX_RECOVERY_ATTEMPTS);
            reopen.run();
        } else if (step == Step.GRIND && activeMenu instanceof GrindstoneMenu menu
                && !menu.getSlot(GrindstoneMenu.ADDITIONAL_SLOT).hasItem()) {
            ItemStack held = player.getInventory().getItem(bookSlot);
            Slot input = menu.getSlot(GrindstoneMenu.INPUT_SLOT);
            Slot result = menu.getSlot(GrindstoneMenu.RESULT_SLOT);
            if (!input.hasItem() && !result.hasItem() && held.getCount() == 1) {
                if (isEnchantedTargetItem(held)) {
                    recoveryAttempts++;
                    open(client, Step.OPEN_GRINDSTONE, grindstone, Blocks.GRINDSTONE);
                } else if (isPlainTargetItem(held)) {
                    recoveringBook = true;
                    open(client, Step.OPEN_TABLE, table, Blocks.ENCHANTING_TABLE);
                } else return false;
            } else if (isEnchantedTargetItem(input.getItem()) && isPlainTargetItem(result.getItem())) {
                recoveryAttempts++;
                grind(client, menu);
                return true;
            } else return false;
        } else {
            return false;
        }
        actionDelay = RECOVERY_DELAY_TICKS;
        return true;
    }

    private void loadGrindstone(Minecraft client) {
        if (!(client.gui.screen() instanceof GrindstoneScreen screen)) {
            if (client.gui.screen() != null) stop("changed");
            return;
        }
        GrindstoneMenu menu = screen.getMenu();
        if (!waitForContents(menu)) return;
        var book = player.getInventory().getItem(bookSlot);
        if (!isEnchantedTargetItem(book)) return;
        if (menu.getSlot(GrindstoneMenu.INPUT_SLOT).hasItem()
                || menu.getSlot(GrindstoneMenu.ADDITIONAL_SLOT).hasItem()
                || menu.getSlot(GrindstoneMenu.RESULT_SLOT).hasItem()) {
            stop("changed");
            return;
        }
        activeMenu = menu;
        recordPhase("PUT_GRIND_BOOK");
        swap(client, menu, GrindstoneMenu.INPUT_SLOT);
        advance(Step.GRIND);
    }

    private void grind(Minecraft client, GrindstoneMenu menu) {
        if (!isEnchantedTargetItem(menu.getSlot(GrindstoneMenu.INPUT_SLOT).getItem())
                || menu.getSlot(GrindstoneMenu.ADDITIONAL_SLOT).hasItem()) {
            stop("changed");
            return;
        }
        var result = menu.getSlot(GrindstoneMenu.RESULT_SLOT).getItem();
        if (result.isEmpty()) return;
        if (!isPlainTargetItem(result)) {
            stop("result");
            return;
        }
        if (visibleHit(client, table, Blocks.ENCHANTING_TABLE) == null) {
            stop("reach");
            return;
        }
        recordPhase("TAKE_RESULT");
        recoverBook(client, menu, GrindstoneMenu.RESULT_SLOT, () -> completeCycle(client));
    }

    private void completeCycle(Minecraft client) {
        recoveryAttempts = 0;
        recoveringBook = false;
        closeMenu();
        if (initialDisenchant) {
            initialDisenchant = false;
            Inventory inventory = player.getInventory();
            ItemStack stack = inventory.getItem(bookSlot);
            cycleItem = findCycleItem(inventory, stack) == bookSlot ? stack.copy() : ItemStack.EMPTY;
            startNextCycle(client);
            return;
        }
        completed++;
        finishCycleMetrics();

        if (guardianMode) {
            xpNeeded = TARGET_LEVEL;
            xpBottleUsedThisWait = false;
            xpWaitTicks = 0;
            if (completed % 2 == 0) {
                // Attack a reachable armor stand once after every two completed cycles,
                // before using XP bottles or attacking any XP-farm mobs.
                recordPhase("ARMOR_STAND");
                advance(Step.ARMOR_STAND);
            } else {
                recordPhase("WAIT_FOR_XP");
                step = Step.WAIT_FOR_XP;
            }
            return;
        }
        startNextCycle(client);
    }

    private void waitForXp(int required, String reason, Object... args) {
        if (!guardianMode) {
            if (isSingleBook()) closeMenu();
            stop(reason, args);
            return;
        }
        if (!isSingleBook()) {
            stop("changed");
            return;
        }
        xpNeeded = required;
        xpBottleUsedThisWait = false;
        xpWaitTicks = 0;
        closeMenu();
        step = Step.WAIT_FOR_XP;
        message(player, "waiting_xp");
    }

    private void tickArmorStand(Minecraft client) {
        if (client.gui.screen() != null || player.containerMenu != player.inventoryMenu || !isSingleBook()) {
            stop("stopped");
            return;
        }
        if (!player.getMainHandItem().is(ItemTags.SWORDS)) {
            int swordSlot = findHotbarSword();
            if (swordSlot >= 0) {
                player.getInventory().setSelectedSlot(swordSlot);
                return;
            }
            stop("sword");
            return;
        }

        Vec3 eye = player.getEyePosition();
        var stands = client.level.getEntitiesOfClass(ArmorStand.class,
                new AABB(eye, eye).inflate(player.entityInteractionRange()),
                stand -> stand.isAlive() && stand.isAttackable() && stand.isPickable()
                        && player.isWithinEntityInteractionRange(stand, 0));
        stands.sort(Comparator.comparingDouble(stand -> stand.getBoundingBox().distanceToSqr(eye)));
        for (ArmorStand stand : stands) {
            Vec3 point = findVisibleAimPoint(client, stand);
            if (point == null) continue;
            if (!canSwing()) return;
            player.lookAt(EntityAnchorArgument.Anchor.EYES, point);
            client.gameMode.attack(player, stand);
            player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getAttackAnimation(), false);
            actionDelay = afterAttackDelayTicks;
            LOGGER.info("Book Cycle attacked an armor stand after cycle {}", completed);
            break;
        }
        finishArmorStandAttempt();
    }

    private void finishArmorStandAttempt() {
        recordPhase("WAIT_FOR_XP");
        step = Step.WAIT_FOR_XP;
        xpWaitTicks = 0;
    }

    private void tickXpFarm(Minecraft client) {
        if (client.gui.screen() != null || player.containerMenu != player.inventoryMenu || !isSingleBook()) {
            stop("stopped");
            return;
        }
        if (step == Step.WAIT_FOR_XP) {
            xpWaitTicks++;
            if (player.experienceLevel < xpNeeded && xpWaitTicks % XP_WAIT_WARN_TICKS == 0) {
                LOGGER.warn("Book Cycle has waited {} ticks for XP (level {}, required {})",
                        xpWaitTicks, player.experienceLevel, xpNeeded);
            }
            if (tryEat(client)) return;
            if (player.experienceLevel < xpNeeded && tryUseInstantXpBottle(client)) return;
        }
        if (!player.getMainHandItem().is(ItemTags.SWORDS)) {
            int swordSlot = findHotbarSword();
            if (swordSlot >= 0) {
                player.getInventory().setSelectedSlot(swordSlot);
                return;
            }
            stop("sword");
            return;
        }
        if (step == Step.SWING) {
            LivingEntity target = aimedXpMob;
            Vec3 aimPoint = aimedXpPoint;
            if (target != null && aimPoint != null && canSwing() && target.isAlive()
                    && player.isWithinEntityInteractionRange(target, 0)
                    && isAimPointVisible(client, target, aimPoint)) {
                player.lookAt(EntityAnchorArgument.Anchor.EYES, aimPoint);
                client.gameMode.attack(player, target);
                player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getAttackAnimation(), false);
                actionDelay = afterAttackDelayTicks;
                nextXpTargetGuardian = target instanceof Silverfish;
                recordPhase("WAIT_FOR_XP");
            }
            aimedXpMob = null;
            aimedXpPoint = null;
            step = Step.WAIT_FOR_XP;
        } else if (player.experienceLevel < xpNeeded) {
            if (!canSwing()) return;
            if (aimXpMob(client, nextXpTargetGuardian)) return;
            if (aimXpMob(client, !nextXpTargetGuardian)) return;
        }
        if (player.hasInfiniteMaterials() || player.experienceLevel >= xpNeeded) {
            xpNeeded = guardianMode ? TARGET_LEVEL : 1;
            xpWaitTicks = 0;
            startNextCycle(client);
        }
    }

    private boolean tryUseInstantXpBottle(Minecraft client) {
        if (!guardianMode || step != Step.WAIT_FOR_XP || player.experienceLevel >= xpNeeded
                || xpBottleUsedThisWait
                || (lastFailedXpBottle != 0
                && System.nanoTime() - lastFailedXpBottle < XP_BOTTLE_RETRY_DELAY)) return false;

        Inventory inventory = player.getInventory();
        int source = -1;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (isInstantXpBottle(inventory.getItem(slot))) {
                source = slot;
                if (Inventory.isHotbarSlot(slot)) break;
            }
        }
        if (source < 0) return false;

        selectedBeforeXpBottle = inventory.getSelectedSlot();
        if (Inventory.isHotbarSlot(source)) {
            xpBottleHotbarSlot = source;
            inventory.setSelectedSlot(source);
            prepareXpBottle();
            return true;
        }

        xpBottleHotbarSlot = chooseXpBottleHotbarSlot(inventory, selectedBeforeXpBottle);
        if (xpBottleHotbarSlot < 0) {
            selectedBeforeXpBottle = -1;
            return false;
        }

        closeMenu();
        InventoryMenu menu = player.inventoryMenu;
        advance(Step.PREPARE_XP_BOTTLE);
        click(client, menu, InventoryMenu.INV_SLOT_START + source - Inventory.getSelectionSize(),
                xpBottleHotbarSlot, ContainerInput.SWAP, () -> true, () -> {
                    inventory.setSelectedSlot(xpBottleHotbarSlot);
                    prepareXpBottle();
                });
        return true;
    }

    private static boolean isInstantXpBottle(ItemStack stack) {
        if (!stack.is(Items.EXPERIENCE_BOTTLE) || stack.getComponentsPatch().isEmpty()) return false;
        String name = Normalizer.normalize(stack.getHoverName().getString(), Normalizer.Form.NFKC).strip();
        return name.matches("^経験値の瓶\\s*\\(.+\\)$");
    }

    private int chooseXpBottleHotbarSlot(Inventory inventory, int selectedSlot) {
        int nonFood = -1;
        int fallback = -1;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (slot == selectedSlot || slot == bookSlot || inventory.getItem(slot).is(ItemTags.SWORDS)) continue;
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) return slot;
            if (fallback < 0) fallback = slot;
            if (nonFood < 0 && !safeFood(stack)) nonFood = slot;
        }
        return nonFood >= 0 ? nonFood : fallback;
    }

    private void prepareXpBottle() {
        ItemStack held = player.getInventory().getItem(xpBottleHotbarSlot);
        if (!isInstantXpBottle(held)) {
            failXpBottle(Minecraft.getInstance(), "custom bottle missing");
            return;
        }
        xpBottleBeforeCount = held.getCount();
        xpBottleBeforeLevel = player.experienceLevel;
        xpBottleBeforeProgress = player.experienceProgress;
        xpBottleUseSent = false;
        advance(Step.USE_XP_BOTTLE);
        actionDelay = Math.max(actionDelay, OPEN_DELAY_TICKS);
    }

    private void tickXpBottle(Minecraft client) {
        if (client.gui.screen() != null || player.containerMenu != player.inventoryMenu) {
            failXpBottle(client, "inventory changed");
            return;
        }
        if (player.getInventory().getSelectedSlot() != xpBottleHotbarSlot) {
            player.getInventory().setSelectedSlot(xpBottleHotbarSlot);
            return;
        }

        ItemStack held = player.getInventory().getItem(xpBottleHotbarSlot);
        if (xpBottleUseSent) {
            boolean consumed = held.getCount() < xpBottleBeforeCount || !isInstantXpBottle(held);
            boolean gainedXp = player.experienceLevel > xpBottleBeforeLevel
                    || (player.experienceLevel == xpBottleBeforeLevel
                    && player.experienceProgress > xpBottleBeforeProgress + 0.0001F);
            if (consumed || gainedXp) {
                xpBottleUsedThisWait = true;
                finishXpBottle();
            }
            return;
        }

        if (!isInstantXpBottle(held)) {
            failXpBottle(client, "custom bottle missing");
            return;
        }
        aimAtAirForUse(client);
        KeyMapping.click(KeyMappingHelper.getBoundKeyOf(client.options.keyUse));
        xpBottleUseSent = true;
        ticksLeft = TIMEOUT_TICKS;
    }

    private void aimAtAirForUse(Minecraft client) {
        Vec3 eye = player.getEyePosition();
        double range = Math.max(4.5, player.blockInteractionRange() + 1.0);

        // Prefer directions that are unlikely to hit the enchanting setup.
        Vec3[] directions = {
                new Vec3(0, 1, 0),
                player.getLookAngle().yRot((float) (Math.PI / 2)).multiply(1, 0, 1).normalize(),
                player.getLookAngle().yRot((float) (-Math.PI / 2)).multiply(1, 0, 1).normalize(),
                player.getLookAngle().scale(-1).multiply(1, 0, 1).normalize(),
                new Vec3(0, -1, 0)
        };

        for (Vec3 direction : directions) {
            if (direction.lengthSqr() < 0.001) continue;
            Vec3 end = eye.add(direction.normalize().scale(range));
            BlockHitResult hit = client.level.clip(new ClipContext(
                    eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS) {
                player.lookAt(EntityAnchorArgument.Anchor.EYES, end);
                return;
            }
        }

        // If every sampled direction is blocked, look straight up as the least likely
        // direction to interact with the enchanting setup.
        player.setXRot(-90.0F);
    }

    private void finishXpBottle() {
        int restoreSlot = selectedBeforeXpBottle;
        if (restoreSlot < 0 || !player.getInventory().getItem(restoreSlot).is(ItemTags.SWORDS)) {
            int swordSlot = findHotbarSword();
            if (swordSlot >= 0) restoreSlot = swordSlot;
        }
        if (restoreSlot >= 0) player.getInventory().setSelectedSlot(restoreSlot);

        selectedBeforeXpBottle = -1;
        xpBottleHotbarSlot = -1;
        xpBottleUseSent = false;
        step = Step.WAIT_FOR_XP;
        actionDelay = Math.max(actionDelay, OPEN_DELAY_TICKS);
    }

    private void failXpBottle(Minecraft client, String reason) {
        LOGGER.warn("Book Cycle instant XP bottle attempt failed ({}); falling back to XP mobs", reason);
        lastFailedXpBottle = System.nanoTime();
        finishXpBottle();
    }

    private static boolean safeFood(ItemStack stack) {
        var food = stack.get(DataComponents.FOOD);
        var use = stack.get(DataComponents.CONSUMABLE);
        return !stack.isEmpty() && stack.getComponentsPatch().isEmpty() && food != null && food.nutrition() > 0
                && !food.canAlwaysEat() && use != null && use.onConsumeEffects().isEmpty();
    }

    private boolean tryEat(Minecraft client) {
        int threshold = player.getHealth() < player.getMaxHealth() ? 18 : 17;
        if (player.hasInfiniteMaterials() || player.getFoodData().getFoodLevel() > threshold || player.isUsingItem()
                || (lastFailedMeal != 0 && System.nanoTime() - lastFailedMeal < EAT_RETRY_DELAY)) return false;

        mealResumeStep = step;
        Inventory inventory = player.getInventory();
        foodHand = InteractionHand.MAIN_HAND;
        if (safeFood(player.getOffhandItem()) && FISH.contains(player.getOffhandItem().getItem())) {
            selectedBeforeEating = inventory.getSelectedSlot();
            foodHand = InteractionHand.OFF_HAND;
            prepareMeal(client);
            return true;
        }
        int source = -1;
        for (int index = 0; index < Inventory.INVENTORY_SIZE; index++) {
            ItemStack stack = inventory.getItem(index);
            if (!safeFood(stack)) continue;
            if (source == -1 || (!FISH.contains(inventory.getItem(source).getItem()) && FISH.contains(stack.getItem()))) {
                source = index;
            }
        }
        if (source < 0 && safeFood(player.getOffhandItem())) {
            selectedBeforeEating = inventory.getSelectedSlot();
            foodHand = InteractionHand.OFF_HAND;
            prepareMeal(client);
            return true;
        }
        if (source < 0) {
            mealResumeStep = null;
            return false;
        }
        selectedBeforeEating = inventory.getSelectedSlot();
        if (Inventory.isHotbarSlot(source)) {
            inventory.setSelectedSlot(source);
            prepareMeal(client);
            return true;
        }

        mealHotbarSlot = chooseMealHotbarSlot(inventory, selectedBeforeEating);
        if (mealHotbarSlot < 0) {
            mealResumeStep = null;
            return false;
        }
        closeMenu();
        InventoryMenu menu = player.inventoryMenu;
        advance(Step.PREPARE_FOOD);
        click(client, menu, InventoryMenu.INV_SLOT_START + source - Inventory.getSelectionSize(),
                mealHotbarSlot, ContainerInput.SWAP, () -> true, () -> {
                    // Keep the food in this hotbar slot after eating. The sword never leaves its slot,
                    // so future meals only need a cheap selected-slot change instead of two inventory swaps.
                    inventory.setSelectedSlot(mealHotbarSlot);
                    prepareMeal(client);
                });
        return true;
    }

    private void prepareMeal(Minecraft client) {
        closeMenu();
        eatingStack = player.getItemInHand(foodHand).copy();
        advance(Step.EAT);
        actionDelay = OPEN_DELAY_TICKS;
    }

    private void tickEating(Minecraft client) {
        if (client.gui.screen() != null || player.containerMenu != player.inventoryMenu) {
            stop("food_sync");
            return;
        }
        ItemStack inHand = player.getItemInHand(foodHand);
        if (eatingPlayer != player) {
            if (!safeFood(inHand) || !ItemStack.matches(inHand, eatingStack)) {
                failMeal(client, "held item changed");
                return;
            }
            eatingPlayer = player;
            client.gameMode.useItem(player, foodHand);
            return;
        }
        if (ItemStack.matches(inHand, eatingStack) && player.isUsingItem()) return;
        if (ItemStack.matches(inHand, eatingStack)) {
            if (ticksLeft > TIMEOUT_TICKS - 5) return;
            lastFailedMeal = System.nanoTime();
        }
        eatingPlayer = null;
        finishMeal(client);
    }

    private void failMeal(Minecraft client, String reason) {
        LOGGER.warn("Book Cycle meal attempt failed ({}); resuming and retrying later", reason);
        eatingPlayer = null;
        eatingStack = ItemStack.EMPTY;
        lastFailedMeal = System.nanoTime();
        finishMeal(client);
    }

    private void finishMeal(Minecraft client) {
        int restoreSlot = selectedBeforeEating;
        if (guardianMode && (restoreSlot < 0 || !player.getInventory().getItem(restoreSlot).is(ItemTags.SWORDS))) {
            int swordSlot = findHotbarSword();
            if (swordSlot >= 0) restoreSlot = swordSlot;
        }
        if (restoreSlot >= 0) player.getInventory().setSelectedSlot(restoreSlot);

        Step resume = mealResumeStep;
        selectedBeforeEating = -1;
        mealResumeStep = null;

        if (resume == Step.WAIT_FOR_XP) {
            step = Step.WAIT_FOR_XP;
            actionDelay = Math.max(actionDelay, OPEN_DELAY_TICKS);
            return;
        }
        open(client, Step.OPEN_TABLE, table, Blocks.ENCHANTING_TABLE);
    }

    private int chooseMealHotbarSlot(Inventory inventory, int selectedSlot) {
        int fallback = -1;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (slot == selectedSlot || slot == bookSlot) continue;
            if (inventory.getItem(slot).isEmpty()) return slot;
            if (fallback < 0 && !inventory.getItem(slot).is(ItemTags.SWORDS)) fallback = slot;
        }
        return fallback;
    }

    private int findHotbarSword() {
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (player.getInventory().getItem(slot).is(ItemTags.SWORDS)) return slot;
        }
        return -1;
    }

    private boolean aimXpMob(Minecraft client, boolean guardianTarget) {
        if (!guardianMode || step != Step.WAIT_FOR_XP || player.experienceLevel >= xpNeeded || !canSwing()) return false;
        Vec3 eye = player.getEyePosition();
        var nearby = client.level.getEntitiesOfClass(LivingEntity.class,
                new AABB(eye, eye).inflate(player.entityInteractionRange()),
                mob -> (guardianTarget ? mob instanceof Guardian : mob instanceof Silverfish)
                        && mob.isAlive() && mob.isAttackable() && player.isWithinEntityInteractionRange(mob, 0));
        nearby.sort(Comparator.comparingDouble(mob -> mob.getBoundingBox().distanceToSqr(eye)));

        float yaw = player.getYRot();
        float pitch = player.getXRot();
        for (LivingEntity mob : nearby) {
            Vec3 point = findVisibleAimPoint(client, mob);
            if (point == null) continue;
            player.lookAt(EntityAnchorArgument.Anchor.EYES, point);
            closeMenu();
            aimedXpMob = mob;
            aimedXpPoint = point;
            step = Step.SWING;
            return true;
        }
        player.setYRot(yaw);
        player.setXRot(pitch);
        return false;
    }

    private Vec3 findVisibleAimPoint(Minecraft client, LivingEntity mob) {
        if (!mob.isAlive() || !player.isWithinEntityInteractionRange(mob, 0)) return null;

        AABB box = mob.getBoundingBox();
        Vec3 eye = player.getEyePosition();

        // Try the geometrically nearest point first. This is the fast path for unobstructed mobs.
        Vec3 nearest = new Vec3(
                Math.max(box.minX, Math.min(eye.x, box.maxX)),
                Math.max(box.minY, Math.min(eye.y, box.maxY)),
                Math.max(box.minZ, Math.min(eye.z, box.maxZ)));
        if (isAimPointVisible(client, mob, nearest)) return nearest;

        // Scan only the box faces that can face the player's eyes. Every valid attack ray
        // enters the hitbox through one of these surfaces, so this covers narrow openings
        // much better than testing a handful of fixed interior points.
        if (eye.x <= box.minX) {
            Vec3 point = scanAimFace(client, mob, box, 0, box.minX);
            if (point != null) return point;
        } else if (eye.x >= box.maxX) {
            Vec3 point = scanAimFace(client, mob, box, 0, box.maxX);
            if (point != null) return point;
        }

        if (eye.y <= box.minY) {
            Vec3 point = scanAimFace(client, mob, box, 1, box.minY);
            if (point != null) return point;
        } else if (eye.y >= box.maxY) {
            Vec3 point = scanAimFace(client, mob, box, 1, box.maxY);
            if (point != null) return point;
        }

        if (eye.z <= box.minZ) {
            Vec3 point = scanAimFace(client, mob, box, 2, box.minZ);
            if (point != null) return point;
        } else if (eye.z >= box.maxZ) {
            Vec3 point = scanAimFace(client, mob, box, 2, box.maxZ);
            if (point != null) return point;
        }

        return null;
    }

    private Vec3 scanAimFace(Minecraft client, LivingEntity mob, AABB box, int axis, double fixed) {
        for (int first = 0; first < ENTITY_AIM_GRID; first++) {
            double a = (first + 0.5) / ENTITY_AIM_GRID;
            for (int second = 0; second < ENTITY_AIM_GRID; second++) {
                double b = (second + 0.5) / ENTITY_AIM_GRID;
                Vec3 point = switch (axis) {
                    case 0 -> new Vec3(fixed,
                            box.minY + box.getYsize() * a,
                            box.minZ + box.getZsize() * b);
                    case 1 -> new Vec3(
                            box.minX + box.getXsize() * a, fixed,
                            box.minZ + box.getZsize() * b);
                    case 2 -> new Vec3(
                            box.minX + box.getXsize() * a,
                            box.minY + box.getYsize() * b, fixed);
                    default -> throw new IllegalArgumentException("axis");
                };
                if (isAimPointVisible(client, mob, point)) return point;
            }
        }
        return null;
    }

    private boolean isAimPointVisible(Minecraft client, LivingEntity mob, Vec3 point) {
        if (!mob.isAlive() || !player.isWithinEntityInteractionRange(mob, 0)) return false;
        Vec3 eye = player.getEyePosition();
        BlockHitResult blockHit = client.level.clip(new ClipContext(
                eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return blockHit.getType() == HitResult.Type.MISS;
    }

    private boolean canSwing() {
        return player.onGround() && !player.isSprinting() && !player.isUsingItem()
                && player.getMainHandItem().is(ItemTags.SWORDS) && player.getAttackStrengthScale(0) >= 1;
    }

    private void refillFromBarrel(Minecraft client) {
        BlockPos barrel = nearestVisible(client, Blocks.BARREL, checkedBarrels);
        if (barrel == null) {
            closeMenu();
            stop("empty_supply");
            return;
        }
        checkedBarrels.add(barrel);
        open(client, Step.OPEN_BARREL, barrel, Blocks.BARREL);
    }

    private void loadBarrel(Minecraft client) {
        if (!(client.gui.screen() instanceof ContainerScreen screen) || screen.getMenu().getRowCount() != 3) {
            if (client.gui.screen() != null) stop("changed");
            return;
        }
        ChestMenu menu = screen.getMenu();
        if (!waitForContents(menu)) return;
        if (!isSingleBook()) {
            stop("changed");
            return;
        }
        activeMenu = menu;
        int end = menu.getContainer().getContainerSize();
        int source = findItem(menu, Items.LAPIS_LAZULI, 0, end);
        if (source < 0) source = findLapisBlock(menu, 0, end);
        if (source < 0) {
            refillFromBarrel(client);
            return;
        }
        if (player.getInventory().getFreeSlot() < 0) {
            closeMenu();
            stop("inventory_full");
            return;
        }
        ItemStack supply = menu.getSlot(source).getItem().copy();
        int previous = countMatching(menu, supply, end, menu.slots.size());
        click(client, menu, source, 0, ContainerInput.QUICK_MOVE,
                () -> countMatching(menu, supply, end, menu.slots.size()) > previous, () -> {});
        advance(Step.TAKE_SUPPLIES);
    }

    private void takeSupplies(Minecraft client, ChestMenu menu) {
        int start = menu.getContainer().getContainerSize();
        if (!isSingleBook()) {
            stop("changed");
        } else if (findItem(menu, Items.LAPIS_LAZULI, start, menu.slots.size()) >= 0) {
            open(client, Step.OPEN_TABLE, table, Blocks.ENCHANTING_TABLE);
        } else if (findLapisBlock(menu, start, menu.slots.size()) >= 0) {
            startCraft(client);
        }
    }

    private void startCraft(Minecraft client) {
        InventoryMenu menu = player.inventoryMenu;
        if (!isSingleBook()) {
            stop("changed");
            return;
        }
        closeMenu();
        if (menu.getResultSlot().hasItem() || menu.getInputGridSlots().stream().anyMatch(Slot::hasItem)) {
            stop("craft_occupied");
            return;
        }
        client.gui.setScreen(new InventoryScreen(player));
        activeMenu = menu;
        advance(Step.CRAFT_LAPIS);
        nextLapisIngredient(client, menu);
    }

    private void nextLapisIngredient(Minecraft client, InventoryMenu menu) {
        int lapis = countPlainItem(menu, Items.LAPIS_LAZULI, InventoryMenu.INV_SLOT_START, InventoryMenu.USE_ROW_SLOT_END);
        int source = findLapisBlock(menu, InventoryMenu.INV_SLOT_START, InventoryMenu.USE_ROW_SLOT_END);
        if (lapis >= LAPIS_REFILL_TARGET || (source < 0 && lapis >= TARGET_LAPIS)) {
            finishLapisCraft(client, lapis);
        } else if (source < 0) {
            closeMenu();
            stop("craft_result");
        } else {
            ItemStack ingredient = menu.getSlot(source).getItem();
            craftTier = lapisBlockTier(ingredient);
            ItemStack lapisStack = new ItemStack(Items.LAPIS_LAZULI);
            bulkLapisInput = craftTier == 0 && ingredient.getCount() > 1
                    && lapisOutputSpace(menu, lapisStack) + lapisStack.getMaxStackSize() >= ingredient.getCount() * 9;
            pickIngredient(client, menu, source);
        }
    }

    private void finishLapisCraft(Minecraft client, int lapis) {
        expectedLapisCount = lapis;
        open(client, Step.OPEN_TABLE, table, Blocks.ENCHANTING_TABLE);
    }

    private static int lapisOutputSpace(InventoryMenu menu, ItemStack output) {
        int available = 0;
        for (int index = InventoryMenu.INV_SLOT_START; index < InventoryMenu.USE_ROW_SLOT_END; index++) {
            Slot slot = menu.getSlot(index);
            ItemStack stored = slot.getItem();
            if (stored.isEmpty() || ItemStack.isSameItemSameComponents(stored, output)) {
                available += slot.getMaxStackSize(output) - stored.getCount();
            }
        }
        return available;
    }

    private void craftLapis(Minecraft client, InventoryMenu menu) {
        var grid = menu.getInputGridSlots();
        if (!isSingleBook() || grid.stream().skip(1).anyMatch(Slot::hasItem)) {
            stop("craft_result");
            return;
        }
        Slot input = grid.getFirst();
        if (!input.hasItem()) {
            if (!menu.getCarried().isEmpty()) {
                click(client, menu, input.index, bulkLapisInput ? 0 : 1, ContainerInput.PICKUP);
            } else if (ItemStack.isSameItemSameComponents(menu.getSlot(ingredientSource).getItem(), pickedIngredient)) {
                pickIngredient(client, menu, ingredientSource);
            }
            return;
        }
        if (returnIngredient(client, menu, InventoryMenu.INV_SLOT_START, InventoryMenu.USE_ROW_SLOT_END)) return;
        var result = menu.getResultSlot().getItem();
        if (result.isEmpty()) return;
        if (lapisBlockTier(input.getItem()) != craftTier || (!bulkLapisInput && input.getItem().getCount() != 1)) {
            stop("craft_result");
            return;
        }
        boolean expectedResult = craftTier == 0
                ? result.is(Items.LAPIS_LAZULI) && result.getComponentsPatch().isEmpty()
                : lapisBlockTier(result) == craftTier - 1;
        if (!expectedResult || result.getCount() != 9) return;
        int outputCount = input.getItem().getCount() * result.getCount();
        if (lapisOutputSpace(menu, result) < outputCount) {
            int lapis = countPlainItem(menu, Items.LAPIS_LAZULI, InventoryMenu.INV_SLOT_START, InventoryMenu.USE_ROW_SLOT_END);
            if (lapis >= TARGET_LAPIS) finishLapisCraft(client, lapis);
            else {
                closeMenu();
                stop("inventory_full");
            }
            return;
        }
        ItemStack output = result.copy();
        int previous = countMatching(menu, output, InventoryMenu.INV_SLOT_START, InventoryMenu.USE_ROW_SLOT_END);
        click(client, menu, menu.getResultSlot().index, 0, ContainerInput.QUICK_MOVE,
                () -> !input.hasItem() && countMatching(menu, output, InventoryMenu.INV_SLOT_START,
                        InventoryMenu.USE_ROW_SLOT_END) == previous + outputCount,
                () -> nextLapisIngredient(client, menu));
    }

    private boolean open(Minecraft client, Step next, BlockPos target, Block block) {
        BlockHitResult hit = visibleHit(client, target, block);
        if (hit == null) {
            stop("reach");
            return false;
        }
        closeMenu();
        advance(next);
        pendingUse = hit;
        openingTarget = target;
        actionDelay = Math.max(actionDelay, OPEN_DELAY_TICKS);
        player.lookAt(EntityAnchorArgument.Anchor.EYES, hit.getLocation());
        return true;
    }

    private void useTarget(Minecraft client) {
        if (client.gui.screen() != null || player.containerMenu != player.inventoryMenu) {
            stop("changed");
            return;
        }
        BlockHitResult hit = visibleHit(client, pendingUse.getBlockPos(), openingBlock());
        if (hit == null) {
            stop("reach");
            return;
        }
        pendingUse = null;
        player.lookAt(EntityAnchorArgument.Anchor.EYES, hit.getLocation());
        client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
    }

    private Block openingBlock() {
        return switch (step) {
            case OPEN_TABLE -> Blocks.ENCHANTING_TABLE;
            case OPEN_GRINDSTONE -> Blocks.GRINDSTONE;
            case OPEN_BARREL -> Blocks.BARREL;
            case OPEN_CRAFTING_TABLE -> Blocks.CRAFTING_TABLE;
            default -> throw new IllegalStateException("Unexpected step with pending use: " + step);
        };
    }

    private static BlockPos nearestVisible(Minecraft client, Block block) {
        return nearestVisible(client, block, Set.of());
    }

    private static BlockPos nearestVisible(Minecraft client, Block block, Set<BlockPos> excluded) {
        Vec3 eye = client.player.getEyePosition();
        double range = client.player.blockInteractionRange();
        BlockPos nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (BlockPos pos : BlockPos.betweenClosed(new AABB(eye, eye).inflate(range))) {
            if (excluded.contains(pos)) continue;
            BlockHitResult hit = visibleHit(client, pos, block);
            if (hit == null) continue;
            double distance = eye.distanceToSqr(hit.getLocation());
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = pos.immutable();
            }
        }
        return nearest;
    }

    private static BlockHitResult visibleHit(Minecraft client, BlockPos pos, Block block) {
        var state = client.level.getBlockState(pos);
        if (!state.is(block) || !client.level.getWorldBorder().isWithinBounds(pos)) return null;
        Vec3 eye = client.player.getEyePosition();
        double range = client.player.blockInteractionRange();
        // shortcut: sample each outline box at fixed points, refine sampling if narrower gaps matter.
        for (AABB box : state.getShape(client.level, pos).toAabbs()) {
            for (double x : AIM_FRACTIONS) {
                for (double y : AIM_FRACTIONS) {
                    for (double z : AIM_FRACTIONS) {
                        Vec3 end = new Vec3(pos.getX() + box.minX + x * box.getXsize(),
                                pos.getY() + box.minY + y * box.getYsize(),
                                pos.getZ() + box.minZ + z * box.getZsize());
                        BlockHitResult hit = client.level.clip(new ClipContext(
                                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
                        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
                                && eye.distanceToSqr(hit.getLocation()) <= range * range) return hit;
                    }
                }
            }
        }
        return null;
    }

    private boolean waitForContents(AbstractContainerMenu menu) {
        // A vanilla menu's initial content packet advances its state ID from zero.
        return player.containerMenu == menu && menu.getStateId() != 0;
    }

    private void swap(Minecraft client, AbstractContainerMenu menu, int targetSlot) {
        click(client, menu, targetSlot, bookSlot, ContainerInput.SWAP);
    }

    private void click(Minecraft client, AbstractContainerMenu menu, int slot, int button, ContainerInput input) {
        click(client, menu, slot, button, input, () -> true, () -> {});
    }

    private void click(Minecraft client, AbstractContainerMenu menu, int slot, int button, ContainerInput input,
                       BooleanSupplier confirmed, Runnable finish) {
        ItemStack before = menu.getSlot(slot).getItem().copy();
        ItemStack cursor = menu.getCarried().copy();
        ItemStack hotbar = input == ContainerInput.SWAP ? player.getInventory().getItem(button).copy() : ItemStack.EMPTY;
        reserve(menu, () -> ItemStack.matches(menu.getSlot(slot).getItem(), before)
                        && ItemStack.matches(menu.getCarried(), cursor)
                        && (input != ContainerInput.SWAP || ItemStack.matches(player.getInventory().getItem(button), hotbar)),
                () -> {
                    sendingClick = true;
                    try {
                        client.gameMode.handleContainerInput(menu.containerId, slot, button, input, player);
                    } finally {
                        sendingClick = false;
                    }
                }, () -> !ItemStack.matches(menu.getSlot(slot).getItem(), before)
                        && (input != ContainerInput.PICKUP || !ItemStack.matches(menu.getCarried(), cursor))
                        && (input != ContainerInput.SWAP || ItemStack.matches(player.getInventory().getItem(button), before))
                        && confirmed.getAsBoolean(), finish, null);
    }

    private void reserve(AbstractContainerMenu menu, BooleanSupplier ready, Runnable send,
                         BooleanSupplier confirmed, Runnable finish, Runnable retry) {
        if (menuAction != null) throw new IllegalStateException("An inventory action is already pending");
        menuAction = new MenuAction(menu, ready, send, confirmed, finish, retry);
        actionSent = false;
        if (ticksLeft <= 0) ticksLeft = TIMEOUT_TICKS;
    }

    private boolean processMenuAction(Minecraft client) {
        MenuAction action = menuAction;
        boolean inventorySwap = (step == Step.PREPARE_FOOD || step == Step.RESTORE_FOOD
                || step == Step.PREPARE_XP_BOTTLE || step == Step.PREPARE_CYCLE_ITEMS)
                && client.gui.screen() == null && action.menu() == player.inventoryMenu;
        boolean matchingScreen = client.gui.screen() instanceof AbstractContainerScreen<?> screen
                && screen.getMenu() == action.menu();
        if (player.containerMenu != action.menu() || !(matchingScreen || inventorySwap)) {
            stop("stopped");
            return false;
        }
        if (!actionSent) {
            if (!action.ready().getAsBoolean()) {
                menuAction = null;
                return false;
            }
            action.send().run();
            actionSent = true;
            ticksLeft = TIMEOUT_TICKS;
            return false;
        }
        if (action.confirmed().getAsBoolean()) {
            menuAction = null;
            ticksLeft = TIMEOUT_TICKS;
            action.finish().run();
            if (step != null) actionDelay = Math.max(actionDelay, extraDelayTicks);
            return true;
        } else if (action.retry() != null && ticksLeft <= TIMEOUT_TICKS - ENCHANT_RETRY_TICKS
                && action.ready().getAsBoolean()) {
            menuAction = null;
            action.retry().run();
        }
        return false;
    }

    private void pickIngredient(Minecraft client, AbstractContainerMenu menu, int source) {
        ingredientSource = source;
        pickedIngredient = menu.getSlot(source).getItem().copy();
        click(client, menu, source, 0, ContainerInput.PICKUP);
    }

    private boolean holdsPickedIngredient(AbstractContainerMenu menu) {
        ItemStack carried = menu.getCarried();
        return ItemStack.isSameItemSameComponents(carried, pickedIngredient)
                && carried.getCount() <= pickedIngredient.getCount();
    }

    private boolean returnIngredient(Minecraft client, AbstractContainerMenu menu, int start, int end) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) return false;
        if (canReturnIngredient(menu.getSlot(ingredientSource), carried)) {
            click(client, menu, ingredientSource, 0, ContainerInput.PICKUP);
            return true;
        }
        for (int index = start; index < end; index++) {
            if (canReturnIngredient(menu.getSlot(index), carried)) {
                click(client, menu, index, 0, ContainerInput.PICKUP);
                return true;
            }
        }
        stop("inventory_full");
        return true;
    }

    private static boolean canReturnIngredient(Slot slot, ItemStack carried) {
        ItemStack stored = slot.getItem();
        return slot.mayPlace(carried) && (stored.isEmpty() || ItemStack.isSameItemSameComponents(stored, carried))
                && stored.getCount() + carried.getCount() <= slot.getMaxStackSize(carried);
    }

    private void closeMenu() {
        if (activeMenu != null) player.closeContainer();
        activeMenu = null;
        menuAction = null;
        pickedIngredient = ItemStack.EMPTY;
    }

    private void recoverBook(Minecraft client, AbstractContainerMenu menu, int source, Runnable finish) {
        Inventory inventory = player.getInventory();
        if (!inventory.getItem(bookSlot).isEmpty()) {
            int free = inventory.getFreeSlot();
            if (free < 0) {
                stop("book_space");
                return;
            }
            // Direct loot can fill the netherite chestplate's hotbar slot while the netherite chestplate is in a menu.
            for (Slot slot : menu.slots) {
                if (slot.container == inventory && slot.getContainerSlot() == free) {
                    click(client, menu, slot.index, bookSlot, ContainerInput.SWAP,
                            () -> true, () -> recoverBook(client, menu, source, finish));
                    return;
                }
            }
        }
        if (!inventory.getItem(bookSlot).isEmpty()) {
            stop("book_space");
            return;
        }
        click(client, menu, source, bookSlot, ContainerInput.SWAP,
                () -> !(menu instanceof GrindstoneMenu) || !menu.getSlot(GrindstoneMenu.INPUT_SLOT).hasItem(), finish);
    }

    private static boolean isTargetItem(ItemStack stack) {
        return stack.is(Items.NETHERITE_CHESTPLATE) && stack.getCount() == 1;
    }

    private static boolean isEnchantedTargetItem(ItemStack stack) {
        if (!isTargetItem(stack)) return false;
        var enchantments = stack.get(DataComponents.ENCHANTMENTS);
        return enchantments != null && !enchantments.isEmpty();
    }

    private static boolean isPlainTargetItem(ItemStack stack) {
        return isTargetItem(stack) && !isEnchantedTargetItem(stack);
    }

    private boolean isSingleBook() {
        return isPlainTargetItem(player.getInventory().getItem(bookSlot));
    }

    private void advance(Step next) {
        step = next;
        ticksLeft = TIMEOUT_TICKS;
    }

    private void stop(String reason, Object... args) {
        if (step != null) LOGGER.info("Book Cycle stopped during {} ({})", step, reason);
        player.sendSystemMessage(Component.translatable("book_cycle.finished", completed,
                Component.translatable("book_cycle." + reason, args)));
        reset();
    }

    private void reset() {
        eatingPlayer = null;
        if (selectedBeforeEating >= 0 && player == Minecraft.getInstance().player) {
            player.getInventory().setSelectedSlot(selectedBeforeEating);
        }
        selectedBeforeEating = -1;
        mealResumeStep = null;
        lastFailedMeal = 0;
        selectedBeforeXpBottle = -1;
        xpBottleHotbarSlot = -1;
        xpBottleBeforeCount = 0;
        xpBottleBeforeLevel = 0;
        xpBottleBeforeProgress = 0;
        xpBottleUseSent = false;
        xpBottleUsedThisWait = false;
        lastFailedXpBottle = 0;
        activeMenu = null;
        menuAction = null;
        pendingUse = null;
        openingTarget = null;
        actionDelay = 0;
        showControls = false;
        checkedBarrels.clear();
        expectedLapisCount = -1;
        tableBook = ItemStack.EMPTY;
        aimedXpMob = null;
        aimedXpPoint = null;
        nextXpTargetGuardian = true;
        lootRecipe = null;
        pickedIngredient = ItemStack.EMPTY;
        skipLootCrafting = false;
        lootCraftRetryAtTick = 0;
        lootCleanupOnly = false;
        xpWaitTicks = 0;
        step = null;
        player = null;
        tickCounter = 0;
        cycleStartTick = 0;
        cycleStartNanos = 0;
        currentPhase = null;
        phaseStartTick = 0;
        currentPhaseTicks.clear();
        recentCycleTicks.clear();
        cycleServer = null;
        cycleItem = ItemStack.EMPTY;
        initialDisenchant = false;
    }

    private void recordPhase(String newPhase) {
        if (currentPhase != null) {
            int elapsed = tickCounter - phaseStartTick;
            currentPhaseTicks.merge(currentPhase, Math.max(0, elapsed), Integer::sum);
        }
        currentPhase = newPhase;
        phaseStartTick = tickCounter;
    }

    private void finishCycleMetrics() {
        if (currentPhase != null) {
            int elapsed = tickCounter - phaseStartTick;
            currentPhaseTicks.merge(currentPhase, Math.max(0, elapsed), Integer::sum);
            currentPhase = null;
        }
        int totalTicks = Math.max(1, tickCounter - cycleStartTick);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - cycleStartNanos));
        recentCycleTicks.add(totalTicks);

        StringBuilder details = new StringBuilder();
        currentPhaseTicks.forEach((phase, ticks) -> {
            if (!details.isEmpty()) details.append(", ");
            details.append(phase).append("=").append(ticks).append("t");
        });
        LOGGER.info("[BookCycle Perf] Cycle #{} took {}t ({}ms): {}",
                completed, totalTicks, durationMs, details);

        if (recentCycleTicks.size() % 50 == 0) {
            logSummaryPerf();
        }
        currentPhaseTicks.clear();
    }

    private void logSummaryPerf() {
        if (recentCycleTicks.isEmpty()) return;
        var sorted = new java.util.ArrayList<>(recentCycleTicks);
        java.util.Collections.sort(sorted);
        int count = sorted.size();
        int min = sorted.getFirst();
        int max = sorted.getLast();
        int p95 = sorted.get(Math.min(count - 1, (int) Math.ceil(count * 0.95) - 1));
        double avgTicks = sorted.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        double avgMs = avgTicks * 50.0;
        double rate = avgTicks > 0 ? (1200.0 / avgTicks) : 0;
        LOGGER.info(String.format(java.util.Locale.ROOT,
                "[BookCycle Perf Summary] Cycles %d-%d: avg=%.1ft (%.0fms), min=%dt, max=%dt, p95=%dt, rate=%.1f cycles/min",
                Math.max(1, count - 49), count, avgTicks, avgMs, min, max, p95, rate));
    }

    private void startNextCycle(Minecraft client) {
        beginCycleTiming("OPEN_TABLE");
        open(client, Step.OPEN_TABLE, table, Blocks.ENCHANTING_TABLE);
    }

    private void beginCycleTiming(String phase) {
        cycleStartTick = tickCounter;
        cycleStartNanos = System.nanoTime();
        currentPhaseTicks.clear();
        currentPhase = null;
        recordPhase(phase);
    }

    private static void message(LocalPlayer target, String key) {
        target.sendOverlayMessage(Component.translatable("book_cycle." + key));
    }
}
