package dev.haochen.mcmcp.arch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.api.Params;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Targeted edits to a building plan by component id, so a revision says what changes instead of
 * resending the whole plan:
 * <ul>
 *   <li>{@code {"op": "update", "id": "upper-front-left", "set": {"width": 3, "height": 3}}}: merge fields
 *       into a component ({@code null} removes a field). The roof is addressed by its id.</li>
 *   <li>{@code {"op": "remove", "id": "yard-fence"}}: delete a component (the roof becomes type none).</li>
 *   <li>{@code {"op": "add", "to": "openings", "value": {...}}}: append a component.</li>
 *   <li>{@code {"op": "set", "field": "footprint", "value": [13, 9]}}: replace a top-level field;
 *       with {@code "merge": true} an object value is merged instead (e.g. into {@code style}).</li>
 * </ul>
 */
public final class PlanOps {
	static final List<String> LISTS = List.of("floors", "rooms", "partitions", "openings", "stairs", "components", "outdoor", "patches");
	private static final Set<String> FIELDS = Set.of("name", "origin", "facing", "footprint", "style", "roof", "terrain",
			"furnish", "replaceExisting", "requirements", "floors", "rooms", "partitions", "openings", "stairs", "components",
			"outdoor", "patches");

	private PlanOps() {
	}

	/** Applies the ops to a copy of the plan and returns it. */
	public static JsonObject apply(JsonObject plan, JsonArray ops) {
		JsonObject p = plan.deepCopy();
		for (int i = 0; i < ops.size(); i++) {
			if (!ops.get(i).isJsonObject()) {
				throw Params.invalid("ops[" + i + "] must be an object");
			}
			JsonObject op = ops.get(i).getAsJsonObject();
			String where = "ops[" + i + "]";
			switch (Params.string(op, "op")) {
				case "update" -> {
					JsonObject target = find(p, Params.string(op, "id"), where);
					for (var e : Params.object(op, "set").entrySet()) {
						if (e.getKey().equals("id")) {
							throw Params.invalid(where + ": ids cannot be changed (remove and add instead)");
						}
						if (e.getValue().isJsonNull()) {
							target.remove(e.getKey());
						} else {
							target.add(e.getKey(), e.getValue().deepCopy());
						}
					}
				}
				case "remove" -> {
					String id = Params.string(op, "id");
					if (roofId(p).equals(id)) {
						JsonObject roof = new JsonObject();
						roof.addProperty("id", id);
						roof.addProperty("type", "none");
						p.add("roof", roof);
						continue;
					}
					boolean removed = false;
					for (String list : LISTS) {
						JsonArray a = p.has(list) ? p.getAsJsonArray(list) : new JsonArray();
						for (int k = 0; k < a.size(); k++) {
							if (idOf(a.get(k)).equals(id)) {
								a.remove(k);
								removed = true;
								break;
							}
						}
						if (removed) {
							break;
						}
					}
					if (!removed) {
						throw unknown(p, id, where);
					}
				}
				case "add" -> {
					String list = Params.string(op, "to");
					if (!LISTS.contains(list)) {
						throw Params.invalid(where + ".to must be one of " + LISTS);
					}
					JsonObject value = Params.object(op, "value");
					if (!value.has("id")) {
						throw Params.invalid(where + ".value needs an id");
					}
					JsonArray a = p.has(list) ? p.getAsJsonArray(list) : new JsonArray();
					a.add(value.deepCopy());
					p.add(list, a);
				}
				case "set" -> {
					String field = Params.string(op, "field");
					if (!FIELDS.contains(field)) {
						throw Params.invalid(where + ".field must be one of " + FIELDS);
					}
					JsonElement value = Params.require(op, "value");
					if (Params.bool(op, "merge", false) && value.isJsonObject() && p.has(field) && p.get(field).isJsonObject()) {
						for (var e : value.getAsJsonObject().entrySet()) {
							p.getAsJsonObject(field).add(e.getKey(), e.getValue().deepCopy());
						}
					} else {
						p.add(field, value.deepCopy());
					}
				}
				default -> throw Params.invalid(where + ".op must be update, remove, add or set");
			}
		}
		return p;
	}

	private static String roofId(JsonObject p) {
		return p.has("roof") && p.getAsJsonObject("roof").has("id") ? p.getAsJsonObject("roof").get("id").getAsString() : "roof";
	}

	private static String idOf(JsonElement e) {
		return e.isJsonObject() && e.getAsJsonObject().has("id") ? e.getAsJsonObject().get("id").getAsString() : "";
	}

	private static JsonObject find(JsonObject p, String id, String where) {
		if (roofId(p).equals(id)) {
			if (!p.has("roof")) {
				JsonObject roof = new JsonObject();
				roof.addProperty("id", id);
				p.add("roof", roof);
			}
			return p.getAsJsonObject("roof");
		}
		for (String list : LISTS) {
			if (p.has(list)) {
				for (JsonElement e : p.getAsJsonArray(list)) {
					if (idOf(e).equals(id)) {
						return e.getAsJsonObject();
					}
				}
			}
		}
		throw unknown(p, id, where);
	}

	private static RuntimeException unknown(JsonObject p, String id, String where) {
		List<String> ids = new ArrayList<>();
		ids.add(roofId(p));
		for (String list : LISTS) {
			if (p.has(list)) {
				p.getAsJsonArray(list).forEach(e -> ids.add(idOf(e)));
			}
		}
		return Params.invalid(where + ": no component with id \"" + id + "\"; components: " + ids);
	}
}
