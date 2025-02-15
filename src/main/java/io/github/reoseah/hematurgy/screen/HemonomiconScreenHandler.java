package io.github.reoseah.hematurgy.screen;

import io.github.reoseah.hematurgy.item.BloodItem;
import io.github.reoseah.hematurgy.item.HemonomiconItem;
import io.github.reoseah.hematurgy.recipe.HemonomiconRecipe;
import io.github.reoseah.hematurgy.resource.book_element.SlotConfiguration;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LecternBlockEntity;
import net.minecraft.component.DataComponentType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.screen.Property;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class HemonomiconScreenHandler extends ScreenHandler {
    public static final ScreenHandlerType<HemonomiconScreenHandler> TYPE = new ScreenHandlerType<>(HemonomiconScreenHandler::new, FeatureFlags.DEFAULT_ENABLED_FEATURES);

    public static final int PREVIOUS_PAGE_BUTTON = 0;
    public static final int NEXT_PAGE_BUTTON = 1;

    public final Context context;
    public final Property currentPage;
    public final Property isUttering;
    public final Inventory inventory = new HemonomiconInventory(this);
    private @Nullable Identifier utteranceId;
    private long utteranceStart;
    private @Nullable HemonomiconRecipe utteranceRecipe;

    public HemonomiconScreenHandler(int syncId, PlayerInventory playerInv) {
        this(syncId, playerInv, new ClientContext());
    }

    public HemonomiconScreenHandler(int syncId, PlayerInventory playerInv, Context context) {
        super(TYPE, syncId);
        this.context = context;
        this.currentPage = this.addProperty(context.createProperty(HemonomiconItem.CURRENT_PAGE));
        this.isUttering = this.addProperty(Property.create());

        for (int i = 0; i < 16; i++) {
            this.addSlot(new ConfigurableSlot(this.inventory, i, Integer.MIN_VALUE, Integer.MIN_VALUE));
        }

        for (int x = 0; x < 9; x++) {
            this.addSlot(new Slot(playerInv, x, 48 + x * 18, 185));
        }
    }

    public void startUtterance(Identifier id, ServerPlayerEntity player) {
        this.isUttering.set(1);
        this.utteranceId = id;
        this.utteranceStart = player.getWorld().getTime();

        player.getWorld().getRecipeManager() //
                .getAllMatches(HemonomiconRecipe.TYPE, this.inventory, player.getWorld()) //
                .stream() //
                .map(RecipeEntry::value) //
                .filter(recipe -> recipe.utterance.equals(id)) //
                .findFirst() //
                .ifPresent(recipe -> this.utteranceRecipe = recipe);
    }

    public void stopUtterance() {
        this.isUttering.set(0);
        this.utteranceId = null;
        this.utteranceStart = 0;
        this.utteranceRecipe = null;

        // decay blood by a lot as it wasn't decaying during the utterance
        // and it would allow to cheat the decay system otherwise
        for (int i = 0; i < 16; i++) {
            var stack = this.getSlot(i).getStack();
            if (stack != null && stack.isOf(BloodItem.INSTANCE)) {
                var time = stack.get(BloodItem.TIMESTAMP);
                if (time != null) {
                    stack.set(BloodItem.TIMESTAMP, time - 20 * 10);
                }
            }
        }
        this.sendContentUpdates();
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        Slot slot = this.slots.get(index);
        ItemStack stack = slot.getStack();
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack previous = stack.copy();
        if (index < 16) {
            // TODO: merge ritual blood stacks like the way they merge manually
            if (!this.insertItem(stack, 16, 16 + 9, true)) {
                return ItemStack.EMPTY;
            }
            slot.onQuickTransfer(stack, previous);
        } else {
            IntArraySet slotsToSpreadStackTo = new IntArraySet();
            int total = 0;
            for (int i = 0; i < 16; i++) {
                SlotConfiguration definition = ((ConfigurableSlot) this.getSlot(i)).config;
                if (definition != null && !definition.output && definition.ingredient != null && definition.ingredient.test(stack)) {
                    ItemStack slotStack = this.getSlot(i).getStack();
                    if (slotStack.isEmpty() || ItemStack.areItemsAndComponentsEqual(slotStack, stack)) {
                        slotsToSpreadStackTo.add(i);
                        total += slotStack.getCount();
                    }
                }
            }

            if (!slotsToSpreadStackTo.isEmpty()) {
                int targetCount = (total + stack.getCount()) / slotsToSpreadStackTo.size();
                for (int idx : slotsToSpreadStackTo) {
                    ItemStack slotStack = this.getSlot(idx).getStack();
                    int slotCount = slotStack.getCount();
                    int toAdd = Math.min(targetCount - slotCount, this.getSlot(idx).getMaxItemCount(stack) - slotCount);
                    if (toAdd > 0) {
                        stack = this.getSlot(idx).insertStack(stack, toAdd);
                    }
                }
                for (int idx : slotsToSpreadStackTo) {
                    if (stack.isEmpty()) {
                        break;
                    }
                    stack = this.getSlot(idx).insertStack(stack);
                }
            }
//            else if (!this.insertItem(stack, 16, 16 + 9, false)) {
//                return ItemStack.EMPTY;
//            }
        }

        if (stack.isEmpty()) {
            slot.setStack(ItemStack.EMPTY);
        } else {
            slot.markDirty();
        }

        if (stack.getCount() == previous.getCount()) {
            return ItemStack.EMPTY;
        }

        slot.onTakeItem(player, stack);
        return previous;
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        this.dropInventory(player, this.inventory);
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        // this gets called every tick, so it's a tick method effectively

        if (this.utteranceRecipe != null) {
            var recipeDuration = this.utteranceRecipe.duration;
            if (player.getWorld().getTime() - this.utteranceStart >= recipeDuration * player.getWorld().getTickManager().getTickRate()) {
                ItemStack result = this.utteranceRecipe.craft(this.inventory, player.getWorld(), player);
                if (!result.isEmpty()) {
                    this.insertResult(result, player);
                }
                this.stopUtterance();
            }
        }

        return this.context.canUse(player);
    }


    protected void insertResult(ItemStack result, PlayerEntity player) {
        boolean inserted = false;
        for (int i = 0; i < 16; i++) {
            SlotConfiguration configuration = ((ConfigurableSlot) this.slots.get(i)).getConfiguration();
            if (configuration != null && configuration.output) {
                ItemStack excess = insertStack(i, result);
                if (!excess.isEmpty()) {
                    player.dropItem(excess, false);
                }
                inserted = true;
                break;
            }
        }
        if (!inserted) {
            player.dropItem(result, false);
        }
    }

    protected ItemStack insertStack(int slot, ItemStack stack) {
        ItemStack current = this.inventory.getStack(slot);
        if (current.isEmpty()) {
            this.inventory.setStack(slot, stack);
            return ItemStack.EMPTY;
        } else if (ItemStack.areItemsAndComponentsEqual(current, stack)) {
            int amount = Math.min(stack.getCount(), current.getMaxCount() - current.getCount());
            if (amount > 0) {
                current.increment(amount);
                this.inventory.setStack(slot, current);
                stack.decrement(amount);
            }
            return stack;
        }
        return stack;
    }


    @Override
    public boolean onButtonClick(PlayerEntity player, int id) {
        switch (id) {
            case PREVIOUS_PAGE_BUTTON -> {
                int page = this.currentPage.get();
                if (page < 2) {
                    return false;
                }
                this.currentPage.set(page - 2);
                this.dropInventory(player, this.inventory);

                return true;
            }
            case NEXT_PAGE_BUTTON -> {
                int page = this.currentPage.get();
                this.currentPage.set(page + 2);
                this.dropInventory(player, this.inventory);

                return true;
            }
        }
        return false;
    }

    public void configureSlots(SlotConfiguration[] definitions) {
        for (int i = 0; i < definitions.length; i++) {
            ((ConfigurableSlot) this.slots.get(i)).setConfiguration(definitions[i]);
        }
        for (int i = definitions.length; i < 16; i++) {
            ((ConfigurableSlot) this.slots.get(i)).setConfiguration(null);
        }
    }

    public static abstract class Context {
        public abstract Property createProperty(DataComponentType<Integer> component);

        public abstract boolean canUse(PlayerEntity player);

//        public abstract ActivitySource getActivitySource();
    }

    public static class ClientContext extends Context {
        @Override
        public Property createProperty(DataComponentType<Integer> component) {
            return Property.create();
        }

        @Override
        public boolean canUse(PlayerEntity player) {
            return true;
        }

//        @Override
//        public ActivitySource getActivitySource() {
//            return null;
//        }
    }

    public static class LecternContext extends Context {
        private final World world;
        private final BlockPos pos;
        private final ItemStack stack;

        public LecternContext(World world, BlockPos pos, ItemStack stack) {
            this.world = world;
            this.pos = pos;
            this.stack = stack;
        }

        @Override
        public Property createProperty(DataComponentType<Integer> component) {
            return new Property() {
                @Override
                public int get() {
                    return stack.getOrDefault(component, 0);
                }

                @Override
                public void set(int value) {
                    stack.set(component, value);

                    BlockEntity be = world.getBlockEntity(pos);
                    if (be != null) {
                        be.markDirty();
                    }
                }
            };
        }

        @Override
        public boolean canUse(PlayerEntity player) {
            return this.world.getBlockState(this.pos).getBlock() instanceof LecternBlock && this.world.getBlockEntity(this.pos) instanceof LecternBlockEntity lectern && lectern.getBook() == this.stack && player.squaredDistanceTo(this.pos.getX() + 0.5D, this.pos.getY() + 0.5D, this.pos.getZ() + 0.5D) <= 64;
        }

//        @Override
//        public ActivitySource getActivitySource() {
//            if (this.world instanceof ServerWorld serverWorld) {
//                ActivityTracker tracker = ActivityTracker.get(serverWorld);
//                return tracker.getOrCreateLectern(this.pos);
//            }
//            return null;
//        }
    }

    public static class HandContext extends Context {
        private final Hand hand;
        private final ItemStack stack;

        public HandContext(Hand hand, ItemStack stack) {
            this.hand = hand;
            this.stack = stack;
        }

        @Override
        public Property createProperty(DataComponentType<Integer> component) {
            return new Property() {
                @Override
                public int get() {
                    return stack.getOrDefault(component, 0);
                }

                @Override
                public void set(int value) {
                    stack.set(component, value);
                }
            };
        }

        @Override
        public boolean canUse(PlayerEntity player) {
            return player.getStackInHand(this.hand) == this.stack;
        }

//        @Override
//        public ActivitySource getActivitySource() {
//            return null;
//        }
    }

    private static class HemonomiconInventory extends SimpleInventory {
        private final ScreenHandler handler;

        public HemonomiconInventory(ScreenHandler handler) {
            super(16);
            this.handler = handler;
        }

        public ItemStack removeStack(int slot, int amount) {
            ItemStack stack = super.removeStack(slot, amount);
            if (!stack.isEmpty()) {
                this.handler.onContentChanged(this);
            }
            return stack;
        }

        public void setStack(int slot, ItemStack stack) {
            super.setStack(slot, stack);
            this.handler.onContentChanged(this);
        }
    }
}
