package dev.jbang.source.builders;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jboss.jandex.*;
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinate;

import dev.jbang.cli.ExitException;
import dev.jbang.dependencies.DependencyUtil;
import dev.jbang.source.*;
import dev.jbang.spi.IntegrationManager;
import dev.jbang.spi.IntegrationResult;
import dev.jbang.util.JarUtil;
import dev.jbang.util.JavaUtil;
import dev.jbang.util.TemplateEngine;
import dev.jbang.util.Util;

import io.quarkus.qute.Template;

public abstract class BaseBuilder implements Builder {
	protected final SourceSet ss;
	protected final RunContext ctx;

	protected boolean fresh = Util.isFresh();
	protected Util.Shell shell = Util.getShell();

	public static final String ATTR_BUILD_JDK = "Build-Jdk";
	public static final String ATTR_JBANG_JAVA_OPTIONS = "JBang-Java-Options";
	public static final String ATTR_BOOT_CLASS_PATH = "Boot-Class-Path";

	public static final Type STRINGARRAYTYPE = Type.create(DotName.createSimple("[Ljava.lang.String;"),
			Type.Kind.ARRAY);
	public static final Type STRINGTYPE = Type.create(DotName.createSimple("java.lang.String"), Type.Kind.CLASS);
	public static final Type INSTRUMENTATIONTYPE = Type.create(
			DotName.createSimple("java.lang.instrument.Instrumentation"), Type.Kind.CLASS);

	public BaseBuilder(SourceSet ss, RunContext ctx) {
		this.ss = ss;
		this.ctx = ctx;
	}

	public BaseBuilder setFresh(boolean fresh) {
		this.fresh = fresh;
		return this;
	}

	public BaseBuilder setShell(Util.Shell shell) {
		this.shell = shell;
		return this;
	}

	@Override
	public Jar build() throws IOException {
		Jar result = null;

		Path outjar = ss.getJarFile();
		boolean nativeBuildRequired = ctx.isNativeImage() && !Files.exists(getImageName(outjar));
		IntegrationResult integrationResult = new IntegrationResult(null, null, null);
		String requestedJavaVersion = ctx.getJavaVersionOr(ss);
		// always build the jar for native mode
		// it allows integrations the options to produce the native image
		boolean buildRequired = true;
		if (fresh) {
			Util.verboseMsg("Building as fresh build explicitly requested.");
		} else if (nativeBuildRequired) {
			Util.verboseMsg("Building as native build required.");
		} else if (Files.isReadable(outjar)) {
			// We already have a Jar, check if we can still use it
			Jar jarSrc = ss.asJar();

			if (jarSrc == null) {
				Util.verboseMsg("Building as previous built jar not found.");
			} else if (!jarSrc.isUpToDate()) {
				Util.verboseMsg("Building as previous build jar found but it or its dependencies not up-to-date.");
			} else if (JavaUtil.javaVersion(requestedJavaVersion) < JavaUtil.minRequestedVersion(
					jarSrc.getJavaVersion())) {
				Util.verboseMsg(
						String.format(
								"Building as requested Java version %s < than the java version used during last build %s",
								requestedJavaVersion, jarSrc.getJavaVersion()));
			} else {
				Util.verboseMsg("No build required. Reusing jar from " + jarSrc.getJarFile());
				result = (Jar) ctx.importJarMetadataFor(jarSrc);
				buildRequired = false;
			}
		} else {
			Util.verboseMsg("Build required as " + outjar + " not readable or not found.");
		}

		if (buildRequired) {
			// set up temporary folder for compilation
			Path compileDir = getCompileDir();
			Util.deletePath(compileDir, true);
			compileDir.toFile().mkdirs();
			// do the actual building
			try {
				integrationResult = compile();
				createJar();
				result = ss.asJar();
			} finally {
				// clean up temporary folder
				Util.deletePath(compileDir, true);
			}
		}

		if (nativeBuildRequired) {
			if (integrationResult.nativeImagePath != null) {
				Files.move(integrationResult.nativeImagePath, getImageName(outjar));
			} else {
				buildNative();
			}
		}

		return result;
	}

	// build with javac and then jar...
	public IntegrationResult compile() throws IOException {
		String requestedJavaVersion = ctx.getJavaVersionOr(ss);
		Path compileDir = getCompileDir();
		List<String> optionList = new ArrayList<>();
		optionList.add(getCompilerBinary(requestedJavaVersion));
		optionList.addAll(ss.getCompileOptions());
		String path = ctx.resolveClassPath(ss).getClassPath();
		if (!Util.isBlankString(path)) {
			optionList.addAll(Arrays.asList("-classpath", path));
		}
		optionList.addAll(Arrays.asList("-d", compileDir.toAbsolutePath().toString()));

		// add source files to compile
		optionList.addAll(ss.getSources()
							.stream()
							.map(x -> x.getFile().toString())
							.collect(Collectors.toList()));

		// add additional files
		ss.copyResourcesTo(compileDir);

		Path pomPath = generatePom(compileDir);

		Util.infoMsg(String.format("Building %s...", ss.getMainSource().isAgent() ? "javaagent" : "jar"));
		Util.verboseMsg("Compile: " + String.join(" ", optionList));
		runCompiler(optionList);

		ctx.setBuildJdk(JavaUtil.javaVersion(requestedJavaVersion));
		// todo: setting properties to avoid loosing properties in integration call.
		Properties old = System.getProperties();
		Properties temp = new Properties(System.getProperties());
		for (Map.Entry<String, String> entry : ctx.getProperties().entrySet()) {
			System.setProperty(entry.getKey(), entry.getValue());
		}
		IntegrationResult integrationResult = IntegrationManager.runIntegrations(ss, ctx, compileDir, pomPath);
		System.setProperties(old);

		if (ctx.getMainClassOr(ss) == null) { // if non-null user forced set main
			if (integrationResult.mainClass != null) {
				ctx.setMainClass(integrationResult.mainClass);
			} else {
				searchForMain(compileDir);
			}
		}
		if (integrationResult.javaArgs != null && !integrationResult.javaArgs.isEmpty()) {
			// Add integration options to the java options
			ss.addRuntimeOptions(integrationResult.javaArgs);
		}

		return integrationResult;
	}

	protected void runCompiler(List<String> optionList) throws IOException {
		runCompiler(new ProcessBuilder(optionList).inheritIO());
	}

	protected void runCompiler(ProcessBuilder processBuilder) throws IOException {
		Process process = processBuilder.start();
		try {
			process.waitFor();
		} catch (InterruptedException e) {
			throw new ExitException(1, e);
		}

		if (process.exitValue() != 0) {
			throw new ExitException(1, "Error during compile");
		}
	}

	public void createJar() throws IOException {
		createJar(ss, ctx, getCompileDir(), ss.getJarFile());
	}

	public static void createJar(SourceSet ss, RunContext ctx, Path compileDir, Path jarFile) throws IOException {
		String mainclass = ctx.getMainClassOr(ss);
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		if (mainclass != null) {
			manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainclass);
		}

		ss.getManifestAttributes().forEach((k, v) -> manifest.getMainAttributes().putValue(k, v));

		// When persistent JVM args are set they are appended to any runtime
		// options set on the Source (that way persistent args can override
		// options set on the Source)
		List<String> rtArgs = ss.getRuntimeOptions();
		String runtimeOpts = String.join(" ", escapeArguments(rtArgs));
		if (!runtimeOpts.isEmpty()) {
			manifest.getMainAttributes()
					.putValue(ATTR_JBANG_JAVA_OPTIONS, runtimeOpts);
		}
		int buildJdk = ctx.getBuildJdk();
		if (buildJdk > 0) {
			String val = buildJdk >= 9 ? Integer.toString(buildJdk) : "1." + buildJdk;
			manifest.getMainAttributes().putValue(ATTR_BUILD_JDK, val);
		}

		FileOutputStream target = new FileOutputStream(jarFile.toFile());
		JarUtil.jar(target, compileDir.toFile().listFiles(), null, null, manifest);
		target.close();
	}

	protected void buildNative()
			throws IOException {
		List<String> optionList = new ArrayList<>();
		optionList.add(resolveInGraalVMHome("native-image", ctx.getJavaVersionOr(ss)));

		optionList.add("-H:+ReportExceptionStackTraces");

		optionList.add("--enable-https");

		String classpath = ctx.resolveClassPath(ss).getClassPath();
		if (!Util.isBlankString(classpath)) {
			optionList.add("--class-path=" + classpath);
		}

		optionList.add("-jar");
		optionList.add(ss.getJarFile().toString());

		optionList.add(getImageName(ss.getJarFile()).toString());

		runNativeBuilder(optionList);
	}

	protected void runNativeBuilder(List<String> optionList) throws IOException {
		Path nilog = Files.createTempFile("jbang", "native-image");
		Util.verboseMsg("native-image: " + String.join(" ", optionList));
		Util.infoMsg("log: " + nilog.toString());

		Process process = new ProcessBuilder(optionList).inheritIO().redirectOutput(nilog.toFile()).start();
		try {
			process.waitFor();
		} catch (InterruptedException e) {
			throw new ExitException(1, e);
		}

		if (process.exitValue() != 0) {
			throw new ExitException(1, "Error during native-image");
		}
	}

	/** based on jar what will the binary image name be. **/
	public static Path getImageName(Path outjar) {
		if (Util.isWindows()) {
			return Paths.get(outjar + ".exe");
		} else {
			return Paths.get(outjar + ".bin");
		}
	}

	private static String resolveInGraalVMHome(String cmd, String requestedVersion) {
		String newcmd = resolveInEnv("GRAALVM_HOME", cmd);

		if (newcmd.equals(cmd) &&
				!new File(newcmd).exists()) {
			return JavaUtil.resolveInJavaHome(cmd, requestedVersion);
		} else {
			return newcmd;
		}
	}

	private static String resolveInEnv(String env, String cmd) {
		if (System.getenv(env) != null) {
			Path dir = Paths.get(System.getenv(env)).toAbsolutePath().resolve("bin");
			Path cmdPath = Util.searchPath(cmd, dir.toString());
			return cmdPath != null ? cmdPath.toString() : cmd;
		} else {
			return cmd;
		}
	}

	// NB: This might not be a definitive list of safe characters
	static Pattern cmdSafeChars = Pattern.compile("[a-zA-Z0-9.,_+=:;@()-]*");
	// TODO: Figure out what the real list of safe characters is for PowerShell
	static Pattern pwrSafeChars = Pattern.compile("[a-zA-Z0-9.,_+=:;@()-]*");
	static Pattern shellSafeChars = Pattern.compile("[a-zA-Z0-9._+=:@%/-]*");

	/**
	 * Escapes list of arguments where necessary using the current OS' way of
	 * escaping
	 */
	public static List<String> escapeOSArguments(List<String> args, Util.Shell shell) {
		return args.stream().map(arg -> escapeOSArgument(arg, shell)).collect(Collectors.toList());
	}

	/**
	 * Escapes list of arguments where necessary using a generic way of escaping
	 * (we'll just be using the Unix way)
	 */
	static List<String> escapeArguments(List<String> args) {
		return args.stream().map(BaseBuilder::escapeUnixArgument).collect(Collectors.toList());
	}

	public static String escapeOSArgument(String arg, Util.Shell shell) {
		switch (shell) {
		case bash:
			return escapeUnixArgument(arg);
		case cmd:
			return escapeCmdArgument(arg);
		case powershell:
			return escapePowershellArgument(arg);
		}
		return arg;
	}

	static String escapeUnixArgument(String arg) {
		if (!shellSafeChars.matcher(arg).matches()) {
			arg = arg.replaceAll("(['])", "'\\\\''");
			arg = "'" + arg + "'";
		}
		return arg;
	}

	public static String escapeArgsFileArgument(String arg) {
		if (!shellSafeChars.matcher(arg).matches()) {
			arg = arg.replaceAll("([\"'\\\\])", "\\\\$1");
			arg = "\"" + arg + "\"";
		}
		return arg;
	}

	static String escapeCmdArgument(String arg) {
		if (!cmdSafeChars.matcher(arg).matches()) {
			// Windows quoting is just weird
			arg = arg.replaceAll("([()!^<>&|% ])", "^$1");
			arg = arg.replaceAll("([\"])", "\\\\^$1");
			arg = "^\"" + arg + "^\"";
		}
		return arg;
	}

	static String escapePowershellArgument(String arg) {
		if (!pwrSafeChars.matcher(arg).matches()) {
			arg = arg.replaceAll("(['])", "''");
			arg = "'" + arg + "'";
		}
		return arg;
	}

	protected void searchForMain(Path tmpJarDir) {
		try {
			// using Files.walk method with try-with-resources
			try (Stream<Path> paths = Files.walk(tmpJarDir)) {
				List<Path> items = paths.filter(Files::isRegularFile)
										.filter(f -> !f.toFile().getName().contains("$"))
										.filter(f -> f.toFile().getName().endsWith(".class"))
										.collect(Collectors.toList());

				Indexer indexer = new Indexer();
				Index index;
				for (Path item : items) {
					try (InputStream stream = new FileInputStream(item.toFile())) {
						indexer.index(stream);
					}
				}
				index = indexer.complete();

				Collection<ClassInfo> classes = index.getKnownClasses();

				List<ClassInfo> mains = classes	.stream()
												.filter(getMainFinder())
												.collect(Collectors.toList());
				String mainName = getSuggestedMain();
				if (mains.size() > 1 && mainName != null) {
					List<ClassInfo> suggestedmain = mains	.stream()
															.filter(ci -> ci.simpleName().equals(mainName))
															.collect(Collectors.toList());
					if (!suggestedmain.isEmpty()) {
						mains = suggestedmain;
					}
				}

				if (!mains.isEmpty()) {
					ss.setMainClass(mains.get(0).name().toString());
					if (mains.size() > 1) {
						Util.warnMsg(
								"Could not locate unique main() method. Use -m to specify explicit main method. Falling back to use first found: "
										+ mains	.stream()
												.map(x -> x.name().toString())
												.collect(Collectors.joining(",")));
					}
				}

				if (ss.getMainSource().isAgent()) {
					Optional<ClassInfo> agentmain = classes	.stream()
															.filter(pubClass -> pubClass.method("agentmain",
																	STRINGTYPE,
																	INSTRUMENTATIONTYPE) != null
																	||
																	pubClass.method("agentmain",
																			STRINGTYPE) != null)
															.findFirst();

					if (agentmain.isPresent()) {
						ss.setAgentMainClass(agentmain.get().name().toString());
					}

					Optional<ClassInfo> premain = classes	.stream()
															.filter(pubClass -> pubClass.method("premain",
																	STRINGTYPE,
																	INSTRUMENTATIONTYPE) != null
																	||
																	pubClass.method("premain",
																			STRINGTYPE) != null)
															.findFirst();

					if (premain.isPresent()) {
						ss.setPreMainClass(premain.get().name().toString());
					}
				}
			}
		} catch (IOException e) {
			throw new ExitException(1, e);
		}
	}

	protected String getSuggestedMain() {
		if (!ss.getResourceRef().isStdin()) {
			return ss.getResourceRef().getFile().getFileName().toString().replace(getMainExtension(), "");
		} else {
			return null;
		}
	}

	protected abstract String getMainExtension();

	protected Predicate<ClassInfo> getMainFinder() {
		return pubClass -> pubClass.method("main", STRINGARRAYTYPE) != null;
	}

	protected abstract String getCompilerBinary(String requestedJavaVersion);

	protected Path generatePom(Path tmpJarDir) throws IOException {
		Template pomTemplate = TemplateEngine.instance().getTemplate("pom.qute.xml");

		Path pomPath = null;
		if (pomTemplate == null) {
			// ignore
			Util.warnMsg("Could not locate pom.xml template");
		} else {
			String baseName = Util.getBaseName(ss.getResourceRef().getFile().getFileName().toString());
			String group = "group";
			String artifact = baseName;
			String version = "999-SNAPSHOT";
			if (ss.getGav().isPresent()) {
				MavenCoordinate coord = DependencyUtil.depIdToArtifact(
						DependencyUtil.gavWithVersion(ss.getGav().get()));
				group = coord.getGroupId();
				artifact = coord.getArtifactId();
				version = coord.getVersion();
			}
			String pomfile = pomTemplate
										.data("baseName", baseName)
										.data("group", group)
										.data("artifact", artifact)
										.data("version", version)
										.data("description", ss.getDescription().orElse(""))
										.data("dependencies", ctx.resolveClassPath(ss).getArtifacts())
										.render();

			pomPath = tmpJarDir.resolve("META-INF/maven/" + group.replace(".", "/") + "/pom.xml");
			Files.createDirectories(pomPath.getParent());
			Util.writeString(pomPath, pomfile);
		}
		return pomPath;
	}

	protected Path getCompileDir() {
		return ss.getJarFile().getParent().resolve(ss.getJarFile().getFileName() + ".tmp");
	}
}
