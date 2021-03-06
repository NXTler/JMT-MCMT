package org.jmt.mcmt.modlauncher;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.spi.FileSystemProvider;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import net.minecraftforge.fml.loading.FMLPaths;

public class FastUtilTransformerService  implements ITransformer<ClassNode>, ITransformationService {

	private static final Logger LOGGER = LogManager.getLogger();
	private static final Marker M_LOCATOR = MarkerManager.getMarker("LOCATE");
	private boolean isActive = true;
	
	@Override
	public String name() {
		return "sync_fu";
	}
	
	@Override
	public Entry<Set<String>, Supplier<Function<String, Optional<URL>>>> additionalClassesLocator() {
		LOGGER.info("Sync_Fu preparing...");
		LOGGER.info("Prepping fu_add...");
		Optional<URL> fujarurl = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)).flatMap(path -> {
        	File file = new File(path);
        	if (file.isDirectory()) {
        		return Arrays.stream(file.list((d, n) -> n.endsWith(".jar")));
        	}
        	return Arrays.stream(new String[] {path});
        })
        .filter(p -> p.contains("fastutil")) // Can add more if necesary;
        .map(Paths::get)
        .map(path -> { 
        	try {
        		return path.toUri().toURL();
        	} catch (Exception e) {
        		return null;
        	}
		}).findFirst();
		URL rootUrl = null;
		try {
			rootUrl = fujarurl.get();
		} catch (Exception e) {
			LOGGER.warn("Failed to find FastUtil jar; this WILL result in more exceptions");
			isActive = false;
		}
		LOGGER.info("Sync_Fu found fu...");
		if (!isActive) {
			// We are dead
			return null;
		}
		final URL rootURLf = rootUrl;
		return new Entry<Set<String>, Supplier<Function<String, Optional<URL>>>>() {

			@Override
			public Set<String> getKey() {
				Set<String> out = new HashSet<String>();
				out.add("it.unimi.dsi.fastutil.");
				return out;
			}

			@Override
			public Supplier<Function<String, Optional<URL>>> getValue() {
				return () -> {
					return s -> {
						URL urlOut;
						try {
							urlOut = new URL("jar:" + rootURLf.toString() + "!/" + s);
							//LOGGER.debug(urlOut.toString());
							return Optional.of(urlOut);
						} catch (MalformedURLException e) {
							e.printStackTrace();
							return null;
						}
					};
				};
			}

			@Override
			public Supplier<Function<String, Optional<URL>>> setValue(Supplier<Function<String, Optional<URL>>> value) {
				throw new IllegalStateException();
			}
			
		};
	}

	@SuppressWarnings("rawtypes")
	@Override
	public List<ITransformer> transformers() {
		
		List<ITransformer> out = new ArrayList<>();
		out.add(this);
		return out;
	}

	int posfilter = Opcodes.ACC_PUBLIC;
	int negfilter = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ABSTRACT | Opcodes.ACC_BRIDGE;
	
	private static final Marker marker = MarkerManager.getMarker("JMTSUPERTRANS");
	
	@Override
	public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
		LOGGER.info(marker, "sync_fu " + input.name + " Transformer Called");
		for (MethodNode mn : input.methods) {
			if ((mn.access & posfilter) == posfilter
					&& (mn.access & negfilter) == 0
					&& !mn.name.equals("<init>")) {
				mn.access |= Opcodes.ACC_SYNCHRONIZED;
				LOGGER.debug(marker, "Patching " + mn.name);
			}
		}
		LOGGER.info(marker, "sync_fu " + input.name + " Transformer Complete");
		return input;
	}

	@Override
	public TransformerVoteResult castVote(ITransformerVotingContext context) {
		return TransformerVoteResult.YES;
	}

	@Override
	public Set<Target> targets() {
		Set<Target> out = new HashSet<ITransformer.Target>();
		if (!isActive) {
			// Is Dead
			return out;
		}
		out.add(Target.targetClass("it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap"));
		out.add(Target.targetClass("it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet"));
		out.add(Target.targetClass("it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap"));
		return out;
	}
	
	@Override
	public void initialize(IEnvironment environment) {
		
	}

	@Override
	public void beginScanning(IEnvironment environment) {
		System.out.println("HAI1");
		/*
		try {
			Field f = FMLLoader.class.getDeclaredField("coreModProvider");
			f.setAccessible(true);
			ICoreModProvider icmp = (ICoreModProvider) f.get(null);
			f.set(null, new FakeCoreModProvider(icmp));
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		*/
		try {
			CodeSource src = SyncFuLocator.class.getProtectionDomain().getCodeSource();
			URL jar = src.getLocation();
			if (!jar.toString().endsWith(".jar")) {
				LOGGER.warn(M_LOCATOR, "This be dev!!!");
				return;
			}
			URI uri = new URI("jar:".concat(jar.toString()).concat("!/"));
			//Thanks SO https://stackoverflow.com/a/48298758
			for (FileSystemProvider provider: FileSystemProvider.installedProviders()) {
		        if (provider.getScheme().equalsIgnoreCase("jar")) {
		            try {
		                provider.getFileSystem(uri);
		            } catch (FileSystemNotFoundException e) {
		                // in this case we need to initialize it first:
		                provider.newFileSystem(uri, Collections.emptyMap());
		            }
		        }
		    }
	        Path myPath = Paths.get(uri);
	        System.out.println(myPath);
	        Stream<Path> walk = Files.walk(myPath, 1).peek(p -> LOGGER.warn(M_LOCATOR, "Found {}", p)).filter(p -> p.toString().endsWith(".jar"));
	        Path root = FMLPaths.MODSDIR.get();
	        for (Iterator<Path> it = walk.iterator(); it.hasNext();){
	        	Path file = it.next();
	        	LOGGER.info(M_LOCATOR, "Found target jar: {}", file);
	        	Files.copy(file, root.resolve(file.getFileName().toString()));
	        }
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onLoad(IEnvironment env, Set<String> otherServices) throws IncompatibleEnvironmentException {}

}
