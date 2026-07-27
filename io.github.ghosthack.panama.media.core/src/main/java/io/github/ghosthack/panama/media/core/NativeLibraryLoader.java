package io.github.ghosthack.panama.media.core;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fluent builder for locating and loading native libraries via Panama FFM.
 * <p>
 * Encapsulates the three-step loading strategy used by every panama-media
 * module:
 * <ol>
 *   <li>Check a per-module system property for an explicit path override.</li>
 *   <li>Probe a list of platform-specific well-known paths in preference order.</li>
 *   <li>Fall back to {@link System#loadLibrary(String)} using one or more
 *       candidate names.</li>
 * </ol>
 *
 * <p>Typical usage in a {@code Lib*.java} static initialiser:
 * <pre>{@code
 * private static final NativeLibraryLoader.Builder LOADER = NativeLibraryLoader
 *         .forLibrary("webp")
 *         .systemProperty("panama.media.libwebp.lib")
 *         .onMac(
 *             "/opt/homebrew/lib/libwebp.dylib",
 *             "/opt/local/lib/libwebp.dylib",
 *             "/usr/local/lib/libwebp.dylib")
 *         .onLinuxAarch64(
 *             "/usr/lib/aarch64-linux-gnu/libwebp.so",
 *             "/usr/local/lib/libwebp.so",
 *             "/usr/lib/libwebp.so")
 *         .onLinuxX86_64(
 *             "/usr/lib/x86_64-linux-gnu/libwebp.so",
 *             "/usr/local/lib/libwebp.so",
 *             "/usr/lib64/libwebp.so",
 *             "/usr/lib/libwebp.so");
 *
 * private static final SymbolLookup LOOKUP;
 * private static final boolean AVAILABLE;
 *
 * static {
 *     SymbolLookup lookup = null;
 *     boolean ok = false;
 *     try {
 *         lookup = LOADER.load();
 *         // ... resolve MethodHandles from lookup ...
 *         ok = true;
 *     } catch (Throwable _) {}
 *     LOOKUP = lookup;
 *     AVAILABLE = ok;
 * }
 *
 * public static boolean isAvailable() { return AVAILABLE; }
 * }</pre>
 */
public final class NativeLibraryLoader {

    private NativeLibraryLoader() {}

    /**
     * Starts building a loader for the given library.
     *
     * @param primaryFallbackName the name passed to {@link System#loadLibrary}
     *                            if all path candidates fail (e.g. {@code "webp"})
     */
    public static Builder forLibrary(String primaryFallbackName) {
        return new Builder(primaryFallbackName);
    }

    /** Builder that accumulates platform-specific configuration and executes loading. */
    public static final class Builder {

        private final String primaryName;
        private String       propertyKey;
        private String[]     macCandidates          = {};
        private String[]     linuxAarch64Candidates = {};
        private String[]     linuxX86_64Candidates  = {};
        private String[]     windowsCandidates      = {};
        private String[]     additionalFallbacks    = {};
        private final List<Builder> dependencies    = new ArrayList<>();

        private Builder(String primaryName) {
            this.primaryName = primaryName;
        }

        /**
         * System property key whose value may be an absolute path to the
         * library file, bypassing all path scanning.
         * <p>
         * If the property is set but points to a non-existent file,
         * {@link #load()} throws {@link UnsatisfiedLinkError} immediately
         * rather than falling through to the candidate scan.
         */
        public Builder systemProperty(String key) {
            this.propertyKey = key;
            return this;
        }

        /**
         * Candidate absolute paths probed on macOS, in preference order.
         * The first existing regular file wins.
         */
        public Builder onMac(String... candidates) {
            this.macCandidates = candidates;
            return this;
        }

        /**
         * Candidate absolute paths probed on Linux aarch64 / ARM64,
         * in preference order.
         */
        public Builder onLinuxAarch64(String... candidates) {
            this.linuxAarch64Candidates = candidates;
            return this;
        }

        /**
         * Candidate absolute paths probed on Linux x86_64,
         * in preference order.
         */
        public Builder onLinuxX86_64(String... candidates) {
            this.linuxX86_64Candidates = candidates;
            return this;
        }

        /**
         * Candidate absolute paths probed on Windows, in preference order.
         * The first existing regular file wins.
         */
        public Builder onWindows(String... candidates) {
            this.windowsCandidates = candidates;
            return this;
        }

        /**
         * Additional names tried with {@link System#loadLibrary} in order
         * after path scanning fails and the primary name has been attempted.
         * <p>
         * Useful for versioned or variant library names, for example:
         * <pre>{@code
         * .forLibrary("MagickWand-7.Q16HDRI")
         * .fallbackNames("MagickWand-7.Q16", "MagickWand-7.Q8")
         * }</pre>
         */
        public Builder fallbackNames(String... additional) {
            this.additionalFallbacks = additional;
            return this;
        }

        /**
         * Pre-loads a dependency library silently before attempting to load
         * the main library. Failure is silently ignored — the dependency may
         * be statically linked into the main library or loaded by it on demand.
         * <p>
         * Example — loading {@code libde265} before {@code libheif}:
         * <pre>{@code
         * .loadFirst(NativeLibraryLoader.forLibrary("de265")
         *         .onMac("/opt/homebrew/lib/libde265.dylib", ...)
         *         .onLinuxAarch64(...))
         * }</pre>
         */
        public Builder loadFirst(Builder dep) {
            this.dependencies.add(dep);
            return this;
        }

        /**
         * Resolves the library and returns a {@link SymbolLookup} bound to
         * {@link Arena#global()}.
         *
         * <p>Resolution order:
         * <ol>
         *   <li>If {@link #systemProperty} is set and the property value is
         *       non-blank, treat it as an absolute path. Return its lookup if
         *       the file exists; otherwise throw immediately.</li>
         *   <li>Probe the platform-appropriate candidate list in order; return
         *       the lookup for the first existing file.</li>
         *   <li>Try {@link System#loadLibrary} with the primary name, then each
         *       name from {@link #fallbackNames}; return
         *       {@link SymbolLookup#loaderLookup()} on the first success.</li>
         * </ol>
         *
         * @return a {@link SymbolLookup} over the loaded library
         * @throws UnsatisfiedLinkError if the system-property path is set but
         *         missing, or if all candidates and fallback names fail
         */
        public SymbolLookup load() {
            // Pre-load dependencies silently, in registration order
            for (Builder dep : dependencies) {
                try { dep.load(); } catch (Throwable _) { /* intentionally ignored */ }
            }

            // Step 1 — system property override
            if (propertyKey != null) {
                String val = System.getProperty(propertyKey);
                if (val != null && !val.isBlank()) {
                    Path p = Path.of(val);
                    if (Files.isRegularFile(p))
                        return SymbolLookup.libraryLookup(p, Arena.global());
                    throw new UnsatisfiedLinkError(
                            propertyKey + " points to non-existent file: " + val);
                }
            }

            // Step 2 — platform-specific well-known paths
            String arch = System.getProperty("os.arch", "").toLowerCase();

            String[] candidates;
            if (Platform.IS_MAC) {
                candidates = macCandidates;
            } else if (Platform.IS_LINUX) {
                candidates = (arch.contains("aarch64") || arch.contains("arm"))
                        ? linuxAarch64Candidates
                        : linuxX86_64Candidates;
            } else if (Platform.IS_WINDOWS) {
                candidates = windowsCandidates;
            } else {
                candidates = new String[0];
            }

            for (String c : candidates) {
                Path p = Path.of(c);
                if (Files.isRegularFile(p))
                    return SymbolLookup.libraryLookup(p, Arena.global());
            }

            // Step 3 — System.loadLibrary fallback
            List<String> names = new ArrayList<>();
            names.add(primaryName);
            names.addAll(Arrays.asList(additionalFallbacks));

            UnsatisfiedLinkError last = null;
            for (String name : names) {
                try {
                    System.loadLibrary(name);
                    return SymbolLookup.loaderLookup();
                } catch (UnsatisfiedLinkError e) {
                    last = e;
                }
            }

            throw new UnsatisfiedLinkError(
                    "Could not load '" + primaryName + "'" +
                    (last != null ? ": " + last.getMessage() : ""));
        }

        /**
         * Returns {@code true} if {@link #load()} would succeed without
         * throwing.
         * <p>
         * Calls {@link #load()} internally; native library loading is
         * idempotent — the OS will not load the same library twice.
         */
        public boolean isAvailable() {
            try {
                load();
                return true;
            } catch (Throwable _) {
                return false;
            }
        }
    }
}
