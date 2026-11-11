package io.github.palkane.cepackoptimizer;

import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.recipe.*;
import com.github.retrooper.packetevents.protocol.recipe.data.*;
import com.github.retrooper.packetevents.protocol.recipe.display.*;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.*;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDeclareRecipes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRecipeBookAdd;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** Only recipe presentation stacks change; recipe keys and matching data retain their identities. */
final class RecipeResourceBridge {
    private final UnaryOperator<ItemStack> items;
    private boolean changed;
    private record ExtraSlot(Class<?> type, Constructor<?> constructor, List<Method> getters) {}
    private static final List<ExtraSlot> EXTRA_SLOTS = extraSlots();

    private RecipeResourceBridge(UnaryOperator<ItemStack> items) { this.items = items; }

    private static List<ExtraSlot> extraSlots() {
        List<ExtraSlot> result = new ArrayList<>();
        String[][] definitions = {
                {"WithAnyPotionSlotDisplay", "getDisplay"},
                {"OnlyWithComponentSlotDisplay", "getSource", "getComponent"},
                {"DyedSlotDisplay", "getSource", "getTarget"}
        };
        for (String[] definition : definitions) {
            try {
                Class<?> type = Class.forName(SlotDisplay.class.getPackageName() + "." + definition[0]);
                List<Method> getters = new ArrayList<>();
                for (int i = 1; i < definition.length; i++) getters.add(type.getMethod(definition[i]));
                Class<?>[] parameters = getters.stream().map(Method::getReturnType).toArray(Class<?>[]::new);
                result.add(new ExtraSlot(type, type.getConstructor(parameters), List.copyOf(getters)));
            } catch (ClassNotFoundException ignored) {
                // These slot wrappers were added after the minimum supported PacketEvents.
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot adapt recipe display " + definition[0], e);
            }
        }
        return List.copyOf(result);
    }

    static boolean translate(PacketWrapper<?> packet, UnaryOperator<ItemStack> items) {
        var rewrite = new RecipeResourceBridge(items);
        if (packet instanceof WrapperPlayServerRecipeBookAdd book) {
            List<WrapperPlayServerRecipeBookAdd.AddEntry> entries = new ArrayList<>();
            for (var entry : book.getEntries()) {
                var content = entry.getContents();
                var after = new RecipeDisplayEntry(content.getId(), rewrite.display(content.getDisplay()),
                        content.getGroup(), content.getCategory(), content.getIngredients());
                entries.add(new WrapperPlayServerRecipeBookAdd.AddEntry(after, entry.isNotification(), entry.isHighlight()));
            }
            if (rewrite.changed) book.setEntries(entries);
        } else if (packet instanceof WrapperPlayServerDeclareRecipes recipes) {
            if (recipes.getRecipes() != null) {
                Recipe<?>[] after = Arrays.stream(recipes.getRecipes()).map(rewrite::recipe).toArray(Recipe<?>[]::new);
                if (rewrite.changed) recipes.setRecipes(after);
            } else {
                List<SingleInputOptionDisplay> after = new ArrayList<>();
                for (var recipe : recipes.getStonecutterRecipes())
                    after.add(new SingleInputOptionDisplay(recipe.getInput(), rewrite.slot(recipe.getOptionDisplay())));
                if (rewrite.changed) recipes.setStonecutterRecipes(after);
            }
        }
        return rewrite.changed;
    }

    private ItemStack item(ItemStack before) {
        if (before == null) return null;
        ItemStack after = items.apply(before);
        changed |= !before.equals(after);
        return after;
    }

    private List<SlotDisplay<?>> slots(List<SlotDisplay<?>> source) {
        return source.stream().map(this::slot).toList();
    }

    private SlotDisplay<?> slot(SlotDisplay<?> source) {
        if (source instanceof ItemStackSlotDisplay value) return new ItemStackSlotDisplay(item(value.getStack()));
        if (source instanceof CompositeSlotDisplay value) return new CompositeSlotDisplay(slots(value.getContents()));
        if (source instanceof WithRemainderSlotDisplay value)
            return new WithRemainderSlotDisplay(slot(value.getInput()), slot(value.getRemainder()));
        if (source instanceof SmithingTrimSlotDisplay value) {
            var base = slot(value.getBase());
            var material = slot(value.getMaterial());
            return value.getTrimPattern() == null
                    ? new SmithingTrimSlotDisplay(base, material, slot(value.getPattern()))
                    : new SmithingTrimSlotDisplay(base, material, value.getTrimPattern());
        }
        for (ExtraSlot extra : EXTRA_SLOTS) {
            if (!extra.type.isInstance(source)) continue;
            try {
                Object[] args = new Object[extra.getters.size()];
                for (int i = 0; i < args.length; i++) {
                    Object value = extra.getters.get(i).invoke(source);
                    args[i] = value instanceof SlotDisplay<?> child ? slot(child) : value;
                }
                return (SlotDisplay<?>)extra.constructor.newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot preserve nested recipe display", e);
            }
        }
        return source;
    }

    private RecipeDisplay<?> display(RecipeDisplay<?> source) {
        if (source instanceof ShapedCraftingRecipeDisplay value)
            return new ShapedCraftingRecipeDisplay(value.getWidth(), value.getHeight(), slots(value.getIngredients()),
                    slot(value.getResult()), slot(value.getCraftingStation()));
        if (source instanceof ShapelessCraftingRecipeDisplay value)
            return new ShapelessCraftingRecipeDisplay(slots(value.getIngredients()),
                    slot(value.getResult()), slot(value.getCraftingStation()));
        if (source instanceof FurnaceRecipeDisplay value)
            return new FurnaceRecipeDisplay(slot(value.getIngredient()), slot(value.getFuel()),
                    slot(value.getResult()), slot(value.getCraftingStation()), value.getDuration(), value.getExperience());
        if (source instanceof StonecutterRecipeDisplay value)
            return new StonecutterRecipeDisplay(slot(value.getInput()), slot(value.getResult()), slot(value.getCraftingStation()));
        if (source instanceof SmithingRecipeDisplay value)
            return new SmithingRecipeDisplay(slot(value.getTemplate()), slot(value.getBase()), slot(value.getAddition()),
                    slot(value.getResult()), slot(value.getCraftingStation()));
        return source;
    }

    private Ingredient ingredient(Ingredient source) {
        return source == null ? null : new Ingredient(Arrays.stream(source.getOptions()).map(this::item).toArray(ItemStack[]::new));
    }

    private Ingredient[] ingredients(Ingredient[] source) {
        return Arrays.stream(source).map(this::ingredient).toArray(Ingredient[]::new);
    }

    private <T extends RecipeData> Recipe<?> recipe(Recipe<T> source) {
        RecipeData data = source.getData();
        RecipeData after = data;
        if (data instanceof ShapedRecipeData value)
            after = new ShapedRecipeData(value.getGroup(), value.getCategory(), item(value.getResult()),
                    value.isShowNotification(), value.getWidth(), value.getHeight(), ingredients(value.getIngredients()));
        else if (data instanceof ShapelessRecipeData value)
            after = new ShapelessRecipeData(value.getGroup(), value.getCategory(),
                    ingredients(value.getIngredients()), item(value.getResult()));
        else if (data instanceof CookedRecipeData value)
            after = new CookedRecipeData(value.getGroup(), value.getCategory(), ingredient(value.getIngredient()),
                    item(value.getResult()), value.getExperience(), value.getCookingTime());
        else if (data instanceof StoneCuttingRecipeData value)
            after = new StoneCuttingRecipeData(value.getGroup(), ingredient(value.getIngredient()), item(value.getResult()));
        else if (data instanceof SmithingRecipeData value)
            after = new SmithingRecipeData(ingredient(value.getTemplate()), ingredient(value.getBase()),
                    ingredient(value.getAddition()), item(value.getResult()));
        else if (data instanceof SmithingTrimRecipeData value)
            after = new SmithingTrimRecipeData(ingredient(value.getTemplate()), ingredient(value.getBase()), ingredient(value.getAddition()));
        return withData(source, after);
    }

    @SuppressWarnings("unchecked")
    private static <T extends RecipeData> Recipe<T> withData(Recipe<T> source, RecipeData data) {
        return new Recipe<>(source.getKey(), source.getSerializer(), (T)data);
    }
}
