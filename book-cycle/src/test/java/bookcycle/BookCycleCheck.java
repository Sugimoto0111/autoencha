package bookcycle;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.gui.screens.inventory.GrindstoneScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

public final class BookCycleCheck implements ClientModInitializer {
    private static final BlockPos TABLE = new BlockPos(2, 100, 1);
    private static final BlockPos FENCE = new BlockPos(1, 101, 1);
    private static final BlockPos GRINDSTONE = new BlockPos(-1, 100, 0);
    private static final BlockPos BARREL = new BlockPos(2, 100, 2);
    private static final BlockPos EMPTY_BARREL = new BlockPos(0, 100, 2);
    private static final List<String> COMPRESSED_NAMES = List.of("",
            "圧縮ラピスラズリブロック I (9個分)",
            "多重圧縮ラピスラズリブロック II (81個分)",
            "多重圧縮ラピスラズリブロック III (729個分)",
            "多重圧縮ラピスラズリブロック IV (6,561個分)",
            "多重圧縮ラピスラズリブロック V (59,049個分)",
            "多重圧縮ラピスラズリブロック VI (531,441個分)",
            "多重圧縮ラピスラズリブロック VII (4,782,969個分)",
            "多重圧縮ラピスラズリブロック VIII (43,046,721個分)",
            "多重圧縮ラピスラズリブロック IX (387,420,489個分)");

    private boolean creating;
    private boolean started;
    private CompletableFuture<Void> setup;
    private Guardian guardian;
    private Guardian scheduledGuardian;
    private int scheduledAttacks;
    private boolean xpGranted;
    private int setupTicks;
    private int runningTicks;
    private boolean sawTable;
    private boolean sawGrindstone;
    private boolean sawCraftBeforeBarrel;
    private boolean sawCraftAfterBarrel;
    private boolean sawBarrel;
    private int compressedStages;
    private int enchantClicks;
    private int lootPlacements;
    private boolean skippedGrindstone;
    private boolean bulkSlabs;
    private boolean requestedPause;
    private boolean resumedFromControls;
    private int clientTick;
    private int attackTick = -1;
    private boolean checkedAttackDelay;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        clientTick++;
        if (!creating && client.gui.screen() instanceof TitleScreen screen) {
            creating = true;
            client.options.pauseOnLostFocus = false;
            var settings = new LevelSettings("Book Cycle check", GameType.SURVIVAL,
                    LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
            client.createWorldOpenFlows().createFreshLevel("book-cycle-check", settings,
                    new WorldOptions(42L, false, false), WorldPresets::createNormalWorldDimensions, screen);
            return;
        }
        var server = client.getSingleplayerServer();
        if (client.player == null || server == null) return;
        if (setup == null) {
            setup = server.submit(() -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                for (BlockPos floor : BlockPos.betweenClosed(-3, 99, -3, 3, 99, 3)) {
                    server.overworld().setBlock(floor, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
                server.overworld().setBlock(TABLE, Blocks.ENCHANTING_TABLE.defaultBlockState(), Block.UPDATE_ALL);
                server.overworld().setBlock(FENCE, Blocks.DARK_OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
                server.overworld().setBlock(GRINDSTONE, Blocks.GRINDSTONE.defaultBlockState(), Block.UPDATE_ALL);
                server.overworld().setBlock(new BlockPos(-2, 100, 2), Blocks.CRAFTING_TABLE.defaultBlockState(), Block.UPDATE_ALL);
                server.overworld().setBlock(EMPTY_BARREL, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
                server.overworld().setBlock(BARREL, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
                BarrelBlockEntity barrel = (BarrelBlockEntity) server.overworld().getBlockEntity(BARREL);
                barrel.setItem(0, new ItemStack(Items.LAPIS_LAZULI, 2));
                barrel.setItem(1, new ItemStack(Items.LAPIS_BLOCK));
                barrel.setItem(2, new ItemStack(Items.GOLD_INGOT, 4));
                barrel.setItem(3, compressedLapis(9, 1));
                ItemStack sameNameWrongItem = new ItemStack(Items.DIAMOND_BLOCK);
                sameNameWrongItem.set(DataComponents.CUSTOM_NAME, Component.literal(COMPRESSED_NAMES.get(1)));
                barrel.setItem(4, sameNameWrongItem);
                server.addTickable(() -> {
                    if (scheduledGuardian != null && !scheduledGuardian.isAlive()) {
                        scheduledAttacks++;
                        int rounds = player.getStats().getValue(Stats.CUSTOM.get(Stats.ENCHANT_ITEM));
                        check(rounds == scheduledAttacks * 2,
                                "expected attack after " + (scheduledAttacks * 2) + " rounds, got " + rounds);
                        scheduledGuardian.discard();
                        scheduledGuardian = scheduledAttacks < 2 ? spawnGuardian(server.overworld()) : null;
                    }
                    var menu = player.inventoryMenu;
                    if (player.containerMenu != menu) return;
                    var input = menu.getInputGridSlots().getFirst().getItem();
                    if (!input.has(DataComponents.CUSTOM_NAME) || !input.is(Items.LAPIS_BLOCK)) return;
                    int tier = COMPRESSED_NAMES.indexOf(input.getHoverName().getString());
                    if (tier <= 0) return;
                    ItemStack result = tier == 1 ? new ItemStack(Items.LAPIS_BLOCK, 9) : compressedLapis(tier - 1, 9);
                    if (ItemStack.matches(menu.getResultSlot().getItem(), result)) return;
                    menu.getResultSlot().set(result);
                    menu.setRemoteSlot(menu.getResultSlot().index, result);
                    player.connection.send(new ClientboundContainerSetSlotPacket(
                            menu.containerId, menu.incrementStateId(), menu.getResultSlot().index, result));
                    compressedStages |= 1 << tier;
                });
                player.teleportTo(0.5, 100, 1.5);
                player.getInventory().setItem(6, new ItemStack(Items.BOOK));
                player.getInventory().setItem(10, new ItemStack(Items.LAPIS_LAZULI, 2));
                player.getInventory().setItem(11, new ItemStack(Items.LAPIS_BLOCK));
                player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
                player.getInventory().setItem(13, new ItemStack(Items.PRISMARINE_SHARD, 32));
                player.getInventory().setItem(12, new ItemStack(Items.PRISMARINE_CRYSTALS, 10));
                player.getInventory().setItem(14, new ItemStack(Items.COD, 64));
                player.getInventory().setItem(16, new ItemStack(Items.COD, 8));
                player.getInventory().setItem(19, new ItemStack(Items.PUFFERFISH, 12));
                player.getInventory().setItem(20, new ItemStack(Items.SEA_LANTERN, 3));
                ItemStack customCod = new ItemStack(Items.COD);
                customCod.set(DataComponents.CUSTOM_NAME, Component.literal("Keep me"));
                player.getInventory().setItem(15, customCod);
                player.getInventory().setItem(17, new ItemStack(Items.PRISMARINE_SLAB, 3));
                player.getInventory().setItem(18, new ItemStack(Items.PRISMARINE, 12));
                player.awardRecipes(server.overworld().recipeAccess().getLearnableRecipes().stream()
                        .filter(recipe -> List.of("sea_lantern", "prismarine", "prismarine_slab")
                                .contains(recipe.id().identifier().getPath())).toList());
                player.setExperienceLevels(0);
                player.getFoodData().setSaturation(0);
                player.getFoodData().setFoodLevel(16);
                guardian = spawnGuardian(server.overworld());
            });
            return;
        }
        if (!started) {
            if (!setup.isDone() || client.gui.screen() != null || ++setupTicks < 14) return;
            setup.join();
            var center = client.level.getBlockState(TABLE).getShape(client.level, TABLE).bounds().getCenter()
                    .add(TABLE.getX(), TABLE.getY(), TABLE.getZ());
            var hit = client.level.clip(new ClipContext(client.player.getEyePosition(), center,
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
            check(hit.getBlockPos().equals(FENCE), "fence must obstruct the ray to the center of the table");
            var key = KeyMapping.get("key.book_cycle.controls");
            check(key != null, "no controls key mapping");
            client.gameMode = new MultiPlayerGameMode(client, client.getConnection()) {
                @Override
                public void handleContainerInput(int containerId, int slot, int button, ContainerInput input, Player player) {
                    check(!(player.containerMenu instanceof CraftingMenu) || input != ContainerInput.PICKUP,
                            "loot crafting must not pick up ingredients with the cursor");
                    if (player.containerMenu instanceof CraftingMenu menu && slot == menu.getResultSlot().index
                            && menu.getResultSlot().getItem().is(Items.PRISMARINE_SLAB)) {
                        bulkSlabs |= menu.getInputGridSlots().stream().anyMatch(s -> s.getItem().getCount() >= 6);
                    }
                    var menu = player.containerMenu;
                    ItemStack before = menu.getSlot(slot).getItem().copy();
                    ItemStack carried = menu.getCarried().copy();
                    super.handleContainerInput(containerId, slot, button, input, player);
                    if (BookCycle.isSendingClick()) {
                        check(ItemStack.matches(before, menu.getSlot(slot).getItem())
                                && ItemStack.matches(carried, menu.getCarried()), "automation must wait for server updates");
                    }
                }

                @Override
                public void handlePlaceRecipe(int containerId, RecipeDisplayId recipe, boolean useMaxItems) {
                    lootPlacements++;
                    check(useMaxItems, "loot recipes must be placed in bulk");
                    super.handlePlaceRecipe(containerId, recipe, useMaxItems);
                }

                @Override
                public void handleInventoryButtonClick(int containerId, int buttonId) {
                    if (++enchantClicks <= 2) return;
                    super.handleInventoryButtonClick(containerId, buttonId);
                }

                @Override
                public InteractionResult useItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
                    if (attackTick >= 0 && hit.getBlockPos().equals(TABLE)) {
                        int elapsed = clientTick - attackTick;
                        check(elapsed >= 9 && elapsed < 20, "expected a 9-tick attack delay, got " + elapsed);
                        checkedAttackDelay = true;
                        attackTick = -1;
                    }
                    if (!skippedGrindstone && hit.getBlockPos().equals(GRINDSTONE)) {
                        skippedGrindstone = true;
                        return InteractionResult.PASS;
                    }
                    return super.useItemOn(player, hand, hit);
                }

                @Override
                public void attack(Player player, Entity target) {
                    if (target instanceof Guardian && xpGranted) attackTick = clientTick;
                    super.attack(player, target);
                }
            };
            client.player.setYRot(0);
            KeyMapping.click(key.getDefaultKey());
            started = true;
            return;
        }
        if (client.gui.screen() != null && client.gui.screen().getTitle().getString().equals("Book Cycle")) {
            var fields = client.gui.screen().children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList();
            check(fields.size() == 2, "expected separate general and post-attack delay controls");
            var delay = fields.getFirst();
            var attackDelay = fields.getLast();
            check(delay.getValue().equals(requestedPause ? "5" : "0"), "wrong saved delay on controls screen");
            check(attackDelay.getValue().equals(requestedPause ? "9" : "0"), "wrong saved post-attack delay");
            delay.setValue(requestedPause ? "0" : "5");
            if (!requestedPause) {
                delay.setValue("99");
                attackDelay.setValue("9");
                attackDelay.setValue("41");
            }
            String label = requestedPause ? "gui.done" : "book_cycle.start_guardian";
            var button = client.gui.screen().children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(widget -> widget.getMessage().equals(Component.translatable(label))).findFirst().orElseThrow();
            check(button.active, "expected a usable action on the controls screen");
            button.onPress(new KeyEvent(0, 0, 0));
            if (requestedPause) resumedFromControls = true;
            return;
        }
        if (!xpGranted) {
            xpGranted = server.submit(() -> {
                if (guardian.isAlive()) return false;
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                check(player.getStats().getValue(Stats.CUSTOM.get(Stats.ENCHANT_ITEM)) == 0,
                        "cycle should wait until the first attack supplies XP");
                player.setExperienceLevels(70);
                guardian.discard();
                scheduledGuardian = spawnGuardian(server.overworld());
                return true;
            }).join();
            return;
        }
        if (client.gui.screen() instanceof EnchantmentScreen) sawTable = true;
        if (client.gui.screen() instanceof GrindstoneScreen) {
            check(sawTable, "table must open before the grindstone");
            sawGrindstone = true;
            if (!requestedPause) {
                var screen = client.gui.screen();
                var key = KeyMapping.get("key.book_cycle.controls");
                check(!ScreenKeyboardEvents.allowKeyPress(screen).invoker().allowKeyPress(screen,
                        new KeyEvent(key.getDefaultKey().getValue(), 0, 0)), "controls must handle the key during crafting menus");
                requestedPause = true;
            }
        }
        if (client.gui.screen() instanceof ContainerScreen) sawBarrel = true;
        if (client.gui.screen() instanceof InventoryScreen) {
            if (sawBarrel) sawCraftAfterBarrel = true;
            else {
                sawCraftBeforeBarrel = true;
                check(client.player.getInventory().getItem(14).is(Items.COD), "one stack of cod must be kept");
            }
        }
        if (++runningTicks >= 1400 && (runningTicks >= 2200 || server.submit(() -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            return compressedStages == 0b1111111110
                    && player.getStats().getValue(Stats.CUSTOM.get(Stats.ENCHANT_ITEM)) > 22;
        }).join())) {
            check(sawTable && sawGrindstone, "both enchanting screens must open");
            check(resumedFromControls, "controls must pause and resume the loop");
            check(checkedAttackDelay, "the configured post-attack delay must be exercised");
            check(enchantClicks > 2 && skippedGrindstone, "missing enchant and grindstone responses must be exercised");
            check(lootPlacements >= 3 && bulkSlabs, "loot crafting must batch all three recipes, including existing prismarine");
            check(sawBarrel && sawCraftBeforeBarrel && sawCraftAfterBarrel, "expected hand craft, barrel refill and barrel block craft");
            server.submit(() -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                check(player.getStats().getValue(Stats.CUSTOM.get(Stats.ENCHANT_ITEM)) > 22,
                        "expected enchanting to resume using decompressed lapis");
                check(compressedStages == 0b1111111110, "all nine compression tiers should be processed");
                check(scheduledAttacks == 2, "two attacks triggered by the round counter must happen");
                BarrelBlockEntity barrel = (BarrelBlockEntity) server.overworld().getBlockEntity(BARREL);
                check(barrel.getItem(0).isEmpty() && barrel.getItem(1).isEmpty(), "expected all barrel lapis taken");
                check(barrel.getItem(2).getCount() == 4, "unrelated barrel contents moved");
                check(barrel.getItem(3).isEmpty(), "tier nine block was left in the barrel");
                check(barrel.getItem(4).is(Items.DIAMOND_BLOCK), "non-lapis with matching display name was taken");
                check(player.getStats().getValue(Stats.ITEM_CRAFTED.get(Items.SEA_LANTERN)) >= 2,
                        "lanterns must get priority over prismarine and slabs");
                check(player.getStats().getValue(Stats.ITEM_DROPPED.get(Items.SEA_LANTERN)) >= 5,
                        "both preexisting and newly crafted lanterns must be dropped");
                check(player.getStats().getValue(Stats.ITEM_DROPPED.get(Items.PRISMARINE_SLAB)) >= 36,
                        "slabs made from both preexisting and crafted prismarine must be dropped");
                check(player.getInventory().getItem(17).getCount() >= 3, "preexisting slabs were dropped");
                check(player.getStats().getValue(Stats.ITEM_DROPPED.get(Items.PRISMARINE_SHARD)) == 0,
                        "shards must be crafted, not dropped");
                check(player.getStats().getValue(Stats.ITEM_DROPPED.get(Items.COD)) >= 8,
                        "expected a complete stack of eight cod dropped on the server");
                check(player.getStats().getValue(Stats.ITEM_USED.get(Items.COD)) > 0, "auto-eating must consume cod from inventory");
                check(player.getStats().getValue(Stats.ITEM_USED.get(Items.PUFFERFISH)) == 0, "pufferfish must never be eaten");
                check(player.getInventory().getItem(15).has(DataComponents.CUSTOM_NAME), "custom cod was dropped");
                check(player.getInventory().getItem(0).is(Items.DIAMOND_SWORD), "sword was dropped");
            }).join();
            var book = client.player.getInventory().getItem(6);
            check(book.is(Items.BOOK) || book.is(Items.ENCHANTED_BOOK) || client.gui.screen() != null,
                    "the book should be in the hotbar or an active menu");
            System.out.println("BOOK CYCLE CHECK PASSED: bulk crafting and recovery from missing enchant and grindstone responses");
            client.stop();
        }
    }

    private static void check(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }

    private static Guardian spawnGuardian(ServerLevel level) {
        Guardian guardian = EntityTypes.GUARDIAN.create(level, EntitySpawnReason.COMMAND);
        check(guardian != null, "could not spawn a guardian");
        guardian.setNoAi(true);
        guardian.setNoGravity(true);
        guardian.setHealth(1);
        guardian.setPos(0.5, 100.85, -0.25);
        check(level.addFreshEntity(guardian), "guardian spawn was rejected");
        return guardian;
    }

    private static ItemStack compressedLapis(int tier, int count) {
        ItemStack stack = new ItemStack(Items.LAPIS_BLOCK, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(COMPRESSED_NAMES.get(tier)).withStyle(ChatFormatting.GOLD));
        return stack;
    }
}
