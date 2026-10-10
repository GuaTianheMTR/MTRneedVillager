package mtr.vilager.passenger.gua.helper;

import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Vector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 反射访问 MTR 4.x 服务端模拟器（org.mtr.mod.Init.main -> org.mtr.core.Main.simulators）。
 * MTR 的乘客机制（ride/stopRiding）只暴露给玩家，这里让村民也能用。
 * 任何一步反射失败都返回安全默认值并打印一次警告，不让模组崩溃。
 */
public final class MTRSimulatorBridge {

	private static final Logger LOGGER = LoggerFactory.getLogger("mtrvilager");

	private static Simulator simulator;
	private static boolean bridgeFailed;
	private static boolean warned;
	private static List<Vehicle> cachedVehicles = Collections.emptyList();
	private static long cacheTime;

	private MTRSimulatorBridge() {
	}

	public static Simulator getSimulator() {
		if (simulator == null && !bridgeFailed) {
			try {
				final Class<?> initClass = Class.forName("org.mtr.mod.Init");
				final Field mainField = initClass.getDeclaredField("main");
				mainField.setAccessible(true);
				final Object main = mainField.get(null);
				if (main == null) {
					throw new IllegalStateException("MTR main not initialized");
				}
				final Field simulatorsField = main.getClass().getDeclaredField("simulators");
				simulatorsField.setAccessible(true);
				final Object simulators = simulatorsField.get(main);
				simulator = (Simulator) simulators.getClass().getMethod("get", int.class).invoke(simulators, 0);
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				bridgeFailed = true;
				simulator = null;
				if (!warned) {
					warned = true;
					LOGGER.warn("[MTRneedVillager] 无法访问 MTR 模拟器（版本不兼容？），乘车功能已禁用", e);
				}
			}
		}
		return simulator;
	}

	/** 枚举服务端所有列车（含车库内停放的），结果缓存 1 秒避免每 tick 反射 */
	@SuppressWarnings("unchecked")
	public static List<Vehicle> getAllVehicles() {
		final long now = System.currentTimeMillis();
		if (now - cacheTime < 1000) {
			return cachedVehicles;
		}
		cacheTime = now;
		final Simulator simulator = getSimulator();
		if (simulator == null) {
			cachedVehicles = Collections.emptyList();
			return cachedVehicles;
		}
		final List<Vehicle> vehicles = new ArrayList<>();
		try {
			final Field vehiclesField = Siding.class.getDeclaredField("vehicles");
			vehiclesField.setAccessible(true);
			for (final Siding siding : simulator.sidings) {
				final Set<Vehicle> set = (Set<Vehicle>) vehiclesField.get(siding);
				if (set != null) {
					vehicles.addAll(set);
				}
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			if (!warned) {
				warned = true;
				LOGGER.warn("[MTRneedVillager] 无法枚举列车", e);
			}
		}
		cachedVehicles = vehicles;
		return cachedVehicles;
	}

	/** 每节车厢的两个转向架（bogie）世界坐标；bogie 连线的中点约等于车厢中心 */
	public static List<CarPosition> getCarPositions(Vehicle vehicle) {
		final List<CarPosition> positions = new ArrayList<>();
		try {
			for (final var car : vehicle.getVehicleCarsAndPositions()) {
				final var bogies = car.right();
				if (bogies.isEmpty()) {
					continue;
				}
				final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair<Vector, Vector> bogiePair = bogies.get(0);
				final Vector bogie1 = bogiePair.left();
				final Vector bogie2 = bogiePair.right();
				final double x = (bogie1.x() + bogie2.x()) / 2;
				final double z = (bogie1.z() + bogie2.z()) / 2;
				// 车厢地板大约在转向架上方 1 格
				final double y = bogie1.y() + 1;
				positions.add(new CarPosition(x, y, z, bogie1, bogie2));
			}
		} catch (RuntimeException | LinkageError e) {
			if (!warned) {
				warned = true;
				LOGGER.warn("[MTRneedVillager] 无法读取列车车厢位置", e);
			}
		}
		return positions;
	}

	public static long getVehicleId(Vehicle vehicle) {
		try {
			return vehicle.getId();
		} catch (RuntimeException | LinkageError e) {
			return -1;
		}
	}

	/** 按 id 查找列车 */
	public static Vehicle getVehicle(long vehicleId) {
		for (final Vehicle vehicle : getAllVehicles()) {
			if (vehicle.getId() == vehicleId) {
				return vehicle;
			}
		}
		return null;
	}

	/** 列车是否处于停站（含车库）状态 */
	public static boolean isStopped(Vehicle vehicle) {
		return !isMoving(vehicle);
	}

	/** 是否在行驶中（MTR 版本兼容：异常时按“停着”处理） */
	public static boolean isMoving(Vehicle vehicle) {
		try {
			return vehicle.isMoving();
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** 是否在线路上运营（MTR 版本兼容：异常时按“在线路上”处理，让村民仍能尝试乘车） */
	public static boolean isOnRoute(Vehicle vehicle) {
		try {
			return vehicle.getIsOnRoute();
		} catch (RuntimeException | LinkageError e) {
			return true;
		}
	}

	/** 列车是否正在车站停靠（开门上下客）：停着且停站计时在走；找不到字段时降级为“停着” */
	public static boolean isDwelling(Vehicle vehicle) {
		if (!isStopped(vehicle)) {
			return false;
		}
		for (Class<?> cls = vehicle.getClass(); cls != null; cls = cls.getSuperclass()) {
			try {
				final Field dwellField = cls.getDeclaredField("elapsedDwellTime");
				dwellField.setAccessible(true);
				return dwellField.getLong(vehicle) > 0;
			} catch (NoSuchFieldException e) {
				// 继续向父类找
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				if (!warned) {
					warned = true;
					LOGGER.warn("[MTRneedVillager] 无法读取列车停站状态", e);
				}
				return true;
			}
		}
		return true;
	}

	/** 村民是否已真正成为该列车的乘客（ride 成功与否的判定） */
	public static boolean isRiding(Simulator simulator, UUID uuid, long vehicleId) {
		return simulator != null && simulator.isRiding(uuid, vehicleId);
	}

	/** 让村民 ride：simulator.run 是异步队列，ride 只是 map 记录（永不失败），排队即可 */
	public static void ride(Simulator simulator, UUID uuid, long vehicleId) {
		try {
			simulator.run(() -> simulator.ride(uuid, vehicleId));
		} catch (RuntimeException | LinkageError e) {
			// 忽略
		}
	}

	public static void stopRiding(Simulator simulator, UUID uuid) {
		try {
			simulator.run(() -> simulator.stopRiding(uuid));
		} catch (RuntimeException | LinkageError e) {
			// 忽略
		}
	}

	/** 一节车厢在世界中的位置（bogie 连线中点） */
	public static final class CarPosition {
		public final double x;
		public final double y;
		public final double z;
		public final Vector bogie1;
		public final Vector bogie2;

		public CarPosition(double x, double y, double z, Vector bogie1, Vector bogie2) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.bogie1 = bogie1;
			this.bogie2 = bogie2;
		}
	}
}
