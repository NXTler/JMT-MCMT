package org.jmt.mcmt.asmdest;

import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jmt.mcmt.commands.StatsCommand;
import org.jmt.mcmt.config.GeneralConfig;
import org.jmt.mcmt.paralelised.ChunkLock;

import net.minecraft.block.BlockEventData;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.play.server.SBlockActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.tileentity.PistonTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.server.ServerChunkProvider;
import net.minecraft.world.server.ServerTickList;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.CrashReportExtender;

/**
 * This is where all current ASM hooks are terminated
 * 
 * So DON'T rename this file (Or there will be a lot of other work todo)
 * 
 * Fun point: So because this is hooking into a lot of the stuff, be careful what you reference here
 * I attempted to reference a function on {@link GeneralConfig} and it got VERY angery at me with a "class refuses to load" error
 * So remember that if you start getting class loading errors
 * 
 * 
 * TODO: Add more docs
 * 
 * @author jediminer543
 *
 */
public class ASMHookTerminator {

	private static final Logger LOGGER = LogManager.getLogger();
	
	static Phaser p;
	static ExecutorService ex;
	static MinecraftServer mcs;
	static AtomicBoolean isTicking = new AtomicBoolean();
	static AtomicInteger threadID = new AtomicInteger();
	
	
	public static void setupThreadpool(int parallelism) {
		threadID = new AtomicInteger();
		ForkJoinWorkerThreadFactory fjpf = p -> {
			ForkJoinWorkerThread fjwt = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(p);
			fjwt.setName("MCMT-Pool-Thread-"+threadID.getAndIncrement());
			return fjwt;
		};
		ex = new ForkJoinPool(
				parallelism,
				 fjpf,
	             null, true);
	}
	
	/**
	 * Creates and sets up the thread pool
	 */
	static {
		// Must be static here due to class loading shenanagins
		setupThreadpool(4);
	}
	
	static Map<String, Set<Thread>> mcThreadTracker = new ConcurrentHashMap<String, Set<Thread>>();
	
	// Statistics
	public static AtomicInteger currentWorlds = new AtomicInteger();
	public static AtomicInteger currentEnts = new AtomicInteger();
	public static AtomicInteger currentTEs = new AtomicInteger();
	public static AtomicInteger currentEnvs = new AtomicInteger();
	
	//Operation logging
	public static Set<String> currentTasks = ConcurrentHashMap.newKeySet(); 
	
	
	public static void regThread(String poolName, Thread thread) {
		mcThreadTracker.computeIfAbsent(poolName, s -> ConcurrentHashMap.newKeySet()).add(thread);
	}
	
	public static boolean isThreadPooled(String poolName, Thread t) {
		return mcThreadTracker.containsKey(poolName) && mcThreadTracker.get(poolName).contains(t);
	}

	public static void preTick(MinecraftServer server) {
		if (p != null) {
			LOGGER.warn("Multiple servers?");
			return;
		} else {
			isTicking.set(true);
			p = new Phaser();
			p.register();
			mcs = server;
			StatsCommand.setServer(mcs);
		}
	}
	
	public static void callTick(ServerWorld serverworld, BooleanSupplier hasTimeLeft, MinecraftServer server) {
		if (GeneralConfig.disabled || GeneralConfig.disableWorld) {
			try {
				serverworld.tick(hasTimeLeft);
			} catch (Exception e) {
				throw e;
			} finally {
				net.minecraftforge.fml.hooks.BasicEventHooks.onPostWorldTick(serverworld);
			}
			return;
		}
		if (mcs != server) {
			LOGGER.warn("Multiple servers?");
			GeneralConfig.disabled = true;
			serverworld.tick(hasTimeLeft);
			return;
		} else {
			String taskName = "WorldTick: " + serverworld.toString() + "@" + serverworld.hashCode();
			if (GeneralConfig.opsTracing) currentTasks.add(taskName);
			p.register();
			ex.execute(() -> {
				try {
					currentWorlds.incrementAndGet();
					serverworld.tick(hasTimeLeft);
					net.minecraftforge.fml.hooks.BasicEventHooks.onPostWorldTick(serverworld);
				} finally {
					p.arriveAndDeregister();
					currentWorlds.decrementAndGet();
					if (GeneralConfig.opsTracing) currentTasks.remove(taskName);
				}
			});
		}
		
	}
	
	public static void callEntityTick(Entity entityIn, ServerWorld serverworld) {
		if (GeneralConfig.disabled || GeneralConfig.disableEntity) {
			entityIn.tick();
			return;
		}
		String taskName = "EntityTick: " + entityIn.toString() + "@" + entityIn.hashCode();
		if (GeneralConfig.opsTracing) currentTasks.add(taskName);
		p.register();
		ex.execute(() -> {
			try {
				currentEnts.incrementAndGet();
				entityIn.tick();
			} finally {
				currentEnts.decrementAndGet();
				p.arriveAndDeregister();
				if (GeneralConfig.opsTracing) currentTasks.remove(taskName);
			}
		});
	}
	
	public static void callTickEnvironment(ServerWorld world, Chunk chunk, int k, ServerChunkProvider scp) {
		if (GeneralConfig.disabled  || GeneralConfig.disableEnvironment) {
			world.tickEnvironment(chunk, k);
			return;
		}
		String taskName = "EnvTick: " + chunk.toString() + "@" + chunk.hashCode();
		if (GeneralConfig.opsTracing) currentTasks.add(taskName);
		p.register();
		ex.execute(() -> {
			try {
				currentEnvs.incrementAndGet();
				world.tickEnvironment(chunk, k);
			} finally {
				currentEnvs.decrementAndGet();
				p.arriveAndDeregister();
				if (GeneralConfig.opsTracing) currentTasks.remove(taskName);
			}
		});
	}
	
	public static boolean filterTE(ITickableTileEntity tte) {
		boolean isLocking = false;
		if (GeneralConfig.teBlackList.contains(tte.getClass())) {
			isLocking = true;
		}
		// Apparently a string starts with check is faster than Class.getPackage; who knew (I didn't)
		if (!isLocking && GeneralConfig.chunkLockModded && !tte.getClass().getName().startsWith("net.minecraft.tileentity.")) {
			isLocking = true;
		}
		if (isLocking && GeneralConfig.teWhiteList.contains(tte.getClass())) {
			isLocking = false;
		}
		if (tte instanceof PistonTileEntity) {
			isLocking = true;
		}
		return isLocking;
	}
		
	public static void callTileEntityTick(ITickableTileEntity tte, World world) {
		if (GeneralConfig.disabled  || GeneralConfig.disableTileEntity || !(world instanceof ServerWorld)) {
			tte.tick();
			return;
		}
		String taskName = "TETick: " + tte.toString()  + "@" + tte.hashCode();
		if (GeneralConfig.opsTracing) currentTasks.add(taskName);
		p.register();
		ex.execute(() -> {
			try {
				final boolean doLock = filterTE(tte);
				if (doLock) {
					BlockPos bp = ((TileEntity) tte).getPos();
					long[] locks = ChunkLock.lock(bp, 1);
					try {
						currentTEs.incrementAndGet();
						tte.tick();
					} finally {
						ChunkLock.unlock(locks);
					}
				} else {
					currentTEs.incrementAndGet();
					tte.tick();
				}
			} catch (Exception e) {
				System.err.println("Exception ticking TE at " + ((TileEntity) tte).getPos());
				e.printStackTrace();
			} finally {
				currentTEs.decrementAndGet();
				p.arriveAndDeregister();
				if (GeneralConfig.opsTracing) currentTasks.remove(taskName);
			}
		});
	}
	
	public static void sendQueuedBlockEvents(Deque<BlockEventData> d, ServerWorld sw) {
		Iterator<BlockEventData> bed = d.iterator();
		while (bed.hasNext()) {
			BlockEventData blockeventdata = bed.next();
			if (sw.fireBlockEvent(blockeventdata)) {
				/* 1.16.1 code; AKA the only thing that changed  */
				sw.getServer().getPlayerList().sendToAllNearExcept((PlayerEntity)null, (double)blockeventdata.getPosition().getX(), (double)blockeventdata.getPosition().getY(), (double)blockeventdata.getPosition().getZ(), 64.0D, sw.func_234923_W_(), new SBlockActionPacket(blockeventdata.getPosition(), blockeventdata.getBlock(), blockeventdata.getEventID(), blockeventdata.getEventParameter()));
				/* */
				/* 1.15.2 code; AKA the only thing that changed  
				sw.getServer().getPlayerList().sendToAllNearExcept((PlayerEntity)null, (double)blockeventdata.getPosition().getX(), (double)blockeventdata.getPosition().getY(), (double)blockeventdata.getPosition().getZ(), 64.0D, sw.getDimension().getType(), new SBlockActionPacket(blockeventdata.getPosition(), blockeventdata.getBlock(), blockeventdata.getEventID(), blockeventdata.getEventParameter()));
				/* */
			}
			if (!isTicking.get()) {
				LOGGER.fatal("Block updates outside of tick");
			}
			bed.remove();
		}
	}
	
	public static void postTick(MinecraftServer server) {
		if (mcs != server) {
			LOGGER.warn("Multiple servers?");
			return;
		} else {
			p.arriveAndAwaitAdvance();
			isTicking.set(false);
			p = null;
		}
	}
	
	public static String populateCrashReport() {
		StringBuilder confInfo = new StringBuilder();
		confInfo.append("\n");
		confInfo.append("\t"); confInfo.append("Config Info:"); confInfo.append("\n");
		confInfo.append("\t"); confInfo.append("\t- Disabled: "); 
		confInfo.append(GeneralConfig.disabled); confInfo.append("\n");
		confInfo.append("\t"); confInfo.append("\t- World Disabled: "); 
		confInfo.append(GeneralConfig.disableWorld); confInfo.append("\n");
		confInfo.append("\t"); confInfo.append("\t- Entity Disabled: "); 
		confInfo.append(GeneralConfig.disableEntity); confInfo.append("\n");
		confInfo.append("\t"); confInfo.append("\t- Env Disabled: "); 
		confInfo.append(GeneralConfig.disableEnvironment); confInfo.append("\n");
		confInfo.append("\t"); confInfo.append("\t- TE Disabled: "); 
		confInfo.append(GeneralConfig.disableTileEntity); confInfo.append("\n");
		confInfo.append("\t"); confInfo.append("\t- SCP Disabled: "); 
		confInfo.append(GeneralConfig.disableChunkProvider); confInfo.append("\n");
		if (GeneralConfig.opsTracing) {
			confInfo.append("\t"); confInfo.append("-- Running Operations Begin -- "); confInfo.append("\n");
			for (String s : currentTasks) {
				confInfo.append("\t"); confInfo.append("\t"); confInfo.append(s); confInfo.append("\n");
			}
			confInfo.append("\t"); confInfo.append("-- Running Operations End -- "); confInfo.append("\n");
		}
		return confInfo.toString();
	}
	
	static {
		CrashReportExtender.registerCrashCallable("MCMT", ASMHookTerminator::populateCrashReport);
	}
		
	public static <T> void fixSTL(ServerTickList<T> stl) {
		LOGGER.debug("FixSTL Called");
		stl.pendingTickListEntriesTreeSet.addAll(stl.pendingTickListEntriesHashSet);
	}
	
	//Below is debug code for science reasons
	/*
	 * 	static Random debugRand = new Random();
	//Debug section begin
	if (true) {
		try {
			LOGGER.error("Locking");
			Thread.sleep(100000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	//End Debug Section
	*/
}

