package dev.haochen.mcmcp.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.haochen.mcmcp.net.RpcException;
import dev.haochen.mcmcp.world.BlockList;
import dev.haochen.mcmcp.world.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.ScriptableObject;

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
	public record Result(BlockList blocks, List<String> logs, long elapsedMs, int skippedExisting) {
	}

	/** A library evaluated before the script, in the same scope (functions it defines are global). */
	public record Library(String name, String source) {
	}

	/** Options besides the source. {@code keepExisting}: never replace non-air world blocks. */
	public record Options(List<Library> libraries, boolean keepExisting) {
		public static final Options NONE = new Options(List.of(), false);
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
		return run(server, level, source, origin, guard, timeoutMs, maxBlocks, loadChunks, Options.NONE);
	}

	public CompletableFuture<Result> run(MinecraftServer server, ServerLevel level, String source, BlockPos origin,
										 Guard guard, int timeoutMs, int maxBlocks, boolean loadChunks, Options options) {
		return CompletableFuture.supplyAsync(
				() -> runNow(server, level, source, origin, guard, timeoutMs, maxBlocks, loadChunks, options), executor);
	}

	public void shutdown() {
		executor.shutdownNow();
	}

	private static Result runNow(MinecraftServer server, ServerLevel level, String source, BlockPos origin,
								 Guard guard, int timeoutMs, int maxBlocks, boolean loadChunks, Options options) {
		long start = System.currentTimeMillis();
		TimedContext cx = (TimedContext) FACTORY.enterContext();
		BuilderObject b = null;
		try {
			cx.deadline = start + timeoutMs;
			ScriptableObject scope = cx.initSafeStandardObjects();
			b = new BuilderObject(scope, origin, guard, new WorldSnapshot(server, level, loadChunks), maxBlocks);
			b.keepExisting(options.keepExisting());
			scope.defineProperty("b", b, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
			for (Library lib : options.libraries()) {
				cx.evaluateString(scope, constToLet(lib.source()), lib.name(), 1, null);
			}
			cx.evaluateString(scope, constToLet(source), "script", 1, null);
			return new Result(b.blocks(), b.logs(), System.currentTimeMillis() - start, b.skippedExisting());
		} catch (Timeout t) {
			throw error("script exceeded the time limit of " + timeoutMs + " ms", t.line, null, b);
		} catch (RhinoException e) {
			throw error(explain(e.details()), e.lineNumber(), e.sourceName(), b);
		} catch (StackOverflowError e) {
			throw error("stack overflow (too deep recursion)", 0, null, b);
		} finally {
			Context.exit();
		}
	}

	/**
	 * Rhino scopes {@code const} to the enclosing function, so a {@code const} in a loop body keeps
	 * the value from the first iteration, and {@code for (const x of ...)} does not even parse; its
	 * {@code let} is correct. So every {@code const} keyword (outside strings, templates and
	 * comments) becomes {@code let} plus two spaces, which keeps line and column numbers. The price:
	 * reassigning a constant is not an error. See docs/decisions.md D11.
	 */
	static String constToLet(String src) {
		StringBuilder out = new StringBuilder(src);
		int n = src.length();
		int i = 0;
		java.util.ArrayDeque<Integer> templateBraces = new java.util.ArrayDeque<>(); // brace depth at each ${
		int braces = 0;
		while (i < n) {
			char c = src.charAt(i);
			if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
				while (i < n && src.charAt(i) != '\n') {
					i++;
				}
			} else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
				int end = src.indexOf("*/", i + 2);
				i = end < 0 ? n : end + 2;
			} else if (c == '"' || c == '\'') {
				i++;
				while (i < n && src.charAt(i) != c && src.charAt(i) != '\n') {
					i += src.charAt(i) == '\\' ? 2 : 1;
				}
				i++;
			} else if (c == '`' || c == '}' && !templateBraces.isEmpty() && templateBraces.peek() == braces) {
				if (c == '}') {
					templateBraces.pop();
				}
				i++;
				while (i < n && src.charAt(i) != '`') {
					if (src.charAt(i) == '\\') {
						i += 2;
					} else if (src.charAt(i) == '$' && i + 1 < n && src.charAt(i + 1) == '{') {
						templateBraces.push(braces);
						i += 2;
						break;
					} else {
						i++;
					}
				}
				if (i < n && src.charAt(i) == '`') {
					i++;
				}
			} else if (c == '{') {
				braces++;
				i++;
			} else if (c == '}') {
				braces--;
				i++;
			} else if (Character.isJavaIdentifierStart(c)) {
				int start = i;
				while (i < n && Character.isJavaIdentifierPart(src.charAt(i))) {
					i++;
				}
				boolean member = start > 0 && src.charAt(start - 1) == '.';
				if (!member && i - start == 5 && src.startsWith("const", start)) {
					out.replace(start, i, "let  ");
				}
			} else {
				i++;
			}
		}
		return out.toString();
	}

	/** Rhino's messages for syntax it does not support, in plain words. */
	private static String explain(String details) {
		if (details != null && details.contains("reserved word") && details.contains("class")) {
			return details + " (class syntax is not supported by this engine; use functions and plain objects)";
		}
		return details;
	}

	private static RpcException error(String message, int line, String file, BuilderObject b) {
		JsonObject data = new JsonObject();
		if (line > 0) {
			data.addProperty("line", line);
		}
		boolean inLibrary = file != null && !file.equals("script");
		if (inLibrary) {
			data.addProperty("file", file);
		}
		data.addProperty("message", message);
		if (b != null && !b.logs().isEmpty()) {
			JsonArray logs = new JsonArray();
			b.logs().forEach(logs::add);
			data.add("logs", logs);
		}
		return new RpcException(RpcException.SCRIPT_ERROR,
				(inLibrary ? file + " " : "") + (line > 0 ? "line " + line + ": " : "") + message, data);
	}
}
