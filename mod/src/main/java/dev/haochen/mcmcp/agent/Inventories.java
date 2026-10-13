package dev.haochen.mcmcp.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.api.Params;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

/** Inventory helpers for the agent: counting, choosing tools, moving items into the hand. */
public final class Inventories {
	private Inventories() {
	}

	public static Item item(String id) {
		ResourceLocation rl = ResourceLocation.tryParse(id);
		Item item = rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
		if (item == null || item == Items.AIR) {
			throw Params.invalid("unknown item: " + id);
		}
		return item;
	}

	public static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	public static int count(Inventory inv, Item item) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(item)) {
				n += s.getCount();
			}
		}
		return n;
	}

	/** Moves the stack in {@code slot} into the selected hotbar slot (swapping) and selects it. */
	public static void toHand(Inventory inv, int slot) {
		if (Inventory.isHotbarSlot(slot)) {
			inv.selected = slot;
			return;
		}
		ItemStack held = inv.getItem(inv.selected);
		inv.setItem(inv.selected, inv.getItem(slot));
		inv.setItem(slot, held);
	}

	/** Puts the item into the main hand; false if the agent has none. */
	public static boolean holdItem(Inventory inv, Item item) {
		if (inv.getSelected().is(item)) {
			return true;
		}
		for (int i = 0; i < inv.items.size(); i++) {
			if (inv.items.get(i).is(item)) {
				toHand(inv, i);
				return true;
			}
		}
		return false;
	}

	/** Holds the inventory item that mines {@code state} fastest (preferring ones that get drops). */
	public static void holdBestTool(AgentPlayer agent, BlockState state) {
		Inventory inv = agent.getInventory();
		int best = -1;
		float bestScore = scoreOf(inv.getSelected(), state);
		for (int i = 0; i < inv.items.size(); i++) {
			float score = scoreOf(inv.items.get(i), state);
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		if (best >= 0) {
			toHand(inv, best);
		}
	}

	private static float scoreOf(ItemStack stack, BlockState state) {
		float speed = stack.getDestroySpeed(state);
		boolean drops = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
		// Don't wear out a tool on blocks it doesn't help with (a pickaxe on dirt).
		float wear = stack.isDamageableItem() && speed <= 1 ? 0.5f : 0;
		return speed + (drops ? 1000 : 0) - wear;
	}

	public static void consumeOne(Inventory inv, Item item) {
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(item)) {
				s.shrink(1);
				return;
			}
		}
	}

	/** {@code [{"item", "count"}]} totals by item. */
	public static JsonArray summary(Inventory inv) {
		Map<Item, Integer> totals = new LinkedHashMap<>();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty()) {
				totals.merge(s.getItem(), s.getCount(), Integer::sum);
			}
		}
		JsonArray a = new JsonArray();
		totals.forEach((item, n) -> {
			JsonObject o = new JsonObject();
			o.addProperty("item", id(item));
			o.addProperty("count", n);
			a.add(o);
		});
		return a;
	}
}
