package dev.haochen.mcmcp.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.api.Params;
import dev.haochen.mcmcp.net.RpcException;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Crafting from the recipe book without a GUI: finds a crafting recipe for the item, checks the
 * inventory has the ingredients (and that a crafting table is within reach for recipes larger
 * than the 2x2 inventory grid), consumes them and adds the result. Container items such as
 * buckets are not returned.
 */
public final class Crafting {
	private Crafting() {
	}

	public static JsonObject craft(AgentPlayer agent, Item item, int count) {
		var server = agent.getServer();
		List<RecipeHolder<CraftingRecipe>> recipes = new ArrayList<>();
		for (RecipeHolder<CraftingRecipe> r : server.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
			if (!r.value().isSpecial() && r.value().getResultItem(server.registryAccess()).is(item)) {
				recipes.add(r);
			}
		}
		if (recipes.isEmpty()) {
			throw Params.invalid("no crafting recipe makes " + Inventories.id(item));
		}
		boolean table = tableNearby(agent);
		int made = 0, crafts = 0;
		String lastProblem = null;
		while (made < count) {
			boolean any = false;
			for (RecipeHolder<CraftingRecipe> r : recipes) {
				CraftingRecipe recipe = r.value();
				if (!recipe.canCraftInDimensions(2, 2) && !table) {
					lastProblem = "needs a crafting table within reach (place one with agent_place_block)";
					continue;
				}
				int[] slots = match(agent.getInventory(), recipe.getIngredients());
				if (slots == null) {
					lastProblem = "missing ingredients: " + describe(recipe.getIngredients());
					continue;
				}
				for (int slot : slots) {
					agent.getInventory().getItem(slot).shrink(1);
				}
				ItemStack out = recipe.getResultItem(server.registryAccess()).copy();
				made += out.getCount();
				crafts++;
				if (!agent.getInventory().add(out)) {
					agent.drop(out, false);
				}
				any = true;
				break;
			}
			if (!any) {
				break;
			}
		}
		if (crafts == 0) {
			JsonObject data = new JsonObject();
			data.add("inventory", Inventories.summary(agent.getInventory()));
			throw new RpcException(RpcException.INVALID_PARAMS, "cannot craft " + Inventories.id(item) + ": " + lastProblem, data);
		}
		JsonObject o = new JsonObject();
		o.addProperty("item", Inventories.id(item));
		o.addProperty("crafted", made);
		o.addProperty("have", Inventories.count(agent.getInventory(), item));
		if (made < count) {
			o.addProperty("warning", "stopped after " + made + ": " + lastProblem);
		}
		o.add("inventory", Inventories.summary(agent.getInventory()));
		return o;
	}

	/** One inventory slot per non-empty ingredient (a slot may serve several), or null. */
	private static int[] match(Inventory inv, List<Ingredient> ingredients) {
		int[] used = new int[inv.getContainerSize()];
		List<Integer> slots = new ArrayList<>();
		for (Ingredient ing : ingredients) {
			if (ing.isEmpty()) {
				continue;
			}
			int found = -1;
			for (int i = 0; i < inv.items.size(); i++) {
				ItemStack s = inv.items.get(i);
				if (!s.isEmpty() && s.getCount() > used[i] && ing.test(s)) {
					found = i;
					break;
				}
			}
			if (found < 0) {
				return null;
			}
			used[found]++;
			slots.add(found);
		}
		return slots.stream().mapToInt(Integer::intValue).toArray();
	}

	private static String describe(List<Ingredient> ingredients) {
		JsonArray parts = new JsonArray();
		for (Ingredient ing : ingredients) {
			if (!ing.isEmpty()) {
				ItemStack[] items = ing.getItems();
				parts.add(items.length == 1 ? Inventories.id(items[0].getItem())
						: "one of " + Arrays.stream(items).limit(4).map(s -> Inventories.id(s.getItem())).toList());
			}
		}
		return parts.toString();
	}

	private static boolean tableNearby(AgentPlayer agent) {
		BlockPos c = agent.blockPosition();
		for (BlockPos p : BlockPos.betweenClosed(c.offset(-4, -3, -4), c.offset(4, 4, 4))) {
			if (agent.serverLevel().getBlockState(p).is(Blocks.CRAFTING_TABLE)
					&& agent.getEyePosition().distanceToSqr(p.getCenter()) <= MineTask.REACH * MineTask.REACH) {
				return true;
			}
		}
		return false;
	}
}
