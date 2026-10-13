package dev.haochen.mcmcp.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.haochen.mcmcp.McMcpMod;
import dev.haochen.mcmcp.api.AgentApiAccess;
import dev.haochen.mcmcp.api.Json;
import dev.haochen.mcmcp.jobs.RestoreJob;
import dev.haochen.mcmcp.net.RpcException;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.Set;

/**
 * Phase 2 (v2): inspection and targeted revision of registered buildings. These are the
 * deterministic halves of the visual loop (no vision model): validation, design metrics,
 * requirements and world verification, and diffs that touch only the changed components.
 */
public class RevisionGameTests implements FabricGameTest {
	private static final String EMPTY = FabricGameTest.EMPTY_STRUCTURE;

	@GameTest(template = EMPTY, batch = "arch_confirm_frozen", timeoutTicks = 200)
	public void confirmationDoesNotExpandTheApprovedTerrainWork(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		BlockPos at = ArchGameTests.FAR.offset(0, 0, 2040);
		JsonObject b = plan(helper, "frozen-test", at);
		int id = AgentApiAccess.call("build.preview", args(b)).get("previewId").getAsInt();
		var entry = McMcpMod.services().buildings().get("frozen-test");
		int count = entry.compiled.size();
		helper.setBlock(at.offset(2, -2, 2), Blocks.AIR);
		helper.setBlock(at.offset(2, -3, 2), Blocks.AIR);
		JsonObject confirm = new JsonObject(); confirm.addProperty("previewId", id);
		int job = AgentApiAccess.call("build.confirmPreview", confirm).get("jobId").getAsInt();
		helper.startSequence().thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job))
				.thenExecute(() -> {
					helper.assertTrue(entry.compiled.size() == count, "confirmation retains the preview's block list");
					helper.assertBlockPresent(Blocks.AIR, at.offset(2, -2, 2));
					helper.assertBlockPresent(Blocks.AIR, at.offset(2, -3, 2));
				}).thenSucceed();
	}

	@GameTest(template = EMPTY, batch = "arch_preview_stack")
	public void overlappingPreviewsKeepTheNewestDisplay(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		var s = McMcpMod.services(); var level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(2, 0, 2));
		helper.setBlock(new BlockPos(2, 0, 2), Blocks.AIR);
		var older = new dev.haochen.mcmcp.world.BlockList(); older.put(pos, Blocks.STONE.defaultBlockState());
		var newer = new dev.haochen.mcmcp.world.BlockList(); newer.put(pos, Blocks.GOLD_BLOCK.defaultBlockState());
		int id1 = s.previews().create(s, new dev.haochen.mcmcp.api.Plan(level, older, "test", "older", true, older.stats(1))).get("previewId").getAsInt();
		int id2 = s.previews().create(s, new dev.haochen.mcmcp.api.Plan(level, newer, "test", "newer", true, newer.stats(1))).get("previewId").getAsInt();
		helper.assertTrue(s.previews().visibleState(level, pos).is(Blocks.GOLD_BLOCK), "newest preview wins");
		var revised = new dev.haochen.mcmcp.world.BlockList(); revised.put(pos, Blocks.DIAMOND_BLOCK.defaultBlockState());
		s.previews().update(s, id1, new dev.haochen.mcmcp.api.Plan(level, revised, "test", "revised older", true, revised.stats(1)));
		helper.assertTrue(s.previews().visibleState(level, pos).is(Blocks.GOLD_BLOCK), "revising an older preview preserves the newer overlay");
		s.previews().discard(id2);
		helper.assertTrue(s.previews().visibleState(level, pos).is(Blocks.DIAMOND_BLOCK), "discard reveals the remaining revised preview");
		s.previews().discard(id1);
		helper.assertTrue(s.previews().visibleState(level, pos).isAir(), "last discard shows the world");
		helper.succeed();
	}

	@GameTest(template = EMPTY, batch = "arch_capture_fov")
	public void interiorPlanningAndCaptureUseTheSameFov(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		JsonObject b = plan(helper, "fov-test", ArchGameTests.FAR.offset(0, 0, 1800));
		int id = AgentApiAccess.call("build.preview", args(b)).get("previewId").getAsInt();
		var previous = McMcpMod.viewHook();
		try {
			McMcpMod.setViewHook((shots, width, height, fov, daylight) -> {
				helper.assertTrue(fov == 85, "renderer receives the same 85-degree FOV as view planning");
				return java.util.concurrent.CompletableFuture.completedFuture(shots.stream().map(shot -> new byte[0]).toList());
			});
			JsonObject p = new JsonObject(); p.addProperty("name", "fov-test"); p.addProperty("fov", 35);
			JsonObject result = AgentApiAccess.call("arch.inspect", p);
			helper.assertTrue(result.get("fov").getAsInt() == 85 && result.getAsJsonArray("shots").size() == 7, "report exposes the effective FOV");
		} finally { McMcpMod.setViewHook(previous); McMcpMod.services().previews().discard(id); }
		helper.succeed();
	}

	@GameTest(template = EMPTY, batch = "arch_partial_cancel")
	public void partialCancellationIsUndoableAndCapturesAfterEarlierJobs(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		var s = McMcpMod.services();
		BlockPos pos = helper.absolutePos(new BlockPos(2, 0, 2));
		helper.setBlock(new BlockPos(2, 0, 2), Blocks.AIR);
		var blocks = new dev.haochen.mcmcp.world.BlockList();
		blocks.put(pos, Blocks.STONE.defaultBlockState()); blocks.put(pos.east(), Blocks.STONE.defaultBlockState());
		var cells = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<dev.haochen.mcmcp.arch.PlanCompiler.WorldCell>();
		cells.put(pos.asLong(), new dev.haochen.mcmcp.arch.PlanCompiler.WorldCell(Blocks.STONE.defaultBlockState(), "test"));
		cells.put(pos.east().asLong(), new dev.haochen.mcmcp.arch.PlanCompiler.WorldCell(Blocks.STONE.defaultBlockState(), "test"));
		JsonObject b = new JsonObject(); b.addProperty("name", "partial-test");
		var entry = s.buildings().update(b, helper.getLevel(), cells);
		var manager = new dev.haochen.mcmcp.jobs.JobManager(1, (method, params) -> {});
		manager.submit(new dev.haochen.mcmcp.jobs.PlaceJob("test", "earlier queued write", helper.getLevel(), 1,
				java.util.List.of(new dev.haochen.mcmcp.jobs.PlaceJob.Placement(pos, Blocks.GOLD_BLOCK.defaultBlockState())).iterator(), s.history()));
		var p = new dev.haochen.mcmcp.api.Plan(helper.getLevel(), blocks, "test", "partial build", true, blocks.stats(5));
		var job = new dev.haochen.mcmcp.jobs.BuildingJob(s, entry, p, blocks.placements());
		manager.submit(job); entry.pendingJobId = job.id();
		manager.tick(helper.getLevel().getServer()); // Earlier job writes gold.
		manager.tick(helper.getLevel().getServer()); // Building writes one block, then is cancelled.
		manager.cancel(helper.getLevel().getServer(), job.id());
		helper.assertTrue(entry.incomplete && entry.pendingJobId < 0, "partial build is not reported complete");
		helper.assertTrue(entry.original(pos).state().is(Blocks.GOLD_BLOCK), "originals captured after the earlier queued write");
		manager.submit(new RestoreJob(s.history(), true));
		manager.tick(helper.getLevel().getServer());
		helper.assertBlockPresent(Blocks.GOLD_BLOCK, new BlockPos(2, 0, 2));
		helper.assertTrue(!entry.built && !entry.incomplete, "undo restores the pre-building registry state");
		helper.succeed();
	}

	@GameTest(template = EMPTY, batch = "arch_staged_preview", timeoutTicks = 300)
	public void replacementPreviewCanBeDiscardedOrConfirmedAndUndone(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		BlockPos at = ArchGameTests.FAR.offset(0, 0, 1440);
		JsonObject original = plan(helper, "staged-test", at);
		JsonObject moved = original.deepCopy(); moved.add("origin", Json.blockPos(helper.absolutePos(at.offset(24, 0, 0))));
		int[] job = {AgentApiAccess.call("build.placeBlueprint", args(original)).get("jobId").getAsInt()};
		helper.startSequence()
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job[0]))
				.thenExecute(() -> {
					var e = McMcpMod.services().buildings().get("staged-test"); var baseline = e.applied;
					int id = AgentApiAccess.call("build.preview", args(moved)).get("previewId").getAsInt();
					helper.assertTrue(e.applied == baseline, "preview preserves the applied baseline");
					helper.assertBlockPresent(Blocks.SPRUCE_PLANKS, at.offset(2, 0, 2));
					McMcpMod.services().previews().discard(id);
					helper.assertTrue(e.plan.equals(original), "discard restores the built design");
					id = AgentApiAccess.call("build.preview", args(moved)).get("previewId").getAsInt();
					JsonObject confirm = new JsonObject(); confirm.addProperty("previewId", id);
					job[0] = AgentApiAccess.call("build.confirmPreview", confirm).get("jobId").getAsInt();
				})
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job[0]))
				.thenExecute(() -> {
					helper.assertBlockPresent(Blocks.AIR, at.offset(2, 0, 2));
					helper.assertBlockPresent(Blocks.SPRUCE_PLANKS, at.offset(26, 0, 2));
					job[0] = McMcpGameTests.submitUndo();
				})
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job[0]))
				.thenExecute(() -> {
					helper.assertBlockPresent(Blocks.SPRUCE_PLANKS, at.offset(2, 0, 2));
					helper.assertTrue(McMcpMod.services().buildings().get("staged-test").plan.equals(original), "undo restores the original design");
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY, batch = "arch_persistence")
	public void registryReloadKeepsDimensionBaselineAndOriginalNbt(GameTestHelper helper) throws Exception {
		var dir = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("."), "registry-persistence-");
		var first = new dev.haochen.mcmcp.arch.BuildingRegistry(dir);
		var second = new dev.haochen.mcmcp.arch.BuildingRegistry(dir);
		var other = new dev.haochen.mcmcp.arch.BuildingRegistry(dir.resolve("other-world"));
		try {
			BlockPos local = new BlockPos(2, 0, 2), pos = helper.absolutePos(local);
			helper.setBlock(local, Blocks.CHEST);
			net.minecraft.world.level.block.entity.ChestBlockEntity chest = helper.getBlockEntity(local);
			chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 5));
			var original = dev.haochen.mcmcp.history.BlockSnapshot.capture(helper.getLevel(), pos);
			JsonObject b = new JsonObject(); b.addProperty("name", "persisted-test");
			var cells = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<dev.haochen.mcmcp.arch.PlanCompiler.WorldCell>();
			cells.put(pos.asLong(), new dev.haochen.mcmcp.arch.PlanCompiler.WorldCell(Blocks.STONE.defaultBlockState(), "test"));
			var nether = helper.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
			var e = first.update(b, nether, cells); e.built = true; e.revision = 2; e.rounds = 2; e.applied = e.snapshot();
			dev.haochen.mcmcp.arch.BuildingRegistry.captureOriginal(e, pos, original);
			first.save(e); first.flush();
			helper.assertTrue(second.list().size() == 1, "saved entries appear without prior get calls");
			var loaded = second.get("persisted-test");
			helper.assertTrue(loaded.level == nether && loaded.built && loaded.revision == 2 && loaded.rounds == 2, "dimension, state and counters survive reload");
			helper.assertTrue(loaded.compiled.get(pos.asLong()).state().is(Blocks.STONE) && loaded.original(pos).equals(original), "baseline and container NBT survive reload");
			helper.assertTrue(other.list().isEmpty(), "separate world directory has no building entries");
		} finally { first.close(); second.close(); other.close(); }
		var legacyDir = java.nio.file.Files.createDirectories(dir.resolve("legacy"));
		JsonObject legacyPlan = plan(helper, "legacy-design", ArchGameTests.FAR.offset(0, 0, 1920));
		java.nio.file.Files.writeString(legacyDir.resolve("legacy-design.json"), legacyPlan.toString());
		var imported = new dev.haochen.mcmcp.arch.BuildingRegistry(dir.resolve("new-world"), legacyDir);
		try {
			var legacy = imported.get("legacy-design");
			helper.assertTrue(legacy.plan.equals(legacyPlan) && !legacy.built && legacy.applied == null && legacy.level == null,
					"legacy files import as unbound designs, not built overworld structures");
		} finally { imported.close(); }
		helper.succeed();
	}

	@GameTest(template = EMPTY, batch = "arch_square_sites")
	public void squareSitesHonorEastFacingAndKeepTerracesSeparate(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		JsonObject p = JsonParser.parseString("{\"footprint\":[7,7],\"radius\":24,\"margin\":2,\"top\":10,\"facing\":\"east\",\"loadChunks\":true}").getAsJsonObject();
		p.add("center", Json.blockPos(helper.absolutePos(new BlockPos(-400, 0, 120))));
		var candidates = AgentApiAccess.call("site.analyze", p).getAsJsonArray("candidates");
		helper.assertTrue(candidates.size() > 1, "multiple square sites found");
		for (int i = 0; i < candidates.size(); i++) {
			var a = candidates.get(i).getAsJsonObject(); helper.assertTrue(a.get("facing").getAsString().equals("east"), "square may face east");
			var pa = a.getAsJsonArray("origin");
			for (int j = 0; j < i; j++) {
				var pb = candidates.get(j).getAsJsonObject().getAsJsonArray("origin");
				helper.assertTrue(Math.abs(pa.get(0).getAsInt() - pb.get(0).getAsInt()) >= 11 || Math.abs(pa.get(2).getAsInt() - pb.get(2).getAsInt()) >= 11,
						"candidate terraces do not intersect");
			}
		}
		helper.succeed();
	}

	@GameTest(template = EMPTY, batch = "arch_sparse_chunks")
	public void sparsePlanDoesNotLoadChunksBetweenComponents(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		BlockPos a = helper.absolutePos(new BlockPos(12000, 0, 4000)), b = a.east(16000), middle = a.east(8000);
		var blocks = new dev.haochen.mcmcp.world.BlockList(); blocks.put(a, Blocks.STONE.defaultBlockState()); blocks.put(b, Blocks.STONE.defaultBlockState());
		helper.assertTrue(!helper.getLevel().hasChunkAt(middle), "middle chunk starts unloaded");
		new dev.haochen.mcmcp.api.Plan(helper.getLevel(), blocks, "test", "sparse plan", true, blocks.stats(5)).validate(McMcpMod.services());
		helper.assertTrue(helper.getLevel().hasChunkAt(a) && helper.getLevel().hasChunkAt(b) && !helper.getLevel().hasChunkAt(middle), "only touched chunks were loaded");
		helper.succeed();
	}

	@GameTest(template = EMPTY, batch = "arch_perf")
	public void compilationAllocationMeasurement(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		var c = dev.haochen.mcmcp.api.ArchApi.compile(helper.getLevel().getServer(), args(plan(helper, "perf-test", ArchGameTests.FAR.offset(0, 0, 1560))));
		var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		long id = Thread.currentThread().threadId();
		for (int i = 0; i < 100; i++) c.compiler().worldCells();
		long bytes = bean.getThreadAllocatedBytes(id), time = System.nanoTime(); int count = 0;
		for (int i = 0; i < 1000; i++) count += c.compiler().worldCells().size();
		long elapsed = System.nanoTime() - time, allocated = bean.getThreadAllocatedBytes(id) - bytes;
		System.out.println("[PERF] worldCells calls=1000 blocks=" + c.compiler().worldCells().size() + " ms=" + elapsed / 1_000_000.0 + " allocatedBytes=" + allocated);
		helper.assertTrue(count > 0 && c.validation().get("ok").getAsBoolean(), "compiled design remains valid during repeated access");
		helper.succeed();
	}

	@GameTest(template = EMPTY, batch = "arch_queued_cancel")
	public void queuedBuildingIsNotBuiltAndCancellationClearsPending(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		JsonObject b = plan(helper, "queued-test", ArchGameTests.FAR.offset(0, 0, 1200));
		int id = AgentApiAccess.call("build.placeBlueprint", args(b)).get("jobId").getAsInt();
		var e = McMcpMod.services().buildings().get("queued-test");
		helper.assertTrue(!e.built && e.status().get("state").getAsString().equals("building"), "queued is not built: " + e.status());
		JsonObject cancel = new JsonObject();
		cancel.addProperty("jobId", id);
		AgentApiAccess.call("job.cancel", cancel);
		helper.assertTrue(!e.built && e.status().get("state").getAsString().equals("planned"), "cancelled before placement: " + e.status());
		helper.succeed();
	}

	@GameTest(template = EMPTY, batch = "arch_original_nbt", timeoutTicks = 300)
	public void componentRemovalRestoresChestContentsAndMetadata(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		BlockPos at = ArchGameTests.FAR.offset(0, 0, 1320), chestPos = at.offset(20, 0, 0);
		helper.setBlock(chestPos, Blocks.CHEST);
		net.minecraft.world.level.block.entity.ChestBlockEntity chest = helper.getBlockEntity(chestPos);
		chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 5));
		JsonObject b = plan(helper, "nbt-test", at);
		b.addProperty("replaceExisting", true);
		ArchGameTests.patch(b, "annex", 20, 0, 0, "minecraft:stone_bricks");
		b.getAsJsonArray("patches").get(0).getAsJsonObject().addProperty("id", "annex-patch");
		JsonObject initial = args(b);
		initial.addProperty("ignoreValidation", true);
		int[] job = {AgentApiAccess.call("build.placeBlueprint", initial).get("jobId").getAsInt()};
		helper.startSequence()
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job[0]))
				.thenExecute(() -> {
					JsonObject revision = ops("nbt-test", "[{\"op\":\"remove\",\"id\":\"annex-patch\"}]");
					revision.addProperty("confirm", true);
					job[0] = AgentApiAccess.call("arch.revise", revision).getAsJsonObject("job").get("jobId").getAsInt();
				})
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job[0]))
				.thenExecute(() -> {
					net.minecraft.world.level.block.entity.ChestBlockEntity restored = helper.getBlockEntity(chestPos);
					helper.assertTrue(restored.getItem(0).getCount() == 5, "restored chest contains its five diamonds");
					job[0] = McMcpGameTests.submitUndo();
				})
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job[0]))
				.thenExecute(() -> {
					helper.assertBlockPresent(Blocks.STONE_BRICKS, chestPos);
					helper.assertTrue(McMcpMod.services().buildings().get("nbt-test").plan.getAsJsonArray("patches").size() == 1, "undo restores design metadata");
					job[0] = McMcpMod.services().jobs().submit(new RestoreJob(McMcpMod.services().history(), false)).id();
				})
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job[0]))
				.thenExecute(() -> {
					net.minecraft.world.level.block.entity.ChestBlockEntity restored = helper.getBlockEntity(chestPos);
					helper.assertTrue(restored.getItem(0).getCount() == 5, "redo preserves restored chest contents");
					helper.assertTrue(McMcpMod.services().buildings().get("nbt-test").plan.getAsJsonArray("patches").isEmpty(), "redo restores revised design metadata");
					job[0] = McMcpGameTests.submitUndo();
				})
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job[0]))
				.thenExecute(() -> job[0] = McMcpGameTests.submitUndo())
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job[0]))
				.thenExecute(() -> {
					var e = McMcpMod.services().buildings().get("nbt-test");
					helper.assertTrue(!e.built && !e.originals.isEmpty(), "undo retains original snapshots for history");
					JsonObject otherDimension = args(b); otherDimension.addProperty("dimension", "minecraft:the_nether");
					try {
						AgentApiAccess.call("build.preview", otherDimension);
						throw new GameTestAssertException("originals must not cross dimensions after undo");
					} catch (RpcException error) {
						helper.assertTrue(error.code() == RpcException.INVALID_PARAMS && error.getMessage().contains("dimension"), "dimension ownership is retained");
					}
				})
				.thenSucceed();
	}

	private static void expectCode(GameTestHelper helper, int code, String method, JsonObject params) {
		try {
			AgentApiAccess.call(method, params);
			throw new GameTestAssertException(method + " should fail with " + code);
		} catch (RpcException e) {
			helper.assertTrue(e.code() == code, method + ": expected " + code + ", got " + e.code() + ": " + e.getMessage());
		}
	}

	@GameTest(template = EMPTY, batch = "arch_input_guards")
	public void invalidSiteRequestsFailBeforeChunkAccess(GameTestHelper helper) {
		JsonObject p = JsonParser.parseString("{\"center\":[100000,-60,100000],\"radius\":49}").getAsJsonObject();
		expectCode(helper, RpcException.INVALID_PARAMS, "site.analyze", p);
		p.addProperty("radius", 24);
		p.add("footprint", JsonParser.parseString("[9]"));
		expectCode(helper, RpcException.INVALID_PARAMS, "site.analyze", p);
		p.add("footprint", JsonParser.parseString("[9.5,7]"));
		expectCode(helper, RpcException.INVALID_PARAMS, "site.analyze", p);
		p.remove("footprint");
		p.addProperty("facing", "up");
		expectCode(helper, RpcException.INVALID_PARAMS, "site.analyze", p);
		p.remove("facing");
		p.addProperty("margin", -1);
		expectCode(helper, RpcException.INVALID_PARAMS, "site.analyze", p);
		helper.succeed();
	}

	@GameTest(template = EMPTY, batch = "arch_revision_guards")
	public void rejectedRevisionsPreservePreviewAndCounters(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		var s = McMcpMod.services();
		JsonObject b = plan(helper, "guard-test", ArchGameTests.FAR.offset(0, 0, 480));
		int id = AgentApiAccess.call("build.preview", args(b)).get("previewId").getAsInt();
		var entry = s.buildings().get("guard-test");
		entry.rounds = 2;
		JsonObject rename = ops("guard-test", "[{\"op\":\"set\",\"field\":\"name\",\"value\":\"renamed-test\"}]");
		rename.addProperty("userApproved", true);
		expectCode(helper, RpcException.INVALID_PARAMS, "arch.revise", rename);
		helper.assertTrue(entry.rounds == 2 && entry.revision == 0, "rejected approval must not change counters");
		helper.assertTrue(s.buildings().find("renamed-test") == null, "revision must not create another building");

		var previous = s.previews().plan(id);
		var allowed = s.config().allowedRegions;
		try {
			s.config().allowedRegions = java.util.List.of();
			JsonObject change = ops("guard-test", "[{\"op\":\"update\",\"id\":\"roof\",\"set\":{\"overhang\":1}}]");
			expectCode(helper, RpcException.OUTSIDE_ALLOWED_REGION, "arch.revise", change);
			helper.assertTrue(s.previews().plan(id) == previous, "rejected revision preserves preview");
			helper.assertTrue(entry.plan.equals(b) && entry.rounds == 2, "rejected revision preserves registry");
			JsonObject confirm = new JsonObject();
			confirm.addProperty("previewId", id);
			expectCode(helper, RpcException.OUTSIDE_ALLOWED_REGION, "build.confirmPreview", confirm);
			helper.assertTrue(s.previews().active(id), "rejected confirmation preserves preview");
		} finally {
			s.config().allowedRegions = allowed;
			s.previews().discard(id);
		}
		helper.succeed();
	}

	@GameTest(template = EMPTY, batch = "arch_confirmation_guards")
	public void invalidBuildingCannotBeConfirmedUntilRepaired(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		JsonObject b = plan(helper, "confirm-test", ArchGameTests.FAR.offset(0, 0, 600));
		ArchGameTests.patch(b, "roof", 4, 12, 2, "minecraft:air");
		b.getAsJsonArray("patches").get(0).getAsJsonObject().addProperty("id", "damage");
		int id = AgentApiAccess.call("build.preview", args(b)).get("previewId").getAsInt();
		JsonObject confirm = new JsonObject();
		confirm.addProperty("previewId", id);
		expectCode(helper, RpcException.BUILDING_INVALID, "build.confirmPreview", confirm);
		helper.assertTrue(McMcpMod.services().previews().active(id), "invalid preview remains available to repair");
		AgentApiAccess.call("arch.revise", ops("confirm-test", "[{\"op\":\"remove\",\"id\":\"damage\"}]"));
		int job = AgentApiAccess.call("build.confirmPreview", confirm).get("jobId").getAsInt();
		helper.startSequence().thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job)).thenSucceed();
	}

	@GameTest(template = EMPTY, batch = "arch_preview_eviction")
	public void evictedPreviewIsNoLongerReportedAsActive(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		var s = McMcpMod.services();
		for (int i = 0; i < 6; i++) {
			JsonObject b = plan(helper, "eviction-" + i, ArchGameTests.FAR.offset(0, 0, 720));
			AgentApiAccess.call("build.preview", args(b));
		}
		JsonObject get = new JsonObject();
		get.addProperty("name", "eviction-0");
		JsonObject status = AgentApiAccess.call("arch.get", get).getAsJsonObject("status");
		helper.assertTrue(status.get("state").getAsString().equals("planned") && !status.has("previewId"), "eviction clears registry: " + status);
		for (int i = 1; i < 6; i++) {
			s.previews().discard(s.buildings().get("eviction-" + i).previewId);
		}
		helper.succeed();
	}

	@GameTest(template = EMPTY, batch = "arch_foundation_verification", timeoutTicks = 200)
	public void verificationKeepsDeepFoundationInItsBaseline(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		BlockPos at = ArchGameTests.FAR.offset(0, 6, 840);
		// The -2 block stops a subsequent terrain compile, hiding damage at -3.
		for (int y = -2; y >= -4; y--) {
			helper.setBlock(at.offset(2, y, 2), Blocks.AIR);
		}
		helper.setBlock(at.offset(2, -5, 2), Blocks.STONE);
		JsonObject b = plan(helper, "foundation-test", at);
		int job = AgentApiAccess.call("build.placeBlueprint", args(b)).get("jobId").getAsInt();
		helper.startSequence()
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job))
				.thenExecute(() -> {
					helper.assertBlockPresent(Blocks.COBBLESTONE, at.offset(2, -3, 2));
					int expected = McMcpMod.services().buildings().get("foundation-test").compiled.size();
					JsonObject before = inspect("foundation-test").getAsJsonObject("verification");
					helper.assertTrue(before.get("blocks").getAsInt() == expected && before.get("matching").getAsInt() == expected,
							"verification includes every originally compiled block: " + before);
					helper.setBlock(at.offset(2, -3, 2), Blocks.AIR);
					JsonObject damaged = inspect("foundation-test").getAsJsonObject("verification");
					helper.assertTrue(damaged.getAsJsonObject("mismatchesByComponent").get("foundation").getAsInt() == 1,
							"buried foundation damage is detected: " + damaged);
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY, batch = "arch_confirmation_override", timeoutTicks = 200)
	public void validationOverrideStillEnforcesSafetyLimits(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		var s = McMcpMod.services();
		JsonObject b = plan(helper, "override-test", ArchGameTests.FAR.offset(0, 0, 960));
		ArchGameTests.patch(b, "roof", 4, 12, 2, "minecraft:air");
		int id = AgentApiAccess.call("build.preview", args(b)).get("previewId").getAsInt();
		JsonObject confirm = new JsonObject();
		confirm.addProperty("previewId", id);
		confirm.addProperty("ignoreValidation", true);
		int cap = s.config().maxVolumePerOp;
		try {
			s.config().maxVolumePerOp = 1;
			expectCode(helper, RpcException.VOLUME_TOO_LARGE, "build.confirmPreview", confirm);
			helper.assertTrue(s.previews().active(id), "safety rejection preserves overridden preview");
		} finally {
			s.config().maxVolumePerOp = cap;
		}
		int job = AgentApiAccess.call("build.confirmPreview", confirm).get("jobId").getAsInt();
		helper.startSequence().thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job)).thenSucceed();
	}

	@GameTest(template = EMPTY, batch = "arch_revision_terrain", timeoutTicks = 200)
	public void windowRevisionDoesNotRemoveDeepFoundation(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		BlockPos at = ArchGameTests.FAR.offset(0, 6, 1080);
		for (int y = -2; y >= -4; y--) {
			helper.setBlock(at.offset(2, y, 2), Blocks.AIR);
		}
		helper.setBlock(at.offset(2, -5, 2), Blocks.STONE);
		JsonObject b = plan(helper, "terrain-revision-test", at);
		int[] job = {AgentApiAccess.call("build.placeBlueprint", args(b)).get("jobId").getAsInt()};
		helper.startSequence()
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job[0]))
				.thenExecute(() -> {
					JsonObject change = ops("terrain-revision-test", "[{\"op\":\"update\",\"id\":\"upper-front-left\",\"set\":{\"width\":3}}]");
					change.addProperty("confirm", true);
					JsonObject revised = AgentApiAccess.call("arch.revise", change);
					helper.assertTrue(revised.getAsJsonObject("diff").get("removed").getAsInt() == 0,
							"changing windows must preserve the foundation: " + revised);
					job[0] = revised.getAsJsonObject("job").get("jobId").getAsInt();
				})
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job[0]))
				.thenExecute(() -> helper.assertBlockPresent(Blocks.COBBLESTONE, at.offset(2, -3, 2)))
				.thenSucceed();
	}

	private static JsonObject plan(GameTestHelper helper, String name, BlockPos localOrigin) {
		JsonObject b = ArchGameTests.examplePlan();
		b.addProperty("name", name);
		b.add("origin", Json.blockPos(helper.absolutePos(localOrigin)));
		JsonObject req = new JsonObject();
		req.addProperty("roofSymmetric", true);
		req.addProperty("symmetricFront", true);
		req.addProperty("minWindowsPerFloor", 2);
		b.add("requirements", req);
		return b;
	}

	private static JsonObject args(JsonObject building) {
		JsonObject p = new JsonObject();
		p.add("building", building);
		p.addProperty("loadChunks", true);
		return p;
	}

	private static JsonObject inspect(String name) {
		JsonObject p = new JsonObject();
		p.addProperty("name", name);
		p.addProperty("images", false);
		return AgentApiAccess.call("arch.inspect", p);
	}

	private static Set<String> codes(JsonArray issues) {
		Set<String> out = new HashSet<>();
		for (JsonElement e : issues) {
			out.add(e.getAsJsonObject().get("code").getAsString());
		}
		return out;
	}

	private static boolean requirement(JsonObject inspection, String name) {
		for (JsonElement e : inspection.getAsJsonObject("design").getAsJsonArray("requirements")) {
			if (e.getAsJsonObject().get("name").getAsString().equals(name)) {
				return e.getAsJsonObject().get("pass").getAsBoolean();
			}
		}
		throw new GameTestAssertException("no requirement " + name + " in " + inspection);
	}

	private static JsonObject ops(String name, String json) {
		JsonObject p = new JsonObject();
		p.addProperty("name", name);
		p.add("ops", JsonParser.parseString(json));
		return p;
	}

	/** Acceptance: a malformed roof is detected, fixed by changing only the roof, and verified in the preview. */
	@GameTest(template = EMPTY, batch = "arch_revise_roof", timeoutTicks = 200)
	public void malformedRoofIsDetectedAndFixedInPlace(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		BlockPos at = ArchGameTests.FAR.offset(0, 0, 120);
		JsonObject b = plan(helper, "roof-test", at);
		// Damage one slope: two missing roof blocks and two stairs turned the wrong way.
		ArchGameTests.patch(b, "roof", 3, 10, 0, "minecraft:dark_oak_stairs[facing=north]",
				4, 12, 2, "minecraft:air", 6, 12, 2, "minecraft:air", 7, 10, 0, "minecraft:dark_oak_stairs[facing=north]");
		b.getAsJsonArray("patches").get(0).getAsJsonObject().addProperty("id", "roof-damage");

		JsonObject preview = AgentApiAccess.call("build.preview", args(b));
		helper.assertTrue(preview.get("building").getAsString().equals("roof-test"), "registered: " + preview);

		JsonObject before = inspect("roof-test");
		Set<String> errors = codes(before.getAsJsonObject("validation").getAsJsonArray("errors"));
		helper.assertTrue(errors.contains("ROOF_GAP") && errors.contains("ROOF_ORIENTATION"), "roof defects found: " + errors);
		helper.assertTrue(codes(before.getAsJsonObject("design").getAsJsonArray("issues")).contains("ROOF_ASYMMETRIC"), "asymmetry measured");
		helper.assertTrue(!requirement(before, "roofSymmetric"), "roofSymmetric requirement fails");
		helper.assertTrue(requirement(before, "symmetricFront") && requirement(before, "minWindowsPerFloor"), "other requirements pass");
		helper.assertTrue(before.getAsJsonArray("views").size() == 7, "6 view kinds, interior per floor: " + before.getAsJsonArray("views"));

		JsonObject fix = AgentApiAccess.call("arch.revise", ops("roof-test", "[{\"op\": \"remove\", \"id\": \"roof-damage\"}]"));
		helper.assertTrue(fix.get("applied").getAsString().equals("preview"), "applied to the preview: " + fix);
		JsonObject diff = fix.getAsJsonObject("diff");
		helper.assertTrue(diff.get("changed").getAsInt() == 4 && diff.get("removed").getAsInt() == 0, "only the 4 damaged blocks change: " + diff);
		helper.assertTrue(diff.getAsJsonArray("components").size() == 1 && diff.getAsJsonArray("components").get(0).getAsString().equals("roof"),
				"only the roof component: " + diff);
		helper.assertTrue(diff.get("unchanged").getAsInt() > 1500, "the rest of the house is untouched: " + diff);

		JsonObject after = inspect("roof-test");
		helper.assertTrue(after.getAsJsonObject("validation").get("ok").getAsBoolean(), "valid after the fix: " + after.getAsJsonObject("validation"));
		helper.assertTrue(requirement(after, "roofSymmetric"), "roof symmetric now");
		helper.assertTrue(!codes(after.getAsJsonObject("design").getAsJsonArray("issues")).contains("ROOF_ASYMMETRIC"), "no roof issues");
		helper.assertTrue(McMcpMod.services().buildings().find("roof-test").plan.toString().contains("roof-damage") == false, "patch gone from the plan");

		JsonObject discard = new JsonObject();
		discard.addProperty("previewId", preview.get("previewId").getAsInt());
		AgentApiAccess.call("build.discardPreview", discard);
		helper.succeed();
	}

	/** Built buildings: confirm required, only the diff is placed (undoable), verification, revision limit. */
	@GameTest(template = EMPTY, batch = "arch_revise_world", timeoutTicks = 600)
	public void builtBuildingIsRevisedBlockByBlock(GameTestHelper helper) {
		McMcpGameTests.allowEverywhere();
		McMcpMod.services().config().maxRevisionRounds = 3;
		BlockPos at = ArchGameTests.FAR.offset(0, 0, 240);
		JsonObject b = plan(helper, "world-test", at);
		int[] job = {AgentApiAccess.call("build.placeBlueprint", args(b)).get("jobId").getAsInt()};
		// Upper front-left window at local x=2..3; widening to 3 turns the wall at x=4 into glass.
		BlockPos widened = at.offset(4, 6, 8);
		helper.startSequence()
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job[0]))
				.thenExecute(() -> {
					helper.assertBlockPresent(Blocks.WHITE_TERRACOTTA, widened);
					JsonObject verify = inspect("world-test").getAsJsonObject("verification");
					helper.assertTrue(verify.get("matching").getAsInt() == verify.get("blocks").getAsInt(), "built as planned: " + verify);

					String widen = "[{\"op\": \"update\", \"id\": \"upper-front-left\", \"set\": {\"width\": 3}}]";
					JsonObject dry = AgentApiAccess.call("arch.revise", ops("world-test", widen));
					helper.assertTrue(dry.get("applied").getAsString().equals("nothing"), "built buildings need confirm: " + dry);
					helper.assertBlockPresent(Blocks.WHITE_TERRACOTTA, widened);

					JsonObject confirmed = ops("world-test", widen);
					confirmed.addProperty("confirm", true);
					JsonObject r = AgentApiAccess.call("arch.revise", confirmed);
					helper.assertTrue(r.get("applied").getAsString().equals("world"), "applied: " + r);
					helper.assertTrue(r.getAsJsonObject("diff").get("changed").getAsInt() == 2, "two wall blocks become glass: " + r.getAsJsonObject("diff"));
					helper.assertTrue(r.get("roundsLeft").getAsInt() == 2, "one round used: " + r);
					job[0] = r.getAsJsonObject("job").get("jobId").getAsInt();
				})
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job[0]))
				.thenExecute(() -> {
					helper.assertBlockPresent(Blocks.GLASS_PANE, widened);
					JsonObject verify = inspect("world-test").getAsJsonObject("verification");
					helper.assertTrue(verify.get("matching").getAsInt() == verify.get("blocks").getAsInt(), "world matches revision: " + verify);
					job[0] = McMcpGameTests.submitUndo();
				})
				.thenWaitUntil(() -> McMcpGameTests.assertDone(helper, job[0]))
				.thenExecute(() -> {
					helper.assertBlockPresent(Blocks.WHITE_TERRACOTTA, widened); // undo reverts just the revision
					JsonObject verify = inspect("world-test").getAsJsonObject("verification");
					helper.assertTrue(verify.get("matching").getAsInt() == verify.get("blocks").getAsInt(), "undo restores the matching design baseline: " + verify);
					// One round used by the widening; two more reach maxRevisionRounds = 3, then userApproved continues.
					JsonObject o = ops("world-test", "[{\"op\": \"update\", \"id\": \"roof\", \"set\": {\"overhang\": 1}}]");
					o.addProperty("confirm", true);
					String before = McMcpMod.services().buildings().find("world-test").status().toString();
					int left1 = AgentApiAccess.call("arch.revise", o).get("roundsLeft").getAsInt();
					int left2 = AgentApiAccess.call("arch.revise", o).get("roundsLeft").getAsInt();
					try {
						JsonObject r4 = AgentApiAccess.call("arch.revise", o);
						throw new GameTestAssertException("the 4th revision must hit the limit; before: " + before + "; rounds left " + left1 + ", " + left2 + ", " + r4);
					} catch (RpcException e) {
						helper.assertTrue(e.getMessage().contains("revision limit"), e.getMessage());
					}
					o.addProperty("userApproved", true);
					helper.assertTrue(AgentApiAccess.call("arch.revise", o).get("roundsLeft").getAsInt() == 2, "approval resets the rounds");
				})
				.thenSucceed();
	}
}
