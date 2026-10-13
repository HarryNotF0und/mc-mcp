package dev.haochen.mcmcp.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.net.RpcException;
import dev.haochen.mcmcp.world.BlockList;
import dev.haochen.mcmcp.world.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.Loop;
import org.mozilla.javascript.ast.VariableDeclaration;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs build scripts with Rhino on a dedicated worker thread (never the server thread), with:
 * no access to any Java class, no LiveConnect globals, a wall-clock deadline enforced by the
 * instruction observer, and a cap on the number of blocks. Scripts only produce a
 * {@link BlockList}; the caller decides whether to place it.
 */
public final class ScriptSandbox {
	public record Result(BlockList blocks, List<String> logs, long elapsedMs) {
	}

	/** Thrown from the instruction observer. An {@link Error}, so scripts cannot catch it. */
	private static final class Timeout extends Error {
		final int line;

		Timeout(int line) {
			super(null, null, false, false);
			this.line = line;
		}
	}

	private static final class TimedContext extends Context {
		long deadline;

		TimedContext(ContextFactory factory) {
			super(factory);
		}
	}

	private static final class Factory extends ContextFactory {
		@Override
		protected Context makeContext() {
			TimedContext cx = new TimedContext(this);
			cx.setInterpretedMode(true);
			cx.setLanguageVersion(Context.VERSION_ECMASCRIPT);
			cx.setLocale(java.util.Locale.ENGLISH); // error messages in English regardless of the host locale
			cx.setClassShutter(className -> false);
			cx.setInstructionObserverThreshold(10_000);
			cx.setMaximumInterpreterStackDepth(2_000);
			return cx;
		}

		@Override
		protected void observeInstructionCount(Context cx, int instructionCount) {
			if (System.currentTimeMillis() > ((TimedContext) cx).deadline) {
				// reportRuntimeError only builds the exception; it carries the current script line.
				EvaluatorException where = Context.reportRuntimeError("timeout");
				throw new Timeout(where.lineNumber());
			}
		}
	}

	private static final Factory FACTORY = new Factory();

	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "mcmcp-script");
		t.setDaemon(true);
		return t;
	});

	public CompletableFuture<Result> run(MinecraftServer server, ServerLevel level, String source, BlockPos origin,
										 Guard guard, int timeoutMs, int maxBlocks, boolean loadChunks) {
		return CompletableFuture.supplyAsync(
				() -> runNow(server, level, source, origin, guard, timeoutMs, maxBlocks, loadChunks), executor);
	}

	public void shutdown() {
		executor.shutdownNow();
	}

	private static Result runNow(MinecraftServer server, ServerLevel level, String source, BlockPos origin,
								 Guard guard, int timeoutMs, int maxBlocks, boolean loadChunks) {
		long start = System.currentTimeMillis();
		TimedContext cx = (TimedContext) FACTORY.enterContext();
		BuilderObject b = null;
		try {
			cx.deadline = start + timeoutMs;
			ScriptableObject scope = cx.initSafeStandardObjects();
			b = new BuilderObject(scope, origin, guard, new WorldSnapshot(server, level, loadChunks), maxBlocks);
			scope.defineProperty("b", b, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
			rejectConstInLoops(cx, source);
			cx.evaluateString(scope, source, "script", 1, null);
			return new Result(b.blocks(), b.logs(), System.currentTimeMillis() - start);
		} catch (Timeout t) {
			throw error("script exceeded the time limit of " + timeoutMs + " ms", t.line, b);
		} catch (RhinoException e) {
			throw error(e.details(), e.lineNumber(), b);
		} catch (StackOverflowError e) {
			throw error("stack overflow (too deep recursion)", 0, b);
		} finally {
			Context.exit();
		}
	}

	/**
	 * Rhino scopes {@code const} to the enclosing function, so a {@code const} in a loop body keeps
	 * the value from the first iteration (and destructuring ones throw "redeclaration"). That
	 * silently builds the wrong thing, so reject it up front. See docs/decisions.md D11.
	 */
	private static void rejectConstInLoops(Context cx, String source) {
		CompilerEnvirons env = new CompilerEnvirons();
		env.initFromContext(cx);
		AstRoot root = new Parser(env, env.getErrorReporter()).parse(source, "script", 1);
		root.visit(node -> {
			if (node instanceof VariableDeclaration decl && decl.isConst()) {
				for (AstNode p = decl.getParent(); p != null && !(p instanceof FunctionNode); p = p.getParent()) {
					if (p instanceof Loop) {
						throw new EvaluatorException("use let instead of const inside loops (in this engine a const in a "
								+ "loop body keeps its first value)", "script", decl.getLineno());
					}
				}
			}
			return true;
		});
	}

	private static RpcException error(String message, int line, BuilderObject b) {
		JsonObject data = new JsonObject();
		if (line > 0) {
			data.addProperty("line", line);
		}
		data.addProperty("message", message);
		if (b != null && !b.logs().isEmpty()) {
			JsonArray logs = new JsonArray();
			b.logs().forEach(logs::add);
			data.add("logs", logs);
		}
		return new RpcException(RpcException.SCRIPT_ERROR,
				(line > 0 ? "line " + line + ": " : "") + message, data);
	}
}
